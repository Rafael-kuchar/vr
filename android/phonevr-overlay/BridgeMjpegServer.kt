package viritualisres.phonevr

import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Small MJPEG endpoint used by the existing MediaPipe hand-tracking script. */
internal class BridgeMjpegServer(private val port: Int = 8080) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    private var latestFrame: ByteArray? = null
    private var frameNumber = 0L

    @Volatile var clients: Int = 0
        private set

    fun start() {
        if (running) return
        running = true
        Thread(::acceptLoop, "vrpb-mjpeg-accept").apply { isDaemon = true }.start()
    }

    fun push(jpeg: ByteArray) {
        lock.withLock {
            latestFrame = jpeg
            frameNumber++
            changed.signalAll()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        lock.withLock { changed.signalAll() }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val server = ServerSocket(port)
                serverSocket = server
                while (running) {
                    val socket = server.accept()
                    Thread({ serve(socket) }, "vrpb-mjpeg-client").apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {
                if (running) try { Thread.sleep(700) } catch (_: InterruptedException) { }
            } finally {
                try { serverSocket?.close() } catch (_: Exception) { }
                serverSocket = null
            }
        }
    }

    private fun nextFrame(after: Long): Pair<Long, ByteArray?> = lock.withLock {
        if (frameNumber == after && running) changed.await(1, TimeUnit.SECONDS)
        frameNumber to latestFrame
    }

    private fun serve(socket: Socket) {
        var counted = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 4000
            val reader = socket.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            socket.soTimeout = 0
            val path = request.split(' ').getOrNull(1) ?: "/"
            val out = BufferedOutputStream(socket.getOutputStream())
            if (path.startsWith("/shot")) {
                val frame = lock.withLock { latestFrame }
                if (frame == null) {
                    out.write("HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray())
                } else {
                    out.write("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                    out.write(frame)
                }
                out.flush()
                return
            }
            if (!path.startsWith("/video")) {
                val body = "VR Phone Bridge camera endpoint\n/video = MJPEG stream\n/shot.jpg = latest frame\n".toByteArray()
                out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
                return
            }

            clients++
            counted = true
            out.write("HTTP/1.0 200 OK\r\nCache-Control: no-cache\r\nPragma: no-cache\r\nConnection: close\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n".toByteArray())
            out.flush()
            var last = -1L
            while (running) {
                val (id, frame) = nextFrame(last)
                if (frame == null || id == last) continue
                last = id
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            // Client disconnected or the app is shutting down.
        } finally {
            if (counted) clients--
            try { socket.close() } catch (_: Exception) { }
        }
    }
}
