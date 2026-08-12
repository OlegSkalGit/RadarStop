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
import com.example.radardetector.util.AppPrefs
import com.example.radardetector.util.LocationUtils
import com.example.radardetector.util.getAppVersionName
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
    private var isHighSpeedGpsMode: Boolean = false

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

    fun getSecondsUntilDeepSleep(): Long {
        if (isDeepSleepState) return 0L
        val start = stationaryStopStartTimeMs
        if (start == 0L) return 180L
        val elapsedMs = System.currentTimeMillis() - start
        val remainingMs = maxOf(0L, 3 * 60 * 1000L - elapsedMs)
        return remainingMs / 1000L
    }

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
        watchdogHandler.removeCallbacks(staleGpsRunnable)

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.log("RadarForegroundService", "enterDeepSleep", true, "RELEASED PowerManager PARTIAL_WAKE_LOCK for full CPU Doze sleep during Deep Sleep.")
            }
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "enterDeepSleep", false, "Error releasing WakeLock: ${e.message}")
        }

        if (!isAccelerometerRegistered) {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                isAccelerometerRegistered = true
                AppLogger.log("RadarForegroundService", "enterDeepSleep", true, "REGISTERED Accelerometer sensor for motion wakeup.")
            }
        }
        val isSystemGpsDisabled = LocationUtils.isGpsDisabled(locationManager)

        val deepSleepNotif = "Deep Sleep: Stationed (>3m). Accelerometer active."
        val loc = lastLocation ?: LocationUtils.getLastKnownLocationCascade(locationManager)

        val targetLoc = loc ?: LocationUtils.createLocation(0.0, 0.0, "dummy")

        val sleepMetrics = RadarMath.evaluateLocationData(
            targetLoc,
            0f,
            dbHelper,
            getRamCachedLoadResult(),
            isStationary = true,
            isDeepSleep = true,
            isGpsDisabled = isSystemGpsDisabled,
            notificationOverride = deepSleepNotif
        )
        publishStateAndMetrics(sleepMetrics)
    }

    fun wakeUpFromDeepSleep(reason: String) {
        if (!isDeepSleepState) return
        isDeepSleepState = false
        AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", true, "WAKEUP TRIGGERED ($reason). Unregistering accelerometer sensor, resuming watchdogHandler and 1s GPS updates...")
        
        try {
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadarStop:ForegroundWakeLock").apply {
                    acquire()
                }
                AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", true, "RE-ACQUIRED PowerManager PARTIAL_WAKE_LOCK for continuous CPU execution.")
            }
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", false, "Error re-acquiring WakeLock: ${e.message}")
        }

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
        lastPointIntervalMs = 2333L
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)
        watchdogHandler.removeCallbacks(staleGpsRunnable)
        watchdogHandler.postDelayed(staleGpsRunnable, STALE_CHECK_INTERVAL_MS)

        // Check if system GPS is disabled before attempting wakeup notification
        val isSystemGpsDisabled = LocationUtils.isGpsDisabled(locationManager)

        if (isSystemGpsDisabled) {
            AppLogger.log("RadarForegroundService", "wakeUpFromDeepSleep", false, "System GPS is disabled. Remaining in Deep Sleep.")
            enterDeepSleep()
            return
        }

        // Instant metrics evaluation from last known location upon wakeup
        try {
            val searchingNotif = "Searching for GPS..."
            updateNotificationText(searchingNotif)
            val bestKnown = lastLocation ?: LocationUtils.getLastKnownLocationCascade(locationManager)
            if (bestKnown != null) {
                reloadCameraCacheForLocation(bestKnown)
                val initialMetrics = RadarMath.evaluateLocationData(
                    bestKnown,
                    effectiveSpeedKmh,
                    dbHelper,
                    getRamCachedLoadResult(),
                    notificationOverride = searchingNotif
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
    @Volatile
    private var lastPointIntervalMs: Long = 2333L
    private val WATCHDOG_CHECK_INTERVAL_MS = 60000L
    private val STALE_CHECK_INTERVAL_MS = 2000L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || isDeepSleepState) return
            checkWatchdogStall()
            watchdogHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
        }
    }

    private val staleGpsRunnable = object : Runnable {
        override fun run() {
            if (isRunning && !isDeepSleepState) {
                checkStaleGpsAndResetSpeed()
            }
            watchdogHandler.postDelayed(this, STALE_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        AppPrefs.setUserStopped(this, false)
        lastLocationTimeMs = System.currentTimeMillis()

        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isSystemGpsDisabled = LocationUtils.isGpsDisabled(locationManager)
        val initialText = if (isSystemGpsDisabled) "GPS is Disabled in System Settings" else "Searching for GPS..."
        lastNotificationText = initialText
        startForeground(NOTIF_ID, buildNotification(initialText))

        val appVersionName = getAppVersionName()

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

        dbHelper = DatabaseHelper(this)
        syncManager = OverpassSyncManager(
            this,
            dbHelper,
            onStatusUpdate = { statusMsg -> updateNotificationText(statusMsg) },
            onSyncSuccess = { _, totalCount ->
                cachedTotalCameraCount = totalCount
                val loc = lastLocation ?: LocationUtils.getLastKnownLocationCascade(locationManager)

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
        watchdogHandler.postDelayed(staleGpsRunnable, STALE_CHECK_INTERVAL_MS)

        AppLogger.log("RadarForegroundService", "onCreate", true, "Searching for GPS satellites...")

        registerGpsUpdates(1000L, force = true)

        // Try last known location for instant startup
        try {
            val bestKnown = LocationUtils.getLastKnownLocationCascade(locationManager)
            if (bestKnown != null) {
                lastLocation = bestKnown
                AppLogger.log("RadarForegroundService", "onCreate", true, "Found last known location (${bestKnown.latitude}, ${bestKnown.longitude}). Loading 10x10km DB cache & evaluating initial metrics immediately...")
                reloadCameraCacheForLocation(bestKnown)
                val initialMetrics = RadarMath.evaluateLocationData(
                    bestKnown,
                    0f,
                    dbHelper,
                    getRamCachedLoadResult(),
                    notificationOverride = initialText
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

        val isSystemGpsDisabled = LocationUtils.isGpsDisabled(locationManager)

        if (isSystemGpsDisabled) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "GPS Hardware Provider is DISABLED in Android System Settings! Transitioning to Deep Sleep.")
            enterDeepSleep()
            return
        }

        currentGpsIntervalMs = intervalMs
        lastGpsRegisterTimeMs = now
        try {
            locationManager.removeUpdates(this)
            val minDistance = if (intervalMs >= 15000L) 10f else 0f
            var registeredCount = 0

            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    try {
                        locationManager.requestLocationUpdates(
                            provider,
                            intervalMs,
                            minDistance,
                            this
                        )
                        registeredCount++
                    } catch (e: Exception) {}
                }
            }
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", true, "Location polling registered on $registeredCount providers at ${intervalMs}ms. Searching satellites with instant network fallback...")
        } catch (e: SecurityException) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            AppLogger.log("RadarForegroundService", "registerGpsUpdates", false, "GPS request failed: ${e.message}")
        }
    }

    fun checkWatchdogStall() {
        if (isDeepSleepState) return
        val now = System.currentTimeMillis()
        val timeSinceLastLoc = now - lastLocationTimeMs
        if (timeSinceLastLoc >= 3 * 60 * 1000L) {
            AppLogger.log(
                "RadarForegroundService",
                "checkWatchdogStall",
                true,
                "NO GPS FIXES for ${timeSinceLastLoc / 1000}s (>=3 min). Entering Deep Sleep mode with active accelerometer..."
            )
            enterDeepSleep()
        } else if (timeSinceLastLoc >= WATCHDOG_CHECK_INTERVAL_MS) {
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

    fun checkStaleGpsAndResetSpeed() {
        if (isDeepSleepState || !isRunning) return
        val now = System.currentTimeMillis()
        val timeSinceLastLoc = now - lastLocationTimeMs
        val dynamicStaleTimeoutMs = minOf((lastPointIntervalMs * 1.5).toLong(), 10000L)
        if (timeSinceLastLoc >= dynamicStaleTimeoutMs) {
            val currentMetrics = lastMetrics
            if (currentMetrics != null && (currentMetrics.speedKmh > 0f || !currentMetrics.isStationary)) {
                effectiveSpeedKmh = 0f
                val staleMetrics = currentMetrics.copy(
                    speedKmh = 0f,
                    isStationary = true,
                    closestAlertCamera = null,
                    minDistanceToAlert = Float.MAX_VALUE
                )
                lastMetrics = staleMetrics
                metricsListener?.invoke(staleMetrics)
                audioEngine.stopAlert()
                AppLogger.log("RadarForegroundService", "checkStaleGpsAndResetSpeed", true, "GPS paused for ${timeSinceLastLoc / 1000}s (Threshold: ${dynamicStaleTimeoutMs}ms). Speed reset to 0 km/h [Stationary].")
            }
        }

        // Periodic stationary Deep Sleep evaluation (independent of onLocationChanged & accuracy > 100m)
        val isFullStop = (lastMetrics?.isStationary == true) || effectiveSpeedKmh == 0f || timeSinceLastLoc >= 10000L
        if (isFullStop) {
            if (stationaryStopStartTimeMs == 0L) {
                stationaryStopStartTimeMs = now
            }
            val timeStoppedMs = now - stationaryStopStartTimeMs
            if (timeStoppedMs >= 3 * 60 * 1000L) {
                AppLogger.log("RadarForegroundService", "checkStaleGpsAndResetSpeed", true, "STATIONARY/NO_GPS TIMEOUT: Stationary/no GPS for ${timeStoppedMs / 1000}s (>= 3 min). Entering Deep Sleep mode.")
                enterDeepSleep()
            }
        } else {
            stationaryStopStartTimeMs = 0L
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
            val now = System.currentTimeMillis()
            val prevLoc = lastLocation
            if (prevLoc != null) {
                val dt = if (location.time > 0L && prevLoc.time > 0L && location.time > prevLoc.time) {
                    location.time - prevLoc.time
                } else {
                    now - lastLocationTimeMs
                }
                if (dt in 100L..30000L) {
                    lastPointIntervalMs = dt
                }
            }
            lastLocationTimeMs = now
            val dynamicStaleTimeoutMs = minOf((lastPointIntervalMs * 1.5).toLong(), 10000L)
            audioEngine.notifyLocationUpdate(dynamicStaleTimeoutMs)
            lastLocation = location

        val trajResult = trajectoryFilter.processLocation(location)

        val instantSpeed = if (location.hasSpeed() && location.speed > 0f) location.speed * 3.6f else 0f
        val olsSpeed = trajResult.averageSpeedKmh
        val directDistSpeed = if (prevLoc != null) {
            val dtSec = (location.time - prevLoc.time) / 1000.0
            if (dtSec in 0.2..60.0) LocationUtils.calculateSpeedKmh(prevLoc, location, dtSec) else 0f
        } else 0f

        val speedKmh = if (trajResult.isStationary) {
            0f
        } else {
            maxOf(instantSpeed, olsSpeed, directDistSpeed)
        }
        effectiveSpeedKmh = speedKmh

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

        // Hysteresis / Floating threshold (60 +/- 10 km/h: 50..70 km/h buffer zone)
        // Prevents rapid switching between 500m (5s/3s) and 1000m (1s) modes when cruising around 60 km/h in city traffic.
        if (effectiveSpeedKmh > 70f) {
            isHighSpeedGpsMode = true
        } else if (effectiveSpeedKmh < 50f) {
            isHighSpeedGpsMode = false
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
            olsSpeed,
            isHighSpeedGpsMode
        )
        lastMetrics = metrics
        metricsListener?.invoke(metrics)

        // 3. Trigger network sync update
        syncManager.onLocationUpdate(location, speedKmh)

        // 4. Weak GPS Check (Alerting paused if accuracy > 100m, effective speed reset to 0, stationary timer evaluated)
        val isAccuracyWeak = location.hasAccuracy() && location.accuracy > 100f
        if (isAccuracyWeak) {
            effectiveSpeedKmh = 0f
            if (!isWeakGpsState) {
                isWeakGpsState = true
                AppLogger.log("RadarForegroundService", "onLocationChanged", false, "GPS accuracy degraded (>100m [${location.accuracy.toInt()}m]). Alerting paused & speed set to 0.")
            }
            val accInt = location.accuracy.toInt()
            val weakNotif = "Weak GPS signal (>100m [${accInt}m])"
            updateNotificationText(weakNotif)
            audioEngine.stopAlert()

            val weakMetrics = RadarMath.evaluateLocationData(
                effectiveLoc,
                0f,
                dbHelper,
                getRamCachedLoadResult(),
                trajResult.trajectoryBearing,
                trajResult.points,
                isStationary = true,
                instantSpeedKmh = 0f,
                olsSpeedKmh = 0f,
                isHighSpeedMode = false,
                isGpsDisabled = false,
                notificationOverride = weakNotif
            )
            lastMetrics = weakMetrics
            metricsListener?.invoke(weakMetrics)

            if (stationaryStopStartTimeMs == 0L) {
                stationaryStopStartTimeMs = now
            }
            val timeStoppedMs = now - stationaryStopStartTimeMs
            if (timeStoppedMs >= 3 * 60 * 1000L) {
                AppLogger.log("RadarForegroundService", "onLocationChanged", true, "WEAK GPS TIMEOUT: Weak GPS (>100m) for ${timeStoppedMs / 1000}s (>= 3 min). Entering Deep Sleep mode.")
                enterDeepSleep()
            }
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

        // Evaluate temporary metrics for camera distance thresholds
        val tempMetrics = RadarMath.evaluateLocationData(
            effectiveLoc,
            effectiveSpeedKmh,
            dbHelper,
            getRamCachedLoadResult(),
            trajResult.trajectoryBearing,
            trajResult.points,
            trajResult.isStationary,
            instantSpeed,
            olsSpeed,
            isHighSpeedGpsMode
        )

        val minDistToAnyCamera = tempMetrics.minDistToAnyCamera
        val closestAlertCamera = tempMetrics.closestAlertCamera
        val minDistanceToAlert = tempMetrics.minDistanceToAlert

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

            val continuousThreshold = tempMetrics.continuousThreshold
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

        val maxGpsReadDistance = if (isHighSpeedGpsMode) 1000f else 500f
        val isInActiveLinearZone = (activeLinearEntryCam != null)
        val isWithinGps1sDistance = (minDistToAnyCamera <= maxGpsReadDistance)
        val hasNearbyCameraIn3km = isInActiveLinearZone || (minDistToAnyCamera <= 3000f)

        val isFullStop = tempMetrics.isStationary || speedKmh == 0f

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

        var activeAlertNotifText: String? = null

        if (speedKmh <= 30f) {
            if (currentAlertCameraId != null) {
                AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Exited camera alert zone (Speed dropped <= 30 km/h).")
                currentAlertCameraId = null
            }
            audioEngine.stopAlert()
        } else if (closestAlertCamera != null) {
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
            activeAlertNotifText = "Radar! Distance: ${distInt}m (${speedInt} km/h)"
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
                    activeAlertNotifText = "Radar! Linear Zone Alert (${speedInt} km/h)"
                }
            }

            if (activeAlertNotifText == null) {
                if (currentAlertCameraId != null) {
                    AppLogger.log("RadarForegroundService", "onLocationChanged", true, "Exited camera alert zone (Camera #${currentAlertCameraId} cleared).")
                    currentAlertCameraId = null
                }
                audioEngine.stopAlert()
            }
        }

        val defaultStatusText = "Active. Cameras: ${cachedCameras.size} in 10x10km / $cachedTotalCameraCount total in DB"
        val finalNotifText = activeAlertNotifText ?: defaultStatusText

        val finalMetrics = RadarMath.evaluateLocationData(
            effectiveLoc,
            effectiveSpeedKmh,
            dbHelper,
            getRamCachedLoadResult(),
            trajResult.trajectoryBearing,
            trajResult.points,
            trajResult.isStationary,
            instantSpeed,
            olsSpeed,
            isHighSpeedGpsMode,
            isGpsDisabled = false,
            notificationOverride = finalNotifText
        )

        publishStateAndMetrics(finalMetrics)
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
        AppPrefs.setUserStopped(this, true)
        AlarmWatchdogReceiver.cancelAlarm(this)
        AppLogger.log("RadarForegroundService", "stopSelfAndCleanup", true, "Cancelled background AlarmManager timer.")
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.removeCallbacks(staleGpsRunnable)
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

    fun publishStateAndMetrics(metrics: ProcessedLocationMetrics) {
        lastMetrics = metrics
        updateNotificationText(metrics.notificationText)
        metricsListener?.invoke(metrics)
    }

    override fun onProviderEnabled(provider: String) {
        AppLogger.log("RadarForegroundService", "onProviderEnabled", true, "GPS provider enabled by user/system ($provider). Waking up from Deep Sleep.")
        if (provider == LocationManager.GPS_PROVIDER) {
            wakeUpFromDeepSleep("GPS turned ON by user/system")
        }
    }

    override fun onProviderDisabled(provider: String) {
        AppLogger.log("RadarForegroundService", "onProviderDisabled", false, "GPS provider disabled by user/system ($provider). Entering Deep Sleep mode.")
        if (provider == LocationManager.GPS_PROVIDER) {
            audioEngine.stopAlert()
            enterDeepSleep()
        }
    }

    private fun notifyStateChange(isGpsDisabled: Boolean = false, notificationText: String? = null) {
        val loc = lastLocation ?: LocationUtils.getLastKnownLocationCascade(locationManager)

        val targetLoc = loc ?: LocationUtils.createLocation(0.0, 0.0, "dummy")

        val metrics = RadarMath.evaluateLocationData(
            targetLoc,
            if (isGpsDisabled) 0f else effectiveSpeedKmh,
            dbHelper,
            getRamCachedLoadResult(),
            isGpsDisabled = isGpsDisabled,
            notificationOverride = notificationText ?: lastNotificationText
        )
        publishStateAndMetrics(metrics)
    }

    private var motionSpikeCount = 0
    private var lastSpikeTimeMs = 0L

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (!isDeepSleepState) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val g = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val delta = Math.abs(g - SensorManager.GRAVITY_EARTH)
            val now = System.currentTimeMillis()

            if (delta >= 1.0f) {
                if (now - lastSpikeTimeMs < 1500L) {
                    motionSpikeCount++
                } else {
                    motionSpikeCount = 1
                }
                lastSpikeTimeMs = now

                if (motionSpikeCount >= 3) {
                    motionSpikeCount = 0
                    AppLogger.log(
                        "RadarForegroundService",
                        "onSensorChanged",
                        true,
                        "SUSTAINED MOTION CONFIRMED (3 spikes >= 1.0 m/s²). Waking up from Deep Sleep..."
                    )
                    wakeUpFromDeepSleep("Confirmed Motion Spikes (delta: ${String.format(java.util.Locale.US, "%.2f", delta)})")
                }
            } else {
                if (now - lastSpikeTimeMs > 2000L) {
                    motionSpikeCount = 0
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
