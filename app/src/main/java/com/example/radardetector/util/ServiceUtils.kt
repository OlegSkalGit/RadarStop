package com.example.radardetector.util

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
}

inline fun <reified T : Activity> Context.createSingleTopIntent(): Intent {
    return Intent(this, T::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}

fun Context.getAppVersionName(): String {
    return try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L)).versionName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }
    } catch (e: Exception) {
        "1.0"
    }
}
