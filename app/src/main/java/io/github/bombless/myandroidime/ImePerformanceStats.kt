package io.github.bombless.myandroidime

import android.content.Context

/** Persists the last input-method startup measurement for the app's status screen. */
internal object ImePerformanceStats {
    private const val PREFS_NAME = "ime_performance_stats"
    private const val KEY_STARTUP_DURATION_MS = "startup_duration_ms"

    fun markStarting(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_STARTUP_DURATION_MS, -1L)
            .apply()
    }

    fun markReady(context: Context, durationMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_STARTUP_DURATION_MS, durationMs.coerceAtLeast(0L))
            .apply()
    }

    fun startupDurationMs(context: Context): Long? {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_STARTUP_DURATION_MS, -1L)
        return value.takeIf { it >= 0L }
    }
}
