package com.example.radardetector.math

import android.location.Location
import kotlin.math.abs
import kotlin.math.cos

object RadarMath {

    fun angleDifference(bearing1: Float, bearing2: Float): Float {
        var diff = (bearing1 - bearing2) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }

    /**
     * Calculates V_approach = V_car * cos(Delta_alpha)
     * Returns Pair(V_approach in km/h, distance in meters)
     */
    fun calculateApproachSpeed(carLocation: Location, cameraLat: Double, cameraLon: Double): Pair<Float, Float> {
        val cameraLocation = Location("").apply {
            latitude = cameraLat
            longitude = cameraLon
        }
        val distance = carLocation.distanceTo(cameraLocation)
        val bearingToCamera = carLocation.bearingTo(cameraLocation)
        val carBearing = carLocation.bearing

        val deltaAlphaDegrees = angleDifference(carBearing, bearingToCamera)
        val deltaAlphaRad = Math.toRadians(deltaAlphaDegrees.toDouble())

        val speedKmh = carLocation.speed * 3.6f
        val vApproach = (speedKmh * cos(deltaAlphaRad)).toFloat()

        return Pair(vApproach, distance)
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
}
