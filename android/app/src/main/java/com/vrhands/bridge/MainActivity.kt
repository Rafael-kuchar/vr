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
 *
 * v0.2: the camera server starts when the app opens, every step and every error is written to a log
 * on the screen (and at http://PHONE:8080/ in the PC browser). Tap the screen to hide/show the log.
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
    private lateinit var infoView: TextView
    private lateinit var logView: TextView

    private var glView: GLSurfaceView? = null
    private var player: ExoPlayer? = null
    private var tracker: ArTracker? = null
    private var poseSender: PoseSender? = null
    private var mjpeg: MjpegServer? = null

    @Volatile private var started = false
    private var arActive = false
    private var installRequested = false
    private var pcIp = ""
    private var logVisible = true

    private val startTime = System.currentTimeMillis()
    private val logLines = ArrayList<String>()
    @Volatile private var statusLine = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        buildUi()
        hideSystemUi()
        log("App aberto (v0.2). Celular: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        startServer()
        log("Abra no navegador do PC: http://${bestIp()}:$CAMERA_PORT/  (tem que aparecer o status)")
    }

    // ---------------- log ----------------
    private fun log(msg: String) {
        val sec = (System.currentTimeMillis() - startTime) / 1000
        synchronized(logLines) {
            logLines.add("[${sec}s] $msg")
            while (logLines.size > 80) logLines.removeAt(0)
        }
        runOnUiThread { refreshLog() }
    }

    private fun setStatus(msg: String) {
        statusLine = msg
        runOnUiThread { refreshLog() }
    }

    private fun refreshLog() {
        val lines = synchronized(logLines) { logLines.takeLast(9).joinToString("\n") }
        logView.text = if (statusLine.isEmpty()) lines else statusLine + "\n" + lines
    }

    private fun fullLog(): String {
        return synchronized(logLines) { logLines.joinToString("\n") }
    }

    private fun statusPage(): String {
        val sb = StringBuilder()
        sb.append("VR Phone Bridge - servidor da camera OK\n")
        sb.append("Camera p/ hands.py: /video     Foto: /shot.jpg\n\n")
        sb.append("Estado: ").append(if (started) "iniciado" else "aguardando o botao Iniciar no celular").append("\n")
        sb.append(statusLine).append("\n\n")
        sb.append(fullLog()).append("\n")
        return sb.toString()
    }

    // ---------------- UI ----------------
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        logView = TextView(this).apply {
            setTextColor(Color.rgb(120, 255, 120))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            textSize = 11f
            setPadding(120, 24, 120, 24)
        }

        setupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 24, 48, 24)
        }
        val title = TextView(this).apply {
            text = "VR Phone Bridge  (beta 0.2)"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        infoView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 8)
        }
        refreshInfo()
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
        setupPanel.addView(infoView)
        setupPanel.addView(ipField, LinearLayout.LayoutParams(900, ViewGroup.LayoutParams.WRAP_CONTENT))
        setupPanel.addView(start, LinearLayout.LayoutParams(500, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(setupPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(logView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.setOnClickListener {
            if (started) {
                logVisible = !logVisible
                logView.visibility = if (logVisible) View.VISIBLE else View.GONE
            }
        }
        setContentView(root)
    }

    private fun refreshInfo() {
        val ip = bestIp()
        val all = ipList().joinToString("   ") { "${it.first}: ${it.second}" }
        infoView.text = "IP do celular no Wi-Fi: $ip     (todos: ${if (all.isEmpty()) "nenhum, esta no Wi-Fi?" else all})\n" +
            "Teste no navegador do PC: http://$ip:$CAMERA_PORT/\n" +
            "No PC: python hands.py http://$ip:$CAMERA_PORT/video"
    }

    // ---------------- camera server (starts with the app) ----------------
    private fun startServer() {
        if (mjpeg != null) return
        val s = MjpegServer(CAMERA_PORT)
        s.log = { m -> log(m) }
        s.statusText = { statusPage() }
        mjpeg = s
        s.start()
    }

    // ---------------- start button ----------------
    private fun onStartClicked() {
        val ip = ipField.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Digite o IP do PC", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("pc_ip", ip).apply()
        pcIp = ip
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log("Pedindo permissao da camera...")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startAll()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("Permissao da camera: OK")
                startAll()
            } else {
                log("Permissao da camera NEGADA. Sem ela o 6DOF e a camera nao funcionam.")
                Toast.makeText(this, "Sem permissao de camera o 6DOF nao funciona", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAll() {
        if (started) return
        started = true
        setupPanel.visibility = View.GONE
        log("Iniciando. PC = $pcIp (pose na porta $POSE_PORT)")

        val sender = PoseSender(pcIp, POSE_PORT)
        poseSender = sender
        val server = mjpeg ?: MjpegServer(CAMERA_PORT).also { it.start() }
        mjpeg = server

        val t = ArTracker(this, sender, server, { msg -> setStatus(msg) }, { msg -> log(msg) })
        tracker = t

        try {
            startPlayer()
        } catch (e: Exception) {
            log("Video do jogo nao iniciou: ${e.javaClass.simpleName}: ${e.message}")
        }

        val gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(t)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView = gl
        root.addView(gl, FrameLayout.LayoutParams(64, 64, Gravity.BOTTOM or Gravity.END))

        log("Iniciando ARCore...")
        resumeAr()
    }

    // ---------------- ARCore ----------------
    private fun resumeAr() {
        val t = tracker ?: return
        if (arActive) return
        try {
            if (t.session == null) {
                val avail = ArCoreApk.getInstance().checkAvailability(this)
                log("ARCore: disponibilidade = ${avail.name}")
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        log("ARCore: abrindo a instalacao/atualizacao (Play Store). Volte ao app depois.")
                        return                      // the install screen opens; onResume calls this again
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }
                log("ARCore: instalado, criando a sessao...")
                t.createSession()
                log("ARCore: sessao criada")
            }
            t.resumeSession()
            glView?.onResume()
            arActive = true
            log("ARCore: sessao ligada (camera aberta)")
        } catch (e: UnavailableUserDeclinedInstallationException) {
            showError("Instale o 'Servicos do Google Play para RA' (ARCore) para usar o 6DOF.")
        } catch (e: UnavailableArcoreNotInstalledException) {
            showError("ARCore nao esta instalado. Instale 'Servicos do Google Play para RA'.")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showError("Este celular nao suporta ARCore (sem 6DOF).")
        } catch (e: CameraNotAvailableException) {
            showError("Camera em uso por outro app (IP Webcam, Iriun...). Feche e reabra.")
        } catch (e: Exception) {
            showError("Erro ao iniciar o ARCore: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun showError(msg: String) {
        log("ERRO: $msg")
        runOnUiThread { logView.setTextColor(Color.rgb(255, 130, 130)) }
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
        log("Video do jogo: esperando em udp porta $VIDEO_PORT (so aparece quando o PC mandar)")
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
        refreshInfo()
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

    /** (interface name, IPv4) of every active network interface */
    private fun ipList(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (ni in Collections.list(ifaces)) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in Collections.list(ni.inetAddresses)) {
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            out.add(Pair(ni.name, a.hostAddress ?: ""))
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** the Wi-Fi address (wlan0), not the mobile-data one */
    private fun bestIp(): String {
        val l = ipList()
        val pick = l.firstOrNull { it.first.startsWith("wlan") }
            ?: l.firstOrNull { it.first.startsWith("eth") || it.first.startsWith("ap") }
            ?: l.firstOrNull()
        return pick?.second ?: "IP_DO_CELULAR"
    }
}
