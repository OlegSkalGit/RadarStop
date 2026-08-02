package com.example.radardetector

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.example.radardetector.audio.AcousticRadarEngine
import com.example.radardetector.util.AppLogger
import java.io.File

class LogViewerActivity : Activity() {

    private lateinit var textViewLog: TextView
    private lateinit var spinnerLogFiles: Spinner
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentTextSizeSp = 12f
    private lateinit var audioEngine: AcousticRadarEngine

    private var availableLogFiles: List<File> = emptyList()
    private var selectedLogFileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ADB Logs"
        AppLogger.initNewSession(this)
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
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
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
                audioEngine.playSingleBeep()
            }
        }
        val btnMap = Button(this).apply {
            text = "Map"
            setOnClickListener {
                val intent = Intent(this@LogViewerActivity, RadarMapActivity::class.java)
                startActivity(intent)
            }
        }
        val btnRefresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener {
                val todayFile = AppLogger.getTodayFileName()
                if (selectedLogFileName == todayFile) {
                    refreshLog()
                }
            }
        }
        val btnShare = Button(this).apply {
            text = "Share"
            setOnClickListener { shareLog() }
        }
        val btnClear = Button(this).apply {
            text = "Clear"
            setOnClickListener {
                val fileToDelete = selectedLogFileName
                if (!fileToDelete.isNullOrEmpty()) {
                    AppLogger.deleteLogFile(fileToDelete)
                    updateSpinnerFiles(selectToday = true)
                }
            }
        }
        val btnClose = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        btnLayout.addView(btnBeep)
        btnLayout.addView(btnMap)
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
                AppLogger.setLoggingEnabled(this@LogViewerActivity, isChecked)
                if (isChecked) {
                    AppLogger.log("LogViewerActivity", "onCheckedChanged", true, "ADB file logging enabled by user.")
                    updateSpinnerFiles(selectToday = true)
                } else {
                    refreshLog()
                }
            }
        }

        loggingBar.addView(checkBoxLogging)
        rootLayout.addView(loggingBar)

        val spinnerLabel = TextView(this).apply {
            text = "Select Log File:"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, 4, 0, 4)
        }
        rootLayout.addView(spinnerLabel)

        spinnerLogFiles = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        }
        rootLayout.addView(spinnerLogFiles)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        textViewLog = TextView(this).apply {
            textSize = currentTextSizeSp
            setTextColor(Color.parseColor("#E0E0E0"))
            setTypeface(Typeface.MONOSPACE)
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        scrollView.addView(textViewLog)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        updateSpinnerFiles(selectToday = true)
    }

    private fun updateSpinnerFiles(selectToday: Boolean = false) {
        availableLogFiles = AppLogger.getAvailableLogFiles(this)
        val todayFileName = AppLogger.getTodayFileName()

        val fileNames = availableLogFiles.map { file ->
            val sizeKb = file.length() / 1024
            val tag = if (file.name == todayFileName) " [Today]" else ""
            "${file.name} (${sizeKb} KB)$tag"
        }

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, fileNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(12, 12, 12, 12)
                    setBackgroundColor(Color.parseColor("#2A2A2A"))
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#2A2A2A"))
                    textSize = 14f
                    setPadding(16, 16, 16, 16)
                }
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLogFiles.adapter = adapter

        if (availableLogFiles.isNotEmpty()) {
            var targetIndex = 0
            if (selectToday) {
                val idx = availableLogFiles.indexOfFirst { it.name == todayFileName }
                if (idx >= 0) targetIndex = idx
            } else {
                val idx = availableLogFiles.indexOfFirst { it.name == selectedLogFileName }
                if (idx >= 0) targetIndex = idx
            }
            spinnerLogFiles.setSelection(targetIndex)
            selectedLogFileName = availableLogFiles[targetIndex].name
        } else {
            selectedLogFileName = null
        }

        spinnerLogFiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in availableLogFiles.indices) {
                    selectedLogFileName = availableLogFiles[position].name
                    refreshLog()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        refreshLog()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun formatLogSpannable(logText: String): CharSequence {
        if (logText.startsWith("Log file") || logText.startsWith("No log file") || logText.startsWith("Error")) {
            return logText
        }

        val builder = SpannableStringBuilder()
        val lines = logText.split('\n')

        for (line in lines) {
            if (line.isEmpty()) continue

            val lineStart = builder.length
            builder.append(line).append('\n')

            val color = when {
                line.contains("FAILURE") -> Color.parseColor("#FF3366") // Red for errors
                line.contains("SPEED THRESHOLD") || line.contains("Radar!") || line.contains("Linear Zone Alert") -> Color.parseColor("#FFD700") // Gold for speed/radar alerts
                line.contains("Weak GPS") -> Color.parseColor("#FF9100") // Orange for weak GPS
                line.contains("SUCCESS") -> Color.parseColor("#00FF66") // Green for success
                else -> Color.parseColor("#00E5FF") // Cyan for general info
            }

            val endBracket = line.indexOf(']')
            if (line.startsWith('[') && endBracket > 0) {
                // Color ONLY the date timestamp tag [yyyy-MM-dd HH:mm:ss]
                builder.setSpan(
                    ForegroundColorSpan(color),
                    lineStart,
                    lineStart + endBracket + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // Rest of line in clean light gray/white
                builder.setSpan(
                    ForegroundColorSpan(Color.parseColor("#E0E0E0")),
                    lineStart + endBracket + 1,
                    lineStart + line.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                builder.setSpan(
                    ForegroundColorSpan(color),
                    lineStart,
                    lineStart + line.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return builder
    }

    private fun refreshLog() {
        Thread {
            val fileName = selectedLogFileName
            val rawText = AppLogger.readLogText(fileName)
            val formattedContent = formatLogSpannable(rawText)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    textViewLog.setText(formattedContent, TextView.BufferType.SPANNABLE)
                }
            }
        }.start()
    }

    private fun shareLog() {
        Thread {
            try {
                val fileName = selectedLogFileName
                val logText = AppLogger.readLogText(fileName)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "RadarStop Log (${fileName ?: "Today"})")
                            putExtra(Intent.EXTRA_TEXT, logText)
                        }
                        startActivity(Intent.createChooser(intent, "Share / Open Log"))
                        AppLogger.log("LogViewerActivity", "shareLog", true, "Triggered system share chooser for ${fileName}.")
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("LogViewerActivity", "shareLog", false, "Error: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        audioEngine.release()
        super.onDestroy()
    }
}
