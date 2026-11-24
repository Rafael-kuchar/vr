package com.vrhands.bridge

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan

/**
 * ARCore session + the (tiny, invisible) GL surface ARCore needs to run.
 * For every camera frame:
 *   1. head pose (6DOF)  -> PoseSender (UDP to the PC)
 *   2. camera image      -> JPEG -> MjpegServer (for hands.py)
 */
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
    private var lastJpegKb = 0
    private var fovText = ""
    private var failedCamera = false
    private var firstFrame = false
    private val seenErrors = HashMap<String, Long>()

    /** Creates and configures the session (call from the main thread, after ARCore is installed). */
    fun createSession() {
        val s = Session(activity)
        val config = Config(s)
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.updateMode = Config.UpdateMode.BLOCKING
        s.configure(config)
        session = s
    }

    fun resumeSession() {
        session?.resume()          // can throw CameraNotAvailableException: the caller handles it
    }

    fun pauseSession() {
        session?.pause()
    }

    fun closeSession() {
        session?.close()
        session = null
    }

    // ---------------- GLSurfaceView.Renderer ----------------
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

            // 1) head pose first (so the JPEG work below never delays it)
            val p = camera.displayOrientedPose
            poseSender.send(p.tx(), p.ty(), p.tz(), p.qx(), p.qy(), p.qz(), p.qw(), tracking)

            // field of view of the camera (to put in hands.py: CAM_FOV_H)
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

            // 2) camera image -> MJPEG (only if someone is watching)
            if (mjpeg.clients > 0) {
                try {
                    frame.acquireCameraImage().use { img ->
                        val jpg = yuvToJpeg(img, 80)
                        lastJpegKb = jpg.size / 1024
                        mjpeg.push(jpg)
                    }
                } catch (_: NotYetAvailableException) {
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
                onStatus("$st | ${"%.0f".format(fps)} fps | pose enviada: ${poseSender.sentCount} pacotes | camera p/ hands.py: ${mjpeg.clients} cliente(s), ${lastJpegKb} KB/frame | $fovText")
            }
        } catch (e: CameraNotAvailableException) {
            if (!failedCamera) {
                failedCamera = true
                onStatus("Camera em uso por outro app (feche o IP Webcam / Iriun / camera) e reabra.")
            }
        } catch (e: Exception) {
            // session paused / not ready: skip this frame, but say why (once in a while)
            reportError(e)
        }
    }

    private fun reportError(e: Exception) {
        val key = "${e.javaClass.simpleName}: ${e.message}"
        val now = System.nanoTime()
        val last = seenErrors[key]
        if (last == null || now - last > 5_000_000_000L) {
            seenErrors[key] = now
            onLog("ARCore (frame): $key")
        }
    }

    // ---------------- YUV_420_888 -> JPEG ----------------
    private fun yuvToJpeg(image: Image, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val nv21 = ByteArray(w * h * 3 / 2)

        val yBuf = yPlane.buffer
        var pos = 0
        for (row in 0 until h) {
            yBuf.position(row * yPlane.rowStride)
            yBuf.get(nv21, pos, w)
            pos += w
        }
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[pos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                nv21[pos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }
        val out = ByteArrayOutputStream(48 * 1024)
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }
}
