package com.example.radardetector.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized utility for managing application SharedPreferences ("radar_prefs").
 */
object AppPrefs {
    private const val PREFS_NAME = "radar_prefs"

    const val KEY_USER_STOPPED = "user_stopped"
    const val KEY_AUTOSTART = "autostart"
    const val KEY_DEBUG_MODE = "debug_mode"
    const val KEY_LOGGING_ENABLED = "pref_logging_enabled"
    const val KEY_LAST_UPDATE_CHECK = "last_update_check_ms"
    const val KEY_LAST_SYNC_TIME_MS = "last_sync_time_ms"
    const val KEY_LAST_COUNTRY_SYNC_MS = "last_country_sync_ms"
    const val KEY_LAST_SYNCED_LAT = "last_synced_lat"
    const val KEY_LAST_SYNCED_LON = "last_synced_lon"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // User Stopped
    fun isUserStopped(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USER_STOPPED, false)
    }

    fun setUserStopped(context: Context, stopped: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USER_STOPPED, stopped).apply()
    }

    // Autostart
    fun isAutostartEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTOSTART, false)
    }

    fun setAutostartEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    // Debug Mode
    fun isDebugMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEBUG_MODE, false)
    }

    fun setDebugMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }

    // Logging Enabled
    fun isLoggingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LOGGING_ENABLED, false)
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
    }

    // Update Check Timestamp
    fun getLastUpdateCheckMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0L)
    }

    fun setLastUpdateCheckMs(context: Context, timeMs: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK, timeMs).apply()
    }

    // Overpass Sync
    fun getLastSyncTimeMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SYNC_TIME_MS, 0L)
    }

    fun setLastSyncData(context: Context, timeMs: Long, lat: Double, lon: Double) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_SYNC_TIME_MS, timeMs)
            .putFloat(KEY_LAST_SYNCED_LAT, lat.toFloat())
            .putFloat(KEY_LAST_SYNCED_LON, lon.toFloat())
            .apply()
    }

    fun getLastSyncedLat(context: Context): Double {
        return getPrefs(context).getFloat(KEY_LAST_SYNCED_LAT, 0f).toDouble()
    }

    fun getLastSyncedLon(context: Context): Double {
        return getPrefs(context).getFloat(KEY_LAST_SYNCED_LON, 0f).toDouble()
    }

    fun getLastCountrySyncMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_COUNTRY_SYNC_MS, 0L)
    }

    fun setLastCountrySyncMs(context: Context, timeMs: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putLong(KEY_LAST_COUNTRY_SYNC_MS, timeMs).apply()
    }
}
