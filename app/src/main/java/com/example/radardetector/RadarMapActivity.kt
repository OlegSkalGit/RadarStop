package com.example.radardetector

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.example.radardetector.util.AppLogger
import com.example.radardetector.util.AppPrefs
import com.example.radardetector.util.LocationUtils
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.radardetector.audio.AcousticRadarEngine
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.math.*
import com.example.radardetector.network.AppUpdateManager
import com.example.radardetector.network.OverpassSyncManager
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.ui.CountrySelectionDialog
import com.example.radardetector.ui.UiUtils
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RadarMapActivity : Activity() {

    private lateinit var mapView: RadarMapView
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var tvStatusLine1: TextView
    private lateinit var tvStatusLine2: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvSpeedUnit: TextView
    private lateinit var tvSubLabel: TextView
    private lateinit var tvCamNearWarning: TextView
    private lateinit var tvSleepCountdown: TextView
    private lateinit var btnGpsFix: Button
    private lateinit var bottomStatusPanel: LinearLayout
    private var isDebugMode: Boolean = false
    private var lastMapUpdateTimeMs: Long = System.currentTimeMillis()
    private val mapRefreshHandler = Handler(Looper.getMainLooper())
    private val MAP_REFRESH_INTERVAL_MS = 5000L

    private val mapRefreshRunnable = object : Runnable {
        override fun run() {
            updateSleepCountdown()
            val service = RadarForegroundService.instance
            if (service?.isDeepSleepState == true) {
                mapRefreshHandler.postDelayed(this, 3000L)
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastMapUpdateTimeMs >= MAP_REFRESH_INTERVAL_MS) {
                refreshMapState()
            }
            mapRefreshHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RadarForegroundService.isRunning) {
            finish()
            return
        }
        AppLogger.log("RadarMapActivity", "onCreate", true, "RadarMapActivity launched.")

        dbHelper = DatabaseHelper(this)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        mapView = RadarMapView(this)
        rootLayout.addView(mapView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Top Overlay Panel
        val topOverlayPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 0)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnClose = UiUtils.createStyledButton(this, "Close") {
            finish()
        }

        btnGpsFix = Button(this).apply {
            text = "Enable GPS"
            setTextColor(Color.parseColor("#FF1744"))
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
        }

        val leftSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        val rightSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        val btnMenu = UiUtils.createStyledButton(this, "Menu") {
            showMainMenuDialog()
        }

        topRow.addView(btnClose)
        topRow.addView(leftSpacer)
        topRow.addView(btnGpsFix)
        topRow.addView(rightSpacer)
        topRow.addView(btnMenu)
        topOverlayPanel.addView(topRow)

        val labelsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 12, 0, 0)
        }

        val topLeftContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 16, 24, 16)
            minimumWidth = (130 * resources.displayMetrics.density).toInt()
        }

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        tvSpeedValue = TextView(this).apply {
            text = "0"
            textSize = 44f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        tvSpeedUnit = TextView(this).apply {
            text = " km/h"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        speedRow.addView(tvSpeedValue)
        speedRow.addView(tvSpeedUnit)

        tvSubLabel = TextView(this).apply {
            text = "Stopped"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isSingleLine = true
            visibility = View.VISIBLE
        }

        topLeftContainer.addView(speedRow)
        topLeftContainer.addView(tvSubLabel)

        val labelsSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        val topRightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 16, 24, 16)
        }

        tvSleepCountdown = TextView(this).apply {
            text = "Sleep in 3:00"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        topRightContainer.addView(tvSleepCountdown)

        labelsRow.addView(topLeftContainer)
        labelsRow.addView(labelsSpacer)
        labelsRow.addView(topRightContainer)
        topOverlayPanel.addView(labelsRow)

        val topOverlayParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        rootLayout.addView(topOverlayPanel, topOverlayParams)

        // Bottom Overlay Panel
        val bottomOverlayPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
        }

        val bottomLeftContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(24, 0, 0, 16)
        }

        tvCamNearWarning = TextView(this).apply {
            text = "cam near"
            textSize = 44f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF1744"))
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 16, 24, 16)
            visibility = View.GONE
        }

        bottomLeftContainer.addView(tvCamNearWarning)
        bottomOverlayPanel.addView(bottomLeftContainer)

        bottomStatusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E614181F"))
            setPadding(24, 12, 24, 16)
        }

        val btnSpoilerToggle = Button(this).apply {
            text = "Status ▲"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            textSize = 13f
        }

        val statusSpoilerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }

        btnSpoilerToggle.setOnClickListener {
            if (statusSpoilerContent.visibility == View.VISIBLE) {
                statusSpoilerContent.visibility = View.GONE
                btnSpoilerToggle.text = "Status ▲"
            } else {
                statusSpoilerContent.visibility = View.VISIBLE
                btnSpoilerToggle.text = "Status ▼"
            }
        }

        tvStatusLine1 = TextView(this).apply {
            text = "GPS: Searching... | Interval: --"
            setTextColor(Color.WHITE)
            textSize = 13f
        }

        tvStatusLine2 = TextView(this).apply {
            text = "Beep Status: -- | Cameras: --"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            setPadding(0, 4, 0, 0)
        }

        statusSpoilerContent.addView(tvStatusLine1)
        statusSpoilerContent.addView(tvStatusLine2)

        bottomStatusPanel.addView(btnSpoilerToggle)
        bottomStatusPanel.addView(statusSpoilerContent)

        bottomOverlayPanel.addView(bottomStatusPanel)

        val bottomParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        rootLayout.addView(bottomOverlayPanel, bottomParams)

        setContentView(rootLayout)
    }

    private fun updateGpsSpeedDisplay(
        isGpsDisabled: Boolean,
        metrics: com.example.radardetector.math.ProcessedLocationMetrics?
    ) {
        val state = if (isGpsDisabled || metrics?.isGpsDisabled == true) {
            RadarState.GPS_DISABLED
        } else {
            metrics?.radarState ?: RadarState.SEARCHING_GPS
        }

        val color = Color.parseColor(state.mapColorHex)
        val speedInt = metrics?.speedKmh?.toInt() ?: 0

        tvSpeedValue.setTextColor(color)
        tvSpeedUnit.setTextColor(color)
        tvSubLabel.setTextColor(color)

        when (state) {
            RadarState.GPS_DISABLED, RadarState.SEARCHING_GPS -> {
                tvSpeedValue.text = state.mapSpeedText
                tvSpeedValue.textSize = 32f
                tvSpeedUnit.visibility = View.GONE
                tvSubLabel.text = state.mapSubLabelText
                tvSubLabel.visibility = View.VISIBLE
                tvCamNearWarning.visibility = View.GONE
            }
            RadarState.REGULAR_SPEED -> {
                tvSpeedValue.text = "$speedInt"
                tvSpeedValue.textSize = 44f
                tvSpeedUnit.visibility = View.VISIBLE
                tvSubLabel.text = ""
                tvSubLabel.visibility = View.GONE
            }
            else -> {
                tvSpeedValue.text = if (state == RadarState.DEEP_SLEEP || state == RadarState.STOPPED) "0" else "$speedInt"
                tvSpeedValue.textSize = 44f
                tvSpeedUnit.visibility = View.VISIBLE
                tvSubLabel.text = state.mapSubLabelText
                tvSubLabel.visibility = View.VISIBLE
            }
        }
    }

    private fun updateUi(metrics: com.example.radardetector.math.ProcessedLocationMetrics) {
        try {
            val speedKmh = metrics.speedKmh
            val gpsStatusStr = metrics.gpsStatusStr
            val closestAlertCam = metrics.closestAlertCamera
            val minAlertDist = metrics.minDistanceToAlert

            // Update Top-Right Camera Warning
            val isCamNear = closestAlertCam != null && minAlertDist <= 300f
            tvCamNearWarning.visibility = if (isCamNear) View.VISIBLE else View.GONE

            val isGpsDisabled = LocationUtils.isGpsDisabled(this)
            updateGpsSpeedDisplay(isGpsDisabled, metrics)

            val activeIntervalMs = RadarForegroundService.currentGpsIntervalMs
            val activeSec = if (activeIntervalMs > 0) activeIntervalMs / 1000L else 1L
            val pollingIntervalStr = when (activeIntervalMs) {
                1000L -> "1s (Camera Nearby)"
                3000L -> if (speedKmh <= 30f) "3s (Grace Period)" else "3s (Normal)"
                5000L -> if (speedKmh <= 30f) "5s (Stationary Sleep)" else "5s (Smart Sleep)"
                else -> "${activeSec}s"
            }

            val beepStatusStr = when {
                metrics.isAccuracyWeak -> "PAUSED (Weak GPS)"
                metrics.isStationary -> "PAUSED (Stationary Stop)"
                speedKmh <= 30f -> "PAUSED (Speed <= 30 km/h)"
                closestAlertCam != null -> {
                    val isDeparting = RadarForegroundService.instance?.isDepartingFromPointCam == true
                    if (isDeparting && minAlertDist > 200f) {
                        "OFF (Idle)"
                    } else {
                        val delayMs = RadarMath.calculateBeepDelay(minAlertDist, isDeparting)
                        val distInt = minAlertDist.toInt()
                        "ALERT ${distInt}m (${delayMs}ms)"
                    }
                }
                else -> "OFF (Idle)"
            }

            val displayGpsStatusStr = if (metrics.isStationary) "$gpsStatusStr [STATIONARY]" else gpsStatusStr
            val totalDb = metrics.cameraLoadResult.totalInDb
            val instInt = metrics.instantSpeedKmh.toInt()
            val olsInt = metrics.olsSpeedKmh.toInt()
            tvStatusLine1.text = "Speed: ${speedKmh.toInt()} km/h (Inst: $instInt | OLS: $olsInt) | $displayGpsStatusStr | Interval: $pollingIntervalStr"
            tvStatusLine2.text = "Beep Status: $beepStatusStr | Cams: ${metrics.inRange3kmCount} in 3km / ${metrics.cameraLoadResult.boxCameraCount} in 10x10km / $totalDb total DB"

            mapView.updateData(metrics.location, metrics.cameraLoadResult.cameras, metrics.trajectoryBearing, metrics.trajectoryPoints)
        } catch (e: Exception) {
            AppLogger.log("RadarMapActivity", "updateUi", false, "Error updating UI: ${e.message}")
        }
    }

    private fun updateSleepCountdown() {
        if (!::tvSleepCountdown.isInitialized) return
        if (isDebugMode) {
            val service = RadarForegroundService.instance
            val isSleeping = service?.isDeepSleepState == true
            if (isSleeping) {
                tvSleepCountdown.text = "Sleep: 0:00"
            } else {
                val sec = service?.getSecondsUntilDeepSleep() ?: 180L
                val m = sec / 60
                val s = sec % 60
                tvSleepCountdown.text = String.format(java.util.Locale.US, "Sleep in %d:%02d", m, s)
            }
            tvSleepCountdown.visibility = View.VISIBLE
        } else {
            tvSleepCountdown.visibility = View.GONE
        }
    }

    private fun updateDebugVisibility() {
        isDebugMode = AppPrefs.isDebugMode(this)
        bottomStatusPanel.visibility = if (isDebugMode) View.VISIBLE else View.GONE
        updateSleepCountdown()
        if (::mapView.isInitialized) {
            mapView.postInvalidate()
        }
    }

    private fun showMainMenuDialog() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }

        val container = UiUtils.createDarkDialogContainer(this)
        val itemStyleParams = UiUtils.createStandardItemParams()

        // 1. Autostart On / Off
        var isAutostart = AppPrefs.isAutostartEnabled(this)
        lateinit var btnAutostart: Button
        btnAutostart = UiUtils.createStyledButton(this, if (isAutostart) "Disable Autostart" else "Enable Autostart", itemStyleParams) {
            isAutostart = !isAutostart
            AppPrefs.setAutostartEnabled(this, isAutostart)
            btnAutostart.text = if (isAutostart) "Disable Autostart" else "Enable Autostart"
            val statusMsg = if (isAutostart) "Start with system - Enable" else "Start with system - Disable"
            Toast.makeText(this@RadarMapActivity, statusMsg, Toast.LENGTH_SHORT).show()
            AppLogger.log("RadarMapActivity", "AutostartToggle", true, statusMsg)
        }
        container.addView(btnAutostart)
        container.addView(UiUtils.createDialogDivider(this))

        // 2. Load country cameras & Check updates
        val btnLoadCountryCams = UiUtils.createStyledButton(this, "Load country cameras", itemStyleParams) {
            dialog.dismiss()
            CountrySelectionDialog.show(this@RadarMapActivity, dbHelper)
        }

        val btnCheckUpdates = UiUtils.createStyledButton(this, "Check updates", itemStyleParams) {
            dialog.dismiss()
            AppUpdateManager.performManualUpdateCheck(this@RadarMapActivity)
        }
        container.addView(btnLoadCountryCams)
        container.addView(btnCheckUpdates)
        container.addView(UiUtils.createDialogDivider(this))

        // 3. Help
        val btnHelp = UiUtils.createStyledButton(this, "Help", itemStyleParams) {
            dialog.dismiss()
            startActivity(Intent(this@RadarMapActivity, HelpActivity::class.java))
        }
        container.addView(btnHelp)
        container.addView(UiUtils.createDialogDivider(this))

        // 4. Debug On / Off + Logs & Test Beep
        var isDebug = AppPrefs.isDebugMode(this)

        val debugSubContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateDebugItems() {
            debugSubContainer.removeAllViews()
            if (isDebug) {
                val btnLogs = UiUtils.createStyledButton(this@RadarMapActivity, "Logs", itemStyleParams) {
                    dialog.dismiss()
                    startActivity(Intent(this@RadarMapActivity, LogViewerActivity::class.java))
                }

                val btnTestBeep = UiUtils.createStyledButton(this@RadarMapActivity, "Test Beep", itemStyleParams) {
                    RadarForegroundService.instance?.playTestBeep()
                }

                debugSubContainer.addView(btnLogs)
                debugSubContainer.addView(btnTestBeep)
            }
        }

        lateinit var btnDebug: Button
        btnDebug = UiUtils.createStyledButton(this, if (isDebug) "Disable Debug" else "Enable Debug", itemStyleParams) {
            isDebug = !isDebug
            AppPrefs.setDebugMode(this, isDebug)
            btnDebug.text = if (isDebug) "Disable Debug" else "Enable Debug"
            if (!isDebug) {
                AppLogger.setLoggingEnabled(this@RadarMapActivity, false)
            }
            updateDebugVisibility()
            populateDebugItems()
        }

        container.addView(btnDebug)
        container.addView(debugSubContainer)
        populateDebugItems()
        container.addView(UiUtils.createDialogDivider(this))

        // 5. Quit
        val btnQuit = UiUtils.createStyledButton(this, "Quit", itemStyleParams) {
            dialog.dismiss()
            val stopIntent = Intent(this@RadarMapActivity, RadarForegroundService::class.java).apply {
                action = RadarForegroundService.ACTION_STOP_SERVICE
            }
            startService(stopIntent)
            finishAffinity()
        }
        container.addView(btnQuit)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun updateGpsButtonState() {
        if (!::btnGpsFix.isInitialized) return
        val isGpsDisabled = LocationUtils.isGpsDisabled(this)
        btnGpsFix.visibility = if (isGpsDisabled) View.VISIBLE else View.GONE
    }

    private fun refreshMapState(metricsParam: com.example.radardetector.math.ProcessedLocationMetrics? = null) {
        lastMapUpdateTimeMs = System.currentTimeMillis()
        updateSleepCountdown()
        RadarForegroundService.instance?.checkStaleGpsAndResetSpeed()
        val metrics = metricsParam ?: RadarForegroundService.lastMetrics
        val isGpsDisabled = metrics?.isGpsDisabled == true

        if (isGpsDisabled || metrics == null) {
            updateGpsSpeedDisplay(isGpsDisabled, metrics)
            if (isGpsDisabled) {
                btnGpsFix.visibility = View.VISIBLE
                tvStatusLine1.text = "Speed: 0 km/h | GPS Disabled in Settings | Interval: --"
                tvStatusLine2.text = "Beep Status: OFF (GPS Disabled) | Cams: --"
            } else {
                tvStatusLine1.text = "Speed: 0 km/h | Searching Satellites... | Interval: --"
                tvStatusLine2.text = "Beep Status: OFF (Searching GPS) | Cams: --"
            }
            return
        }

        btnGpsFix.visibility = View.GONE
        updateUi(metrics)
    }

    override fun onResume() {
        super.onResume()
        if (!RadarForegroundService.isRunning) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateDebugVisibility()
        updateGpsButtonState()

        val s = RadarForegroundService.instance
        if (s?.isDeepSleepState == true) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !LocationUtils.isGpsDisabled(this, lm)) {
                s.wakeUpFromDeepSleep("RadarMapActivity resumed with GPS active")
            }
            refreshMapState()
        }

        RadarForegroundService.serviceStateListener = { isRunning ->
            if (!isRunning) {
                runOnUiThread { finish() }
            }
        }

        RadarForegroundService.metricsListener = { metrics ->
            runOnUiThread { refreshMapState(metrics) }
        }

        mapRefreshHandler.removeCallbacks(mapRefreshRunnable)
        mapRefreshHandler.post(mapRefreshRunnable)

        refreshMapState()
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mapRefreshHandler.removeCallbacks(mapRefreshRunnable)
        RadarForegroundService.metricsListener = null
        RadarForegroundService.serviceStateListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    inner class RadarMapView(context: Context) : View(context) {

        private var currentLocation: Location? = null
        private var cameras: List<Camera> = emptyList()
        private var trajectoryBearing: Float? = null
        private var rawTrajectoryPoints: List<Location> = emptyList()

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A3A4A")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3A4A5A")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80A0C0")
            textSize = 26f
        }
        private val northLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            textSize = 32f
            isFakeBoldText = true
        }

        private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }
        private val rawTailPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A0A0A0")
            style = Paint.Style.FILL
        }
        private val rawTailLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#60A0A0A0")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val camPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            style = Paint.Style.FILL
        }
        private val camLinearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9100")
            style = Paint.Style.FILL
        }
        private val camOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FFCC00")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val camOuterFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#60FFCC00")
            style = Paint.Style.FILL
        }
        private val camTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
        }
        private val outerCamTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFCC00")
            textSize = 20f
        }
        private val bgColor = Color.parseColor("#121212")
        private val tempGeoResults = FloatArray(2)

        fun updateData(
            location: Location,
            newCameras: List<Camera>,
            bearing: Float? = null,
            points: List<Location> = emptyList()
        ) {
            this.currentLocation = location
            this.cameras = newCameras
            this.trajectoryBearing = if (bearing != null && bearing != 0f) bearing else null
            this.rawTrajectoryPoints = points
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val maxRadiusPx = min(w, h) / 2f - 40f
            val maxRangeMeters = 3000f // 3km radius = 6km view canvas

            // 1. Dark Background Grid & Distance Rings (1km, 2km, 3km)
            canvas.drawColor(bgColor)

            for (i in 1..2) {
                val r = maxRadiusPx * (i / 3f)
                canvas.drawCircle(cx, cy, r, gridPaint)
                canvas.drawText("${i} km", cx + 10f, cy - r + 26f, textPaint)
            }
            // 3km Outer Ring
            canvas.drawCircle(cx, cy, maxRadiusPx, outerRingPaint)
            canvas.drawText("3 km (Outer Ring)", cx + 10f, cy - maxRadiusPx + 26f, textPaint)

            // Crosshair Axes (North UP)
            canvas.drawLine(cx, cy - maxRadiusPx, cx, cy + maxRadiusPx, axisPaint)
            canvas.drawLine(cx - maxRadiusPx, cy, cx + maxRadiusPx, cy, axisPaint)

            // Compass Labels (North UP)
            canvas.drawText("N", cx - 10f, cy - maxRadiusPx - 10f, northLabelPaint)
            canvas.drawText("S", cx - 10f, cy + maxRadiusPx + 30f, textPaint)
            canvas.drawText("E", cx + maxRadiusPx + 10f, cy + 10f, textPaint)
            canvas.drawText("W", cx - maxRadiusPx - 30f, cy + 10f, textPaint)

            val loc = currentLocation ?: return
            val locLat = loc.latitude
            val locLon = loc.longitude

            // 2. Plot Cameras inside 3km range AND project outer RAM cameras onto the 3km Outer Ring
            for (cam in cameras) {
                RadarMath.calculateDistanceAndBearing(locLat, locLon, cam.lat, cam.lon, tempGeoResults)
                val dist = tempGeoResults[0]
                val bearingToCam = tempGeoResults[1]
                val rad = Math.toRadians(bearingToCam.toDouble())

                if (dist <= maxRangeMeters) {
                    // Inside 3km circle
                    val dx = (dist * sin(rad)).toFloat()
                    val dy = (dist * cos(rad)).toFloat()

                    val screenX = cx + (dx / maxRangeMeters) * maxRadiusPx
                    val screenY = cy - (dy / maxRangeMeters) * maxRadiusPx

                    val paintToUse = if (cam.isLinear) camLinearPaint else camPaint
                    canvas.drawCircle(screenX, screenY, 7f, paintToUse)
                } else {
                    // Outside 3km range (Loaded in RAM): Project onto the Outer Ring
                    val outerX = cx + (maxRadiusPx * sin(rad)).toFloat()
                    val outerY = cy - (maxRadiusPx * cos(rad)).toFloat()

                    canvas.drawCircle(outerX, outerY, 5f, camOuterFillPaint)
                    canvas.drawCircle(outerX, outerY, 5f, camOuterPaint)
                }
            }

            // 2.5 Draw Raw Trajectory Tail (Gray Points & Lines) ONLY when Debug Mode is ON and buffer has >= 2 points
            if (isDebugMode && rawTrajectoryPoints.size >= 2) {
                var prevSx = -1f
                var prevSy = -1f
                for (rawLoc in rawTrajectoryPoints) {
                    RadarMath.calculateDistanceAndBearing(locLat, locLon, rawLoc.latitude, rawLoc.longitude, tempGeoResults)
                    val dist = tempGeoResults[0]
                    if (dist <= maxRangeMeters) {
                        val bearingToPt = tempGeoResults[1]
                        val rad = Math.toRadians(bearingToPt.toDouble())
                        val dx = (dist * sin(rad)).toFloat()
                        val dy = (dist * cos(rad)).toFloat()

                        val screenX = cx + (dx / maxRangeMeters) * maxRadiusPx
                        val screenY = cy - (dy / maxRangeMeters) * maxRadiusPx

                        if (prevSx >= 0f && prevSy >= 0f) {
                            canvas.drawLine(prevSx, prevSy, screenX, screenY, rawTailLinePaint)
                        }
                        canvas.drawCircle(screenX, screenY, 6f, rawTailPointPaint)
                        prevSx = screenX
                        prevSy = screenY
                    }
                }
            }

            // 3. Draw Vehicle Position Marker at Center (radius reduced 3x to 5.33f)
            canvas.drawCircle(cx, cy, 5.33f, carPaint)
        }
    }
}
