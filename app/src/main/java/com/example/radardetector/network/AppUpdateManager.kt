package com.example.radardetector.network

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.example.radardetector.util.AppLogger
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
            onResult?.let { mainHandler.post { it(msg) } }
            return
        }

        executor.execute {
            performUpdateCheck(context, prefs, onResult)
        }
    }

    private fun performUpdateCheck(
        context: Context,
        prefs: SharedPreferences,
        onResult: ((String) -> Unit)?
    ) {
        val appContext = context.applicationContext
        AppLogger.log("AppUpdateManager", "performUpdateCheck", true, "Starting GitHub release update check for $REPO_OWNER/$REPO_NAME...")

        // 1. Get current installed app version (versionName) ONLY
        val installedVersionName = try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }
        } catch (e: Exception) {
            "1.0"
        }
        val localInstalledVer = extractVersionNumbers(installedVersionName ?: "1.0")

        AppLogger.log(
            "AppUpdateManager",
            "performUpdateCheck",
            true,
            "Local installed version (versionName): $installedVersionName ($localInstalledVer)"
        )

        // 2. Fetch releases from GitHub API
        var conn: HttpURLConnection? = null
        try {
            val url = URL(API_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "RadarStop-Updater/1.0")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val code = conn.responseCode
            if (code != 200) {
                val err = "GitHub API returned HTTP status code $code"
                AppLogger.log("AppUpdateManager", "performUpdateCheck", false, err)
                onResult?.let { mainHandler.post { it(err) } }
                return
            }

            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(jsonText)

            var latestRemoteVer: List<Int> = emptyList()
            var latestRemoteName = ""
            var latestRemoteUrl = ""

            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
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

            // 3. Compare remote version directly with local installed version
            if (isVersionNewer(latestRemoteVer, localInstalledVer)) {
                AppLogger.log(
                    "AppUpdateManager",
                    "performUpdateCheck",
                    true,
                    "NEW VERSION DETECTED! Downloading $latestRemoteName from GitHub releases..."
                )
                val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
                val destFile = File(downloadDir, latestRemoteName)
                val success = downloadFileWithRedirects(latestRemoteUrl, destFile)
                if (success) {
                    prefs.edit().putLong(PREF_KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                    val successMsg = "New version downloaded: $latestRemoteName"
                    AppLogger.log(
                        "AppUpdateManager",
                        "performUpdateCheck",
                        true,
                        "SUCCESS: App update downloaded to ${destFile.absolutePath} (${destFile.length()} bytes)"
                    )
                    onResult?.let { mainHandler.post { it(successMsg) } }
                } else {
                    val failMsg = "Failed to download update APK: $latestRemoteName"
                    AppLogger.log("AppUpdateManager", "performUpdateCheck", false, failMsg)
                    onResult?.let { mainHandler.post { it(failMsg) } }
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

        } catch (e: Exception) {
            val errMsg = "Update check error: ${e.message}"
            AppLogger.log("AppUpdateManager", "performUpdateCheck", false, errMsg)
            onResult?.let { mainHandler.post { it(errMsg) } }
        } finally {
            conn?.disconnect()
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
