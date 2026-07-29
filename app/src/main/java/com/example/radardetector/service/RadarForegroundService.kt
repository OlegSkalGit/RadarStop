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
import com.example.radardetector.LogViewerActivity
import com.example.radardetector.audio.AcousticRadarEngine
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.math.RadarMath
import com.example.radardetector.network.OverpassSyncManager
import com.example.radardetector.util.AppLogger

class RadarForegroundService : Service(), LocationListener {

    companion object {
        const val CHANNEL_ID = "radar_detector_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP_SERVICE = "com.example.radardetector.ACTION_STOP_SERVICE"

        @Volatile
        var isRunning = false
    }

    private lateinit var locationManager: LocationManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var syncManager: OverpassSyncManager
    private lateinit var audioEngine: AcousticRadarEngine

    private var currentIntervalMs: Long = 0L
    private var lastLocation: Location? = null

    private var cachedCameras: List<Camera> = emptyList()
    private var cachedBoxMinLat = 0.0
    private var cachedBoxMaxLat = 0.0
    private var cachedBoxMinLon = 0.0
    private var cachedBoxMaxLon = 0.0

    private var lastStationaryTimeMs = 0L
    private var lastLoggedSpeedMode: String = ""
    private var currentAlertCameraId: Long? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        AppLogger.initNewSession(this)
        AppLogger.log("RadarForegroundService", "onCreate", true, "Service instance created.")

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        dbHelper = DatabaseHelper(this)
        syncManager = OverpassSyncManager(this, dbHelper) { statusMsg ->
            updateNotificationText(statusMsg)
        }
        audioEngine = AcousticRadarEngine(this)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting up..."))

