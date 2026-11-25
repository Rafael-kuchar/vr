package com.vrhands.bridge

import android.content.Context

class HeadsetSettings(private val ctx: Context) {
    @Volatile var k1 = DEF_K1
    @Volatile var k2 = DEF_K2
    @Volatile var tanV = DEF_TANV
    @Volatile var srcTan = DEF_SRCTAN
    @Volatile var ipd = 0f
    @Volatile var vOff = 0f
    @Volatile var swapEyes = false
    @Volatile var warp = false
    @Volatile var warpMs = DEF_WARP_MS
    @Volatile var warpInvert = false

    companion object {
        const val DEF_K1 = 0.20f
        const val DEF_K2 = 0.05f
        const val DEF_TANV = 0.85f
        const val DEF_SRCTAN = 1.0f
        const val DEF_WARP_MS = 80f
    }

    private fun prefs() = ctx.getSharedPreferences("headset", Context.MODE_PRIVATE)

    fun load() {
        val p = prefs()
        k1 = p.getFloat("k1", DEF_K1)
        k2 = p.getFloat("k2", DEF_K2)
        tanV = p.getFloat("tanV", DEF_TANV)
        srcTan = p.getFloat("srcTan", DEF_SRCTAN)
        ipd = p.getFloat("ipd", 0f)
        vOff = p.getFloat("vOff", 0f)
        swapEyes = p.getBoolean("swapEyes", false)
        warp = p.getBoolean("warp", false)
        warpMs = p.getFloat("warpMs", DEF_WARP_MS)
        warpInvert = p.getBoolean("warpInvert", false)
    }

    fun save() {
        prefs().edit()
            .putFloat("k1", k1)
            .putFloat("k2", k2)
            .putFloat("tanV", tanV)
            .putFloat("srcTan", srcTan)
            .putFloat("ipd", ipd)
            .putFloat("vOff", vOff)
            .putBoolean("swapEyes", swapEyes)
            .putBoolean("warp", warp)
            .putFloat("warpMs", warpMs)
            .putBoolean("warpInvert", warpInvert)
            .apply()
    }

    fun reset() {
        k1 = DEF_K1
        k2 = DEF_K2
        tanV = DEF_TANV
        srcTan = DEF_SRCTAN
        ipd = 0f
        vOff = 0f
        swapEyes = false
        warp = false
        warpMs = DEF_WARP_MS
        warpInvert = false
        save()
    }
}
