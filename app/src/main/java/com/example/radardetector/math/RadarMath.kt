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
     * Checks if camera azimuth is within ±45° of vehicle bearing ("head-on" or "rear-facing").
     * Returns true if cameraDir is null (fallback radius mode).
     */
    fun isAzimuthValid(carBearing: Float, cameraDir: Float?): Boolean {
        if (cameraDir == null) return true
        val diff = abs(angleDifference(carBearing, cameraDir))
        return diff <= 45f || abs(angleDifference(carBearing, (cameraDir + 180f) % 360f)) <= 45f
    }

    /**
     * Symmetrically calculates beep interval delay based on distance (0..300m)
     * for both approach (300m -> 0m) and departure (0m -> 300m).
     */
    fun calculateBeepDelay(distanceMeters: Float): Long {
        return when {
            distanceMeters > 200f -> 1500L
            distanceMeters > 100f -> 800L
            distanceMeters > 50f  -> 400L
            else                  -> 100L
        }
    }
}
