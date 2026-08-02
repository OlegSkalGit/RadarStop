package com.example.radardetector.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.util.AppLogger

class RadarServiceWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        AppLogger.log("RadarServiceWorker", "doWork", true, "15-min WorkManager self-healing tick triggered. Service running: ${RadarForegroundService.isRunning}")

        if (RadarForegroundService.isRunning) {
            val serviceInstance = RadarForegroundService.instance
            if (serviceInstance != null) {
                AppLogger.log("RadarServiceWorker", "doWork", true, "Service is running. Triggering GPS stall check from WorkManager...")
                serviceInstance.checkWatchdogStall()
            }
        } else {
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
                    AppLogger.log("RadarServiceWorker", "doWork", true, "Self-Healing: RadarForegroundService automatically restarted by WorkManager.")
                } catch (e: Exception) {
                    AppLogger.log("RadarServiceWorker", "doWork", false, "Self-Healing failed to restart service: ${e.message}")
                }
            }
        }

        return Result.success()
    }
}
