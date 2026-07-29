package com.example.radardetector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.radardetector.service.RadarForegroundService

class SplashActivity : Activity() {

    companion object {
        private const val REQ_FOREGROUND_PERMS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (RadarForegroundService.isRunning) {
            Toast.makeText(this, "Radar Detector Active", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_FOREGROUND_PERMS)
        } else {
            onForegroundPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_FOREGROUND_PERMS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                onForegroundPermissionsGranted()
            } else {
                Toast.makeText(this, "Location and notification permissions are required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun onForegroundPermissionsGranted() {
        checkBackgroundLocationAndBattery()
    }

    private fun checkBackgroundLocationAndBattery() {
        val needsBgLoc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val needsBattery = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !pm.isIgnoringBatteryOptimizations(packageName)

        if (needsBgLoc || needsBattery) {
            showExplanationDialog(needsBgLoc, needsBattery)
        } else {
            startRadarServiceAndFinish()
        }
    }

    private fun showExplanationDialog(needsBgLoc: Boolean, needsBattery: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Background & Battery Settings")
            .setMessage("Radar Detector requires continuous background location access ('Allow all the time') and battery optimization exemption to alert you of speed cameras while driving.")
            .setPositiveButton("Configure") { _, _ ->
                if (needsBgLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (needsBattery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                startRadarServiceAndFinish()
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun startRadarServiceAndFinish() {
        val serviceIntent = Intent(this, RadarForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        finish()
    }
}
