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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.radardetector.HelpActivity
import com.example.radardetector.audio.AcousticRadarEngine
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.math.RadarMath
import com.example.radardetector.network.OverpassSyncManager
import com.example.radardetector.receiver.AlarmWatchdogReceiver
import com.example.radardetector.util.AppLogger
import android.os.PowerManager
import java.util.concurrent.Executors

class RadarForegroundService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val CHANNEL_ID = "radar_detector_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP_SERVICE = "com.example.radardetector.ACTION_STOP_SERVICE"
        const val ACTION_LOAD_COUNTRY_CAMS = "com.example.radardetector.ACTION_LOAD_COUNTRY_CAMS"
        const val EXTRA_COUNTRY_CODE = "extra_country_code"
        const val EXTRA_COUNTRY_NAME = "extra_country_name"

        @Volatile
        var isRunning = false
        @Volatile
        var currentGpsIntervalMs: Long = 3000L
        @Volatile
        var instance: RadarForegroundService? = null
        @Volatile
        var lastMetrics: com.example.radardetector.math.ProcessedLocationMetrics? = null
        @Volatile
        var metricsListener: ((com.example.radardetector.math.ProcessedLocationMetrics) -> Unit)? = null
        @Volatile
        var serviceStateListener: ((Boolean) -> Unit)? = null

        fun getRamCachedLoadResult(): com.example.radardetector.math.CameraLoadResult? {
            val s = instance
            return if (isRunning && s != null && s.cachedCameras.isNotEmpty()) {
                com.example.radardetector.math.CameraLoadResult(
                    cameras = s.cachedCameras,
                    boxCameraCount = s.cachedCameras.size,
                    totalInDb = s.cachedTotalCameraCount,
                    minLat = s.cachedBoxMinLat,
                    maxLat = s.cachedBoxMaxLat,
                    minLon = s.cachedBoxMinLon,
                    maxLon = s.cachedBoxMaxLon
                )
            } else null
        }
    }

    private lateinit var locationManager: LocationManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var syncManager: OverpassSyncManager
    private lateinit var audioEngine: AcousticRadarEngine

    private var lastLocation: Location? = null

    @Volatile
    internal var cachedCameras: List<Camera> = emptyList()
    private val dbExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    internal var cachedBoxMinLat = 0.0
    @Volatile
    internal var cachedBoxMaxLat = 0.0
    @Volatile
    internal var cachedBoxMinLon = 0.0
    @Volatile
    internal var cachedBoxMaxLon = 0.0
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
    private val logged300mCameraIds = HashSet<Long>()
    private val loggedCrossingCameraIds = HashSet<Long>()

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastMotionCheckTimeMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val watchdogHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastLocationTimeMs: Long = System.currentTimeMillis()
    private val WATCHDOG_CHECK_INTERVAL_MS = 60000L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkWatchdogStall()
            watchdogHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        getSharedPreferences("radar_prefs", Context.MODE_PRIVATE).edit().putBoolean("user_stopped", false).apply()
        lastLocationTimeMs = System.currentTimeMillis()

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

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadarStop:ForegroundWakeLock").apply {
                acquire()
            }
            AppLogger.log("RadarForegroundService", "onCreate", true, "Acquired PowerManager PARTIAL_WAKE_LOCK for continuous CPU execution.")
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "onCreate", false, "Failed to acquire PARTIAL_WAKE_LOCK: ${e.message}")
        }

        AlarmWatchdogReceiver.scheduleNextAlarm(this)

        Toast.makeText(applicationContext, "RadarStop Active", Toast.LENGTH_SHORT).show()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        dbHelper = DatabaseHelper(this)
        syncManager = OverpassSyncManager(
            this,
            dbHelper,
            onStatusUpdate = { statusMsg -> updateNotificationText(statusMsg) },
            onSyncSuccess = { _, totalCount ->
                cachedTotalCameraCount = totalCount
                val loc = lastLocation ?: try {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (e: Exception) { null }

                if (loc != null) {
                    reloadCameraCacheForLocation(loc)
                    lastMetrics = RadarMath.evaluateLocationData(loc, effectiveSpeedKmh, dbHelper, getRamCachedLoadResult())
                } else {
                    updateActiveNotificationStatus()
                }
            }
        )
        audioEngine = AcousticRadarEngine(this)
        audioEngine.playSingleBeep()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            AppLogger.log("RadarForegroundService", "onCreate", true, "Registered Accelerometer sensor for motion wakeup.")
        }
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)

        createNotificationChannel()
        val initialText = "Searching for GPS..."
        lastNotificationText = initialText
        startForeground(NOTIF_ID, buildNotification(initialText))
        AppLogger.log("RadarForegroundService", "onCreate", true, "Searching for GPS satellites...")

        registerGpsUpdates(1000L, force = true)

        // Try last known location for instant startup
        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestKnown = lastGps ?: lastNet
            if (bestKnown != null) {
                AppLogger.log("RadarForegroundService", "onCreate", true, "Found last known location (${bestKnown.latitude}, ${bestKnown.longitude}). Loading 10x10km DB cache immediately...")
                reloadCameraCacheForLocation(bestKnown)
            }
        } catch (e: SecurityException) {
            AppLogger.log("RadarForegroundService", "onCreate", false, "Permission missing for last known location: ${e.message}")
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "onCreate", false, "Error getting last known location: ${e.message}")
        }
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
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    @Volatile
    private var lastGpsRegisterTimeMs: Long = 0L

    private fun registerGpsUpdates(intervalMs: Long, force: Boolean = false) {
        if (!force && currentGpsIntervalMs == intervalMs) return
        val now = System.currentTimeMillis()
        if (!force && intervalMs > currentGpsIntervalMs && currentGpsIntervalMs > 0L && (now - lastGpsRegisterTimeMs < 5000L)) {
            return
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "GPS Hardware Provider is DISABLED in Android System Settings!")
            updateNotificationText("GPS is Disabled in System Settings")
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
            // Dual Provider Fallback: Also listen to NETWORK_PROVIDER every 60s
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    60000L,
                    20f,
                    this
                )
            }
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", true, "GPS polling registered at ${intervalMs}ms (minDistance: ${minDistance}m) + NETWORK fallback 60s. Searching for GPS satellites...")
        } catch (e: SecurityException) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "Location permission missing: ${e.message}")
            updateNotificationText("Weak GPS signal (>15m)")
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "GPS request failed: ${e.message}")
        }
    }

    fun checkWatchdogStall() {
        val now = System.currentTimeMillis()
        val timeSinceLastLoc = now - lastLocationTimeMs
        if (timeSinceLastLoc >= WATCHDOG_CHECK_INTERVAL_MS) {
            AppLogger.log(
                "RadarForegroundService",
                "checkWatchdogStall",
                false,
                "WATCHDOG TRIGGERED: No location updates received for ${timeSinceLastLoc / 1000}s. Forcing GPS re-registration..."
            )
            registerGpsUpdates(currentGpsIntervalMs, force = true)
        } else {
            AppLogger.log(
                "RadarForegroundService",
                "checkWatchdogStall",
                true,
                "GPS WATCHDOG OK: Last location update received ${timeSinceLastLoc / 1000}s ago."
            )
        }
    }

    @Volatile
    internal var cachedTotalCameraCount: Int = 0

    private fun updateActiveNotificationStatus() {
        val statusText = "Active. Cameras: ${cachedCameras.size} in 10x10km / $cachedTotalCameraCount total in DB"
        updateNotificationText(statusText)
    }

    private fun reloadCameraCacheForLocation(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        lastRamReloadLat = lat
        lastRamReloadLon = lon

        val res = RadarMath.load10x10Cameras(dbHelper, lat, lon)
        cachedBoxMinLat = res.minLat
        cachedBoxMaxLat = res.maxLat
        cachedBoxMinLon = res.minLon
        cachedBoxMaxLon = res.maxLon
        cachedCameras = res.cameras
        cachedTotalCameraCount = res.totalInDb
        updateActiveNotificationStatus()
        AppLogger.log(
            "RadarForegroundService",
            "reloadCameraCacheForLocation",
            true,
            "DATABASE LOAD (Sync): Loaded ${cachedCameras.size} cameras from SQLite DB into RAM for current location ($lat, $lon). Total in DB: $cachedTotalCameraCount"
        )
    }

    private var isWeakGpsState: Boolean = false
    private val trajectoryFilter = RadarMath.TrajectoryFilter()

    override fun onLocationChanged(location: Location) {
        if (!isRunning) return
        lastLocationTimeMs = System.currentTimeMillis()
        audioEngine.notifyLocationUpdate()
        lastLocation = location

        val trajResult = trajectoryFilter.processLocation(location)

        val rawSpeedKmh = if (trajResult.isStationary) {
            0f
        } else if (trajResult.averageSpeedKmh > 0f) {
            trajResult.averageSpeedKmh
        } else if (location.hasSpeed() && location.speed > 0f) {
            location.speed * 3.6f
        } else {
            val prevLoc = lastLocation
            if (prevLoc != null && location.time > prevLoc.time) {
                val distM = location.distanceTo(prevLoc)
                val timeS = (location.time - prevLoc.time) / 1000f
                if (timeS > 0f) (distM / timeS) * 3.6f else 0f
            } else 0f
        }
        val speedKmh = RadarMath.calculateEffectiveSpeed(rawSpeedKmh, effectiveSpeedKmh)
        effectiveSpeedKmh = speedKmh

        val now = System.currentTimeMillis()
        val lat = location.latitude
        val lon = location.longitude

        val distFromRamReload = FloatArray(1)
        if (lastRamReloadLat != 0.0 || lastRamReloadLon != 0.0) {
            Location.distanceBetween(lat, lon, lastRamReloadLat, lastRamReloadLon, distFromRamReload)
        }

        // 1. UNCONDITIONAL RAM CACHE LOAD: Always load 10x10km cameras into RAM on fix (even for coarse GPS)
        if (cachedCameras.isEmpty() || distFromRamReload[0] >= 4000f || lat < cachedBoxMinLat || lat > cachedBoxMaxLat || lon < cachedBoxMinLon || lon > cachedBoxMaxLon) {
            reloadCameraCacheForLocation(location)
        }

        val effectiveLoc = trajResult.projectedLocation ?: location
        val metrics = RadarMath.evaluateLocationData(
            effectiveLoc,
            effectiveSpeedKmh,
            dbHelper,
            getRamCachedLoadResult(),
            trajResult.trajectoryBearing,
            trajResult.points
        )
        lastMetrics = metrics
        metricsListener?.invoke(metrics)

        // 3. Trigger network sync update
        syncManager.onLocationUpdate(location, speedKmh)

        // 4. Weak GPS Check (Alerting paused if accuracy > 100m, but RAM cache is ALREADY loaded)
        val isAccuracyWeak = location.hasAccuracy() && location.accuracy > 100f
        if (isAccuracyWeak) {
            if (!isWeakGpsState) {
                isWeakGpsState = true
                AppLogger.log("RadarForegroundService", "onLocationChanged", false, "GPS accuracy degraded (>100m [${location.accuracy.toInt()}m]). Alerting paused.")
            }
            val accInt = location.accuracy.toInt()
            updateNotificationText("Weak GPS signal (>100m [${accInt}m])")
            audioEngine.stopAlert()
            return
        } else {
            if (isWeakGpsState) {
                isWeakGpsState = false
                AppLogger.log("RadarForegroundService", "onLocationChanged", true, "GPS accuracy restored (<=100m). Alerting resumed.")
            }
        }

        if (!trajResult.isValid) {
            AppLogger.log("RadarForegroundService", "onLocationChanged", false, "Candidate location rejected by TrajectoryFilter (backward/jitter projection).")
            return
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

            val isDirMatched = RadarMath.isCameraDirectionMatched(trajResult.trajectoryBearing, camera.dir)

            // Log 1 time when entering 300m zone
            if (distance <= maxBeepAlertDistance && isDirMatched) {
                if (logged300mCameraIds.add(camera.id)) {
                    AppLogger.log(
                        "RadarForegroundService",
                        "onCamera300mEntry",
                        true,
                        "300M ZONE ENTERED: Camera #${camera.id} (Linear: ${camera.isLinear}). Distance: ${distance.toInt()}m."
                    )
                }
            } else if (distance > 400f) {
                logged300mCameraIds.remove(camera.id)
            }

            // Log 1 time at direct camera crossing (within continuous zone <= 50m/100m)
            if (distance <= continuousThreshold && isDirMatched) {
                if (loggedCrossingCameraIds.add(camera.id)) {
                    AppLogger.log(
                        "RadarForegroundService",
                        "onCameraCrossing",
                        true,
                        "DIRECT CAMERA CROSSING: Camera #${camera.id} (Linear: ${camera.isLinear}). Distance: ${distance.toInt()}m."
                    )
                }
            } else if (distance > continuousThreshold + 50f) {
                loggedCrossingCameraIds.remove(camera.id)
            }

            if (distance <= maxBeepAlertDistance && isDirMatched) {
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

        val defaultStatusText = "Active. Cameras: ${cachedCameras.size} in 10x10km / $cachedTotalCameraCount total in DB"

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
                    "CAMERA ALERT DETECTED: Camera #${closestAlertCamera.id}. Speed: ${speedInt} km/h, Distance: ${distInt}m, Bearing: ${closestAlertCamera.dir ?: "Omnidirectional"}, Linear: ${closestAlertCamera.isLinear}"
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
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        lastMetrics = null
        if (instance == this) instance = null
        val stateCb = serviceStateListener
        serviceStateListener = null
        metricsListener = null
        stateCb?.invoke(false)
        getSharedPreferences("radar_prefs", Context.MODE_PRIVATE).edit().putBoolean("user_stopped", true).apply()
        AlarmWatchdogReceiver.cancelAlarm(this)
        AppLogger.log("RadarForegroundService", "stopSelfAndCleanup", true, "Cancelled background AlarmManager timer.")
        watchdogHandler.removeCallbacks(watchdogRunnable)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.log("RadarForegroundService", "stopSelfAndCleanup", true, "Released PowerManager PARTIAL_WAKE_LOCK.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val now = System.currentTimeMillis()
            if (now - lastMotionCheckTimeMs < 60000L) return
            lastMotionCheckTimeMs = now

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val g = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val delta = Math.abs(g - SensorManager.GRAVITY_EARTH)

            if (delta > 1.5f && (now - lastLocationTimeMs >= 60000L)) {
                AppLogger.log(
                    "RadarForegroundService",
                    "onSensorChanged",
                    true,
                    "MOTION DETECTED (delta: ${String.format(java.util.Locale.US, "%.2f", delta)}) while GPS silent for >60s. Triggering Watchdog recovery..."
                )
                checkWatchdogStall()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
