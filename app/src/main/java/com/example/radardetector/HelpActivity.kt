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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.network.OverpassSyncManager
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.util.AppLogger

class HelpActivity : Activity() {

    private lateinit var textViewHelp: TextView
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentTextSizeSp = 15f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Help"

        val prefs = getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)

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

        val headerTitle = TextView(this).apply {
            text = "RadarStop Help"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(headerTitle)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val helpContentText = """
RadarStop

Ultra-lightweight background driver assistant alerting you of speed cameras ahead.

No configuration required.

Sound Alerts & Tracking:

• Speed Threshold:
  Alerts active only when speed > 30 km/h.

• Warning Distances:
  - ≤ 70 km/h: 500m approach warning.
  - > 70 km/h: 1000m approach warning.

• Continuous Beep Zone (50m / 100m):
  - Within 50m (≤70 km/h) or 100m (>70 km/h) of camera: continuous sound alert on approach and departure.
  - Alert stops immediately after leaving the 50m/100m zone upon departure.

• Average Speed Control (Linear Cameras):
  - Single control points operate as standard point cameras.
  - Paired linear sections maintain steady alert (1.5s interval) throughout the section.

• Accumulative DB & Offline Mode:
  - Cameras are saved permanently in local SQLite DB.
  - Use "Load Country Cams" to pre-download full country databases for offline use.
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

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
        }

        val checkBoxAutostart = CheckBox(this).apply {
            text = "Autostart"
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = prefs.getBoolean("autostart", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("autostart", isChecked).apply()
                AppLogger.log("HelpActivity", "onCheckedChanged", true, "Autostart on boot setting changed: $isChecked")
            }
        }

        val btnLoadCountryCams = Button(this).apply {
            text = "Load Country Cams"
            textSize = 11f
            setOnClickListener {
                showCountrySelectionDialog()
            }
        }

        val btnAdb = Button(this).apply {
            text = "ADB"
            setOnClickListener {
                val intent = Intent(this@HelpActivity, LogViewerActivity::class.java)
                startActivity(intent)
            }
        }

        val btnClose = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        val spaceLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        bottomBar.addView(checkBoxAutostart)
        bottomBar.addView(btnLoadCountryCams)
        bottomBar.addView(spaceLayout)
        bottomBar.addView(btnAdb)
        bottomBar.addView(btnClose)
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)
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

    override fun onDestroy() {
        activeSyncManager?.shutdown()
        activeSyncManager = null
        super.onDestroy()
    }
}
