package com.example.radardetector.util

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val PREFS_NAME = "radar_prefs"
    private const val KEY_DEBUG_MODE = "debug_mode"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_USER_STOPPED = "user_stopped"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDebugMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEBUG_MODE, false)
    }

    fun setDebugMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }

    fun isAutostart(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTOSTART, false)
    }

    fun setAutostart(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    fun isUserStopped(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USER_STOPPED, false)
    }

    fun setUserStopped(context: Context, stopped: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USER_STOPPED, stopped).apply()
    }
}
