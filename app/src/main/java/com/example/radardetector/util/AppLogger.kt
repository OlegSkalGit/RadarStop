package com.example.radardetector.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLogger {

    private val fileDateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    }
    private val logTimeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    private var appFilesDir: File? = null
    private val writeExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    var isLoggingEnabled: Boolean = false

    private var currentLogDateStr: String? = null

    @Synchronized
    fun initNewSession(context: Context) {
        try {
            appFilesDir = context.filesDir
            val isDebug = AppPrefs.isDebugMode(context)
            if (!isDebug) {
                isLoggingEnabled = false
                AppPrefs.setLoggingEnabled(context, false)
            } else {
                isLoggingEnabled = AppPrefs.isLoggingEnabled(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        isLoggingEnabled = enabled
        try {
            AppPrefs.setLoggingEnabled(context, enabled)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getTodayFileName(): String {
        return (fileDateFormat.get() ?: SimpleDateFormat("yyyyMMdd", Locale.US)).format(Date()) + ".log"
    }

    private fun getTodayLogFile(): File? {
        val dir = appFilesDir ?: return null
        val dateStr = (fileDateFormat.get() ?: SimpleDateFormat("yyyyMMdd", Locale.US)).format(Date())

        // Check if date changed -> perform cleanup of files > 7 days old before writing new log file
        if (dateStr != currentLogDateStr) {
            cleanupOldLogs()
            currentLogDateStr = dateStr
        }

        val fileName = "$dateStr.log"
        val file = File(dir, fileName)
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return file
    }

    private fun cleanupOldLogs() {
        val dir = appFilesDir ?: return
        val files = dir.listFiles { _, name -> name.matches(Regex("\\d{8}\\.log")) } ?: return
        val nowMs = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000L

        for (file in files) {
            try {
                val dateStr = file.name.removeSuffix(".log")
                val fileDate = (fileDateFormat.get() ?: SimpleDateFormat("yyyyMMdd", Locale.US)).parse(dateStr)
                if (fileDate != null) {
                    val ageMs = nowMs - fileDate.time
                    if (ageMs > sevenDaysMs) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getAvailableLogFiles(context: Context): List<File> {
        val dir = appFilesDir ?: context.filesDir
        val files = dir.listFiles { _, name -> name.matches(Regex("\\d{8}\\.log")) } ?: return emptyList()
        return files.sortedByDescending { it.name }
    }

    fun log(module: String, functionName: String, isSuccess: Boolean, details: String) {
        if (!isLoggingEnabled) return
        writeExecutor.execute {
            try {
                val timestamp = (logTimeFormat.get() ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)).format(Date())
                val statusStr = if (isSuccess) "SUCCESS" else "FAILURE"
                val line = "[$timestamp] [$module::$functionName] $statusStr: $details\n"
                val file = getTodayLogFile() ?: return@execute
                FileWriter(file, true).use { writer ->
                    writer.write(line)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    fun readLogText(fileName: String? = null): String {
        return try {
            val dir = appFilesDir ?: return "Logs directory not initialized."
            val targetFile = if (!fileName.isNullOrEmpty()) {
                File(dir, fileName)
            } else {
                getTodayLogFile()
            }
            if (targetFile != null && targetFile.exists()) {
                val text = targetFile.readText()
                if (text.isEmpty()) {
                    "Log file (${targetFile.name}) is empty."
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
    fun deleteLogFile(fileName: String?): Boolean {
        try {
            val dir = appFilesDir ?: return false
            if (fileName.isNullOrEmpty()) return false
            val file = File(dir, fileName)
            if (file.exists()) {
                return file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
