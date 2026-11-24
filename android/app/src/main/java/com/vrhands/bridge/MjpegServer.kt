package com.vrhands.bridge

import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serves the camera as MJPEG over HTTP, the same way "IP Webcam" does:
 *   http://PHONE_IP:8080/video   (stream)      http://PHONE_IP:8080/shot.jpg   (one picture)
 *   http://PHONE_IP:8080/        (status page: open it in the PC browser to test the connection)
 * hands.py can use it as is:  python hands.py http://PHONE_IP:8080/video
 *
 * The server starts as soon as the app opens (it does not wait for ARCore), and reports every
 * problem (port busy, etc.) through [log], so nothing fails silently any more.
 */
class MjpegServer(private val port: Int) {
    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private var jpeg: ByteArray? = null
    private var frameId = 0L

    @Volatile private var running = false

    /** number of hands.py (or browser) connections currently watching /video */
    @Volatile var clients = 0
        private set

    @Volatile var listening = false
        private set

    @Volatile var requests = 0
        private set

    /** set by the activity: where problems/events are reported, and the text shown at "/" */
    @Volatile var log: (String) -> Unit = { }
    @Volatile var statusText: () -> String = { "" }

    fun start() {
        if (running) return
        running = true
        val t = Thread({ acceptLoop() }, "mjpeg-accept")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        lock.withLock { newFrame.signalAll() }
    }

    fun push(data: ByteArray) {
        lock.withLock {
            jpeg = data
            frameId++
            newFrame.signalAll()
        }
    }

    private fun acceptLoop() {
        var lastError = ""
        while (running) {
            var ss: ServerSocket? = null
            try {
                val tmp = ServerSocket()
                tmp.reuseAddress = true
                tmp.bind(InetSocketAddress(port))
                ss = tmp
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                if (msg != lastError) {
                    log("Servidor da camera NAO subiu na porta $port ($msg). Tentando de novo...")
                    lastError = msg
                }
            }
            val sock = ss
            if (sock == null) {
                listening = false
                try { Thread.sleep(2000) } catch (_: InterruptedException) { }
                continue
            }
            lastError = ""
            listening = true
            log("Servidor da camera ligado (porta $port)")
            if (!running) {
                try { sock.close() } catch (_: Exception) { }
                break
            }
            while (running) {
                try {
                    val c = sock.accept()
                    requests++
                    val t = Thread({ serve(c) }, "mjpeg-client")
                    t.isDaemon = true
                    t.start()
                } catch (e: Exception) {
                    if (running) log("Servidor da camera: erro ao aceitar conexao (${e.javaClass.simpleName}: ${e.message})")
                    break
                }
            }
            listening = false
            try { sock.close() } catch (_: Exception) { }
        }
    }

    private fun waitFrame(last: Long): Pair<Long, ByteArray?> {
        return lock.withLock {
            if (frameId == last && running) newFrame.await(1, TimeUnit.SECONDS)
            Pair(frameId, jpeg)
        }
    }

    private fun serve(s: Socket) {
        var counted = false
        try {
            s.tcpNoDelay = true
            s.soTimeout = 5000                   // only while reading the request
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {                       // skip the headers
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
            }
            s.soTimeout = 0
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = BufferedOutputStream(s.getOutputStream())

            if (path.startsWith("/shot")) {
                val f = jpeg
                if (f == null) {
                    out.write("HTTP/1.0 503 Service Unavailable\r\n\r\nsem imagem da camera ainda\n".toByteArray())
                } else {
                    out.write("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                    out.write(f)
                }
                out.flush()
                return
            }
            if (!path.startsWith("/video")) {
                val body = statusText().toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
                return
            }

            clients++
            counted = true
            out.write(
                ("HTTP/1.0 200 OK\r\nCache-Control: no-cache\r\nPragma: no-cache\r\nConnection: close\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").toByteArray()
            )
            out.flush()
            var last = -1L
            while (running) {
                val (id, frame) = waitFrame(last)
                if (frame == null || id == last) continue
                last = id
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            // client left
        } finally {
            if (counted) clients--
            try { s.close() } catch (_: Exception) { }
        }
    }
}
