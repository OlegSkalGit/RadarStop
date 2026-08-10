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
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.util.AppLogger

class SplashActivity : Activity() {

    private var awaitingSettings = false

    companion object {
        private const val REQ_FOREGROUND_PERMS = 1001
    }

    override fun onResume() {
        super.onResume()
        if (awaitingSettings) {
            awaitingSettings = false
            checkBackgroundLocationAndBattery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLogger.log("SplashActivity", "onCreate", true, "SplashActivity launched.")

        if (RadarForegroundService.isRunning) {
            AppLogger.log("SplashActivity", "onCreate", true, "RadarForegroundService is already running. Launching RadarMapActivity...")
            val intent = Intent(this, RadarMapActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
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
            AppLogger.log("SplashActivity", "checkAndRequestPermissions", false, "Requesting missing permissions: $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_FOREGROUND_PERMS)
        } else {
            AppLogger.log("SplashActivity", "checkAndRequestPermissions", true, "All required foreground permissions already granted.")
            onForegroundPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_FOREGROUND_PERMS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            AppLogger.log("SplashActivity", "onRequestPermissionsResult", allGranted, "Permission grant result: allGranted=$allGranted")
            if (allGranted) {
                onForegroundPermissionsGranted()
            } else {
                Toast.makeText(applicationContext, "Location and notification permissions are required.", Toast.LENGTH_LONG).show()
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

        AppLogger.log("SplashActivity", "checkBackgroundLocationAndBattery", true, "Check status: needsBgLoc=$needsBgLoc, needsBatteryIgnored=$needsBattery")

        if (needsBgLoc || needsBattery) {
            showExplanationDialog(needsBgLoc, needsBattery)
        } else {
            startRadarServiceAndFinish()
        }
    }

    private fun showExplanationDialog(needsBgLoc: Boolean, needsBattery: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Background & Battery Settings")
            .setMessage("RadarStop requires continuous background location access ('Allow all the time') and battery optimization exemption to alert you of speed cameras while driving.")
            .setPositiveButton("Configure") { _, _ ->
                awaitingSettings = true
                if (needsBgLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        AppLogger.log("SplashActivity", "showExplanationDialog", false, "Error launching app settings: ${e.message}")
                    }
                } else if (needsBattery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        AppLogger.log("SplashActivity", "showExplanationDialog", false, "Error launching battery settings: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                AppLogger.log("SplashActivity", "showExplanationDialog", false, "User cancelled configuration dialog.")
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun startRadarServiceAndFinish() {
        AppLogger.log("SplashActivity", "startRadarServiceAndFinish", true, "Starting RadarForegroundService...")
        val serviceIntent = Intent(this, RadarForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            AppLogger.log("SplashActivity", "startRadarServiceAndFinish", false, "Failed to start service: ${e.message}")
            Toast.makeText(applicationContext, "Failed to start RadarStop service: ${e.message}", Toast.LENGTH_LONG).show()
        }
        val mapIntent = Intent(this, RadarMapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mapIntent)
        finish()
    }
}
