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
import com.example.radardetector.math.*
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
        const val EXTRA_START_IN_DEEP_SLEEP = "extra_start_in_deep_sleep"

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

    private var stationaryStopStartTimeMs = 0L
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
    @Volatile
    var isDeepSleepState: Boolean = false
    private var isAccelerometerRegistered: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null

    fun enterDeepSleep() {
        if (isDeepSleepState) return
        isDeepSleepState = true
        AppLogger.log("RadarForegroundService", "enterDeepSleep", true, "DEEP SLEEP: Stationed > 3 minutes. Disabling active GPS polling & watchdogHandler, activating low-level accelerometer motion trigger...")
        try {
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "enterDeepSleep", false, "Error removing GPS updates: ${e.message}")
        }
        currentGpsIntervalMs = 0L
        watchdogHandler.removeCallbacks(watchdogRunnable)

        if (!isAccelerometerRegistered) {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                isAccelerometerRegistered = true
                AppLogger.log("RadarForegroundService", "enterDeepSleep", true, "REGISTERED Accelerometer sensor for motion wakeup.")
            }
        }
        updateNotificationText("Deep Sleep: Stationed (>3m). Accelerometer active.")
    }

    fun wakeUpFromDeepSleep(reason: String) {
        if (!isDeepSleepState) return
        isDeepSleepState = false
        AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", true, "WAKEUP TRIGGERED ($reason). Unregistering accelerometer sensor, resuming watchdogHandler and 1s GPS updates...")
        if (isAccelerometerRegistered) {
            try {
                sensorManager.unregisterListener(this)
            } catch (e: Exception) {
                AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", false, "Error unregistering sensor: ${e.message}")
            }
            isAccelerometerRegistered = false
            AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", true, "UNREGISTERED Accelerometer sensor (application awake).")
        }
        stationaryStopStartTimeMs = 0L
        lastLocationTimeMs = System.currentTimeMillis()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)

        // Instant metrics evaluation from last known location upon wakeup
        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestKnown = lastLocation ?: lastGps ?: lastNet
            if (bestKnown != null) {
                reloadCameraCacheForLocation(bestKnown)
                val initialMetrics = RadarMath.evaluateLocationData(
                    bestKnown,
                    effectiveSpeedKmh,
                    dbHelper,
                    getRamCachedLoadResult()
                )
                lastMetrics = initialMetrics
                metricsListener?.invoke(initialMetrics)
                AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", true, "Instant metrics initialized on wakeup from location (${bestKnown.latitude}, ${bestKnown.longitude}).")
            }
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", false, "Error getting initial location on wakeup: ${e.message}")
        }

        registerGpsUpdates(1000L, force = true)
    }

    private val watchdogHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastLocationTimeMs: Long = System.currentTimeMillis()
    private val WATCHDOG_CHECK_INTERVAL_MS = 60000L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || isDeepSleepState) return
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

        createNotificationChannel()
        val initialText = "Searching for GPS..."
        lastNotificationText = initialText
        startForeground(NOTIF_ID, buildNotification(initialText))

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
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)

        AppLogger.log("RadarForegroundService", "onCreate", true, "Searching for GPS satellites...")

        registerGpsUpdates(1000L, force = true)

        // Try last known location for instant startup
        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestKnown = lastGps ?: lastNet
            if (bestKnown != null) {
                lastLocation = bestKnown
                AppLogger.log("RadarForegroundService", "onCreate", true, "Found last known location (${bestKnown.latitude}, ${bestKnown.longitude}). Loading 10x10km DB cache & evaluating initial metrics immediately...")
                reloadCameraCacheForLocation(bestKnown)
                val initialMetrics = RadarMath.evaluateLocationData(
                    bestKnown,
                    0f,
                    dbHelper,
                    getRamCachedLoadResult()
                )
                lastMetrics = initialMetrics
                metricsListener?.invoke(initialMetrics)
            }
        } catch (e: SecurityException) {
            AppLogger.log("RadarForegroundService", "onCreate", false, "Permission missing for last known location: ${e.message}")
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "onCreate", false, "Error getting last known location: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_START_IN_DEEP_SLEEP, false) == true) {
            AppLogger.log("RadarForegroundService", "onStartCommand", true, "Started with EXTRA_START_IN_DEEP_SLEEP=true. Entering Deep Sleep mode with active accelerometer...")
            enterDeepSleep()
        }

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
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", true, "GPS polling registered at ${intervalMs}ms (minDistance: ${minDistance}m). Searching for GPS satellites...")
        } catch (e: SecurityException) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "Location permission missing: ${e.message}")
            updateNotificationText("Weak GPS signal (>15m)")
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "GPS request failed: ${e.message}")
        }
    }

    fun checkWatchdogStall() {
        if (isDeepSleepState) return
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
    private val trajectoryFilter = TrajectoryFilter()

    override fun onLocationChanged(location: Location) {
        if (!isRunning) return
        try {
            lastLocationTimeMs = System.currentTimeMillis()
            audioEngine.notifyLocationUpdate()
            val prevLoc = lastLocation
            lastLocation = location

        val trajResult = trajectoryFilter.processLocation(location)

        val instantSpeed = if (location.hasSpeed() && location.speed > 0f) location.speed * 3.6f else 0f
        val effectiveInstant = if (instantSpeed <= 3.0f) 0f else instantSpeed
        val olsSpeed = if (trajResult.averageSpeedKmh <= 3.0f) 0f else trajResult.averageSpeedKmh
        val directDistSpeed = if (prevLoc != null) {
            val dtSec = (location.time - prevLoc.time) / 1000.0
            val dist = prevLoc.distanceTo(location)
            val rawDirect = if (dtSec in 0.2..60.0) (dist / dtSec * 3.6).toFloat() else 0f
            if (rawDirect <= 3.0f || (effectiveInstant == 0f && dist < 5f)) 0f else rawDirect
        } else 0f

        val rawMaxSpeed = maxOf(effectiveInstant, olsSpeed, directDistSpeed)
        val speedKmh = if (trajResult.isStationary || rawMaxSpeed <= 3.0f) {
            0f
        } else {
            rawMaxSpeed
        }
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
            trajResult.points,
            trajResult.isStationary,
            instantSpeed,
            olsSpeed
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

        val minDistToAnyCamera = metrics.minDistToAnyCamera
        val closestAlertCamera = metrics.closestAlertCamera
        val minDistanceToAlert = metrics.minDistanceToAlert

        // 1-time logging for entering 300m zone and direct crossing
        closestAlertCamera?.let { camera ->
            val distance = minDistanceToAlert
            if (distance <= 300f) {
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

            val continuousThreshold = metrics.continuousThreshold
            if (distance <= continuousThreshold) {
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
        }


        val maxGpsReadDistance = if (speedKmh <= 60f) 500f else 1000f
        val isInActiveLinearZone = (activeLinearEntryCam != null)
        val isWithinGps1sDistance = (minDistToAnyCamera <= maxGpsReadDistance)
        val hasNearbyCameraIn3km = isInActiveLinearZone || (minDistToAnyCamera <= 3000f)

        val isFullStop = metrics.isStationary || speedKmh == 0f

        val targetInterval = if (isFullStop) {
            if (stationaryStopStartTimeMs == 0L) {
                stationaryStopStartTimeMs = now
            }
            val timeStoppedMs = now - stationaryStopStartTimeMs
            if (timeStoppedMs < 3 * 60 * 1000L) {
                if (lastLoggedSpeedMode != "STOPPED_GRACE_3MIN") {
                    lastLoggedSpeedMode = "STOPPED_GRACE_3MIN"
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "STATIONARY STOP: Vehicle stopped. 3-min Grace Period active: Polling interval kept at 3s.")
                }
                3000L
            } else {
                if (lastLoggedSpeedMode != "DEEP_SLEEP") {
                    lastLoggedSpeedMode = "DEEP_SLEEP"
                }
                enterDeepSleep()
                return
            }
        } else {
            stationaryStopStartTimeMs = 0L
            if (isDeepSleepState) {
                wakeUpFromDeepSleep("Vehicle Motion Started (${speedKmh.toInt()} km/h)")
            }
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
                    if (lastLoggedSpeedMode != "SMART_SLEEP_5S") {
                        lastLoggedSpeedMode = "SMART_SLEEP_5S"
                        AppLogger.log("RadarForegroundService", "onLocationChanged", true, "SPEED THRESHOLD: Smart Sleep (No cameras within 3km). Polling interval: 5s.")
                    }
                    5000L
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
                        .minByOrNull { RadarMath.calculateDistance(effectiveLoc, it.lat, it.lon) }
                    prevDistToEntryCam = RadarMath.calculateDistance(effectiveLoc, activeLinearEntryCam!!.lat, activeLinearEntryCam!!.lon)
                    prevDistToExitCam = activeLinearExitCam?.let { RadarMath.calculateDistance(effectiveLoc, it.lat, it.lon) } ?: Float.MAX_VALUE
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
                    "CAMERA ALERT DETECTED: Camera #${closestAlertCamera.id}. Speed: ${speedInt} km/h, Distance: ${distInt}m, Linear: ${closestAlertCamera.isLinear}"
                )
            }

            val delayMs = RadarMath.calculateBeepDelay(minDistanceToAlert)
            audioEngine.startAlert(delayMs)
            audioEngine.updateDelay(delayMs)

            updateNotificationText("Radar! Distance: ${distInt}m (${speedInt} km/h)")
        } else {
            val entryCam = activeLinearEntryCam
            if (entryCam != null) {
                val distEntry = RadarMath.calculateDistance(effectiveLoc, entryCam.lat, entryCam.lon)
                val exitCam = activeLinearExitCam
                val distExit = exitCam?.let { RadarMath.calculateDistance(effectiveLoc, it.lat, it.lon) } ?: Float.MAX_VALUE

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
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "onLocationChanged", false, "Unhandled exception during location processing: ${e.message}")
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
            val contentIntent = Intent(this, com.example.radardetector.RadarMapActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pContentIntent = PendingIntent.getActivity(
                this, 2, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            cachedNotificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RadarStop Active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pContentIntent)
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
        AppLogger.log("RadarForegroundService", "onProviderEnabled", true, "GPS provider enabled by user/system ($provider).")
        if (provider == LocationManager.GPS_PROVIDER) {
            registerGpsUpdates(1000L, force = true)
            notifyStateChange()
        }
    }

    override fun onProviderDisabled(provider: String) {
        AppLogger.log("RadarForegroundService", "onProviderDisabled", false, "GPS provider disabled by user/system ($provider).")
        if (provider == LocationManager.GPS_PROVIDER) {
            audioEngine.stopAlert()
            updateNotificationText("GPS is Disabled in System Settings")
            notifyStateChange()
        }
    }

    private fun notifyStateChange() {
        val loc = lastLocation ?: try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { null }

        if (loc != null) {
            val metrics = RadarMath.evaluateLocationData(
                loc,
                effectiveSpeedKmh,
                dbHelper,
                getRamCachedLoadResult()
            )
            lastMetrics = metrics
            metricsListener?.invoke(metrics)
        } else {
            lastMetrics?.let { metricsListener?.invoke(it) }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (!isDeepSleepState) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val g = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val delta = Math.abs(g - SensorManager.GRAVITY_EARTH)

            if (delta > 0.4f) {
                AppLogger.log(
                    "RadarForegroundService",
                    "onSensorChanged",
                    true,
                    "MOTION DETECTED (delta: ${String.format(java.util.Locale.US, "%.2f", delta)}) while in Deep Sleep. Waking up application..."
                )
                wakeUpFromDeepSleep("Accelerometer Motion Delta ${String.format(java.util.Locale.US, "%.2f", delta)}")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
