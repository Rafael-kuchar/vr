package com.vrhands.bridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * One app that does everything on the phone:
 *   - ARCore 6DOF head tracking  -> UDP to relay.py on the PC (port 6000)
 *   - camera as MJPEG server     -> http://PHONE:8080/video (for hands.py, replaces IP Webcam)
 *   - receives the game video    <- UDP from the PC (port 5000, H.264 in MPEG-TS), shown fullscreen (side-by-side)
 */
class MainActivity : Activity() {

    companion object {
        const val POSE_PORT = 6000
        const val CAMERA_PORT = 8080
        const val VIDEO_PORT = 5000
        const val REQ_CAMERA = 10
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var setupPanel: LinearLayout
    private lateinit var ipField: EditText
    private lateinit var statusView: TextView

    private var glView: GLSurfaceView? = null
    private var player: ExoPlayer? = null
    private var tracker: ArTracker? = null
    private var poseSender: PoseSender? = null
    private var mjpeg: MjpegServer? = null

    private var started = false
    private var arActive = false
    private var installRequested = false
    private var pcIp = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        buildUi()
        hideSystemUi()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        statusView = TextView(this).apply {
            setTextColor(Color.rgb(120, 255, 120))
            textSize = 10f
            setPadding(16, 6, 16, 6)
        }

        setupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
        }
        val title = TextView(this).apply {
            text = "VR Phone Bridge  (beta)"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val info = TextView(this).apply {
            text = "IP deste celular: ${localIps()}\n" +
                "No PC: python hands.py http://${localIps().split(", ").firstOrNull() ?: "IP"}:$CAMERA_PORT/video"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 12, 0, 12)
        }
        ipField = EditText(this).apply {
            hint = "IP do PC (ex: 192.168.1.10)"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("pc_ip", "") ?: "")
        }
        val start = Button(this).apply {
            text = "Iniciar"
            setOnClickListener { onStartClicked() }
        }
        setupPanel.addView(title)
        setupPanel.addView(info)
        setupPanel.addView(ipField, LinearLayout.LayoutParams(900, ViewGroup.LayoutParams.WRAP_CONTENT))
        setupPanel.addView(start, LinearLayout.LayoutParams(500, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(setupPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        setContentView(root)
    }

    private fun onStartClicked() {
        val ip = ipField.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Digite o IP do PC", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("pc_ip", ip).apply()
        pcIp = ip
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startAll()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAll()
            } else {
                Toast.makeText(this, "Sem permissao de camera o 6DOF nao funciona", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAll() {
        if (started) return
        started = true
        setupPanel.visibility = View.GONE

        val sender = PoseSender(pcIp, POSE_PORT)
        val server = MjpegServer(CAMERA_PORT).also { it.start() }
        poseSender = sender
        mjpeg = server

        val t = ArTracker(this, sender, server) { msg -> runOnUiThread { statusView.text = msg } }
        tracker = t

        startPlayer()

        val gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(t)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView = gl
        root.addView(gl, FrameLayout.LayoutParams(2, 2, Gravity.BOTTOM or Gravity.END))

        statusView.text = "Iniciando ARCore..."
        resumeAr()
    }

    // ---------------- ARCore ----------------
    private fun resumeAr() {
        val t = tracker ?: return
        if (arActive) return
        try {
            if (t.session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return                      // the install screen opens; onResume calls this again
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }
                t.createSession()
            }
            t.resumeSession()
            glView?.onResume()
            arActive = true
        } catch (e: UnavailableUserDeclinedInstallationException) {
            showError("Instale o 'Servicos do Google Play para RA' (ARCore) para usar o 6DOF.")
        } catch (e: UnavailableArcoreNotInstalledException) {
            showError("ARCore nao esta instalado. Instale 'Servicos do Google Play para RA'.")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showError("Este celular nao suporta ARCore (sem 6DOF).")
        } catch (e: CameraNotAvailableException) {
            showError("Camera em uso por outro app (IP Webcam, Iriun...). Feche e reabra.")
        } catch (e: Exception) {
            showError("Erro ao iniciar o ARCore: ${e.message}")
        }
    }

    private fun showError(msg: String) {
        statusView.text = msg
        statusView.setTextColor(Color.rgb(255, 120, 120))
    }

    // ---------------- game video (UDP, H.264 in MPEG-TS) ----------------
    @OptIn(UnstableApi::class)
    private fun startPlayer() {
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(200, 400, 40, 40).build()
        val p = ExoPlayer.Builder(this).setLoadControl(load).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // no video yet / the stream stopped: try again in a second
                ui.postDelayed({ prepareVideo(p) }, 1000)
            }
        })
        val pv = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            player = p
        }
        root.addView(pv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        player = p
        prepareVideo(p)
    }

    @OptIn(UnstableApi::class)
    private fun prepareVideo(p: ExoPlayer) {
        val dataSourceFactory = DataSource.Factory { UdpDataSource(2000, 3000) }
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val source = ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
            .createMediaSource(MediaItem.fromUri("udp://0.0.0.0:$VIDEO_PORT"))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    // ---------------- lifecycle ----------------
    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (started) {
            resumeAr()
            player?.playWhenReady = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (started) {
            glView?.onPause()
            tracker?.pauseSession()
            arActive = false
            player?.playWhenReady = false
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        tracker?.closeSession()
        mjpeg?.stop()
        poseSender?.close()
        super.onDestroy()
    }

    // ---------------- helpers ----------------
    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun localIps(): String {
        return try {
            val list = Collections.list(NetworkInterface.getNetworkInterfaces() ?: return "?")
            list.flatMap { Collections.list(it.inetAddresses) }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .joinToString(", ") { it.hostAddress ?: "" }
                .ifEmpty { "(sem Wi-Fi?)" }
        } catch (e: Exception) {
            "?"
        }
    }
}
