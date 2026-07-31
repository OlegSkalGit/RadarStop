package com.example.radardetector

import android.app.Activity
import android.app.AlertDialog
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

An ultra-lightweight driver assistant running silently in the background to alert you of speed cameras in advance while driving.

No manual configuration is required.

Sound Alerts & Radar Tracking:

• Speed threshold:
  Sound warnings are issued only when your speed exceeds 30 km/h, provided GPS and internet connectivity are available.

• Point Speed Cameras (300m approach & departure tracking):
  - On approach (300m to camera): Beep frequency accelerates (from 1.5s interval down to 0.1s near the camera).
  - On departure (up to 300m past camera): Beep frequency decelerates in reverse (from 0.1s near the camera up to 1.5s at 300m past it).

• Average Speed Control Zones (Linear Cameras):
  - Upon entering an average speed control zone, the app maintains continuous steady sound alerts (1.5s interval) throughout the entire section until passing the exit camera and departing 300m beyond it.

• Accumulative Database & Offline Navigation:
  - Speed cameras for every 100x100 km region are saved to your local SQLite database and remain stored permanently across trips.
  - Tap "Load All Cams" to pre-download regional camera coverage for offline navigation without internet.
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

    private val countries = listOf(
        CountryItem("Ukraine", "UA"),
        CountryItem("Poland", "PL"),
        CountryItem("Germany", "DE"),
        CountryItem("France", "FR"),
        CountryItem("Italy", "IT"),
        CountryItem("Spain", "ES"),
        CountryItem("Czechia", "CZ"),
        CountryItem("Romania", "RO"),
        CountryItem("Hungary", "HU"),
        CountryItem("Slovakia", "SK"),
        CountryItem("United Kingdom", "GB"),
        CountryItem("United States", "US"),
        CountryItem("Canada", "CA"),
        CountryItem("Turkey", "TR"),
        CountryItem("Bulgaria", "BG"),
        CountryItem("Austria", "AT"),
        CountryItem("Netherlands", "NL"),
        CountryItem("Belgium", "BE"),
        CountryItem("Switzerland", "CH"),
        CountryItem("Moldova", "MD"),
        CountryItem("Lithuania", "LT"),
        CountryItem("Latvia", "LV"),
        CountryItem("Estonia", "EE"),
        CountryItem("Georgia", "GE"),
        CountryItem("Armenia", "AM"),
        CountryItem("Azerbaijan", "AZ"),
        CountryItem("Norway", "NO"),
        CountryItem("Sweden", "SE"),
        CountryItem("Finland", "FI"),
        CountryItem("Denmark", "DK"),
        CountryItem("Portugal", "PT"),
        CountryItem("Greece", "GR"),
        CountryItem("Croatia", "HR"),
        CountryItem("Slovenia", "SI"),
        CountryItem("Serbia", "RS")
    )

    private fun showCountrySelectionDialog() {
        val dialog = Dialog(this)
        dialog.setTitle("Select Country")

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
                800
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateList(query: String) {
            listContainer.removeAllViews()
            val filtered = countries.filter {
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

        populateList("")

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Downloading speed cameras for $countryName...", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
