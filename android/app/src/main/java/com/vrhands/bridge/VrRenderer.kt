package com.vrhands.bridge

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the game video (side-by-side, left eye | right eye) fullscreen for a Cardboard-style headset:
 *   - IPD: the two lens centers are [VrSettings.ipdMm] apart
 *   - lens correction: barrel distortion (k1, k2)
 *   - chromatic aberration correction: red and blue sampled at slightly different scales
 *   - zoom, vertical offset, swap eyes, and a test grid (shown too while no video has arrived)
 *
 * The video decoder (ExoPlayer) writes into [onSurface]'s Surface; this class turns it into a texture.
 */
class VrRenderer(
    private val settings: VrSettings,
    private val pxPerMm: Float,
    private val onSurface: (Surface) -> Unit,
    private val onLog: (String) -> Unit
) : GLSurfaceView.Renderer {

    private var program = 0
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val frameFlag = AtomicBoolean(false)
    @Volatile private var hasFrame = false
    private val stMatrix = FloatArray(16)

    private var viewW = 1
    private var viewH = 1
    @Volatile private var eyeAspect = 960f / 1080f      // width / height of ONE eye image
    private var errorLogged = false

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    // uniform locations
    private var aPos = 0
    private var uTex = 0
    private var uST = 0
    private var uRes = 0
    private var uIpdPx = 0
    private var uZoom = 0
    private var uK1 = 0
    private var uK2 = 0
    private var uChroma = 0
    private var uYOff = 0
    private var uAspect = 0
    private var uSwap = 0
    private var uDistort = 0
    private var uTest = 0

    /** called when the decoder reports the video size (the whole side-by-side frame) */
    fun setVideoSize(w: Int, h: Int) {
        if (w > 0 && h > 0) eyeAspect = (w / 2f) / h
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            program = buildProgram()
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uST = GLES20.glGetUniformLocation(program, "uST")
            uRes = GLES20.glGetUniformLocation(program, "uRes")
            uIpdPx = GLES20.glGetUniformLocation(program, "uIpdPx")
            uZoom = GLES20.glGetUniformLocation(program, "uZoom")
            uK1 = GLES20.glGetUniformLocation(program, "uK1")
            uK2 = GLES20.glGetUniformLocation(program, "uK2")
            uChroma = GLES20.glGetUniformLocation(program, "uChroma")
            uYOff = GLES20.glGetUniformLocation(program, "uYOff")
            uAspect = GLES20.glGetUniformLocation(program, "uAspect")
            uSwap = GLES20.glGetUniformLocation(program, "uSwap")
            uDistort = GLES20.glGetUniformLocation(program, "uDistort")
            uTest = GLES20.glGetUniformLocation(program, "uTest")
        } catch (e: Exception) {
            onLog("VR: erro nos shaders: ${e.message}")
            return
        }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surface?.release()
        surfaceTexture?.release()
        hasFrame = false
        frameFlag.set(false)
        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener { frameFlag.set(true) }
        surfaceTexture = st
        val s = Surface(st)
        surface = s
        Matrix.setIdentityM(stMatrix, 0)
        onLog("VR: tela de video pronta")
        onSurface(s)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = maxOf(width, 1)
        viewH = maxOf(height, 1)
        GLES20.glViewport(0, 0, viewW, viewH)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val st = surfaceTexture ?: return
        if (program == 0) return
        try {
            if (frameFlag.getAndSet(false)) {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                hasFrame = true
            }
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)
            GLES20.glUniform2f(uRes, viewW.toFloat(), viewH.toFloat())
            GLES20.glUniform1f(uIpdPx, settings.ipdMm * pxPerMm)
            GLES20.glUniform1f(uZoom, maxOf(settings.zoom, 0.05f))
            GLES20.glUniform1f(uK1, settings.k1)
            GLES20.glUniform1f(uK2, settings.k2)
            GLES20.glUniform1f(uChroma, settings.chroma)
            GLES20.glUniform1f(uYOff, settings.yOffMm * pxPerMm)
            GLES20.glUniform1f(uAspect, eyeAspect)
            GLES20.glUniform1f(uSwap, if (settings.swapEyes) 1f else 0f)
            GLES20.glUniform1f(uDistort, if (settings.distortion) 1f else 0f)
            GLES20.glUniform1f(uTest, if (settings.testGrid || !hasFrame) 1f else 0f)

            quad.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
        } catch (e: Exception) {
            if (!errorLogged) {
                errorLogged = true
                onLog("VR: erro ao desenhar: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val msg = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("link: $msg")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val msg = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException(msg)
        }
        return s
    }

    companion object {
        private const val VERTEX = "attribute vec4 aPos;\n" +
            "void main() { gl_Position = aPos; }\n"

        // p = position of the pixel relative to the lens center, in units of half the screen height.
        // The image is sampled at p * f(r) (barrel distortion), red/blue at slightly different scales (chroma).
        private const val FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uST;\n" +
            "uniform vec2 uRes;\n" +
            "uniform float uIpdPx;\n" +
            "uniform float uZoom;\n" +
            "uniform float uK1;\n" +
            "uniform float uK2;\n" +
            "uniform float uChroma;\n" +
            "uniform float uYOff;\n" +
            "uniform float uAspect;\n" +
            "uniform float uSwap;\n" +
            "uniform float uDistort;\n" +
            "uniform float uTest;\n" +
            "float lineD(float v) { float f = fract(v); return min(f, 1.0 - f); }\n" +
            "float gridAt(vec2 s) {\n" +
            "  vec2 q = s * 5.0;\n" +
            "  float g = step(lineD(q.x), 0.03) + step(lineD(q.y), 0.03);\n" +
            "  g += step(abs(s.x), 0.008) + step(abs(s.y), 0.008);\n" +
            "  return clamp(g, 0.0, 1.0);\n" +
            "}\n" +
            "vec3 eyeColor(vec2 s, float eyeIdx) {\n" +
            "  vec2 e = vec2(s.x / uAspect, s.y);\n" +
            "  if (abs(e.x) > 1.0 || abs(e.y) > 1.0) return vec3(0.0);\n" +
            "  if (uTest > 0.5) { return vec3(gridAt(e)); }\n" +
            "  vec2 t = vec2((e.x * 0.5 + 0.5 + eyeIdx) * 0.5, e.y * 0.5 + 0.5);\n" +
            "  return texture2D(uTex, (uST * vec4(t, 0.0, 1.0)).xy).rgb;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 frag = gl_FragCoord.xy;\n" +
            "  float cx = uRes.x * 0.5;\n" +
            "  bool left = frag.x < cx;\n" +
            "  float lensX = left ? cx - uIpdPx * 0.5 : cx + uIpdPx * 0.5;\n" +
            "  float unit = uRes.y * 0.5;\n" +
            "  vec2 p = vec2(frag.x - lensX, frag.y - (uRes.y * 0.5 + uYOff)) / unit;\n" +
            "  p = p / uZoom;\n" +
            "  float f = 1.0;\n" +
            "  if (uDistort > 0.5) { float r2 = dot(p, p); f = 1.0 + uK1 * r2 + uK2 * r2 * r2; }\n" +
            "  float eyeIdx = left ? 0.0 : 1.0;\n" +
            "  if (uSwap > 0.5) { eyeIdx = 1.0 - eyeIdx; }\n" +
            "  float r = eyeColor(p * f * (1.0 + uChroma), eyeIdx).r;\n" +
            "  float g = eyeColor(p * f, eyeIdx).g;\n" +
            "  float b = eyeColor(p * f * (1.0 - uChroma), eyeIdx).b;\n" +
            "  gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}\n"
    }
}
