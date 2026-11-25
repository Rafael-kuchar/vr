package com.vrhands.bridge

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

class HeadSensor(private val activity: Activity) : SensorEventListener {
    private val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val cap = 600
    private val times = LongArray(cap)
    private val mats = FloatArray(cap * 9)
    private var next = 0
    private var count = 0
    private val lock = Any()
    private val rdev = FloatArray(9)
    private val m = FloatArray(9)

    @Volatile var running = false
        private set
    @Volatile var sensorName = ""
        private set
    @Volatile var samples = 0L
        private set

    fun start(): Boolean {
        if (running) return true
        val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return false
        sensorName = s.name
        running = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
        return running
    }

    fun stop() {
        sm.unregisterListener(this)
        running = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = activity.windowManager.defaultDisplay.rotation

    private fun screenBasis(rotation: Int): FloatArray = when (rotation) {
        Surface.ROTATION_90 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        Surface.ROTATION_270 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        Surface.ROTATION_180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    override fun onSensorChanged(e: SensorEvent) {
        try {
            SensorManager.getRotationMatrixFromVector(rdev, e.values)
        } catch (_: Exception) {
            return
        }
        val s = screenBasis(displayRotation())
        for (i in 0..2) {
            for (j in 0..2) {
                var a = 0f
                for (k in 0..2) a += rdev[i * 3 + k] * s[k * 3 + j]
                m[i * 3 + j] = a
            }
        }
        synchronized(lock) {
            times[next] = e.timestamp
            System.arraycopy(m, 0, mats, next * 9, 9)
            next = (next + 1) % cap
            if (count < cap) count++
        }
        samples++
    }

    fun relative(ageMs: Float, out: FloatArray, invert: Boolean): Boolean {
        synchronized(lock) {
            if (count < 2) return false
            val newest = (next - 1 + cap) % cap
            val target = times[newest] - (ageMs * 1_000_000f).toLong()
            var idx = newest
            var n = 0
            while (n < count) {
                val i = (newest - n + cap) % cap
                idx = i
                if (times[i] <= target) break
                n++
            }
            for (i in 0..2) {
                for (j in 0..2) {
                    var a = 0f
                    for (k in 0..2) a += mats[idx * 9 + k * 3 + i] * mats[newest * 9 + k * 3 + j]
                    if (invert) out[i * 3 + j] = a else out[j * 3 + i] = a
                }
            }
            return true
        }
    }
}
