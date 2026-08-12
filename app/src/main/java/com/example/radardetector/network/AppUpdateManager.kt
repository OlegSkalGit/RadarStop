package com.example.radardetector.network

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.radardetector.receiver.UpdateActionReceiver
import com.example.radardetector.util.AppLogger
import com.example.radardetector.util.getAppVersionName
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AppUpdateManager {

    private const val REPO_OWNER = "OlegSkalGit"
    private const val REPO_NAME = "RadarStop"
    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"
    private const val CHECK_THROTTLE_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val PREFS_NAME = "radar_prefs"
    private const val PREF_KEY_LAST_UPDATE_CHECK = "last_app_update_check_ms"

    const val NOTIFICATION_ID = 9901
    const val NOTIFICATION_CHANNEL_ID = "radar_update_channel"
    const val ACTION_DOWNLOAD_UPDATE = "com.example.radardetector.ACTION_DOWNLOAD_UPDATE"
    const val ACTION_POSTPONE_UPDATE = "com.example.radardetector.ACTION_POSTPONE_UPDATE"
    const val EXTRA_DOWNLOAD_URL = "extra_download_url"
    const val EXTRA_FILE_NAME = "extra_file_name"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun extractVersionNumbers(str: String): List<Int> {
        val regex = Regex("""\d+""")
        return regex.findAll(str).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    fun isVersionNewer(remote: List<Int>, local: List<Int>): Boolean {
        val minLen = minOf(remote.size, local.size)
        for (i in 0 until minLen) {
            if (remote[i] > local[i]) return true
            if (remote[i] < local[i]) return false
        }
        return remote.size > local.size
    }

    fun checkAndDownloadUpdate(
        context: Context,
        force: Boolean = false,
        onResult: ((String) -> Unit)? = null
    ) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckMs = prefs.getLong(PREF_KEY_LAST_UPDATE_CHECK, 0L)
        val now = System.currentTimeMillis()

        if (!force && lastCheckMs != 0L && (now - lastCheckMs < CHECK_THROTTLE_MS)) {
            val hoursLeft = (CHECK_THROTTLE_MS - (now - lastCheckMs)) / 3600000L
            val msg = "Update check skipped (24h throttle active, next check in ~${hoursLeft}h)."
            AppLogger.log("AppUpdateManager", "checkAndDownloadUpdate", true, msg)
            return
        }

        executor.execute {
            performUpdateCheck(context, prefs, force, onResult)
        }
    }

    private fun fetchReleasesFromUrl(urlStr: String): List<org.json.JSONObject> {
        var conn: HttpURLConnection? = null
        val result = mutableListOf<org.json.JSONObject>()
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "RadarStop-Updater/1.0")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode == 200) {
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                if (jsonText.startsWith("{")) {
                    result.add(org.json.JSONObject(jsonText))
                } else if (jsonText.startsWith("[")) {
                    val arr = org.json.JSONArray(jsonText)
                    for (i in 0 until arr.length()) {
                        result.add(arr.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log("AppUpdateManager", "fetchReleasesFromUrl", false, "Error fetching from $urlStr: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return result
    }

    private fun performUpdateCheck(
        context: Context,
        prefs: SharedPreferences,
        force: Boolean,
        onResult: ((String) -> Unit)?
    ) {
        val appContext = context.applicationContext
        AppLogger.log("AppUpdateManager", "performUpdateCheck", true, "Starting GitHub release update check for $REPO_OWNER/$REPO_NAME...")

        val installedVersionName = appContext.getAppVersionName()
        val localInstalledVer = extractVersionNumbers(installedVersionName)

        AppLogger.log(
            "AppUpdateManager",
            "performUpdateCheck",
            true,
            "Local installed version (versionName): $installedVersionName ($localInstalledVer)"
        )

        val releasesList = fetchReleasesFromUrl("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=100")

        if (releasesList.isEmpty()) {
            val err = "Failed to fetch releases from GitHub API."
            AppLogger.log("AppUpdateManager", "performUpdateCheck", false, err)
            onResult?.let { mainHandler.post { it(err) } }
            return
        }

        var latestRemoteVer: List<Int> = emptyList()
        var latestRemoteName = ""
        var latestRemoteUrl = ""

        for (release in releasesList) {
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val assetName = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")

                if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                    val ver = extractVersionNumbers(assetName)
                    if (isVersionNewer(ver, latestRemoteVer)) {
                        latestRemoteVer = ver
                        latestRemoteName = assetName
                        latestRemoteUrl = downloadUrl
                    }
                }
            }
        }

        if (latestRemoteVer.isEmpty() || latestRemoteUrl.isEmpty()) {
            val msg = "No valid APK release assets found on GitHub."
            AppLogger.log("AppUpdateManager", "performUpdateCheck", true, msg)
            prefs.edit().putLong(PREF_KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
            onResult?.let { mainHandler.post { it(msg) } }
            return
        }

        AppLogger.log("AppUpdateManager", "performUpdateCheck", true, "Latest remote version on GitHub: $latestRemoteName ($latestRemoteVer)")

        if (isVersionNewer(latestRemoteVer, localInstalledVer)) {
            AppLogger.log(
                "AppUpdateManager",
                "performUpdateCheck",
                true,
                "NEW VERSION DETECTED! Remote: $latestRemoteName ($latestRemoteVer) > Local: $installedVersionName ($localInstalledVer)"
            )

            if (force) {
                // Manual check: start update download immediately without asking
                AppLogger.log("AppUpdateManager", "performUpdateCheck", true, "Manual check forced - starting update download immediately.")
                prefs.edit().putLong(PREF_KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                startDownload(appContext, latestRemoteUrl, latestRemoteName, onResult)
            } else {
                // Automatic check: prompt user in English ("New version available (Current / New). Download? Later.")
                AppLogger.log("AppUpdateManager", "performUpdateCheck", true, "Automatic check - prompting user for update approval.")
                promptUserForUpdate(context, prefs, installedVersionName, latestRemoteName, latestRemoteUrl, onResult)
            }
        } else {
            prefs.edit().putLong(PREF_KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
            val upToDateMsg = "App is up to date (v$installedVersionName)"
            AppLogger.log(
                "AppUpdateManager",
                "performUpdateCheck",
                true,
                "App is up-to-date. (Remote: $latestRemoteVer <= Installed: $localInstalledVer)"
            )
            onResult?.let { mainHandler.post { it(upToDateMsg) } }
        }
    }

    private fun promptUserForUpdate(
        context: Context,
        prefs: SharedPreferences,
        installedVerStr: String,
        latestRemoteName: String,
        latestRemoteUrl: String,
        onResult: ((String) -> Unit)?
    ) {
        mainHandler.post {
            val title = "New version available"
            val message = "New version available (Current: $installedVerStr / New: $latestRemoteName).\n\nDownload now?"

            val downloadAction = {
                executor.execute {
                    prefs.edit().putLong(PREF_KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                    startDownload(context.applicationContext, latestRemoteUrl, latestRemoteName, onResult)
                }
            }

            val laterAction = {
                postponeUpdate(context.applicationContext)
                val msg = "Update postponed."
                onResult?.let { mainHandler.post { it(msg) } }
            }

            val activity = findActivity(context)
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("Download") { dialog, _ ->
                        dialog.dismiss()
                        downloadAction()
                    }
                    .setNegativeButton("Later") { dialog, _ ->
                        dialog.dismiss()
                        laterAction()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                showUpdateNotification(context, title, message, latestRemoteUrl, latestRemoteName)
            }
        }
    }

    fun postponeUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(PREF_KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
        AppLogger.log("AppUpdateManager", "postponeUpdate", true, "Update postponed by user. Next check in 24h.")
    }

    fun startDownloadFromNotification(context: Context, downloadUrl: String, fileName: String) {
        executor.execute {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(PREF_KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
            startDownload(context, downloadUrl, fileName, null)
        }
    }

    fun startDownload(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onResult: ((String) -> Unit)? = null
    ) {
        val started = downloadWithDownloadManager(context, downloadUrl, fileName)
        if (started) {
            val successMsg = "Downloading update: $fileName"
            AppLogger.log("AppUpdateManager", "startDownload", true, successMsg)
            onResult?.let { mainHandler.post { it(successMsg) } }
        } else {
            val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(publicDownloadDir, fileName)
            val success = downloadFileWithRedirects(downloadUrl, destFile)
            if (success) {
                val successMsg = "New version downloaded: $fileName"
                AppLogger.log(
                    "AppUpdateManager",
                    "startDownload",
                    true,
                    "SUCCESS: App update downloaded to ${destFile.absolutePath} (${destFile.length()} bytes)"
                )
                installApk(context, destFile)
                onResult?.let { mainHandler.post { it(successMsg) } }
            } else {
                val failMsg = "Failed to download update APK: $fileName"
                AppLogger.log("AppUpdateManager", "startDownload", false, failMsg)
                onResult?.let { mainHandler.post { it(failMsg) } }
            }
        }
    }

    private fun findActivity(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    fun performManualUpdateCheck(context: Context) {
        Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()
        checkAndDownloadUpdate(context, force = true) { result ->
            Toast.makeText(context, result, Toast.LENGTH_LONG).show()
        }
    }

    private fun showUpdateNotification(
        context: Context,
        title: String,
        message: String,
        downloadUrl: String,
        fileName: String
    ) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "App Updates",
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }

            val downloadIntent = Intent(context, UpdateActionReceiver::class.java).apply {
                action = ACTION_DOWNLOAD_UPDATE
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_FILE_NAME, fileName)
            }
            val pendingDownload = PendingIntent.getBroadcast(
                context,
                1,
                downloadIntent,
                com.example.radardetector.util.ServiceUtils.PENDING_INTENT_IMMUTABLE_FLAGS
            )

            val laterIntent = Intent(context, UpdateActionReceiver::class.java).apply {
                action = ACTION_POSTPONE_UPDATE
            }
            val pendingLater = PendingIntent.getBroadcast(
                context,
                2,
                laterIntent,
                com.example.radardetector.util.ServiceUtils.PENDING_INTENT_IMMUTABLE_FLAGS
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            val notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_save, "Download", pendingDownload)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Later", pendingLater)
                .setContentIntent(pendingDownload)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
            AppLogger.log("AppUpdateManager", "showUpdateNotification", true, "Posted notification prompt for new version: $fileName")
        } catch (e: Exception) {
            AppLogger.log("AppUpdateManager", "showUpdateNotification", false, "Failed to post update notification: ${e.message}")
        }
    }

    private fun downloadWithDownloadManager(context: Context, downloadUrl: String, fileName: String): Boolean {
        return try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return false

            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!publicDir.exists()) publicDir.mkdirs()
            val existingFile = File(publicDir, fileName)
            if (existingFile.exists()) {
                existingFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("RadarStop Update")
                setDescription("Downloading $fileName...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = downloadManager.enqueue(request)
            AppLogger.log("AppUpdateManager", "downloadWithDownloadManager", true, "Enqueued DownloadManager job $downloadId for $fileName")

            val onCompleteReceiver = object : BroadcastReceiver() {
                override fun onReceive(recvContext: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (id == downloadId) {
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}

                        val downloadedFile = File(publicDir, fileName)
                        if (downloadedFile.exists() && downloadedFile.length() > 0) {
                            AppLogger.log("AppUpdateManager", "onDownloadComplete", true, "Download complete via DownloadManager: ${downloadedFile.absolutePath}")
                            installApk(context, downloadedFile)
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    onCompleteReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    onCompleteReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }

            true
        } catch (e: Exception) {
            AppLogger.log("AppUpdateManager", "downloadWithDownloadManager", false, "Failed to enqueue DownloadManager: ${e.message}")
            false
        }
    }

    fun installApk(context: Context, apkFile: File) {
        mainHandler.post {
            try {
                if (!apkFile.exists()) {
                    AppLogger.log("AppUpdateManager", "installApk", false, "APK file does not exist: ${apkFile.absolutePath}")
                    return@post
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                    } else {
                        Uri.fromFile(apkFile)
                    }
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AppLogger.log("AppUpdateManager", "installApk", true, "Launched installer intent for ${apkFile.name}")
            } catch (e: Exception) {
                AppLogger.log("AppUpdateManager", "installApk", false, "Failed to launch installer intent: ${e.message}")
            }
        }
    }

    private fun downloadFileWithRedirects(urlStr: String, destFile: File, redirectCount: Int = 0): Boolean {
        if (redirectCount > 5) {
            AppLogger.log("AppUpdateManager", "downloadFileWithRedirects", false, "Too many HTTP redirects for $urlStr")
            return false
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("User-Agent", "RadarStop-Updater/1.0")
                instanceFollowRedirects = true
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == 307 ||
                responseCode == 308
            ) {
                val newUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (!newUrl.isNullOrEmpty()) {
                    return downloadFileWithRedirects(newUrl, destFile, redirectCount + 1)
                }
                return false
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                AppLogger.log("AppUpdateManager", "downloadFileWithRedirects", false, "HTTP download status error $responseCode for $urlStr")
                return false
            }

            val totalBytes = conn.contentLengthLong
            val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var downloadedBytes = 0L
                    var lastLoggedPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (percent % 25 == 0 && percent != lastLoggedPercent) {
                                lastLoggedPercent = percent
                                AppLogger.log("AppUpdateManager", "downloadFileWithRedirects", true, "Download progress: $percent% ($downloadedBytes / $totalBytes bytes)")
                            }
                        }
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
                return true
            }
            false
        } catch (e: Exception) {
            AppLogger.log("AppUpdateManager", "downloadFileWithRedirects", false, "Exception downloading $urlStr: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
