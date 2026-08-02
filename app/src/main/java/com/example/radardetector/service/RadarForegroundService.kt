package com.example.radardetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.radardetector.HelpActivity
import com.example.radardetector.audio.AcousticRadarEngine
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.math.RadarMath
import com.example.radardetector.network.OverpassSyncManager
import com.example.radardetector.util.AppLogger
import java.util.concurrent.Executors

class RadarForegroundService : Service(), LocationListener {

    companion object {
        const val CHANNEL_ID = "radar_detector_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP_SERVICE = "com.example.radardetector.ACTION_STOP_SERVICE"
        const val ACTION_LOAD_COUNTRY_CAMS = "com.example.radardetector.ACTION_LOAD_COUNTRY_CAMS"
        const val EXTRA_COUNTRY_CODE = "extra_country_code"
        const val EXTRA_COUNTRY_NAME = "extra_country_name"

        @Volatile
        var isRunning = false
    }

    private lateinit var locationManager: LocationManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var syncManager: OverpassSyncManager
    private lateinit var audioEngine: AcousticRadarEngine

    private var lastLocation: Location? = null

    @Volatile
    private var cachedCameras: List<Camera> = emptyList()
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private var cachedBoxMinLat = 0.0
    private var cachedBoxMaxLat = 0.0
    private var cachedBoxMinLon = 0.0
    private var cachedBoxMaxLon = 0.0
    private var lastRamReloadLat = 0.0
    private var lastRamReloadLon = 0.0

    private var speedDropBelow30TimeMs = 0L
    private var lastLoggedSpeedMode: String = ""
    private var currentAlertCameraId: Long? = null
    private var effectiveSpeedKmh: Float = 0f

    private var activeLinearEntryCam: Camera? = null
    private var activeLinearExitCam: Camera? = null
    private var prevDistToEntryCam: Float = Float.MAX_VALUE
    private var prevDistToExitCam: Float = Float.MAX_VALUE
    private var isDepartingFromEntry: Boolean = false
    private var currentGpsIntervalMs: Long = -1L

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val appVersionName = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (e: Exception) {
            "1.0"
        }

        AppLogger.initNewSession(this)
        AppLogger.log("RadarForegroundService", "onCreate", true, "Foreground Service created. App Version: v$appVersionName")

        Toast.makeText(applicationContext, "RadarStop Active", Toast.LENGTH_SHORT).show()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        dbHelper = DatabaseHelper(this)
        syncManager = OverpassSyncManager(
            this,
            dbHelper,
            onStatusUpdate = { statusMsg -> updateNotificationText(statusMsg) },
            onSyncSuccess = { _, _ ->
                lastLocation?.let { loc ->
                    reloadCameraCacheForLocation(loc)
                }
            }
        )
        audioEngine = AcousticRadarEngine(this)
        audioEngine.playSingleBeep()

        createNotificationChannel()
        val initialText = "Searching for GPS..."
        lastNotificationText = initialText
        startForeground(NOTIF_ID, buildNotification(initialText))

