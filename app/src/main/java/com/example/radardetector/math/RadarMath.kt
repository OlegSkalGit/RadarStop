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
     * Checks if camera direction (if present) aligns with vehicle bearing within +-50 degrees (100 degrees total cone).
     * If carLocation has no bearing (hasBearing() == false), returns true (360 degree mode).
     */
    fun isAzimuthValid(carLocation: Location, cameraDir: Float?): Boolean {
        if (!carLocation.hasBearing() || cameraDir == null) return true
        val carBearing = carLocation.bearing
        val diff = abs(angleDifference(carBearing, cameraDir))
        return diff <= 50f || abs(angleDifference(carBearing, (cameraDir + 180f) % 360f)) <= 50f
    }

    /**
     * Checks if camera is ahead of vehicle (approaching) within a +-100 degree cone up to maxAlertDistance.
     */
    fun isCameraAhead(carLocation: Location, cameraLat: Double, cameraLon: Double, maxAlertDistance: Float): Boolean {
        if (!carLocation.hasBearing() || carLocation.speed * 3.6f < 5f) return true
        val cameraLoc = Location("").apply {
            latitude = cameraLat
            longitude = cameraLon
        }
        val distance = carLocation.distanceTo(cameraLoc)
        if (distance > maxAlertDistance) return false
        val bearingToCamera = carLocation.bearingTo(cameraLoc)
        val angleDiff = abs(angleDifference(carLocation.bearing, bearingToCamera))
        return angleDiff <= 100f
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
}
