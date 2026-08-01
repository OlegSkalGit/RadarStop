package com.example.radardetector.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLogger {

    private const val LOG_FILE_NAME = "radar_app.log"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val writeExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    var isLoggingEnabled: Boolean = false

    @Synchronized
    fun initNewSession(context: Context) {
        try {
            isLoggingEnabled = false
            logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile?.exists() == true) {
                logFile?.delete()
            }
            logFile?.createNewFile()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private const val MAX_LOG_SIZE_BYTES = 256 * 1024L // 256 KB limit

    fun log(module: String, functionName: String, isSuccess: Boolean, details: String) {
        if (!isLoggingEnabled) return
        writeExecutor.execute {
            try {
                val timestamp = dateFormat.format(Date())
                val statusStr = if (isSuccess) "SUCCESS" else "FAILURE"
                val line = "[$timestamp] [$module::$functionName] $statusStr: $details\n"
                val file = logFile ?: return@execute
                if (file.exists() && file.length() >= MAX_LOG_SIZE_BYTES) {
                    file.writeText("[AppLogger] Log file auto-rotated at 256 KB limit.\n")
                }
                FileWriter(file, true).use { writer ->
                    writer.write(line)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    fun readLogText(): String {
        return try {
            val file = logFile
            if (file != null && file.exists()) {
                val text = file.readText()
                if (text.isEmpty()) {
                    "Log file is empty."
                } else if (text.length > 50000) {
                    "... (older logs truncated)\n" + text.takeLast(50000)
                } else {
                    text
                }
            } else {
                "No log file found."
            }
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    @Synchronized
    fun clearLog() {
        try {
            logFile?.writeText("")
            log("AppLogger", "clearLog", true, "Log cleared by user.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
