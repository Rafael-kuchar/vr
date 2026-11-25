package viritualisres.phonevr

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds ARCore 6DOF into PhoneVR's ALVR client and exposes the phone camera as MJPEG
 * for the project's existing MediaPipe/VMT hand tracker.
 * Called on the ALVRActivity lifecycle; ARCore frame updates run on its GL thread.
 */
internal class BridgeTracking(private val activity: ALVRActivity) {
    companion object {
        private const val TAG = "VRPhoneBridge"
        private const val CAMERA_PORT = 8080
        private const val JPEG_INTERVAL_NS = 120_000_000L // ~8 FPS for the hand tracker
        private const val JPEG_SCALE = 2
        private const val JPEG_QUALITY = 58
    }

    private val mjpeg = BridgeMjpegServer(CAMERA_PORT)
    private val encoder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vrpb-camera-jpeg").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val encoding = AtomicBoolean(false)

    @Volatile private var session: Session? = null
    @Volatile private var resumed = false
    private var cameraTexture = 0
    private var lastJpegNs = 0L
    private var lastArCoreError = ""

    init {
        mjpeg.start()
        Log.i(TAG, "Camera MJPEG endpoint starting on :$CAMERA_PORT")
    }

    fun onSurfaceCreated() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        cameraTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun resume() {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted; retaining Cardboard 3DOF fallback")
            return
        }
        try {
            var current = session
            if (current == null) {
                current = Session(activity)
                val filter = CameraConfigFilter(current)
                    .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                    .setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE))
                val best = current.getSupportedCameraConfigs(filter)
                    .minByOrNull { it.imageSize.width * it.imageSize.height }
                if (best != null) current.cameraConfig = best
                val config = Config(current)
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.focusMode = Config.FocusMode.AUTO
                current.configure(config)
                session = current
            }
            if (!resumed) {
                current.resume()
                resumed = true
                Log.i(TAG, "ARCore 6DOF camera session resumed")
            }
        } catch (e: Exception) {
            resumed = false
            reportArCoreError(e)
        }
    }

    fun pause() {
        if (!resumed) return
        try { session?.pause() } catch (e: Exception) { reportArCoreError(e) }
        resumed = false
        activity.submitExternalPose(0f, 0f, 0f, 0f, 0f, 0f, 1f, SystemClock.elapsedRealtimeNanos(), false)
    }

    fun close() {
        pause()
        try { session?.close() } catch (_: Exception) { }
        session = null
        mjpeg.stop()
        encoder.shutdownNow()
    }

    /** Must be called from GLSurfaceView.Renderer.onDrawFrame. */
    fun onDrawFrame() {
        if (!resumed || cameraTexture == 0) return
        val s = session ?: return
        try {
            s.setCameraTextureName(cameraTexture)
            val frame = s.update()
            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) {
                val pose = camera.displayOrientedPose
                activity.submitExternalPose(
                    pose.tx(), pose.ty(), pose.tz(),
                    pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                    SystemClock.elapsedRealtimeNanos(), true
                )
            } else {
                activity.submitExternalPose(0f, 0f, 0f, 0f, 0f, 0f, 1f,
                    SystemClock.elapsedRealtimeNanos(), false)
            }

            val now = SystemClock.elapsedRealtimeNanos()
            if (mjpeg.clients > 0 && now - lastJpegNs >= JPEG_INTERVAL_NS && encoding.compareAndSet(false, true)) {
                var queued = false
                var image: Image? = null
                try {
                    val acquiredImage = frame.acquireCameraImage()
                    image = acquiredImage
                    val sample = copyImage(acquiredImage)
                    encoder.execute {
                        try { mjpeg.push(encodeJpeg(sample)) }
                        catch (e: Exception) { reportArCoreError(e) }
                        finally { encoding.set(false) }
                    }
                    queued = true
                    lastJpegNs = now
                } catch (_: NotYetAvailableException) {
                    // The next ARCore frame may have an image ready.
                } catch (e: Exception) {
                    reportArCoreError(e)
                } finally {
                    try { image?.close() } catch (_: Exception) { }
                    if (!queued) encoding.set(false)
                }
            }
        } catch (e: Exception) {
            reportArCoreError(e)
        }
    }

    private data class ImageSample(
        val width: Int,
        val height: Int,
        val planes: Array<ByteArray>,
        val rowStride: IntArray,
        val pixelStride: IntArray
    )

    private fun copyImage(image: Image): ImageSample {
        val planes = Array(3) { index ->
            val buffer = image.planes[index].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        return ImageSample(
            image.width,
            image.height,
            planes,
            IntArray(3) { image.planes[it].rowStride },
            IntArray(3) { image.planes[it].pixelStride }
        )
    }

    private fun encodeJpeg(sample: ImageSample): ByteArray {
        val outWidth = (sample.width / JPEG_SCALE) and -2
        val outHeight = (sample.height / JPEG_SCALE) and -2
        val nv21 = ByteArray(outWidth * outHeight * 3 / 2)
        var dst = 0
        val y = sample.planes[0]
        for (row in 0 until outHeight) {
            val sourceRow = row * JPEG_SCALE * sample.rowStride[0]
            for (col in 0 until outWidth) {
                nv21[dst++] = y[sourceRow + col * JPEG_SCALE * sample.pixelStride[0]]
            }
        }
        val u = sample.planes[1]
        val v = sample.planes[2]
        for (row in 0 until outHeight / 2) {
            val sourceRowU = row * JPEG_SCALE * sample.rowStride[1]
            val sourceRowV = row * JPEG_SCALE * sample.rowStride[2]
            for (col in 0 until outWidth / 2) {
                val sourceColU = col * JPEG_SCALE * sample.pixelStride[1]
                val sourceColV = col * JPEG_SCALE * sample.pixelStride[2]
                nv21[dst++] = v[sourceRowV + sourceColV]
                nv21[dst++] = u[sourceRowU + sourceColU]
            }
        }
        val out = ByteArrayOutputStream(24 * 1024)
        YuvImage(nv21, ImageFormat.NV21, outWidth, outHeight, null)
            .compressToJpeg(Rect(0, 0, outWidth, outHeight), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    @Synchronized
    private fun reportArCoreError(error: Exception) {
        val message = "${error.javaClass.simpleName}: ${error.message}"
        if (message != lastArCoreError) {
            lastArCoreError = message
            Log.e(TAG, "ARCore: $message", error)
        }
    }
}
