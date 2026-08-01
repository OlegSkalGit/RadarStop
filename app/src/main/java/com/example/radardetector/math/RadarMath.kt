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
     * Checks if camera direction (if present) aligns with vehicle bearing within +-45 degrees.
     */
    fun isAzimuthValid(carBearing: Float, cameraDir: Float?): Boolean {
        if (cameraDir == null) return true
        val diff = abs(angleDifference(carBearing, cameraDir))
        return diff <= 45f || abs(angleDifference(carBearing, (cameraDir + 180f) % 360f)) <= 45f
    }

    /**
     * Checks if camera is ahead of vehicle (approaching) within a +-70 degree cone.
     */
    fun isCameraAhead(carLocation: Location, cameraLat: Double, cameraLon: Double): Boolean {
        if (!carLocation.hasBearing() || carLocation.speed * 3.6f < 5f) return true
        val cameraLoc = Location("").apply {
            latitude = cameraLat
            longitude = cameraLon
        }
        val distance = carLocation.distanceTo(cameraLoc)
        if (distance <= 100f) return true
        val bearingToCamera = carLocation.bearingTo(cameraLoc)
        val angleDiff = abs(angleDifference(carLocation.bearing, bearingToCamera))
        return angleDiff <= 85f
    }

    /**
     * Calculates beep delay based on distance and speed.
     * Continuous rapid beep (150ms) within 50m (<=70 km/h) or 100m (>70 km/h).
     */
    fun calculateBeepDelay(distanceMeters: Float, speedKmh: Float): Long {
        val continuousThreshold = if (speedKmh <= 70f) 50f else 100f
        val maxAlertDistance = if (speedKmh <= 70f) 500f else 1000f
        val dist = abs(distanceMeters)

        return when {
            dist <= continuousThreshold -> 150L // Continuous rapid beep zone
            dist <= maxAlertDistance * 0.33f -> 400L
            dist <= maxAlertDistance * 0.66f -> 800L
            else -> 1500L
        }
    }
}
