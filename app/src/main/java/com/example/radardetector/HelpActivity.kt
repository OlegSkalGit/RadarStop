package com.example.radardetector

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.radardetector.service.RadarForegroundService
import com.example.radardetector.ui.UiUtils
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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(24, 24, 24, 24)
        }

        val headerLayout = UiUtils.createHeaderLayout(this, "RadarStop Help")
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

        scaleGestureDetector = UiUtils.setupTextPinchZoom(this, textViewHelp, currentTextSizeSp, 10f, 36f) { newSp ->
            currentTextSizeSp = newSp
        }

        scrollView.addView(textViewHelp)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
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
}
