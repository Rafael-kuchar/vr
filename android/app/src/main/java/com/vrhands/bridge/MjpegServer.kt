package com.vrhands.bridge

import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serves the camera as MJPEG over HTTP, the same way "IP Webcam" does:
 *   http://PHONE_IP:8080/video   (stream)      http://PHONE_IP:8080/shot.jpg   (one picture)
 * hands.py can use it as is:  python hands.py http://PHONE_IP:8080/video
 */
class MjpegServer(private val port: Int) {
    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private var jpeg: ByteArray? = null
    private var frameId = 0L

    @Volatile private var running = false
    private var server: ServerSocket? = null

    @Volatile var clients = 0
        private set

    fun start() {
        running = true
        Thread({ acceptLoop() }, "mjpeg-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
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
        try {
            server = ServerSocket(port).apply { reuseAddress = true }
        } catch (e: Exception) {
            return
        }
        while (running) {
            try {
                val c = server!!.accept()
                Thread({ serve(c) }, "mjpeg-client").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    private fun waitFrame(last: Long): Pair<Long, ByteArray?> {
        return lock.withLock {
            if (frameId == last && running) newFrame.await(1, TimeUnit.SECONDS)
            Pair(frameId, jpeg)
        }
    }

    private fun serve(s: Socket) {
        clients++
        try {
            s.tcpNoDelay = true
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {                       // skip the headers
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = BufferedOutputStream(s.getOutputStream())

            if (path.startsWith("/shot")) {
                val f = jpeg
                if (f == null) {
                    out.write("HTTP/1.0 503 Service Unavailable\r\n\r\n".toByteArray())
                } else {
                    out.write("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                    out.write(f)
                }
                out.flush()
                return
            }
            if (!path.startsWith("/video")) {
                val body = "VR Phone Bridge camera. Stream: /video   Picture: /shot.jpg\n"
                out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                out.flush()
                return
            }

            out.write(
                ("HTTP/1.0 200 OK\r\nCache-Control: no-cache\r\nPragma: no-cache\r\nConnection: close\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").toByteArray()
            )
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
            clients--
            try { s.close() } catch (_: Exception) {}
        }
    }
}
