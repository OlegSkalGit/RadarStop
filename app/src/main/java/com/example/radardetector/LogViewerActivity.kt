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
import com.example.radardetector.audio.AcousticRadarEngine
import com.example.radardetector.util.AppLogger

class LogViewerActivity : Activity() {

    private lateinit var textViewLog: TextView
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentTextSizeSp = 12f
    private lateinit var audioEngine: AcousticRadarEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ADB Logs"
        audioEngine = AcousticRadarEngine(this)

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                currentTextSizeSp = (currentTextSizeSp * factor).coerceIn(8f, 32f)
                textViewLog.textSize = currentTextSizeSp
                return true
            }
        })

        val appVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        val headerTitle = TextView(this).apply {
            text = "RadarStop Logs (v$appVersionName)"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rootLayout.addView(headerTitle)

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 8)
        }

        val btnBeep = Button(this).apply {
            text = "Beep"
            setOnClickListener {
                audioEngine.playBeeps(5, 500L)
            }
        }
        val btnRefresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshLog() }
        }
        val btnShare = Button(this).apply {
            text = "Share"
            setOnClickListener { shareLog() }
        }
        val btnClear = Button(this).apply {
            text = "Clear"
            setOnClickListener {
                AppLogger.clearLog()
                refreshLog()
            }
        }
        val btnClose = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        btnLayout.addView(btnBeep)
        btnLayout.addView(btnRefresh)
        btnLayout.addView(btnShare)
        btnLayout.addView(btnClear)
        btnLayout.addView(btnClose)
        rootLayout.addView(btnLayout)

        val loggingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 8)
        }

        val checkBoxLogging = CheckBox(this).apply {
            text = "Logging"
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = AppLogger.isLoggingEnabled
            setOnCheckedChangeListener { _, isChecked ->
                AppLogger.isLoggingEnabled = isChecked
                if (isChecked) {
                    AppLogger.log("LogViewerActivity", "onCheckedChanged", true, "ADB file logging enabled by user.")
                }
                refreshLog()
            }
        }

        loggingBar.addView(checkBoxLogging)
        rootLayout.addView(loggingBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        textViewLog = TextView(this).apply {
            textSize = currentTextSizeSp
            setTextColor(Color.parseColor("#00FF66"))
            setTypeface(Typeface.MONOSPACE)
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        scrollView.addView(textViewLog)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
        refreshLog()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshLog() {
        textViewLog.text = AppLogger.readLogText()
    }

    private fun shareLog() {
        try {
            val logText = AppLogger.readLogText()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "RadarStop Log")
                putExtra(Intent.EXTRA_TEXT, logText)
            }
            startActivity(Intent.createChooser(intent, "Share / Open Log"))
            AppLogger.log("LogViewerActivity", "shareLog", true, "Triggered system share chooser.")
        } catch (e: Exception) {
            AppLogger.log("LogViewerActivity", "shareLog", false, "Error: ${e.message}")
        }
    }

    override fun onDestroy() {
        audioEngine.release()
        super.onDestroy()
    }
}
