package com.example.radardetector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Dialog
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import com.example.radardetector.audio.AcousticRadarEngine
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.math.*
import com.example.radardetector.network.AppUpdateManager
import com.example.radardetector.network.OverpassSyncManager
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.util.AppLogger
import java.util.Locale
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
    private lateinit var bottomStatusPanel: LinearLayout
    private var activeSyncManager: OverpassSyncManager? = null
    private data class CountryItem(val name: String, val code: String)

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

        // Top Overlay Panel (Back button on top-left, speed and cam near labels underneath on same level)
        val topOverlayPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 0)
        }

        // Top Row: Back Button on the LEFT
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnClose = Button(this).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            setOnClickListener {
                finish()
            }
        }

        val topSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                0,
                1f
            )
        }

        val btnMenu = Button(this).apply {
            text = "Menu"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            setOnClickListener {
                showMainMenuDialog()
            }
        }

        topRow.addView(btnClose)
        topRow.addView(topSpacer)
        topRow.addView(btnMenu)
        topOverlayPanel.addView(topRow)

        // Labels Row: Left (Speed) and Right (Cam Near) on the SAME horizontal level underneath Back button
        val labelsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 12, 0, 0)
        }

        // Left Container (Speed Display)
        val topLeftContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 16, 24, 16)
        }

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        tvSpeedValue = TextView(this).apply {
            text = "0"
            textSize = 44f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF1744"))
        }

        tvSpeedUnit = TextView(this).apply {
            text = " km/h"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF1744"))
            setPadding(0, 0, 0, 8)
        }

        speedRow.addView(tvSpeedValue)
        speedRow.addView(tvSpeedUnit)

        tvSubLabel = TextView(this).apply {
            text = "LOW speed"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF1744"))
            visibility = View.VISIBLE
        }

        topLeftContainer.addView(speedRow)
        topLeftContainer.addView(tvSubLabel)

        labelsRow.addView(topLeftContainer)
        topOverlayPanel.addView(labelsRow)

        val topOverlayParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        rootLayout.addView(topOverlayPanel, topOverlayParams)

        // Bottom Overlay Panel (Holds "cam near" warning at bottom-left and bottomStatusPanel)
        val bottomOverlayPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
        }

        // Bottom Left Container ("cam near" Warning)
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

        // Bottom Status Spoiler Panel
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

    private fun updateUi(metrics: com.example.radardetector.math.ProcessedLocationMetrics) {
        try {
            val speedKmh = metrics.speedKmh
            val gpsStatusStr = metrics.gpsStatusStr
            val closestAlertCam = metrics.closestAlertCamera
            val minAlertDist = metrics.minDistanceToAlert

            // Update Top-Right Camera Warning
            val isCamNear = closestAlertCam != null && minAlertDist <= 300f
            tvCamNearWarning.visibility = if (isCamNear) View.VISIBLE else View.GONE

            // Update Top-Left Speed Overlay & Dynamic Styling
            val speedInt = speedKmh.toInt()
            tvSpeedValue.text = "$speedInt"

            if (metrics.isAccuracyWeak) {
                tvSpeedValue.setTextColor(Color.WHITE)
                tvSpeedUnit.setTextColor(Color.WHITE)
                tvSubLabel.text = "GPS bad"
                tvSubLabel.setTextColor(Color.WHITE)
                tvSubLabel.visibility = View.VISIBLE
            } else {
                if (speedKmh < 30f) {
                    val redColor = Color.parseColor("#FF1744")
                    tvSpeedValue.setTextColor(redColor)
                    tvSpeedUnit.setTextColor(redColor)
                    tvSubLabel.text = "LOW speed"
                    tvSubLabel.setTextColor(redColor)
                    tvSubLabel.visibility = View.VISIBLE
                } else {
                    val greenColor = Color.parseColor("#00E676")
                    tvSpeedValue.setTextColor(greenColor)
                    tvSpeedUnit.setTextColor(greenColor)
                    val isAccGood = metrics.location.hasAccuracy() && metrics.location.accuracy <= 15f
                    if (isAccGood) {
                        tvSubLabel.text = "GPS good"
                        tvSubLabel.setTextColor(greenColor)
                        tvSubLabel.visibility = View.VISIBLE
                    } else {
                        tvSubLabel.text = ""
                        tvSubLabel.visibility = View.GONE
                    }
                }
            }

            val activeIntervalMs = RadarForegroundService.currentGpsIntervalMs
            val activeSec = if (activeIntervalMs > 0) activeIntervalMs / 1000L else 1L
            val pollingIntervalStr = when (activeIntervalMs) {
                1000L -> "1s (Camera Nearby)"
                3000L -> if (speedKmh <= 30f) "3s (Grace Period)" else "3s (Normal)"
                15000L -> "15s (Smart Sleep)"
                30000L -> "30s (Sleep Mode)"
                else -> "${activeSec}s"
            }

            val beepStatusStr = when {
                metrics.isAccuracyWeak -> "PAUSED (Weak GPS)"
                metrics.isStationary -> "PAUSED (Stationary Stop)"
                speedKmh <= 30f -> "PAUSED (Speed <= 30 km/h)"
                closestAlertCam != null -> {
                    val delayMs = RadarMath.calculateBeepDelay(minAlertDist)
                    val distInt = minAlertDist.toInt()
                    "ALERT ${distInt}m (${delayMs}ms)"
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

    private fun updateDebugVisibility() {
        val prefs = getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)
        val isDebug = prefs.getBoolean("debug_mode", false)
        bottomStatusPanel.visibility = if (isDebug) View.VISIBLE else View.GONE
    }

    private fun showMainMenuDialog() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#252525"))
        }

        val prefs = getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)

        fun createDivider(): View {
            return View(this@RadarMapActivity).apply {
                setBackgroundColor(Color.parseColor("#44FFFFFF"))
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    2
                ).apply {
                    setMargins(0, 10, 0, 10)
                }
                layoutParams = params
            }
        }

        val itemStyleParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 4, 0, 4)
        }

        // 1. Autostart On / Off
        var isAutostart = prefs.getBoolean("autostart", false)
        val btnAutostart = Button(this).apply {
            text = if (isAutostart) "Autostart On" else "Autostart Off"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                isAutostart = !isAutostart
                prefs.edit().putBoolean("autostart", isAutostart).apply()
                text = if (isAutostart) "Autostart On" else "Autostart Off"
                val statusMsg = if (isAutostart) "Start with system - Enable" else "Start with system - Disable"
                Toast.makeText(this@RadarMapActivity, statusMsg, Toast.LENGTH_SHORT).show()
                AppLogger.log("RadarMapActivity", "AutostartToggle", true, statusMsg)
            }
        }
        container.addView(btnAutostart)
        container.addView(createDivider())

        // 2. Load country cameras & Check updates
        val btnLoadCountryCams = Button(this).apply {
            text = "Load country cameras"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                showCountrySelectionDialog()
            }
        }

        val btnCheckUpdates = Button(this).apply {
            text = "Check updates"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                Toast.makeText(this@RadarMapActivity, "Checking for updates...", Toast.LENGTH_SHORT).show()
                AppUpdateManager.checkAndDownloadUpdate(this@RadarMapActivity, force = true) { result ->
                    Toast.makeText(this@RadarMapActivity, result, Toast.LENGTH_LONG).show()
                }
            }
        }
        container.addView(btnLoadCountryCams)
        container.addView(btnCheckUpdates)
        container.addView(createDivider())

        // 3. Help
        val btnHelp = Button(this).apply {
            text = "Help"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this@RadarMapActivity, HelpActivity::class.java)
                startActivity(intent)
            }
        }
        container.addView(btnHelp)
        container.addView(createDivider())

        // 4. Debug On / Off + Logs & Test Beep
        var isDebug = prefs.getBoolean("debug_mode", false)

        val debugSubContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateDebugItems() {
            debugSubContainer.removeAllViews()
            if (isDebug) {
                val btnLogs = Button(this@RadarMapActivity).apply {
                    text = "Logs"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#3A3A3A"))
                    layoutParams = itemStyleParams
                    setOnClickListener {
                        dialog.dismiss()
                        val intent = Intent(this@RadarMapActivity, LogViewerActivity::class.java)
                        startActivity(intent)
                    }
                }

                val btnTestBeep = Button(this@RadarMapActivity).apply {
                    text = "Test Beep"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#3A3A3A"))
                    layoutParams = itemStyleParams
                    setOnClickListener {
                        AcousticRadarEngine(this@RadarMapActivity).playSingleBeep()
                    }
                }

                debugSubContainer.addView(btnLogs)
                debugSubContainer.addView(btnTestBeep)
            }
        }

        val btnDebug = Button(this).apply {
            text = if (isDebug) "Debug On" else "Debug Off"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                isDebug = !isDebug
                prefs.edit().putBoolean("debug_mode", isDebug).apply()
                text = if (isDebug) "Debug On" else "Debug Off"
                if (!isDebug) {
                    AppLogger.setLoggingEnabled(this@RadarMapActivity, false)
                }
                updateDebugVisibility()
                populateDebugItems()
            }
        }

        container.addView(btnDebug)
        container.addView(debugSubContainer)
        populateDebugItems()
        container.addView(createDivider())

        // 5. Turn Off
        val btnTurnOff = Button(this).apply {
            text = "Turn Off"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                val stopIntent = Intent(this@RadarMapActivity, RadarForegroundService::class.java).apply {
                    action = RadarForegroundService.ACTION_STOP_SERVICE
                }
                startService(stopIntent)
                finishAffinity()
            }
        }
        container.addView(btnTurnOff)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showCountrySelectionDialog() {
        val syncManager = OverpassSyncManager(applicationContext, dbHelper)
        activeSyncManager = syncManager

        val dialog = Dialog(this)
        dialog.setTitle("Select Country")
        dialog.setOnDismissListener {
            syncManager.shutdown()
            if (activeSyncManager == syncManager) {
                activeSyncManager = null
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#252525"))
        }

        val searchInput = EditText(this).apply {
            hint = "Search country..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(16, 16, 16, 16)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val loadingLabel = TextView(this).apply {
            text = "Loading countries..."
            setTextColor(Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        listContainer.addView(loadingLabel)

        var activeCountriesList = emptyList<CountryItem>()

        fun populateList(query: String) {
            listContainer.removeAllViews()
            val filtered = activeCountriesList.filter {
                it.name.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true)
            }
            for (item in filtered) {
                val btn = Button(this@RadarMapActivity).apply {
                    text = "${item.name} (${item.code})"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#3A3A3A"))
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 4, 0, 4)
                    }
                    layoutParams = params
                    setOnClickListener {
                        dialog.dismiss()
                        startCountrySync(item.code, item.name)
                    }
                }
                listContainer.addView(btn)
            }
        }

        syncManager.fetchOrGetCachedCountries { fetched ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (fetched.isNotEmpty()) {
                    activeCountriesList = fetched.map { CountryItem(it.first, it.second) }
                    populateList(searchInput.text?.toString() ?: "")
                } else {
                    listContainer.removeAllViews()
                    val errorLabel = TextView(this@RadarMapActivity).apply {
                        text = "No countries available. Check internet."
                        setTextColor(Color.RED)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(0, 32, 0, 32)
                    }
                    listContainer.addView(errorLabel)
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        container.addView(searchInput)
        scrollView.addView(listContainer)
        container.addView(scrollView)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun startCountrySync(countryCode: String, countryName: String) {
        val serviceIntent = Intent(this, RadarForegroundService::class.java).apply {
            action = RadarForegroundService.ACTION_LOAD_COUNTRY_CAMS
            putExtra(RadarForegroundService.EXTRA_COUNTRY_CODE, countryCode)
            putExtra(RadarForegroundService.EXTRA_COUNTRY_NAME, countryName)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Downloading speed cameras for $countryName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLogger.log("RadarMapActivity", "startCountrySync", false, "Failed to start service for country sync: ${e.message}")
            Toast.makeText(this, "Failed to start camera download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!RadarForegroundService.isRunning) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateDebugVisibility()

        RadarForegroundService.serviceStateListener = { isRunning ->
            if (!isRunning) {
                runOnUiThread { finish() }
            }
        }

        RadarForegroundService.metricsListener = { metrics ->
            runOnUiThread { updateUi(metrics) }
        }

        RadarForegroundService.lastMetrics?.let {
            updateUi(it)
        }
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RadarForegroundService.metricsListener = null
        RadarForegroundService.serviceStateListener = null
    }

    override fun onDestroy() {
        activeSyncManager?.shutdown()
        activeSyncManager = null
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
            canvas.drawColor(Color.parseColor("#121212"))

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

            // 2. Plot Cameras inside 3km range AND project outer RAM cameras onto the 3km Outer Ring
            for (cam in cameras) {
                val dist = RadarMath.calculateDistance(loc, cam.lat, cam.lon)

                val camLoc = Location("").apply {
                    latitude = cam.lat
                    longitude = cam.lon
                }
                val bearingToCam = loc.bearingTo(camLoc)
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

            // 2.5 Draw Raw Trajectory Tail (Gray Points & Lines) ALWAYS when buffer has >= 2 points
            if (rawTrajectoryPoints.size >= 2) {
                var prevSx = -1f
                var prevSy = -1f
                for (rawLoc in rawTrajectoryPoints) {
                    val dist = loc.distanceTo(rawLoc)
                    if (dist <= maxRangeMeters) {
                        val bearingToPt = loc.bearingTo(rawLoc)
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
