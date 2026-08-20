package com.example.radardetector.util

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build

/**
 * Unified utility functions for GPS and Location operations across the app.
 */
object LocationUtils {

    fun hasFineLocationPermission(context: Context): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return isLocationEnabled(lm)
    }

    fun isLocationEnabled(lm: LocationManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isGpsProviderEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return isGpsProviderEnabled(lm)
    }

    fun isGpsProviderEnabled(lm: LocationManager): Boolean {
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    fun isPreciseGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return isPreciseGpsEnabled(context, lm)
    }

    fun isPreciseGpsEnabled(context: Context, lm: LocationManager): Boolean {
        return isLocationEnabled(lm) && isGpsProviderEnabled(lm) && hasFineLocationPermission(context)
    }

    /**
     * Checks whether system GPS / location is disabled on the device.
     */
    fun isGpsDisabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return isGpsDisabled(lm)
    }

    fun isGpsDisabled(lm: LocationManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                !lm.isLocationEnabled
            } else {
                !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
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
