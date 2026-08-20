package com.example.radardetector.util

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build

/**
 * Unified utility functions for GPS and Location operations across the app.
 */
object LocationUtils {

    /**
     * Checks whether system GPS / location is disabled on the device.
     */
    fun isGpsDisabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return isGpsDisabled(lm)
    }

    fun isGpsDisabled(context: Context, lm: LocationManager): Boolean {
        return isGpsDisabled(lm)
    }

    /**
     * Checks whether system GPS / location is disabled via LocationManager instance.
     */
    fun isGpsDisabled(lm: LocationManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                !lm.isLocationEnabled || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } else {
                !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }
        } catch (e: Exception) {
            !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }

    /**
     * Attempts to retrieve the best available last known location across GPS, Network, and Passive providers.
     */
    fun getLastKnownLocationCascade(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return getLastKnownLocationCascade(lm)
    }

    /**
     * Attempts to retrieve the best available last known location across GPS, Network, and Passive providers.
     */
    fun getLastKnownLocationCascade(lm: LocationManager): Location? {
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Helper to create a Location instance with specific coordinates.
     */
    fun createLocation(lat: Double, lon: Double, provider: String = ""): Location {
        return Location(provider).apply {
            latitude = lat
            longitude = lon
        }
    }

    /**
     * Calculates speed in km/h between two locations over time interval in seconds.
     */
    fun calculateSpeedKmh(from: Location, to: Location, dtSec: Double): Float {
        return if (dtSec > 0.2) {
            (from.distanceTo(to) / dtSec * 3.6).toFloat()
        } else {
            0f
        }
    }
}
