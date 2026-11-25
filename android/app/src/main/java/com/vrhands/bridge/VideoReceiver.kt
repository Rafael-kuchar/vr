package com.vrhands.bridge

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class VideoReceiver(
    private val port: Int,
    private val log: (String) -> Unit,
    private val onSize: (Int, Int) -> Unit
) {
    companion object {
        private const val CHUNK = 1316
        private const val MAX_FRAME = 1 shl 20
        private const val FRAME_US = 16666L
    }

    private val codecLock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var codecName = ""

    @Volatile private var running = false
    @Volatile var stats = "video: aguardando o PC"
        private set

    private val frameBuf = ByteArray(MAX_FRAME)
    private var frameLen = 0
    private var ptsUs = 0L
    @Volatile private var needKey = true

    @Volatile private var packets = 0L
    @Volatile private var bytes = 0L
    @Volatile private var framesIn = 0L
    @Volatile private var framesOut = 0L
    @Volatile private var dropped = 0L
    @Volatile private var lastPacketNs = 0L
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    private var firstPacket = false
    private var firstRender = false
    private var keyLogged = false
    private var lastErrorText = ""
    private var lastErrorNs = 0L
    private var lastRestartNs = 0L

    fun start() {
        if (running) return
        running = true
        Thread({ receiveLoop() }, "video-rx").apply { isDaemon = true; start() }
        Thread({ outputLoop() }, "video-out").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        synchronized(codecLock) { releaseCodecLocked() }
    }

    fun attach(s: Surface) {
        synchronized(codecLock) {
            releaseCodecLocked()
            surface = s
            createCodecLocked()
        }
    }

    fun detach() {
        synchronized(codecLock) {
            releaseCodecLocked()
            surface = null
        }
    }

    private fun buildFormat(extras: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME)
        if (extras) {
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
        }
        return f
    }

    private fun createCodecLocked() {
        val s = surface ?: return
        if (codec != null) return
        var created: MediaCodec? = null
        for (extras in booleanArrayOf(true, false)) {
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(buildFormat(extras), s, null, 0)
                c.start()
                created = c
                break
            } catch (e: Exception) {
                log("Video: decoder falhou (${e.javaClass.simpleName}: ${e.message})")
                try { c?.release() } catch (_: Exception) { }
            }
        }
        codec = created
        if (created != null) {
            codecName = created.name
            needKey = true
            keyLogged = false
            ptsUs = 0L
            log("Video: decoder ligado ($codecName)")
        }
    }

    private fun releaseCodecLocked() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Exception) { }
        try { c.release() } catch (_: Exception) { }
    }

    private fun restartCodec() {
        val now = System.nanoTime()
        if (now - lastRestartNs < 1_000_000_000L) return
        lastRestartNs = now
        synchronized(codecLock) {
            releaseCodecLocked()
            createCodecLocked()
        }
    }

    private fun reportError(text: String) {
        val now = System.nanoTime()
        if (text != lastErrorText || now - lastErrorNs > 5_000_000_000L) {
            lastErrorText = text
            lastErrorNs = now
            log(text)
        }
    }

    private fun openSocket(): DatagramSocket? {
        return try {
            val sk = DatagramSocket(null)
            sk.reuseAddress = true
            sk.receiveBufferSize = 1 shl 20
            sk.soTimeout = 500
            sk.bind(InetSocketAddress(port))
            log("Video: ouvindo na porta UDP $port")
            sk
        } catch (e: Exception) {
            reportError("Video: nao abriu a porta UDP $port (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    private fun receiveLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) { }
        var socket: DatagramSocket? = null
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        var lastStatNs = System.nanoTime()
        var in0 = 0L
        var out0 = 0L
        var bytes0 = 0L
        while (running) {
            val sk = socket ?: openSocket().also { socket = it }
            if (sk == null) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { }
                continue
            }
            try {
                packet.length = buf.size
                sk.receive(packet)
                handlePacket(buf, packet.length)
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) {
                    reportError("Video: erro de rede (${e.javaClass.simpleName}: ${e.message})")
                    try { sk.close() } catch (_: Exception) { }
                    socket = null
                    try { Thread.sleep(500) } catch (_: InterruptedException) { }
                }
            }
            val now = System.nanoTime()
            if (now - lastStatNs >= 1_000_000_000L) {
                val dt = (now - lastStatNs) / 1e9
                val rxFps = (framesIn - in0) / dt
                val outFps = (framesOut - out0) / dt
                val mbps = (bytes - bytes0) * 8.0 / 1e6 / dt
                in0 = framesIn
                out0 = framesOut
                bytes0 = bytes
                lastStatNs = now
                stats = when {
                    lastPacketNs == 0L -> "video: nenhum pacote do PC ainda (porta $port)"
                    now - lastPacketNs > 1_500_000_000L -> "video: parou de chegar (PC parou de enviar?)"
                    else -> "video: rx ${"%.0f".format(rxFps)} fps | tela ${"%.0f".format(outFps)} fps | ${"%.1f".format(mbps)} Mbps | ${videoW}x${videoH} | descartados $dropped | ${if (needKey) "esperando keyframe" else "ok"}"
                }
            }
        }
        try { socket?.close() } catch (_: Exception) { }
    }

    private fun startsWithAud(d: ByteArray, n: Int): Boolean {
        if (n < 5 || d[0] != 0.toByte() || d[1] != 0.toByte()) return false
        if (d[2] == 1.toByte()) return (d[3].toInt() and 0x1f) == 9
        return d[2] == 0.toByte() && d[3] == 1.toByte() && (d[4].toInt() and 0x1f) == 9
    }

    private fun handlePacket(d: ByteArray, n: Int) {
        packets++
        bytes += n
        lastPacketNs = System.nanoTime()
        if (!firstPacket) {
            firstPacket = true
            log("Video: chegando pacotes do PC")
        }
        if (n <= 0) return
        if (frameLen > 0 && startsWithAud(d, n)) emitFrame()
        if (frameLen + n > MAX_FRAME) {
            frameLen = 0
            needKey = true
            dropped++
            return
        }
        System.arraycopy(d, 0, frameBuf, frameLen, n)
        frameLen += n
        if (n < CHUNK) emitFrame()
    }

    private fun emitFrame() {
        val len = frameLen
        frameLen = 0
        if (len < 6) return
        framesIn++
        val b = frameBuf
        val validStart = b[0] == 0.toByte() && b[1] == 0.toByte() &&
            (b[2] == 1.toByte() || (b[2] == 0.toByte() && b[3] == 1.toByte()))
        if (!validStart) {
            needKey = true
            dropped++
            return
        }
        var hasSps = false
        var hasPps = false
        var hasIdr = false
        var i = 0
        val end = len - 4
        while (i < end) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) {
                val type = b[i + 3].toInt() and 0x1f
                if (type == 7) hasSps = true
                else if (type == 8) hasPps = true
                else if (type == 5) { hasIdr = true; break }
                else if (type == 1) break
                i += 3
            } else {
                i++
            }
        }
        val c = synchronized(codecLock) { codec } ?: return
        if (needKey) {
            if (!(hasIdr && hasSps && hasPps)) {
                dropped++
                return
            }
            needKey = false
            if (!keyLogged) {
                keyLogged = true
                log("Video: primeiro keyframe recebido")
            }
        }
        try {
            val idx = c.dequeueInputBuffer(5000)
            if (idx < 0) {
                dropped++
                needKey = true
                return
            }
            val ib = c.getInputBuffer(idx)
            if (ib == null || ib.capacity() < len) {
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                dropped++
                needKey = true
                return
            }
            ib.clear()
            ib.put(frameBuf, 0, len)
            c.queueInputBuffer(idx, 0, len, ptsUs, if (hasIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            ptsUs += FRAME_US
        } catch (e: Exception) {
            needKey = true
            reportError("Video: erro no decoder (${e.javaClass.simpleName}: ${e.message})")
            restartCodec()
        }
    }

    private fun reportSize(f: MediaFormat) {
        try {
            var w = f.getInteger(MediaFormat.KEY_WIDTH)
            var h = f.getInteger(MediaFormat.KEY_HEIGHT)
            if (f.containsKey("crop-left") && f.containsKey("crop-right") && f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
                w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
            }
            if (w != videoW || h != videoH) {
                videoW = w
                videoH = h
                log("Video: ${w}x${h}")
                onSize(w, h)
            }
        } catch (_: Exception) { }
    }

    private fun outputLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = synchronized(codecLock) { codec }
            if (c == null) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { }
                continue
            }
            try {
                var idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reportSize(c.outputFormat)
                    continue
                }
                if (idx < 0) continue
                while (true) {
                    val next = c.dequeueOutputBuffer(info, 0)
                    if (next >= 0) {
                        c.releaseOutputBuffer(idx, false)
                        idx = next
                    } else {
                        if (next == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) reportSize(c.outputFormat)
                        break
                    }
                }
                c.releaseOutputBuffer(idx, true)
                framesOut++
                if (!firstRender) {
                    firstRender = true
                    log("Video: primeira imagem na tela")
                }
            } catch (e: Exception) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { }
            }
        }
    }
}
