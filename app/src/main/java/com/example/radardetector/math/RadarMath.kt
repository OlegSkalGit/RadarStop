package com.example.radardetector.math

import android.location.Location
import kotlin.math.abs

object RadarMath {

    fun angleDifference(bearing1: Float, bearing2: Float): Float {
        var diff = (bearing1 - bearing2) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }

    /**
     * Calculates geodesic distance in meters between car and camera coordinates.
     */
    fun calculateDistance(carLocation: Location, cameraLat: Double, cameraLon: Double): Float {
        val cameraLocation = Location("").apply {
            latitude = cameraLat
            longitude = cameraLon
        }
        return carLocation.distanceTo(cameraLocation)
    }



    /**
     * Calculates beep delay based on distance (beeps active up to 300m).
     * Continuous rapid beep (150ms) within 50m (<=60 km/h) or 100m (>60 km/h).
     */
    fun calculateBeepDelay(distanceMeters: Float, speedKmh: Float): Long {
        val continuousThreshold = if (speedKmh <= 60f) 50f else 100f
        val maxBeepDistance = 300f
        val dist = abs(distanceMeters)

        return when {
            dist <= continuousThreshold -> 150L // Continuous rapid beep zone
            dist <= maxBeepDistance * 0.33f -> 400L // <= 100m
            dist <= maxBeepDistance * 0.66f -> 800L // <= 200m
            else -> 1500L // <= 300m
        }
    }

    /**
     * Calculates 10x10 km Bounding Box coordinates (minLat, maxLat, minLon, maxLon)
     * corresponding to +-0.045 degrees around lat/lon.
     */
    fun get10x10BoxCoordinates(lat: Double, lon: Double): DoubleArray {
        return doubleArrayOf(lat - 0.045, lat + 0.045, lon - 0.045, lon + 0.045)
    }

    /**
     * Unified camera loader for 10x10 km RAM cache + linear cameras.
     * Used identically by both RadarForegroundService and RadarMapActivity.
     */
    fun load10x10Cameras(dbHelper: com.example.radardetector.db.DatabaseHelper, lat: Double, lon: Double): CameraLoadResult {
        val box = get10x10BoxCoordinates(lat, lon)
        val minLat = box[0]
        val maxLat = box[1]
        val minLon = box[2]
        val maxLon = box[3]

        val boxCams = dbHelper.getCamerasInBox(minLat, maxLat, minLon, maxLon)
        val linearCams = dbHelper.getAllLinearCameras()
        val totalInDb = dbHelper.getCameraCount()
        val merged = (boxCams + linearCams).distinctBy { it.id }

        return CameraLoadResult(
            cameras = merged,
            boxCameraCount = merged.size,
            totalInDb = totalInDb,
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon
        )
    }

    /**
     * Unified speed calculation with smoothing filter (10 km/h delta threshold).
     */
    fun calculateEffectiveSpeed(rawSpeedKmh: Float, currentEffectiveSpeedKmh: Float): Float {
        if (currentEffectiveSpeedKmh == 0f) return rawSpeedKmh
        val delta = abs(rawSpeedKmh - currentEffectiveSpeedKmh)
        return if (delta > 10f) rawSpeedKmh else currentEffectiveSpeedKmh
    }

    /**
     * Single authoritative location evaluator function used identically by both
     * RadarForegroundService and RadarMapActivity to produce 100% unified metrics.
     */
    fun evaluateLocationData(
        location: Location,
        currentEffectiveSpeedKmh: Float,
        dbHelper: com.example.radardetector.db.DatabaseHelper
    ): ProcessedLocationMetrics {
        val rawSpeedKmh = location.speed * 3.6f
        val effectiveSpeedKmh = calculateEffectiveSpeed(rawSpeedKmh, currentEffectiveSpeedKmh)
        val isAccuracyWeak = location.hasAccuracy() && location.accuracy > 15f

        val gpsStatusStr = if (isAccuracyWeak) {
            "GPS: WEAK (>15m [${location.accuracy.toInt()}m])"
        } else if (location.hasAccuracy()) {
            "GPS: OK (±${location.accuracy.toInt()}m)"
        } else {
            "GPS: ACTIVE"
        }

        val loadResult = load10x10Cameras(dbHelper, location.latitude, location.longitude)
        val continuousThresh = if (effectiveSpeedKmh <= 60f) 50f else 100f

        var minDistToAnyCam = Float.MAX_VALUE
        var closestAlertCam: com.example.radardetector.db.Camera? = null
        var minAlertDist = Float.MAX_VALUE

        for (cam in loadResult.cameras) {
            val dist = calculateDistance(location, cam.lat, cam.lon)
            if (dist < minDistToAnyCam) minDistToAnyCam = dist

            if (dist <= 300f) {
                if (dist < minAlertDist) {
                    minAlertDist = dist
                    closestAlertCam = cam
                }
            }
        }

        val inRange3kmCount = loadResult.cameras.count { calculateDistance(location, it.lat, it.lon) <= 3000f }

        return ProcessedLocationMetrics(
            location = location,
            rawSpeedKmh = rawSpeedKmh,
            effectiveSpeedKmh = effectiveSpeedKmh,
            isAccuracyWeak = isAccuracyWeak,
            gpsStatusStr = gpsStatusStr,
            cameraLoadResult = loadResult,
            inRange3kmCount = inRange3kmCount,
            minDistToAnyCamera = minDistToAnyCam,
            closestAlertCamera = closestAlertCam,
            minDistanceToAlert = minAlertDist,
            continuousThreshold = continuousThresh
        )
    }
}

data class CameraLoadResult(
    val cameras: List<com.example.radardetector.db.Camera>,
    val boxCameraCount: Int,
    val totalInDb: Int,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

data class ProcessedLocationMetrics(
    val location: Location,
    val rawSpeedKmh: Float,
    val effectiveSpeedKmh: Float,
    val isAccuracyWeak: Boolean,
    val gpsStatusStr: String,
    val cameraLoadResult: CameraLoadResult,
    val inRange3kmCount: Int,
    val minDistToAnyCamera: Float,
    val closestAlertCamera: com.example.radardetector.db.Camera?,
    val minDistanceToAlert: Float,
    val continuousThreshold: Float
)