        registerGpsUpdates(3000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLogger.log("RadarForegroundService", "onStartCommand", true, "Received ACTION_STOP_SERVICE intent. Stopping service...")
            stopSelfAndCleanup()
            return START_NOT_STICKY
        } else if (intent?.action == ACTION_LOAD_COUNTRY_CAMS) {
            val code = intent.getStringExtra(EXTRA_COUNTRY_CODE) ?: "UA"
            val name = intent.getStringExtra(EXTRA_COUNTRY_NAME) ?: "Ukraine"
            syncManager.triggerCountryCameraSync(code, name)
            return START_STICKY
        }
        return START_STICKY
    }

    @Volatile
    private var lastGpsRegisterTimeMs: Long = 0L

    private fun registerGpsUpdates(intervalMs: Long) {
        if (currentGpsIntervalMs == intervalMs) return
        val now = System.currentTimeMillis()
        if (intervalMs > currentGpsIntervalMs && currentGpsIntervalMs > 0L && (now - lastGpsRegisterTimeMs < 5000L)) {
            return
        }
        currentGpsIntervalMs = intervalMs
        lastGpsRegisterTimeMs = now
        try {
            locationManager.removeUpdates(this)
            val minDistance = if (intervalMs >= 15000L) 10f else 0f
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                minDistance,
                this
            )
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", true, "GPS polling registered at ${intervalMs}ms (minDistance: ${minDistance}m)")
        } catch (e: SecurityException) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "Location permission missing: ${e.message}")
            updateNotificationText("Weak GPS signal (>15m)")
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "GPS request failed: ${e.message}")
        }
    }

    @Volatile
    private var cachedTotalCameraCount: Int = 0

    private fun reloadCameraCacheForLocation(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        lastRamReloadLat = lat
        lastRamReloadLon = lon
        cachedBoxMinLat = lat - 0.045
        cachedBoxMaxLat = lat + 0.045
        cachedBoxMinLon = lon - 0.045
        cachedBoxMaxLon = lon + 0.045
        if (cachedCameras.isEmpty()) {
            val boxCams = dbHelper.getCamerasInBox(cachedBoxMinLat, cachedBoxMaxLat, cachedBoxMinLon, cachedBoxMaxLon)
            val linearCams = dbHelper.getAllLinearCameras()
            cachedCameras = (boxCams + linearCams).distinctBy { it.id }
            cachedTotalCameraCount = dbHelper.getCameraCount()
            AppLogger.log(
                "RadarForegroundService",
                "reloadCameraCacheForLocation",
                true,
                "DATABASE LOAD (Sync): Loaded ${cachedCameras.size} cameras (${linearCams.size} linear) from SQLite DB into RAM for current location ($lat, $lon). Total in DB: $cachedTotalCameraCount"
            )
        } else {
            if (dbExecutor.isShutdown) return
            dbExecutor.execute {
                val freshBox = dbHelper.getCamerasInBox(cachedBoxMinLat, cachedBoxMaxLat, cachedBoxMinLon, cachedBoxMaxLon)
                val linearCams = dbHelper.getAllLinearCameras()
                val count = dbHelper.getCameraCount()
                cachedCameras = (freshBox + linearCams).distinctBy { it.id }
                cachedTotalCameraCount = count
                AppLogger.log(
                    "RadarForegroundService",
                    "reloadCameraCacheForLocation",
                    true,
                    "DATABASE LOAD (Async): Loaded ${cachedCameras.size} cameras (${linearCams.size} linear) from SQLite DB into RAM for current location ($lat, $lon). Total in DB: $cachedTotalCameraCount"
                )
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!isRunning) return
        if (location.hasAccuracy() && location.accuracy > 15f) {
            val accInt = location.accuracy.toInt()
            if (lastLoggedSpeedMode != "WEAK_GPS") {
                lastLoggedSpeedMode = "WEAK_GPS"
                AppLogger.log("RadarForegroundService", "onLocationChanged", false, "GPS accuracy dropped (>15m: ${location.accuracy}m). Alerting paused.")
            }
            updateNotificationText("Weak GPS signal (>15m [${accInt}m])")
            audioEngine.stopAlert()
            return
        }

        val now = System.currentTimeMillis()
        lastLocation = location
        val rawSpeedKmh = location.speed * 3.6f
        val speedKmh = if (effectiveSpeedKmh == 0f) {
            rawSpeedKmh
        } else {
            val delta = Math.abs(rawSpeedKmh - effectiveSpeedKmh)
            if (delta > 10f) rawSpeedKmh else effectiveSpeedKmh
        }
        effectiveSpeedKmh = speedKmh

        syncManager.onLocationUpdate(location, speedKmh)

        val lat = location.latitude
        val lon = location.longitude

        val distFromRamReload = FloatArray(1)
        if (lastRamReloadLat != 0.0 || lastRamReloadLon != 0.0) {
            Location.distanceBetween(lat, lon, lastRamReloadLat, lastRamReloadLon, distFromRamReload)
        }

        if (cachedCameras.isEmpty() || distFromRamReload[0] >= 4000f || lat < cachedBoxMinLat || lat > cachedBoxMaxLat || lon < cachedBoxMinLon || lon > cachedBoxMaxLon) {
            reloadCameraCacheForLocation(location)
        }

        val maxGpsReadDistance = if (speedKmh <= 60f) 500f else 1000f
        val maxBeepAlertDistance = 300f
        val continuousThreshold = if (speedKmh <= 60f) 50f else 100f

        var minDistToAnyCamera = Float.MAX_VALUE
        var closestAlertCamera: Camera? = null
        var minDistanceToAlert = Float.MAX_VALUE

        for (camera in cachedCameras) {
            val distance = RadarMath.calculateDistance(location, camera.lat, camera.lon)
            if (distance < minDistToAnyCamera) {
                minDistToAnyCamera = distance
            }

            val isWithin300mBeepZone = distance <= maxBeepAlertDistance &&
                    RadarMath.isAzimuthValid(location, camera.dir)

            if (isWithin300mBeepZone) {
                if (distance < minDistanceToAlert) {
                    minDistanceToAlert = distance
                    closestAlertCamera = camera
                }
            }
        }

        val isInActiveLinearZone = (activeLinearEntryCam != null)
        val isWithinGps1sDistance = (minDistToAnyCamera <= maxGpsReadDistance)
        val hasNearbyCameraIn3km = isInActiveLinearZone || (minDistToAnyCamera <= 3000f)

        val targetInterval = if (speedKmh <= 30f) {
            if (speedDropBelow30TimeMs == 0L) {
                speedDropBelow30TimeMs = now
            }
            val timeBelow30Ms = now - speedDropBelow30TimeMs
            if (timeBelow30Ms < 3 * 60 * 1000L) {
                if (lastLoggedSpeedMode != "SLOW_GRACE_3MIN") {
                    lastLoggedSpeedMode = "SLOW_GRACE_3MIN"
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "SPEED THRESHOLD: Speed <= 30 km/h (${speedKmh.toInt()} km/h). 3-min Grace Period active: Polling interval kept at 3s.")
                }
                3000L
            } else {
                if (lastLoggedSpeedMode != "SLOW") {
                    lastLoggedSpeedMode = "SLOW"
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "SPEED THRESHOLD: Speed <= 30 km/h for >3 minutes (${speedKmh.toInt()} km/h). Polling interval: 30s. Beep alerts inactive.")
                }
                30000L
            }
        } else {
            speedDropBelow30TimeMs = 0L
            when {
                isWithinGps1sDistance || isInActiveLinearZone -> {
                    if (lastLoggedSpeedMode != "CAMERA_NEARBY_1S") {
                        lastLoggedSpeedMode = "CAMERA_NEARBY_1S"
                        AppLogger.log("RadarForegroundService", "onLocationChanged", true, "SPEED THRESHOLD: Within 1s GPS zone (${minDistToAnyCamera.toInt()}m). Polling interval: 1s.")
                    }
                    1000L
                }
                hasNearbyCameraIn3km -> {
                    if (lastLoggedSpeedMode != "NORMAL_3S") {
                        lastLoggedSpeedMode = "NORMAL_3S"
                        AppLogger.log("RadarForegroundService", "onLocationChanged", true, "SPEED THRESHOLD: Cameras within 3km (${minDistToAnyCamera.toInt()}m). Polling interval: 3s.")
                    }
                    3000L
                }
                else -> {
                    if (lastLoggedSpeedMode != "SMART_SLEEP") {
                        lastLoggedSpeedMode = "SMART_SLEEP"
                        AppLogger.log("RadarForegroundService", "onLocationChanged", true, "SPEED THRESHOLD: Smart Sleep (No cameras within 3km). Polling interval: 15s.")
                    }
                    15000L
                }
            }
        }
        registerGpsUpdates(targetInterval)

        val defaultStatusText = "Active. Cameras: ${cachedCameras.size} nearby / $cachedTotalCameraCount total"

        if (speedKmh <= 30f) {
            if (currentAlertCameraId != null) {
                AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Exited camera alert zone (Speed dropped <= 30 km/h).")
                currentAlertCameraId = null
            }
            audioEngine.stopAlert()
            updateNotificationText(defaultStatusText)
            return
        }

        if (closestAlertCamera != null) {
            val speedInt = speedKmh.toInt()
            val distInt = minDistanceToAlert.toInt()

            if (closestAlertCamera.isLinear) {
                if (activeLinearEntryCam?.id != closestAlertCamera.id) {
                    activeLinearEntryCam = closestAlertCamera
                    activeLinearExitCam = cachedCameras.filter { it.isLinear && it.id != closestAlertCamera.id }
                        .minByOrNull { RadarMath.calculateDistance(location, it.lat, it.lon) }
                    prevDistToEntryCam = RadarMath.calculateDistance(location, activeLinearEntryCam!!.lat, activeLinearEntryCam!!.lon)
                    prevDistToExitCam = activeLinearExitCam?.let { RadarMath.calculateDistance(location, it.lat, it.lon) } ?: Float.MAX_VALUE
                    isDepartingFromEntry = false
                }
            }

            if (currentAlertCameraId != closestAlertCamera.id) {
                currentAlertCameraId = closestAlertCamera.id
                Toast.makeText(
                    applicationContext,
                    "Radar! Distance: ${distInt}m (${speedInt} km/h)",
                    Toast.LENGTH_LONG
                ).show()
                AppLogger.log(
                    "RadarForegroundService",
                    "onLocationChanged",
                    true,
                    "CAMERA ALERT DETECTED (${if (minDistanceToAlert <= continuousThreshold) "Continuous" else "Approach"} Zone): Camera #${closestAlertCamera.id}. Speed: ${speedInt} km/h, Distance: ${distInt}m, Bearing: ${closestAlertCamera.dir ?: "Omnidirectional"}, Linear: ${closestAlertCamera.isLinear}"
                )
            }

            val delayMs = RadarMath.calculateBeepDelay(minDistanceToAlert, speedKmh)
            audioEngine.startAlert(delayMs)
            audioEngine.updateDelay(delayMs)

            updateNotificationText("Radar! Distance: ${distInt}m (${speedInt} km/h)")
        } else {
            val entryCam = activeLinearEntryCam
            if (entryCam != null) {
                val distEntry = RadarMath.calculateDistance(location, entryCam.lat, entryCam.lon)
                val exitCam = activeLinearExitCam
                val distExit = exitCam?.let { RadarMath.calculateDistance(location, it.lat, it.lon) } ?: Float.MAX_VALUE

                if (distEntry > prevDistToEntryCam + 3f || distEntry > 50f) {
                    isDepartingFromEntry = true
                }

                val isDepartingFromExit = (exitCam != null && distExit > prevDistToExitCam + 3f)

                if (isDepartingFromEntry && (isDepartingFromExit || (exitCam == null && distEntry > 1000f))) {
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Exited linear section (simultaneously departing from entry & exit cameras).")
                    activeLinearEntryCam = null
                    activeLinearExitCam = null
                } else {
                    prevDistToEntryCam = distEntry
                    if (exitCam != null) prevDistToExitCam = distExit

                    val speedInt = speedKmh.toInt()
                    val delayMs = 1500L
                    audioEngine.startAlert(delayMs)
                    audioEngine.updateDelay(delayMs)
                    updateNotificationText("Radar! Linear Zone Alert (${speedInt} km/h)")
                    return
                }
            }

            if (currentAlertCameraId != null) {
                AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Exited camera alert zone (Camera #${currentAlertCameraId} cleared).")
                currentAlertCameraId = null
            }
            audioEngine.stopAlert()
            updateNotificationText(defaultStatusText)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RadarStop Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status of Background RadarStop"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private var cachedNotificationBuilder: NotificationCompat.Builder? = null
    private var lastNotificationText: String? = null

    private fun initNotificationBuilder() {
        if (cachedNotificationBuilder == null) {
            val stopIntent = Intent(this, RadarForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            val pStopIntent = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val helpIntent = Intent(this, HelpActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pHelpIntent = PendingIntent.getActivity(
                this, 1, helpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            cachedNotificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RadarStop Active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_menu_help, "Help", pHelpIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn Off", pStopIntent)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        initNotificationBuilder()
        val builder = cachedNotificationBuilder!!
        builder.setContentText(contentText)
        return builder.build()
    }

    private fun updateNotificationText(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun stopSelfAndCleanup() {
        AppLogger.log("RadarForegroundService", "stopSelfAndCleanup", true, "Cleaning up resources and stopping service.")
        isRunning = false
        try {
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        dbExecutor.shutdownNow()
        audioEngine.release()
        syncManager.shutdown()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfAndCleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onProviderEnabled(provider: String) {
        AppLogger.log("RadarForegroundService", "onProviderEnabled", true, "GPS provider enabled by user/system.")
    }
    override fun onProviderDisabled(provider: String) {
        AppLogger.log("RadarForegroundService", "onProviderDisabled", false, "GPS provider disabled by user/system.")
    }
}
