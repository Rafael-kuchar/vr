package com.vrhands.bridge

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan

class StereoRenderer(
    private val activity: Activity,
    private val s: HeadsetSettings,
    private val sensor: HeadSensor,
    private val onSurface: (Surface) -> Unit,
    private val onSurfaceGone: () -> Unit,
    private val onLog: (String) -> Unit,
    private val onFirstVideo: () -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val VERT = """
attribute vec2 a_pos;
varying vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}
"""
        private const val FRAG = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 v_uv;
uniform samplerExternalOES u_tex;
uniform mat4 u_texMat;
uniform vec2 u_res;
uniform vec4 u_p;
uniform vec4 u_lens;
uniform mat3 u_warp;
void main() {
  float eye = step(0.5, v_uv.x);
  float ew = u_res.x * 0.5;
  float side = eye * 2.0 - 1.0;
  float px = v_uv.x * u_res.x - (eye + 0.5) * ew;
  float py = (v_uv.y - 0.5) * u_res.y;
  px -= side * u_lens.x * ew;
  py -= u_lens.y * u_res.y;
  vec2 p = vec2(px, py) / (u_res.y * 0.5);
  float r2 = dot(p, p);
  vec2 t = p * (1.0 + u_p.x * r2 + u_p.y * r2 * r2) * u_p.z;
  if (u_lens.z > 0.5) {
    vec3 r = u_warp * normalize(vec3(t, -1.0));
    if (r.z > -0.05) {
      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
      return;
    }
    t = r.xy / (-r.z);
  }
  vec2 q = t / u_p.w * 0.5 + 0.5;
  if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    return;
  }
  float texEye = mix(eye, 1.0 - eye, u_lens.w);
  vec2 uv = vec2((q.x + texEye) * 0.5, q.y);
  vec2 tc = (u_texMat * vec4(uv, 0.0, 1.0)).xy;
  gl_FragColor = texture2D(u_tex, tc);
}
"""
        private const val HUD_VERT = """
