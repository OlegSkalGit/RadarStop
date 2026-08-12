package com.example.radardetector.util

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.example.radardetector.service.RadarForegroundService

/**
 * Utility functions for service management and common Intent creations.
 */
object ServiceUtils {

    val PENDING_INTENT_IMMUTABLE_FLAGS: Int =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    fun startRadarForegroundService(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            AppLogger.log("ServiceUtils", "startRadarForegroundService", false, "Failed to start service: ${e.message}")
        }
    }

    fun startRadarServiceForCountrySync(context: Context, countryCode: String, countryName: String) {
        val serviceIntent = Intent(context, RadarForegroundService::class.java).apply {
            action = RadarForegroundService.ACTION_LOAD_COUNTRY_CAMS
            putExtra(RadarForegroundService.EXTRA_COUNTRY_CODE, countryCode)
            putExtra(RadarForegroundService.EXTRA_COUNTRY_NAME, countryName)
        }
        startRadarForegroundService(context, serviceIntent)
    }

    fun startRadarServiceInDeepSleep(context: Context) {
        val serviceIntent = Intent(context, RadarForegroundService::class.java).apply {
            putExtra(RadarForegroundService.EXTRA_START_IN_DEEP_SLEEP, true)
        }
        startRadarForegroundService(context, serviceIntent)
    }
}

inline fun <reified T : Activity> Context.createSingleTopIntent(): Intent {
    return Intent(this, T::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}

/**
 * Triple-provider location acquisition (GPS, Network, Passive).
 * Returns the most recently updated Location among all active providers.
 */
fun LocationManager.getBestLastKnownLocation(): Location? {
    return try {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        providers.mapNotNull { provider ->
            try { getLastKnownLocation(provider) } catch (e: Exception) { null }
        }.maxByOrNull { it.time }
    } catch (e: Exception) {
        null
    }
}

fun LocationManager.isGpsSystemDisabled(): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            !isLocationEnabled
        } else {
            !isProviderEnabled(LocationManager.GPS_PROVIDER) && !isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (e: Exception) {
        !isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
}

fun Context.isUserStopped(): Boolean {
    return getSharedPreferences("radar_prefs", Context.MODE_PRIVATE).getBoolean("user_stopped", false)
}

fun Context.setUserStopped(stopped: Boolean) {
    getSharedPreferences("radar_prefs", Context.MODE_PRIVATE).edit().putBoolean("user_stopped", stopped).apply()
}

fun Context.isAutostartEnabled(): Boolean {
    return getSharedPreferences("radar_prefs", Context.MODE_PRIVATE).getBoolean("autostart", false)
}

fun Context.setAutostartEnabled(enabled: Boolean) {
    getSharedPreferences("radar_prefs", Context.MODE_PRIVATE).edit().putBoolean("autostart", enabled).apply()
}

fun Location.isAccuracyWeak(): Boolean {
    return hasAccuracy() && accuracy > 100f
}
