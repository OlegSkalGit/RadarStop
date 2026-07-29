package com.example.radardetector

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.radardetector.util.AppLogger

class LogViewerActivity : Activity() {

    private lateinit var textViewLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Application Logs"

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
            text = "Radar Detector Logs"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rootLayout.addView(headerTitle)

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        val btnRefresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshLog() }
        }
        val btnShare = Button(this).apply {
            text = "Share / External"
            setOnClickListener { shareLog() }
        }
        val btnClear = Button(this).apply {
            text = "Clear Log"
            setOnClickListener {
                AppLogger.clearLog()
                refreshLog()
            }
        }
        val btnClose = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        btnLayout.addView(btnRefresh)
        btnLayout.addView(btnShare)
        btnLayout.addView(btnClear)
        btnLayout.addView(btnClose)
        rootLayout.addView(btnLayout)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        textViewLog = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#00FF66"))
            setTypeface(Typeface.MONOSPACE)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        scrollView.addView(textViewLog)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
        refreshLog()
    }

    private fun refreshLog() {
        textViewLog.text = AppLogger.readLogText()
    }

    private fun shareLog() {
        try {
            val logText = AppLogger.readLogText()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Radar Detector Log")
                putExtra(Intent.EXTRA_TEXT, logText)
            }
            startActivity(Intent.createChooser(intent, "Share / Open Log"))
            AppLogger.log("LogViewerActivity", "shareLog", true, "Triggered system share chooser.")
        } catch (e: Exception) {
            AppLogger.log("LogViewerActivity", "shareLog", false, "Error: ${e.message}")
        }
    }
}
