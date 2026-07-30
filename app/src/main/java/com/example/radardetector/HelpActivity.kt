package com.example.radardetector

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.radardetector.util.AppLogger

class HelpActivity : Activity() {

    private lateinit var textViewHelp: TextView
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentTextSizeSp = 15f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Help"

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

        val checkBoxEnableAdb = CheckBox(this).apply {
            text = "Enable ADB"
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = AppLogger.isLoggingEnabled
            setOnCheckedChangeListener { _, isChecked ->
                AppLogger.isLoggingEnabled = isChecked
                if (isChecked) {
                    AppLogger.log("HelpActivity", "onCheckedChanged", true, "ADB logging enabled by user.")
                }
            }
        }

        val btnOpenAdb = Button(this).apply {
            text = "Open"
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

        bottomBar.addView(checkBoxEnableAdb)
        bottomBar.addView(spaceLayout)
        bottomBar.addView(btnOpenAdb)
        bottomBar.addView(btnClose)
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
