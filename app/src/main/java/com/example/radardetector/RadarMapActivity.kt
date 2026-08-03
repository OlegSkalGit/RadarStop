package com.example.radardetector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.math.RadarMath
import com.example.radardetector.util.AppLogger
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RadarMapActivity : Activity(), LocationListener {

    private lateinit var mapView: RadarMapView
    private lateinit var locationManager: LocationManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var tvStatusLine1: TextView
    private lateinit var tvStatusLine2: TextView
    private var lastLocation: Location? = null
    private var nearbyCameras: List<Camera> = emptyList()
    private var speedDropBelow30TimeMs = 0L
    private var effectiveSpeedKmh: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLogger.log("RadarMapActivity", "onCreate", true, "RadarMapActivity launched. Keep-awake set.")

        dbHelper = DatabaseHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        mapView = RadarMapView(this)
        rootLayout.addView(mapView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Overlay status panel (Top)
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E614181F"))
            setPadding(24, 16, 24, 16)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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

        statusContainer.addView(tvStatusLine1)
        statusContainer.addView(tvStatusLine2)

        val btnClose = Button(this).apply {
            text = "Close"
            textSize = 12f
            setOnClickListener {
                val intent = Intent(this@RadarMapActivity, LogViewerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        }

        topRow.addView(statusContainer)
        topRow.addView(btnClose)

        topPanel.addView(topRow)

        rootLayout.addView(topPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(rootLayout)

        registerGpsUpdates()
    }

    private fun registerGpsUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                this
            )
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastGps != null) {
                onLocationChanged(lastGps)
            }
        } catch (e: SecurityException) {
            tvStatusLine1.text = "GPS Permission missing"
        } catch (e: Exception) {
            tvStatusLine1.text = "GPS Error: ${e.message}"
        }
    }

    override fun onLocationChanged(location: Location) {
        val activeServiceMetrics = if (com.example.radardetector.service.RadarForegroundService.isRunning) {
            com.example.radardetector.service.RadarForegroundService.lastMetrics
        } else null

        val metrics = activeServiceMetrics ?: run {
            val ramCache = com.example.radardetector.service.RadarForegroundService.getRamCachedLoadResult()
            RadarMath.evaluateLocationData(location, effectiveSpeedKmh, dbHelper, ramCache)
        }
        effectiveSpeedKmh = metrics.effectiveSpeedKmh
        lastLocation = metrics.location
        nearbyCameras = metrics.cameraLoadResult.cameras

        val speedKmh = metrics.effectiveSpeedKmh
        val gpsStatusStr = metrics.gpsStatusStr
        val closestAlertCam = metrics.closestAlertCamera
        val minAlertDist = metrics.minDistanceToAlert

        // Polling Interval
        val activeIntervalMs = if (com.example.radardetector.service.RadarForegroundService.isRunning) {
            com.example.radardetector.service.RadarForegroundService.currentGpsIntervalMs
        } else {
            when {
                speedKmh <= 30f -> {
                    val now = System.currentTimeMillis()
                    if (speedDropBelow30TimeMs == 0L) speedDropBelow30TimeMs = now
                    val timeBelow30 = now - speedDropBelow30TimeMs
                    if (timeBelow30 < 3 * 60 * 1000L) 3000L else 30000L
                }
                else -> {
                    speedDropBelow30TimeMs = 0L
                    val maxGpsReadDist = if (speedKmh <= 60f) 500f else 1000f
                    when {
                        metrics.minDistToAnyCamera <= maxGpsReadDist -> 1000L
                        metrics.minDistToAnyCamera <= 3000f -> 3000L
                        else -> 15000L
                    }
                }
            }
        }

        val activeSec = if (activeIntervalMs > 0) activeIntervalMs / 1000L else 1L
        val pollingIntervalStr = when (activeIntervalMs) {
            1000L -> "1s (Camera Nearby)"
            3000L -> if (speedKmh <= 30f) "3s (Grace Period)" else "3s (Normal)"
            15000L -> "15s (Smart Sleep)"
            30000L -> "30s (Sleep Mode)"
            else -> "${activeSec}s"
        }

        // Beep Status
        val beepStatusStr = when {
            metrics.isAccuracyWeak -> "PAUSED (Weak GPS)"
            speedKmh <= 30f -> "PAUSED (Speed <= 30 km/h)"
            closestAlertCam != null -> {
                val delayMs = RadarMath.calculateBeepDelay(minAlertDist, speedKmh)
                "ALERT (${delayMs}ms)"
            }
            else -> "OFF (Idle)"
        }

        val totalDb = dbHelper.getCameraCount()
        tvStatusLine1.text = "Speed: ${speedKmh.toInt()} km/h | $gpsStatusStr | Interval: $pollingIntervalStr"
        tvStatusLine2.text = "Beep Status: $beepStatusStr | Cams: ${metrics.inRange3kmCount} in 3km / ${metrics.cameraLoadResult.boxCameraCount} in 10x10km / $totalDb total DB"

        mapView.updateData(location, nearbyCameras)
    }

    override fun onResume() {
        super.onResume()
        lastLocation?.let { onLocationChanged(it) } ?: run {
            val totalDb = dbHelper.getCameraCount()
            tvStatusLine2.text = "Beep Status: -- | Cams: -- in 3km / -- in 10x10km / $totalDb total DB"
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    inner class RadarMapView(context: Context) : View(context) {

        private var currentLocation: Location? = null
        private var cameras: List<Camera> = emptyList()

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
        private val carVectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF66")
            style = Paint.Style.STROKE
            strokeWidth = 5f
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
        private val camAzimuthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFEA00")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val camTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
        }
        private val outerCamTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFCC00")
            textSize = 20f
        }

        fun updateData(location: Location, newCameras: List<Camera>) {
            this.currentLocation = location
            this.cameras = newCameras
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

            // 3. Draw Vehicle at Center
            canvas.drawCircle(cx, cy, 16f, carPaint)
        }
    }
}
