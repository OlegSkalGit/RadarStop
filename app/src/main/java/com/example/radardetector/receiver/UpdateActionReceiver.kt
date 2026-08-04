package com.example.radardetector.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.radardetector.network.AppUpdateManager

class UpdateActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(AppUpdateManager.NOTIFICATION_ID)

        when (action) {
            AppUpdateManager.ACTION_DOWNLOAD_UPDATE -> {
                val downloadUrl = intent.getStringExtra(AppUpdateManager.EXTRA_DOWNLOAD_URL) ?: return
                val fileName = intent.getStringExtra(AppUpdateManager.EXTRA_FILE_NAME) ?: return
                AppUpdateManager.startDownloadFromNotification(context.applicationContext, downloadUrl, fileName)
            }
            AppUpdateManager.ACTION_POSTPONE_UPDATE -> {
                AppUpdateManager.postponeUpdate(context.applicationContext)
            }
        }
    }
}
