package com.example.radardetector

import android.app.Activity
import android.app.Dialog
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
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
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
        title = "RadarStop Debug"
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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val btnClose = Button(this).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            setOnClickListener {
                val intent = Intent(this@LogViewerActivity, HelpActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        }

        val headerTitle = TextView(this).apply {
            text = "RadarStop Debug"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMenu = Button(this).apply {
            text = "Menu"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            setOnClickListener {
                showDebugMenuDialog()
            }
        }

        headerLayout.addView(btnClose)
        headerLayout.addView(headerTitle)
        headerLayout.addView(btnMenu)
        rootLayout.addView(headerLayout)

        val loggingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
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

        val btnParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            setMargins(4, 0, 4, 0)
        }

        val btnRefresh = Button(this).apply {
            text = "Refresh"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            layoutParams = btnParams
            setOnClickListener {
                val todayFile = AppLogger.getTodayFileName()
                if (selectedLogFileName == todayFile) {
                    refreshLog()
                }
            }
        }

        val btnShare = Button(this).apply {
            text = "Share"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            layoutParams = btnParams
            setOnClickListener { shareLog() }
        }

        val btnClear = Button(this).apply {
            text = "Clear"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            layoutParams = btnParams
            setOnClickListener {
                val fileToDelete = selectedLogFileName
                if (!fileToDelete.isNullOrEmpty()) {
                    AppLogger.deleteLogFile(fileToDelete)
                    updateSpinnerFiles(selectToday = true)
                }
            }
        }

        loggingBar.addView(checkBoxLogging)
        loggingBar.addView(btnRefresh)
        loggingBar.addView(btnShare)
        loggingBar.addView(btnClear)
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

    private fun showDebugMenuDialog() {
        val dialog = Dialog(this)
        dialog.setTitle("Menu")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#252525"))
        }

        val itemStyleParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 4, 0, 4)
        }

        val btnBeep = Button(this).apply {
            text = "Beep"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                audioEngine.playSingleBeep()
            }
        }

        val btnMap = Button(this).apply {
            text = "Map"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = itemStyleParams
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this@LogViewerActivity, RadarMapActivity::class.java)
                startActivity(intent)
            }
        }

        container.addView(btnBeep)
        container.addView(btnMap)

        dialog.setContentView(container)
        dialog.show()
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
                val fileName = selectedLogFileName ?: AppLogger.getTodayFileName()
                val logFile = File(filesDir, fileName)
                if (!logFile.exists()) {
                    runOnUiThread {
                        Toast.makeText(this, "Log file not found ($fileName)", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val contentUri: Uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    logFile
                )

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "RadarStop Log ($fileName)")
                            putExtra(Intent.EXTRA_STREAM, contentUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Share Log File"))
                        AppLogger.log("LogViewerActivity", "shareLog", true, "Triggered system file share for $fileName.")
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("LogViewerActivity", "shareLog", false, "Error sharing file: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        audioEngine.release()
        super.onDestroy()
    }
}
