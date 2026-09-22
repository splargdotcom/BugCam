package com.splarg.bugcam

import android.content.Context

data class AppConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 10,
    val jpegQuality: Int = 85,
    val rotation: Int = 0,
    val port: Int = 8080,
) {
    fun save(context: Context) {
        prefs(context).edit()
            .putInt("width", width).putInt("height", height).putInt("fps", fps)
            .putInt("quality", jpegQuality).putInt("rotation", rotation)
            .putInt("port", port).apply()
    }

    companion object {
        val sizes = listOf(1920 to 1080, 1280 to 720, 640 to 480)
        val rates = listOf(1, 3, 5, 10, 15)
        val rotations = listOf(0, 90, 180, 270)
        private fun prefs(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences("bugcam", Context.MODE_PRIVATE)

        fun load(context: Context): AppConfig {
            val p = prefs(context)
            val size = 1920 to 1080
            return AppConfig(size.first, size.second,
                p.getInt("fps", 10).coerceIn(1, 10),
                p.getInt("quality", 85).coerceIn(50, 95),
                p.getInt("rotation", 0).takeIf { it in rotations } ?: 0,
                p.getInt("port", 8080).coerceIn(1024, 65535))
        }
    }
}


/** Persisted manual focus lock. Device-protected so it is available before first unlock. */
object FocusMemory {
    private const val PREFS = "bugcam"
    private const val KEY_LOCKED = "focus_locked"
    private const val KEY_DISTANCE = "focus_distance_diopters"

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadLockedDistance(context: Context): Float? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_LOCKED, false) || !p.contains(KEY_DISTANCE)) return null
        return p.getFloat(KEY_DISTANCE, Float.NaN).takeIf { it.isFinite() && it >= 0f }
    }

    fun saveLocked(context: Context, distanceDiopters: Float) {
        prefs(context).edit()
            .putBoolean(KEY_LOCKED, true)
            .putFloat(KEY_DISTANCE, distanceDiopters)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_LOCKED, false)
            .remove(KEY_DISTANCE)
            .apply()
    }
}

object BugCamRuntime {
    @Volatile var running = false
    @Volatile var summary = "Stopped"
    @Volatile var lastError: String? = null
}


/** Persisted AE exposure compensation, in Camera2 native 1/6-EV steps. */
object ExposureMemory {
    private const val PREFS = "bugcam"
    private const val KEY_STEPS = "exposure_compensation_steps"

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Int =
        prefs(context).getInt(KEY_STEPS, 0)

    fun save(context: Context, steps: Int) {
        prefs(context).edit()
            .putInt(KEY_STEPS, steps)
            .apply()
    }
}
