package viritualisres.phonevr;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Log;

import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feeds ARCore 6DOF into PhoneVR's ALVR client and exposes the phone camera as
 * MJPEG for the existing MediaPipe/VMT hand tracker.
 * Called from ALVRActivity; ARCore frame updates run on its GL thread.
 */
final class BridgeTracking {
    private static final String TAG = "VRPhoneBridge";
    private static final long JPEG_INTERVAL_NS = 120_000_000L; // ~8 FPS for hand tracking
    private static final int JPEG_SCALE = 2;
    private static final int JPEG_QUALITY = 58;

    private final ALVRActivity activity;
    private final BridgeMjpegServer mjpeg = new BridgeMjpegServer(8080);
    private final ExecutorService encoder = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vrpb-camera-jpeg");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicBoolean encoding = new AtomicBoolean(false);

    private volatile Session session;
    private volatile boolean resumed;
    private volatile int cameraTexture;
    private long lastJpegNs;
    private String lastArCoreError = "";

    BridgeTracking(ALVRActivity activity) {
        this.activity = activity;
        mjpeg.start();
        Log.i(TAG, "Camera MJPEG endpoint starting on :8080");
    }

    void onSurfaceCreated() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        cameraTexture = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    synchronized void resume() {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted; retaining Cardboard 3DOF fallback");
            return;
        }
        try {
            Session current = session;
            if (current == null) {
                current = new Session(activity);
                CameraConfigFilter filter = new CameraConfigFilter(current)
                        .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                        .setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE));
                List<CameraConfig> supported = current.getSupportedCameraConfigs(filter);
                if (supported != null && !supported.isEmpty()) {
                    CameraConfig lowest = supported.get(0);
                    for (CameraConfig candidate : supported) {
                        long candidatePixels = (long) candidate.getImageSize().getWidth() * candidate.getImageSize().getHeight();
                        long lowestPixels = (long) lowest.getImageSize().getWidth() * lowest.getImageSize().getHeight();
                        if (candidatePixels < lowestPixels) lowest = candidate;
                    }
                    current.setCameraConfig(lowest);
                }
                Config config = new Config(current);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
                config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
                config.setFocusMode(Config.FocusMode.AUTO);
                current.configure(config);
                session = current;
            }
            if (!resumed) {
                current.resume();
                resumed = true;
                Log.i(TAG, "ARCore 6DOF camera session resumed");
            }
        } catch (Exception error) {
            resumed = false;
            reportArCoreError(error);
        }
    }

    synchronized void pause() {
        if (!resumed) return;
        try {
            Session current = session;
            if (current != null) current.pause();
        } catch (Exception error) {
            reportArCoreError(error);
        }
        resumed = false;
        activity.submitExternalPose(0f, 0f, 0f, 0f, 0f, 0f, 1f,
                SystemClock.elapsedRealtimeNanos(), false);
    }

    synchronized void close() {
        pause();
        Session current = session;
        session = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        mjpeg.stop();
        encoder.shutdownNow();
    }

    /** Must be called from GLSurfaceView.Renderer.onDrawFrame. */
    void onDrawFrame() {
        if (!resumed || cameraTexture == 0) return;
        Session current = session;
        if (current == null) return;
        try {
            current.setCameraTextureName(cameraTexture);
            Frame frame = current.update();
            com.google.ar.core.Camera camera = frame.getCamera();
            if (camera.getTrackingState() == TrackingState.TRACKING) {
                com.google.ar.core.Pose pose = camera.getDisplayOrientedPose();
                activity.submitExternalPose(
                        pose.tx(), pose.ty(), pose.tz(),
                        pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                        SystemClock.elapsedRealtimeNanos(), true);
            } else {
                activity.submitExternalPose(0f, 0f, 0f, 0f, 0f, 0f, 1f,
                        SystemClock.elapsedRealtimeNanos(), false);
            }

            long now = SystemClock.elapsedRealtimeNanos();
            if (mjpeg.hasDemand() && now - lastJpegNs >= JPEG_INTERVAL_NS
                    && encoding.compareAndSet(false, true)) {
                boolean queued = false;
                Image image = null;
                try {
                    image = frame.acquireCameraImage();
                    final ImageSample sample = copyImage(image);
                    encoder.execute(new Runnable() {
                        @Override public void run() {
                            try { mjpeg.push(encodeJpeg(sample)); }
                            catch (Exception error) { reportArCoreError(error); }
                            finally { encoding.set(false); }
                        }
                    });
                    queued = true;
                    lastJpegNs = now;
                } catch (NotYetAvailableException ignored) {
                    // The next ARCore frame may have an image ready.
                } catch (Exception error) {
                    reportArCoreError(error);
                } finally {
                    if (image != null) {
                        try { image.close(); } catch (Exception ignored) { }
                    }
                    if (!queued) encoding.set(false);
                }
            }
        } catch (Exception error) {
            reportArCoreError(error);
        }
    }

    private static final class ImageSample {
        final int width;
        final int height;
        final byte[][] planes;
        final int[] rowStride;
        final int[] pixelStride;

        ImageSample(int width, int height, byte[][] planes, int[] rowStride, int[] pixelStride) {
            this.width = width;
            this.height = height;
            this.planes = planes;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
        }
    }

    private ImageSample copyImage(Image image) {
        Image.Plane[] imagePlanes = image.getPlanes();
        byte[][] planes = new byte[3][];
        int[] rowStride = new int[3];
        int[] pixelStride = new int[3];
        for (int index = 0; index < 3; index++) {
            ByteBuffer buffer = imagePlanes[index].getBuffer();
            planes[index] = new byte[buffer.remaining()];
            buffer.get(planes[index]);
            rowStride[index] = imagePlanes[index].getRowStride();
            pixelStride[index] = imagePlanes[index].getPixelStride();
        }
        return new ImageSample(image.getWidth(), image.getHeight(), planes, rowStride, pixelStride);
    }

    private static byte[] encodeJpeg(ImageSample sample) {
        int outWidth = (sample.width / JPEG_SCALE) & ~1;
        int outHeight = (sample.height / JPEG_SCALE) & ~1;
        byte[] nv21 = new byte[outWidth * outHeight * 3 / 2];
        int dst = 0;
        byte[] y = sample.planes[0];
        for (int row = 0; row < outHeight; row++) {
            int sourceRow = row * JPEG_SCALE * sample.rowStride[0];
            for (int col = 0; col < outWidth; col++) {
                nv21[dst++] = y[sourceRow + col * JPEG_SCALE * sample.pixelStride[0]];
            }
        }
        byte[] u = sample.planes[1];
        byte[] v = sample.planes[2];
        for (int row = 0; row < outHeight / 2; row++) {
            int sourceRowU = row * JPEG_SCALE * sample.rowStride[1];
            int sourceRowV = row * JPEG_SCALE * sample.rowStride[2];
            for (int col = 0; col < outWidth / 2; col++) {
                int sourceColU = col * JPEG_SCALE * sample.pixelStride[1];
                int sourceColV = col * JPEG_SCALE * sample.pixelStride[2];
                nv21[dst++] = v[sourceRowV + sourceColV];
                nv21[dst++] = u[sourceRowU + sourceColU];
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(24 * 1024);
        YuvImage image = new YuvImage(nv21, ImageFormat.NV21, outWidth, outHeight, null);
        image.compressToJpeg(new Rect(0, 0, outWidth, outHeight), JPEG_QUALITY, output);
        return output.toByteArray();
    }

    private synchronized void reportArCoreError(Exception error) {
        String message = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (!message.equals(lastArCoreError)) {
            lastArCoreError = message;
            Log.e(TAG, "ARCore: " + message, error);
        }
    }
}
