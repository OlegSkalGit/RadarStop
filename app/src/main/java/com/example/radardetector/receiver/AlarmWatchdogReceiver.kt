package com.example.radardetector.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.util.AppLogger
import com.example.radardetector.util.AppPrefs

class AlarmWatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_WATCHDOG = "com.example.radardetector.ACTION_ALARM_WATCHDOG"
        const val ALARM_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        private fun getWatchdogPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, AlarmWatchdogReceiver::class.java).apply {
                action = ACTION_ALARM_WATCHDOG
            }
            return PendingIntent.getBroadcast(
                context,
                1002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun scheduleNextAlarm(context: Context) {
            if (AppPrefs.isUserStopped(context)) {
                AppLogger.log("AlarmWatchdogReceiver", "scheduleNextAlarm", true, "User stopped app manually. Skipping AlarmManager scheduling.")
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = getWatchdogPendingIntent(context)
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
            alarmManager.cancel(getWatchdogPendingIntent(context))
            AppLogger.log("AlarmWatchdogReceiver", "cancelAlarm", true, "AlarmManager watchdog alarm cancelled.")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (AppPrefs.isUserStopped(context)) {
            AppLogger.log("AlarmWatchdogReceiver", "onReceive", true, "App was manually stopped by user. Cancelling alarm and exiting.")
            cancelAlarm(context)
            return
        }

        AppLogger.log("AlarmWatchdogReceiver", "onReceive", true, "15-min AlarmManager exact tick received. Service running: ${RadarForegroundService.isRunning}")

        if (RadarForegroundService.isRunning) {
            val serviceInstance = RadarForegroundService.instance
            if (serviceInstance != null) {
                AppLogger.log("AlarmWatchdogReceiver", "onReceive", true, "Service is running. Triggering GPS stall check...")
                serviceInstance.checkWatchdogStall()
            }
        } else {
            try {
                val serviceIntent = Intent(context, RadarForegroundService::class.java).apply {
                    putExtra(RadarForegroundService.EXTRA_START_IN_DEEP_SLEEP, true)
                }
                com.example.radardetector.util.ServiceUtils.startRadarForegroundService(context, serviceIntent)
                AppLogger.log("AlarmWatchdogReceiver", "onReceive", true, "Service restarted in Deep Sleep mode with active accelerometer by AlarmManager.")
            } catch (e: Exception) {
                AppLogger.log("AlarmWatchdogReceiver", "onReceive", false, "Failed to restart service on alarm tick: ${e.message}")
            }
        }

        // Re-arm next 15-min alarm
        scheduleNextAlarm(context)
    }
}