        updateNotificationText("Searching for GPS...")
        registerGpsUpdates(3000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLogger.log("RadarForegroundService", "onStartCommand", true, "Received ACTION_STOP_SERVICE intent.")
            stopSelfAndCleanup()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun registerGpsUpdates(intervalMs: Long) {
        if (currentIntervalMs == intervalMs) return
        currentIntervalMs = intervalMs
        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                this
            )
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", true, "GPS polling interval set to: ${intervalMs}ms")
        } catch (e: SecurityException) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "Permission missing: ${e.message}")
            updateNotificationText("Weak GPS signal (>15m)")
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "Error: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > 15f) {
            if (lastLoggedSpeedMode != "WEAK_GPS") {
                lastLoggedSpeedMode = "WEAK_GPS"
                AppLogger.log("RadarForegroundService", "onLocationChanged", false, "GPS accuracy dropped (>15m: ${location.accuracy}m). Alerts paused.")
            }
            updateNotificationText("Weak GPS signal (>15m)")
            audioEngine.stopAlert()
            return
        }

        lastLocation = location
        val speedKmh = location.speed * 3.6f

        if (speedKmh == 0f) {
            if (lastStationaryTimeMs == 0L) lastStationaryTimeMs = System.currentTimeMillis()
        } else {
            lastStationaryTimeMs = 0L
        }
        val isStationaryFor3Hours = lastStationaryTimeMs > 0 &&
                (System.currentTimeMillis() - lastStationaryTimeMs >= 3 * 3600 * 1000L)

        syncManager.onLocationUpdate(location, speedKmh, isStationaryFor3Hours)

        val lat = location.latitude
        val lon = location.longitude

        if (cachedCameras.isEmpty() || lat < cachedBoxMinLat || lat > cachedBoxMaxLat || lon < cachedBoxMinLon || lon > cachedBoxMaxLon) {
            cachedBoxMinLat = lat - 0.045
            cachedBoxMaxLat = lat + 0.045
            cachedBoxMinLon = lon - 0.045
            cachedBoxMaxLon = lon + 0.045
            cachedCameras = dbHelper.getCamerasInBox(cachedBoxMinLat, cachedBoxMaxLat, cachedBoxMinLon, cachedBoxMaxLon)
            AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Refreshed camera bounding cache: ${cachedCameras.size} cameras loaded.")
        }

        val hasNearbyCameraIn3km = hasCameraWithinRadius(location, cachedCameras, 3000.0)
        val targetInterval = when {
            speedKmh <= 30f -> {
                if (lastLoggedSpeedMode != "SLOW") {
                    lastLoggedSpeedMode = "SLOW"
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Mode: Speed <= 30 km/h ($speedKmh km/h). GPS interval: 10s.")
                }
                10000L
            }
            speedKmh <= 70f -> {
                if (lastLoggedSpeedMode != "CITY") {
                    lastLoggedSpeedMode = "CITY"
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Mode: City 31-70 km/h ($speedKmh km/h). GPS interval: 3s.")
                }
                3000L
            }
            speedKmh > 70f && !hasNearbyCameraIn3km -> {
                if (lastLoggedSpeedMode != "SMART_SLEEP") {
                    lastLoggedSpeedMode = "SMART_SLEEP"
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Mode: Smart Sleep (>70 km/h, no cameras in 3km horizon). GPS interval: 15s.")
                }
                15000L
            }
            else -> {
                if (lastLoggedSpeedMode != "HIGHWAY") {
                    lastLoggedSpeedMode = "HIGHWAY"
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Mode: Highway (>70 km/h with cameras). GPS interval: 1s.")
                }
                1000L
            }
        }
        registerGpsUpdates(targetInterval)

        if (speedKmh <= 30f) {
            currentAlertCameraId = null
            audioEngine.stopAlert()
            val totalInDb = dbHelper.getCameraCount()
            updateNotificationText("Active. Cameras in DB: $totalInDb")
            return
        }

        var closestAlertCamera: Camera? = null
        var maxVApproach = 0f
        var minDistanceToAlert = Float.MAX_VALUE

        for (camera in cachedCameras) {
            val (vApproach, distance) = RadarMath.calculateApproachSpeed(location, camera.lat, camera.lon)
            if (distance <= 300f && vApproach > 30f) {
                if (RadarMath.isAzimuthValid(location.bearing, camera.dir)) {
                    if (distance < minDistanceToAlert) {
                        minDistanceToAlert = distance
                        maxVApproach = vApproach
                        closestAlertCamera = camera
                    }
                }
            }
        }

        if (closestAlertCamera != null) {
            val speedInt = maxVApproach.toInt()
            val distInt = minDistanceToAlert.toInt()

            if (currentAlertCameraId != closestAlertCamera.id) {
                currentAlertCameraId = closestAlertCamera.id
                Toast.makeText(
                    applicationContext,
                    "Radar! Approaching: $speedInt km/h (${distInt}m)",
                    Toast.LENGTH_LONG
                ).show()
                AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Triggered 5s Toast alert for Camera #${closestAlertCamera.id}")
            }

            val timeToCollisionSec = minDistanceToAlert / (maxVApproach / 3.6f)
            val delayMs = when {
                timeToCollisionSec > 15f -> 1500L
                timeToCollisionSec >= 8f -> 800L
                timeToCollisionSec >= 4f -> 400L
                else -> 100L
            }

            audioEngine.startAlert(delayMs)
            audioEngine.updateDelay(delayMs)

            updateNotificationText("Radar! Approaching: $speedInt km/h (${distInt}m)")
        } else {
            currentAlertCameraId = null
            audioEngine.stopAlert()
            val totalInDb = dbHelper.getCameraCount()
            updateNotificationText("Active. Cameras in DB: $totalInDb")
        }
    }

    private fun hasCameraWithinRadius(userLoc: Location, cameras: List<Camera>, radiusMeters: Double): Boolean {
        for (cam in cameras) {
            val camLoc = Location("").apply {
                latitude = cam.lat
                longitude = cam.lon
            }
            if (userLoc.distanceTo(camLoc) <= radiusMeters) {
                return true
            }
        }
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radar Detector Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status of Background Radar Detector"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, RadarForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val pStopIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val logIntent = Intent(this, LogViewerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pLogIntent = PendingIntent.getActivity(
            this, 1, logIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Radar Detector Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_info_details, "Logs", pLogIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn Off", pStopIntent)
            .build()
    }

    private fun updateNotificationText(text: String) {
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
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
