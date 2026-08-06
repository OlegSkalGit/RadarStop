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
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.math.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RadarForegroundService.isRunning) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLogger.log("RadarMapActivity", "onCreate", true, "RadarMapActivity launched. Keep-awake set.")

        dbHelper = DatabaseHelper(this)

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
            setPadding(24, 24, 24, 16)
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val btnBack = Button(this).apply {
            text = "Back"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            setOnClickListener {
                val intent = Intent(this@RadarMapActivity, LogViewerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        }

        val headerTitle = TextView(this).apply {
            text = "RadarStop Map"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSpacer = Button(this).apply {
            text = "Menu"
            textSize = 14f
            visibility = View.INVISIBLE
        }

        headerLayout.addView(btnBack)
        headerLayout.addView(headerTitle)
        headerLayout.addView(btnSpacer)

        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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

        topPanel.addView(headerLayout)
        topPanel.addView(statusContainer)

        rootLayout.addView(topPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(rootLayout)
    }

    private fun updateUi(metrics: com.example.radardetector.math.ProcessedLocationMetrics) {
        val speedKmh = metrics.speedKmh
        val gpsStatusStr = metrics.gpsStatusStr
        val closestAlertCam = metrics.closestAlertCamera
        val minAlertDist = metrics.minDistanceToAlert

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
            speedKmh <= 30f -> "PAUSED (Speed <= 30 km/h)"
            closestAlertCam != null -> {
                val delayMs = RadarMath.calculateBeepDelay(minAlertDist)
                "ALERT ${minAlertDist.toInt()}m (${delayMs}ms)"
            }
            else -> "OFF (Idle)"
        }

        val totalDb = dbHelper.getCameraCount()
        tvStatusLine1.text = "Speed: ${speedKmh.toInt()} km/h | $gpsStatusStr | Interval: $pollingIntervalStr"
        tvStatusLine2.text = "Beep Status: $beepStatusStr | Cams: ${metrics.inRange3kmCount} in 3km / ${metrics.cameraLoadResult.boxCameraCount} in 10x10km / $totalDb total DB"

        val isBeepingAlert = (closestAlertCam != null && speedKmh > 30f && !metrics.isAccuracyWeak)
        mapView.updateData(
            location = metrics.location,
            newCameras = metrics.cameraLoadResult.cameras,
            bearing = metrics.trajectoryBearing,
            points = metrics.trajectoryPoints,
            alertCam = closestAlertCam,
            alertDist = minAlertDist,
            isBeeping = isBeepingAlert
        )
    }

    override fun onResume() {
        super.onResume()
        if (!RadarForegroundService.isRunning) {
            finish()
            return
        }

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
        private var alertCamera: Camera? = null
        private var alertDistanceMeters: Float = Float.MAX_VALUE
        private var isBeepingAlert: Boolean = false

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
        private val carVectorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF66")
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
        private val alertCamRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF1744")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val alertDistTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFEA00") // Bright Yellow
            textSize = 24f
            isFakeBoldText = true
        }

        fun updateData(
            location: Location,
            newCameras: List<Camera>,
            bearing: Float? = null,
            points: List<Location> = emptyList(),
            alertCam: Camera? = null,
            alertDist: Float = Float.MAX_VALUE,
            isBeeping: Boolean = false
        ) {
            this.currentLocation = location
            this.cameras = newCameras
            this.trajectoryBearing = if (bearing != null && bearing != 0f) bearing else null
            this.rawTrajectoryPoints = points
            this.alertCamera = alertCam
            this.alertDistanceMeters = alertDist
            this.isBeepingAlert = isBeeping
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

                    // Draw Alert Halo & Distance Text during active beeps
                    if (isBeepingAlert && alertCamera?.id == cam.id) {
                        canvas.drawCircle(screenX, screenY, 18f, alertCamRingPaint)
                        val distInt = alertDistanceMeters.toInt()
                        canvas.drawText("${distInt}m", screenX + 16f, screenY + 8f, alertDistTextPaint)
                    }
                } else {
                    // Outside 3km range (Loaded in RAM): Project onto the Outer Ring
                    val outerX = cx + (maxRadiusPx * sin(rad)).toFloat()
                    val outerY = cy - (maxRadiusPx * cos(rad)).toFloat()

                    canvas.drawCircle(outerX, outerY, 5f, camOuterFillPaint)
                    canvas.drawCircle(outerX, outerY, 5f, camOuterPaint)
                }
            }

            // 2.5 Draw Raw Trajectory Tail (Gray Points & Lines) during movement
            if (trajectoryBearing != null && rawTrajectoryPoints.size >= 2) {
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

            // 3. Draw Vehicle at Center with Direction Vector Arrow
            canvas.drawCircle(cx, cy, 16f, carPaint)

            trajectoryBearing?.let { bearing ->
                val rad = Math.toRadians(bearing.toDouble())
                val vecLenPx = 70f
                val endX = cx + (vecLenPx * sin(rad)).toFloat()
                val endY = cy - (vecLenPx * cos(rad)).toFloat()

                // Vector Direction Line
                canvas.drawLine(cx, cy, endX, endY, carVectorPaint)

                // Vector Arrowhead
                val arrowHeadLen = 20f
                val arrowAngle = Math.toRadians(25.0)
                val leftX = endX - (arrowHeadLen * sin(rad - arrowAngle)).toFloat()
                val leftY = endY + (arrowHeadLen * cos(rad - arrowAngle)).toFloat()
                val rightX = endX - (arrowHeadLen * sin(rad + arrowAngle)).toFloat()
                val rightY = endY + (arrowHeadLen * cos(rad + arrowAngle)).toFloat()

                val arrowPath = Path().apply {
                    moveTo(endX, endY)
                    lineTo(leftX, leftY)
                    lineTo(rightX, rightY)
                    close()
                }
                canvas.drawPath(arrowPath, carVectorFillPaint)
            }
        }
    }
}