attribute vec2 a_pos;
uniform vec2 u_center;
uniform vec2 u_half;
varying vec2 v_uv;
void main() {
  v_uv = vec2(a_pos.x * 0.5 + 0.5, 0.5 - a_pos.y * 0.5);
  gl_Position = vec4(u_center + a_pos * u_half, 0.0, 1.0);
}
"""
        private const val HUD_FRAG = """
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
void main() {
  gl_FragColor = texture2D(u_tex, v_uv);
}
"""
        private const val BMP_W = 720
        private const val BMP_H = 110
    }

    private var prog = 0
    private var uTex = 0
    private var uTexMat = 0
    private var uRes = 0
    private var uP = 0
    private var uLens = 0
    private var uWarp = 0
    private var aPos = 0

    private var hudProg = 0
    private var hUCenter = 0
    private var hUHalf = 0
    private var hUTex = 0
    private var hAPos = 0

    private var oesTex = 0
    private var hudTex = 0
    private var st: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val frameReady = AtomicBoolean(false)
    private var gotFrame = false
    private var announced = false

    private val texMat = FloatArray(16)
    private val warpMat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var viewW = 1
    private var viewH = 1

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    @Volatile private var hudText = ""
    @Volatile private var hudUntilNs = 0L
    private var hudShown = "\u0000"

    private val names = arrayOf(
        "Lente k1", "Lente k2", "Zoom (FOV da lente)", "Distancia das lentes (IPD)", "Altura das lentes",
        "FOV da imagem do PC", "Trocar olhos", "Timewarp", "Latencia do timewarp", "Inverter timewarp", "Restaurar padrao"
    )
    @Volatile private var sel = 0

    init {
        Matrix.setIdentityM(texMat, 0)
    }

    private fun fovDeg(tan: Float): Int = Math.toDegrees(2.0 * atan(tan.toDouble())).toInt()

    private fun describe(): String {
        val v = when (sel) {
            0 -> "%.2f".format(s.k1)
            1 -> "%.2f".format(s.k2)
            2 -> "${fovDeg(s.tanV)} graus"
            3 -> "%+d%%".format((s.ipd * 100f).toInt())
            4 -> "%+d%%".format((s.vOff * 100f).toInt())
            5 -> "${fovDeg(s.srcTan)} graus"
            6 -> if (s.swapEyes) "sim" else "nao"
            7 -> if (s.warp) "ligado" else "desligado"
            8 -> "${s.warpMs.toInt()} ms"
            9 -> if (s.warpInvert) "sim" else "nao"
            else -> "(vol + para restaurar)"
        }
        return "${sel + 1}/${names.size} ${names[sel]}: $v"
    }

    fun showHud(text: String, ms: Long = 2500L) {
        hudText = text
        hudUntilNs = System.nanoTime() + ms * 1_000_000L
    }

    fun nextParam() {
        sel = (sel + 1) % names.size
        showHud(describe(), 4000L)
    }

    fun adjust(dir: Int) {
        val d = dir.toFloat()
        when (sel) {
            0 -> s.k1 = (s.k1 + 0.02f * d).coerceIn(-0.3f, 1.2f)
            1 -> s.k2 = (s.k2 + 0.01f * d).coerceIn(-0.3f, 0.8f)
            2 -> s.tanV = (s.tanV + 0.02f * d).coerceIn(0.3f, 1.8f)
            3 -> s.ipd = (s.ipd + 0.01f * d).coerceIn(-0.4f, 0.4f)
            4 -> s.vOff = (s.vOff + 0.01f * d).coerceIn(-0.4f, 0.4f)
            5 -> s.srcTan = (s.srcTan + 0.05f * d).coerceIn(0.4f, 3.0f)
            6 -> s.swapEyes = dir > 0
            7 -> s.warp = dir > 0
            8 -> s.warpMs = (s.warpMs + 5f * d).coerceIn(0f, 300f)
            9 -> s.warpInvert = dir > 0
            else -> if (dir > 0) s.reset()
        }
        s.save()
        showHud(describe(), 4000L)
    }

    @Synchronized
    fun releaseVideo() {
        if (videoSurface != null) {
            try { onSurfaceGone() } catch (_: Exception) { }
        }
        try { videoSurface?.release() } catch (_: Exception) { }
        try { st?.release() } catch (_: Exception) { }
        videoSurface = null
        st = null
        gotFrame = false
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            onLog("Shader NAO compilou: ${GLES20.glGetShaderInfoLog(sh)}")
            GLES20.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    private fun program(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            onLog("Programa GL NAO linkou: ${GLES20.glGetProgramInfoLog(p)}")
            return 0
        }
        return p
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseVideo()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        prog = program(VERT, FRAG)
        hudProg = program(HUD_VERT, HUD_FRAG)
        if (prog != 0) {
            uTex = GLES20.glGetUniformLocation(prog, "u_tex")
            uTexMat = GLES20.glGetUniformLocation(prog, "u_texMat")
            uRes = GLES20.glGetUniformLocation(prog, "u_res")
            uP = GLES20.glGetUniformLocation(prog, "u_p")
            uLens = GLES20.glGetUniformLocation(prog, "u_lens")
            uWarp = GLES20.glGetUniformLocation(prog, "u_warp")
            aPos = GLES20.glGetAttribLocation(prog, "a_pos")
        }
        if (hudProg != 0) {
            hUCenter = GLES20.glGetUniformLocation(hudProg, "u_center")
            hUHalf = GLES20.glGetUniformLocation(hudProg, "u_half")
            hUTex = GLES20.glGetUniformLocation(hudProg, "u_tex")
            hAPos = GLES20.glGetAttribLocation(hudProg, "a_pos")
        }
        val tex = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        oesTex = tex[0]
        hudTex = tex[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        hudShown = "\u0000"

        val t = SurfaceTexture(oesTex)
        t.setOnFrameAvailableListener { frameReady.set(true) }
        val surf = Surface(t)
        synchronized(this) {
            st = t
            videoSurface = surf
        }
        onLog("Headset: renderizador de lentes pronto")
        onSurface(surf)
        showHud("Vol +/- muda o ajuste | toque troca o ajuste", 7000L)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = maxOf(width, 1)
        viewH = maxOf(height, 1)
        GLES20.glViewport(0, 0, viewW, viewH)
        onLog("Headset: tela ${viewW}x${viewH}")
    }

    override fun onDrawFrame(gl: GL10?) {
        val t = st
        if (t != null && frameReady.getAndSet(false)) {
            try {
                t.updateTexImage()
                t.getTransformMatrix(texMat)
                gotFrame = true
                if (!announced) {
                    announced = true
                    onFirstVideo()
                }
            } catch (_: Exception) {
            }
        }
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (gotFrame && prog != 0) drawEyes()
        if (hudProg != 0) drawHud()
    }

    private fun drawEyes() {
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
        GLES20.glUniform2f(uRes, viewW.toFloat(), viewH.toFloat())
        GLES20.glUniform4f(uP, s.k1, s.k2, s.tanV, s.srcTan)
        val warpOn = s.warp && sensor.relative(s.warpMs, warpMat, s.warpInvert)
        GLES20.glUniform4f(uLens, s.ipd, s.vOff, if (warpOn) 1f else 0f, if (s.swapEyes) 1f else 0f)
        GLES20.glUniformMatrix3fv(uWarp, 1, false, warpMat, 0)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun renderHudBitmap(text: String) {
        val bmp = Bitmap.createBitmap(BMP_W, BMP_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = Color.argb(200, 0, 0, 0)
        c.drawRoundRect(RectF(0f, 0f, BMP_W.toFloat(), BMP_H.toFloat()), 24f, 24f, bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        var size = 46f
        p.textSize = size
        while (p.measureText(text) > BMP_W - 40 && size > 18f) {
            size -= 2f
            p.textSize = size
        }
        c.drawText(text, BMP_W / 2f, BMP_H / 2f + size * 0.35f, p)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    private fun drawHud() {
        val text = when {
            !gotFrame -> "Esperando o video do PC..."
            System.nanoTime() < hudUntilNs -> hudText
            else -> ""
        }
        if (text.isEmpty()) return
        if (text != hudShown) {
            renderHudBitmap(text)
            hudShown = text
        }
        GLES20.glUseProgram(hudProg)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
        GLES20.glUniform1i(hUTex, 0)
        val halfW = 0.36f
        val halfH = halfW * viewW.toFloat() * BMP_H / BMP_W / viewH.toFloat()
        quad.position(0)
        GLES20.glEnableVertexAttribArray(hAPos)
        GLES20.glVertexAttribPointer(hAPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform2f(hUHalf, halfW, halfH)
        for (cx in floatArrayOf(-0.5f, 0.5f)) {
            GLES20.glUniform2f(hUCenter, cx, -0.62f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(hAPos)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
