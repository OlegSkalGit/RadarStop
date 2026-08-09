package com.example.radardetector

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.network.AppUpdateManager
import com.example.radardetector.network.OverpassSyncManager
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.util.AppLogger
import com.example.radardetector.util.getAppVersionName

class HelpActivity : Activity() {

    private lateinit var textViewHelp: TextView
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentTextSizeSp = 15f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = "Help"

        val appVersionName = getAppVersionName()

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                currentTextSizeSp = (currentTextSizeSp * factor).coerceIn(10f, 36f)
                textViewHelp.textSize = currentTextSizeSp
                return true
            }
        })

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(24, 24, 24, 24)
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val btnBack = Button(this).apply {
            text = "Back"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            setOnClickListener { finish() }
        }

        val headerTitle = TextView(this).apply {
            text = "RadarStop Help"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        headerLayout.addView(btnBack)
        headerLayout.addView(headerTitle)
        rootLayout.addView(headerLayout)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val helpContentText = """
RadarStop (v$appVersionName)

Ultra-lightweight background driver assistant alerting you of speed cameras ahead.

Key Features & Operations:

• Background Service:
  Runs automatically in the background with minimal battery usage. Tap the notification drawer item anytime to open the Map.

• Speed Threshold & Smart Alerts:
  - Audio warnings activate only at vehicle speeds > 30 km/h.
  - Advance detection begins at 500m (speed ≤ 70 km/h) or 1000m (speed > 70 km/h).
  - Beep frequency gradually accelerates from 2.0s to 0.5s as you get closer to a camera within 300m.

• Linear Camera Zones:
  Steady audio alerts accompany you throughout average speed control sections.

• Map & Navigation:
  - Displays real-time speed, direction, trajectory tail, and nearby speed cameras.
  - Includes full camera details within 3km and 10x10km range.

• Database & Offline Usage:
  - Camera locations are saved permanently in a local database during driving.
  - Pre-download complete country databases via "Load country cameras" for offline travel without internet access.
        """.trimIndent()

        textViewHelp = TextView(this).apply {
            text = helpContentText
            textSize = currentTextSizeSp
            setTextColor(Color.parseColor("#E0E0E0"))
            setLineSpacing(8f, 1.2f)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        scrollView.addView(textViewHelp)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    private fun showHelpMenuDialog() {
        val dialog = Dialog(this)
        dialog.setTitle("Menu")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#252525"))
        }

        val prefs = getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)

        val itemStyleParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 4, 0, 4)
        }

        var isAutostart = prefs.getBoolean("autostart", false)
        val btnAutostart = Button(this).apply {
            text = if (isAutostart) "Autostart: ON" else "Autostart: OFF"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                isAutostart = !isAutostart
                prefs.edit().putBoolean("autostart", isAutostart).apply()
                text = if (isAutostart) "Autostart: ON" else "Autostart: OFF"
                AppLogger.log("HelpActivity", "onCheckedChanged", true, "Autostart on boot setting changed: $isAutostart")
                Toast.makeText(this@HelpActivity, "Autostart ${if (isAutostart) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
            }
        }

        val btnLoadCountryCams = Button(this).apply {
            text = "Load country cameras"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                showCountrySelectionDialog()
            }
        }

        val btnCheckUpdates = Button(this).apply {
            text = "Check updates"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                Toast.makeText(this@HelpActivity, "Checking for updates...", Toast.LENGTH_SHORT).show()
                AppUpdateManager.checkAndDownloadUpdate(this@HelpActivity, force = true) { result ->
                    Toast.makeText(this@HelpActivity, result, Toast.LENGTH_LONG).show()
                }
            }
        }

        val btnDebug = Button(this).apply {
            text = "Debug"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this@HelpActivity, LogViewerActivity::class.java)
                startActivity(intent)
            }
        }

        container.addView(btnAutostart)
        container.addView(btnLoadCountryCams)
        container.addView(btnCheckUpdates)
        container.addView(btnDebug)

        dialog.setContentView(container)
        dialog.show()
    }

    private data class CountryItem(val name: String, val code: String)
    private var activeSyncManager: OverpassSyncManager? = null

    private fun showCountrySelectionDialog() {
        val dbHelper = DatabaseHelper(applicationContext)
        val syncManager = OverpassSyncManager(applicationContext, dbHelper)
        activeSyncManager = syncManager

        val dialog = Dialog(this)
        dialog.setTitle("Select Country")
        dialog.setOnDismissListener {
            syncManager.shutdown()
            if (activeSyncManager == syncManager) {
                activeSyncManager = null
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#252525"))
        }

        val searchInput = EditText(this).apply {
            hint = "Search country..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(16, 16, 16, 16)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val loadingLabel = TextView(this).apply {
            text = "Loading countries..."
            setTextColor(Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        listContainer.addView(loadingLabel)

        var activeCountriesList = emptyList<CountryItem>()

        fun populateList(query: String) {
            listContainer.removeAllViews()
            val filtered = activeCountriesList.filter {
                it.name.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true)
            }
            for (item in filtered) {
                val btn = Button(this@HelpActivity).apply {
                    text = "${item.name} (${item.code})"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#3A3A3A"))
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 4, 0, 4)
                    }
                    layoutParams = params
                    setOnClickListener {
                        dialog.dismiss()
                        startCountrySync(item.code, item.name)
                    }
                }
                listContainer.addView(btn)
            }
        }

        syncManager.fetchOrGetCachedCountries { fetched ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (fetched.isNotEmpty()) {
                    activeCountriesList = fetched.map { CountryItem(it.first, it.second) }
                    populateList(searchInput.text?.toString() ?: "")
                } else {
                    listContainer.removeAllViews()
                    val errorLabel = TextView(this@HelpActivity).apply {
                        text = "No countries available. Check internet."
                        setTextColor(Color.RED)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(0, 32, 0, 32)
                    }
                    listContainer.addView(errorLabel)
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        container.addView(searchInput)
        scrollView.addView(listContainer)
        container.addView(scrollView)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun startCountrySync(countryCode: String, countryName: String) {
        val serviceIntent = Intent(this, RadarForegroundService::class.java).apply {
            action = RadarForegroundService.ACTION_LOAD_COUNTRY_CAMS
            putExtra(RadarForegroundService.EXTRA_COUNTRY_CODE, countryCode)
            putExtra(RadarForegroundService.EXTRA_COUNTRY_NAME, countryName)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Downloading speed cameras for $countryName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLogger.log("HelpActivity", "startCountrySync", false, "Failed to start service for country sync: ${e.message}")
            Toast.makeText(this, "Failed to start camera download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (!RadarForegroundService.isRunning) {
            finish()
        }
    }

    override fun onDestroy() {
        activeSyncManager?.shutdown()
        activeSyncManager = null
        super.onDestroy()
    }
}
