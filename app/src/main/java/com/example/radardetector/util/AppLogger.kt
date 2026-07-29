package com.example.radardetector.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val LOG_FILE_NAME = "radar_app.log"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun initNewSession(context: Context) {
        try {
            logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile?.exists() == true) {
                logFile?.delete()
            }
            logFile?.createNewFile()
            log("AppLogger", "initNewSession", true, "New logging session initialized. Stale log cleared.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun log(module: String, functionName: String, isSuccess: Boolean, details: String) {
        try {
            val file = logFile ?: return
            val timestamp = dateFormat.format(Date())
            val statusStr = if (isSuccess) "SUCCESS" else "FAILURE"
            val line = "[$timestamp] [$module::$functionName] $statusStr: $details\n"

            FileWriter(file, true).use { writer ->
                writer.write(line)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun readLogText(): String {
        return try {
            val file = logFile
            if (file != null && file.exists()) {
                val text = file.readText()
                if (text.isEmpty()) "Log file is empty." else text
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
