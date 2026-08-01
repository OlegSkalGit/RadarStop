package com.example.radardetector.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.util.AppLogger

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)
            val isAutostartEnabled = prefs.getBoolean("autostart", false)
            AppLogger.log("BootReceiver", "onReceive", true, "System boot event received ($action). Autostart setting: $isAutostartEnabled")

            if (isAutostartEnabled) {
                val hasFineLocation = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasBgLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                } else true

                if (!hasFineLocation || !hasBgLocation) {
                    AppLogger.log("BootReceiver", "onReceive", false, "Required location permissions (Fine/Background) missing. Skipping autostart.")
                    return
                }

                try {
                    val serviceIntent = Intent(context, RadarForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    AppLogger.log("BootReceiver", "onReceive", true, "RadarForegroundService automatically launched on system boot.")
                } catch (e: IllegalStateException) {
                    AppLogger.log("BootReceiver", "onReceive", false, "ForegroundServiceStartNotAllowedException / Background start restricted by Android OS: ${e.message}")
                } catch (e: Exception) {
                    AppLogger.log("BootReceiver", "onReceive", false, "Failed to start service on boot: ${e.message}")
                }
            }
        }
    }
}
