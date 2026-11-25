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
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

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
    private var stereoView: GLSurfaceView? = null
    private var stereo: StereoRenderer? = null
    private var headSensor: HeadSensor? = null
    private var video: VideoReceiver? = null
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

    private val ticker = object : Runnable {
        override fun run() {
            refreshLog()
            ui.postDelayed(this, 500)
        }
    }

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
        log("App aberto (v0.4 headset). Celular: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        startServer()
        log("Abra no navegador do PC: http://${bestIp()}:$CAMERA_PORT/  (tem que aparecer o status)")
    }

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
        val parts = ArrayList<String>()
        if (statusLine.isNotEmpty()) parts.add(statusLine)
        video?.let { parts.add(it.stats) }
        parts.add(lines)
        logView.text = parts.joinToString("\n")
    }

    private fun fullLog(): String {
        return synchronized(logLines) { logLines.joinToString("\n") }
    }

    private fun statusPage(): String {
        val sb = StringBuilder()
        sb.append("VR Phone Bridge - servidor da camera OK\n")
        sb.append("Camera p/ hands.py: /video     Foto: /shot.jpg\n\n")
        sb.append("Estado: ").append(if (started) "iniciado" else "aguardando o botao Iniciar no celular").append("\n")
        sb.append(statusLine).append("\n")
        sb.append(video?.stats ?: "video: nao iniciado").append("\n\n")
        sb.append(fullLog()).append("\n")
        return sb.toString()
    }

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
            text = "VR Phone Bridge  (beta 0.4 headset)"
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

    private fun startServer() {
        if (mjpeg != null) return
        val s = MjpegServer(CAMERA_PORT)
        s.log = { m -> log(m) }
        s.statusText = { statusPage() }
        mjpeg = s
        s.start()
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

        val gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(t)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView = gl
        root.addView(gl, 0, FrameLayout.LayoutParams(64, 64, Gravity.BOTTOM or Gravity.END))

        try {
            startVideo()
        } catch (e: Exception) {
            log("Video do jogo nao iniciou: ${e.javaClass.simpleName}: ${e.message}")
        }

        ui.post(ticker)
        log("Iniciando ARCore...")
        resumeAr()
    }

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
                        return
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

    private fun startVideo() {
        if (video != null) return
        val receiver = VideoReceiver(VIDEO_PORT, { m -> log(m) }, { w, h -> log("Video: ${w}x${h}") })
        video = receiver
        val settings = HeadsetSettings(this)
        settings.load()
        val sensor = HeadSensor(this)
        headSensor = sensor
        if (sensor.start()) log("Giroscopio: ${sensor.sensorName}") else log("Giroscopio nao encontrado (timewarp indisponivel)")
        val renderer = StereoRenderer(
            this, settings, sensor,
            { surf -> receiver.attach(surf) },
            { receiver.detach() },
            { m -> log(m) },
            { onFirstVideoFrame() }
        )
        stereo = renderer
        val sv = GLSurfaceView(this)
        sv.setEGLContextClientVersion(2)
        sv.setRenderer(renderer)
        sv.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        sv.setZOrderMediaOverlay(true)
        sv.setOnClickListener { renderer.nextParam() }
        sv.setOnLongClickListener {
            toggleLog()
            true
        }
        stereoView = sv
        root.addView(sv, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        receiver.start()
        log("Headset: video em udp porta $VIDEO_PORT. Toque = trocar ajuste, volume +/- = mudar, segurar = mostrar/esconder este log")
    }

    private fun onFirstVideoFrame() {
        runOnUiThread {
            logVisible = false
            logView.visibility = View.GONE
        }
    }

    private fun toggleLog() {
        logVisible = !logVisible
        logView.visibility = if (logVisible) View.VISIBLE else View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val r = stereo
        if (started && r != null) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                r.adjust(1)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                r.adjust(-1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        refreshInfo()
        if (started) {
            stereoView?.onResume()
            headSensor?.start()
            resumeAr()
        }
    }

    override fun onPause() {
        super.onPause()
        if (started) {
            stereo?.releaseVideo()
            stereoView?.onPause()
            headSensor?.stop()
            glView?.onPause()
            tracker?.pauseSession()
            arActive = false
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        video?.stop()
        video = null
        headSensor?.stop()
        tracker?.closeSession()
        mjpeg?.stop()
        poseSender?.close()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

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

    private fun bestIp(): String {
        val l = ipList()
        val pick = l.firstOrNull { it.first.startsWith("wlan") }
            ?: l.firstOrNull { it.first.startsWith("eth") || it.first.startsWith("ap") }
            ?: l.firstOrNull()
        return pick?.second ?: "IP_DO_CELULAR"
    }
}
