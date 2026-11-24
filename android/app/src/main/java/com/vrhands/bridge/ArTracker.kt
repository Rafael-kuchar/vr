package com.vrhands.bridge

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan

class ArTracker(
    private val activity: Activity,
    private val poseSender: PoseSender,
    private val mjpeg: MjpegServer,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null

    private var textureId = 0
    private var viewW = 1
    private var viewH = 1
    private var geometryDirty = true

    private var frames = 0
    private var lastStatusNs = System.nanoTime()
    @Volatile private var lastJpegKb = 0
    @Volatile private var lastJpegMs = 0L
    private val streamDiv = 2
    private val jpegQuality = 70
    private var fovText = ""
    private var failedCamera = false
    private var firstFrame = false
    private val seenErrors = HashMap<String, Long>()

    private val encoder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jpeg").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val encoding = AtomicBoolean(false)
    private val planeData = arrayOf(ByteArray(0), ByteArray(0), ByteArray(0))
    private val rowStrides = IntArray(3)
    private val pixStrides = IntArray(3)
    private var imgW = 0
    private var imgH = 0
    private var nv21Buf: ByteArray? = null
    private val jpegOut = ByteArrayOutputStream(32 * 1024)

    fun createSession() {
        val s = Session(activity)
        pickCameraConfig(s)
        val config = Config(s)
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.updateMode = Config.UpdateMode.BLOCKING
        s.configure(config)
        session = s
    }

    private fun pickCameraConfig(s: Session) {
        try {
            val current = s.cameraConfig
            val filter = CameraConfigFilter(s)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30, CameraConfig.TargetFps.TARGET_FPS_60))
                .setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE))
            val best = s.getSupportedCameraConfigs(filter)
                .filter { it.imageSize.width == current.imageSize.width && it.imageSize.height == current.imageSize.height }
                .maxByOrNull { it.fpsRange.upper }
            if (best != null && best.fpsRange.upper > current.fpsRange.upper) {
                s.cameraConfig = best
                onLog("ARCore: camera em ${best.fpsRange.upper} fps (${best.imageSize.width}x${best.imageSize.height})")
            } else {
                onLog("ARCore: camera em ${current.fpsRange.upper} fps (${current.imageSize.width}x${current.imageSize.height})")
            }
        } catch (e: Throwable) {
            onLog("ARCore: nao consegui escolher a config da camera (${e.javaClass.simpleName})")
        }
    }

    fun resumeSession() {
        session?.resume()
    }

    fun pauseSession() {
        session?.pause()
    }

    fun closeSession() {
        session?.close()
        session = null
        encoder.shutdownNow()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        onLog("GL: superficie criada")
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = maxOf(width, 1)
        viewH = maxOf(height, 1)
        geometryDirty = true
        GLES20.glViewport(0, 0, viewW, viewH)
        onLog("GL: tamanho ${viewW}x${viewH}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = session ?: return
        try {
            if (geometryDirty) {
                @Suppress("DEPRECATION")
                val rotation = activity.windowManager.defaultDisplay.rotation
                s.setDisplayGeometry(rotation, viewW, viewH)
                geometryDirty = false
            }
            s.setCameraTextureName(textureId)
            val frame = s.update()
            if (!firstFrame) {
                firstFrame = true
                onLog("ARCore: recebendo frames da camera")
            }
            val camera = frame.camera
            val tracking = camera.trackingState == TrackingState.TRACKING

            val p = camera.displayOrientedPose
            poseSender.send(p.tx(), p.ty(), p.tz(), p.qx(), p.qy(), p.qz(), p.qw(), tracking)

            if (fovText.isEmpty()) {
                try {
                    val intr = camera.imageIntrinsics
                    val fx = intr.focalLength[0]
                    val w = intr.imageDimensions[0]
                    val fov = Math.toDegrees(2.0 * atan(w / (2.0 * fx))).toInt()
                    fovText = "camera ${intr.imageDimensions[0]}x${intr.imageDimensions[1]}, FOV horizontal ~$fov graus"
                } catch (_: Exception) {
                }
            }

            if (mjpeg.clients > 0 && encoding.compareAndSet(false, true)) {
                var submitted = false
                try {
                    frame.acquireCameraImage().use { img -> copyPlanes(img) }
                    encoder.execute { encodeJob() }
                    submitted = true
                } catch (_: NotYetAvailableException) {
                } catch (e: Exception) {
                    reportError(e)
                } finally {
                    if (!submitted) encoding.set(false)
                }
            }

            frames++
            failedCamera = false
            val now = System.nanoTime()
            if (now - lastStatusNs >= 1_000_000_000L) {
                val fps = frames * 1e9 / (now - lastStatusNs)
                frames = 0
                lastStatusNs = now
                val why = try { camera.trackingFailureReason.name } catch (_: Throwable) { "?" }
                val st = if (tracking) "6DOF OK" else "ARCore ainda nao rastreia ($why): mexa o celular devagar olhando algo com textura"
                onStatus("$st | ${"%.0f".format(fps)} fps | pose enviada: ${poseSender.sentCount} pacotes | camera p/ hands.py: ${mjpeg.clients} cliente(s), ${lastJpegKb} KB/frame, jpeg ${lastJpegMs} ms | $fovText")
            }
        } catch (e: CameraNotAvailableException) {
            if (!failedCamera) {
                failedCamera = true
                onStatus("Camera em uso por outro app (feche o IP Webcam / Iriun / camera) e reabra.")
            }
        } catch (e: Exception) {
            reportError(e)
        }
    }

    @Synchronized
    private fun reportError(e: Exception) {
        val key = "${e.javaClass.simpleName}: ${e.message}"
        val now = System.nanoTime()
        val last = seenErrors[key]
        if (last == null || now - last > 5_000_000_000L) {
            seenErrors[key] = now
            onLog("ARCore (frame): $key")
        }
    }

    private fun copyPlanes(img: Image) {
        imgW = img.width
        imgH = img.height
        val planes = img.planes
        for (i in 0 until 3) {
            val buf = planes[i].buffer
            val n = buf.remaining()
            if (planeData[i].size < n) planeData[i] = ByteArray(n)
            buf.get(planeData[i], 0, n)
            rowStrides[i] = planes[i].rowStride
            pixStrides[i] = planes[i].pixelStride
        }
    }

    private fun encodeJob() {
        try {
            val t0 = System.nanoTime()
            val jpg = yuvToJpeg(imgW, imgH, streamDiv, jpegQuality)
            lastJpegMs = (System.nanoTime() - t0) / 1_000_000L
            lastJpegKb = jpg.size / 1024
            mjpeg.push(jpg)
        } catch (e: Exception) {
            reportError(e)
        } finally {
            encoding.set(false)
        }
    }

    private fun yuvToJpeg(w: Int, h: Int, div: Int, quality: Int): ByteArray {
        val ow = (w / div) and 1.inv()
        val oh = (h / div) and 1.inv()
        val size = ow * oh * 3 / 2
        val nv21: ByteArray = nv21Buf?.takeIf { it.size == size } ?: ByteArray(size).also { nv21Buf = it }
        val y = planeData[0]
        val ys = rowStrides[0]
        var pos = 0
        for (oy in 0 until oh) {
            var idx = oy * div * ys
            for (ox in 0 until ow) {
                nv21[pos++] = y[idx]
                idx += div
            }
        }
        val u = planeData[1]
        val v = planeData[2]
        val us = rowStrides[1]
        val vs = rowStrides[2]
        val up = pixStrides[1]
        val vp = pixStrides[2]
        for (cr in 0 until oh / 2) {
            val srow = cr * div
            val ub = srow * us
            val vb = srow * vs
            for (cc in 0 until ow / 2) {
                val sc = cc * div
                nv21[pos++] = v[vb + sc * vp]
                nv21[pos++] = u[ub + sc * up]
            }
        }
        jpegOut.reset()
        YuvImage(nv21, ImageFormat.NV21, ow, oh, null).compressToJpeg(Rect(0, 0, ow, oh), quality, jpegOut)
        return jpegOut.toByteArray()
    }
}
