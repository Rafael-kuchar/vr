package com.vrhands.bridge

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything that can be adjusted about how the game image is shown in the Cardboard.
 * Saved on the phone, so it is kept between sessions. Read by the GL thread (hence @Volatile).
 */
class VrSettings(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("vr_view", Context.MODE_PRIVATE)

    /** distance between the two lens centers, in millimeters (your IPD) */
    @Volatile var ipdMm: Float = prefs.getFloat("ipd", DEF_IPD)

    /** image size: bigger = zoom in (smaller field of view) */
    @Volatile var zoom: Float = prefs.getFloat("zoom", DEF_ZOOM)

    /** lens distortion coefficients (barrel correction): k1 acts in the middle, k2 near the edges */
    @Volatile var k1: Float = prefs.getFloat("k1", DEF_K1)
    @Volatile var k2: Float = prefs.getFloat("k2", DEF_K2)

    /** chromatic aberration correction: red and blue are scaled in opposite ways (0 = off) */
    @Volatile var chroma: Float = prefs.getFloat("chroma", DEF_CHROMA)

    /** moves both images up (+) or down (-), in millimeters */
    @Volatile var yOffMm: Float = prefs.getFloat("yoff", DEF_YOFF)

    @Volatile var distortion: Boolean = prefs.getBoolean("distortion", true)
    @Volatile var swapEyes: Boolean = prefs.getBoolean("swap", false)
    @Volatile var testGrid: Boolean = prefs.getBoolean("grid", false)

    fun save() {
        prefs.edit()
            .putFloat("ipd", ipdMm)
            .putFloat("zoom", zoom)
            .putFloat("k1", k1)
            .putFloat("k2", k2)
            .putFloat("chroma", chroma)
            .putFloat("yoff", yOffMm)
            .putBoolean("distortion", distortion)
            .putBoolean("swap", swapEyes)
            .putBoolean("grid", testGrid)
            .apply()
    }

    fun reset() {
        ipdMm = DEF_IPD
        zoom = DEF_ZOOM
        k1 = DEF_K1
        k2 = DEF_K2
        chroma = DEF_CHROMA
        yOffMm = DEF_YOFF
        distortion = true
        swapEyes = false
        testGrid = false
        save()
    }

    companion object {
        const val DEF_IPD = 63f
        const val DEF_ZOOM = 1.0f
        const val DEF_K1 = 0.22f
        const val DEF_K2 = 0.10f
        const val DEF_CHROMA = 0.010f
        const val DEF_YOFF = 0f
    }
}
