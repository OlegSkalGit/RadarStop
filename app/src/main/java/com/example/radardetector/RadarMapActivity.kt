package com.example.radardetector

import android.app.Activity
import android.content.Context
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RadarMapActivity : Activity(), LocationListener {

    private lateinit var mapView: RadarMapView
    private lateinit var locationManager: LocationManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var tvStatus: TextView
    private var lastLocation: Location? = null
    private var nearbyCameras: List<Camera> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLogger.log("RadarMapActivity", "onCreate", true, "RadarMapActivity launched. Screen keep-awake set.")

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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#CC181818"))
            setPadding(24, 16, 24, 16)
        }

        tvStatus = TextView(this).apply {
            text = "Searching GPS... (Scale: 6 km)"
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = Button(this).apply {
            text = "Close"
            textSize = 12f
            setOnClickListener { finish() }
        }

        topBar.addView(tvStatus)
        topBar.addView(btnClose)

        rootLayout.addView(topBar, FrameLayout.LayoutParams(
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
            tvStatus.text = "GPS Permission missing"
        } catch (e: Exception) {
            tvStatus.text = "GPS Error: ${e.message}"
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val lat = location.latitude
        val lon = location.longitude
        val speedKmh = location.speed * 3.6f

        val boxCams = dbHelper.getCamerasInBox(lat - 0.045, lat + 0.045, lon - 0.045, lon + 0.045)
        val linearCams = dbHelper.getAllLinearCameras()
        nearbyCameras = (boxCams + linearCams).distinctBy { it.id }

        val bearingStr = if (location.hasBearing()) "${location.bearing.toInt()}°" else "N/A"
        tvStatus.text = "Speed: ${speedKmh.toInt()} km/h | Heading: $bearingStr | Cams in 6km: ${nearbyCameras.size}"

        mapView.updateData(location, nearbyCameras)
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
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3A4A5A")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80A0C0")
            textSize = 28f
        }
        private val northLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            textSize = 34f
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
        private val camAzimuthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFEA00")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val camTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
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
            val maxRangeMeters = 3000f // 3km radius = 6km total canvas scale

            // 1. Background Grid & Distance Rings (1km, 2km, 3km)
            canvas.drawColor(Color.parseColor("#121212"))

            for (i in 1..3) {
                val r = maxRadiusPx * (i / 3f)
                canvas.drawCircle(cx, cy, r, gridPaint)
                canvas.drawText("${i} km", cx + 10f, cy - r + 30f, textPaint)
            }

            // Crosshair Axes (North UP)
            canvas.drawLine(cx, cy - maxRadiusPx, cx, cy + maxRadiusPx, axisPaint)
            canvas.drawLine(cx - maxRadiusPx, cy, cx + maxRadiusPx, cy, axisPaint)

            // Compass Cardinal Labels (North IS UP)
            canvas.drawText("N", cx - 10f, cy - maxRadiusPx - 10f, northLabelPaint)
            canvas.drawText("S", cx - 10f, cy + maxRadiusPx + 32f, textPaint)
            canvas.drawText("E", cx + maxRadiusPx + 10f, cy + 10f, textPaint)
            canvas.drawText("W", cx - maxRadiusPx - 35f, cy + 10f, textPaint)

            val loc = currentLocation ?: return

            // 2. Plot Cameras within 6km area (3km radius)
            for (cam in cameras) {
                val dist = RadarMath.calculateDistance(loc, cam.lat, cam.lon)
                if (dist > maxRangeMeters) continue

                val camLoc = Location("").apply {
                    latitude = cam.lat
                    longitude = cam.lon
                }
                val bearingToCam = loc.bearingTo(camLoc)
                val rad = Math.toRadians(bearingToCam.toDouble())
                val dx = (dist * sin(rad)).toFloat()
                val dy = (dist * cos(rad)).toFloat()

                val screenX = cx + (dx / maxRangeMeters) * maxRadiusPx
                val screenY = cy - (dy / maxRangeMeters) * maxRadiusPx

                // Draw Camera Marker
                val paintToUse = if (cam.isLinear) camLinearPaint else camPaint
                canvas.drawCircle(screenX, screenY, 14f, paintToUse)

                // Draw Camera Azimuth Arrow / Direction Line (if available)
                if (cam.dir != null) {
                    val camRad = Math.toRadians(cam.dir.toDouble())
                    val dirLen = 45f
                    val arrowEndX = screenX + (dirLen * sin(camRad)).toFloat()
                    val arrowEndY = screenY - (dirLen * cos(camRad)).toFloat()

                    canvas.drawLine(screenX, screenY, arrowEndX, arrowEndY, camAzimuthPaint)
                    canvas.drawCircle(arrowEndX, arrowEndY, 5f, camAzimuthPaint)
                }

                // Draw Camera ID / Distance label
                val label = "#${cam.id} (${dist.toInt()}m)"
                canvas.drawText(label, screenX + 18f, screenY + 8f, camTextPaint)
            }

            // 3. Draw Vehicle at Center
            canvas.drawCircle(cx, cy, 16f, carPaint)

            // Vehicle Movement Vector
            if (loc.hasBearing()) {
                val carRad = Math.toRadians(loc.bearing.toDouble())
                val vectorLen = 80f
                val vecEndX = cx + (vectorLen * sin(carRad)).toFloat()
                val vecEndY = cy - (vectorLen * cos(carRad)).toFloat()

                canvas.drawLine(cx, cy, vecEndX, vecEndY, carVectorPaint)

                // Arrowhead for vehicle direction
                val path = Path().apply {
                    moveTo(vecEndX, vecEndY)
                    val leftRad = Math.toRadians((loc.bearing - 150).toDouble())
                    val rightRad = Math.toRadians((loc.bearing + 150).toDouble())
                    lineTo((vecEndX + 20 * sin(leftRad)).toFloat(), (vecEndY - 20 * cos(leftRad)).toFloat())
                    lineTo((vecEndX + 20 * sin(rightRad)).toFloat(), (vecEndY - 20 * cos(rightRad)).toFloat())
                    close()
                }
                val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#00FF66")
                    style = Paint.Style.FILL
                }
                canvas.drawPath(path, arrowPaint)
            }
        }
    }
}
