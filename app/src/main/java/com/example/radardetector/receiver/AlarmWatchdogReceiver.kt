package com.example.radardetector.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.util.AppLogger

class AlarmWatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_WATCHDOG = "com.example.radardetector.ACTION_ALARM_WATCHDOG"
        const val ALARM_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        fun scheduleNextAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, AlarmWatchdogReceiver::class.java).apply {
                action = ACTION_ALARM_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAtMs = System.currentTimeMillis() + ALARM_INTERVAL_MS

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
                AppLogger.log("AlarmWatchdogReceiver", "scheduleNextAlarm", true, "Next 15-min exact alarm scheduled.")
            } catch (e: SecurityException) {
                AppLogger.log("AlarmWatchdogReceiver", "scheduleNextAlarm", false, "Permission missing for exact alarm: ${e.message}")
            } catch (e: Exception) {
                AppLogger.log("AlarmWatchdogReceiver", "scheduleNextAlarm", false, "Failed to schedule exact alarm: ${e.message}")
            }
        }

        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, AlarmWatchdogReceiver::class.java).apply {
                action = ACTION_ALARM_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.log("AlarmWatchdogReceiver", "onReceive", true, "15-min AlarmManager exact tick received. Service running: ${RadarForegroundService.isRunning}")

        if (!RadarForegroundService.isRunning) {
            val prefs = context.getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)
            val isAutostartEnabled = prefs.getBoolean("autostart", false)
            if (isAutostartEnabled) {
                try {
                    val serviceIntent = Intent(context, RadarForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    AppLogger.log("AlarmWatchdogReceiver", "onReceive", true, "Service restarted by 15-min AlarmManager tick.")
                } catch (e: Exception) {
                    AppLogger.log("AlarmWatchdogReceiver", "onReceive", false, "Failed to restart service on alarm tick: ${e.message}")
                }
            }
        }

        // Re-arm next 15-min alarm
        scheduleNextAlarm(context)
    }
}
