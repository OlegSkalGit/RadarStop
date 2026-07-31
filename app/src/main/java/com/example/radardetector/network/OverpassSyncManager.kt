package com.example.radardetector.network

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.util.AppLogger
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class OverpassSyncManager(
    private val context: Context,
    private val dbHelper: DatabaseHelper,
    private val onStatusUpdate: (String) -> Unit,
    private val onSyncSuccess: (downloadedCount: Int, totalInDb: Int) -> Unit
) {

    companion object {
        private val MIRRORS = arrayOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://api.openstreetmap.fr/oapi/interpreter"
        )
        private const val SYNC_THROTTLE_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val RETRY_PAUSE_MS = 5 * 60 * 1000L // 5 minutes retry delay on network failure
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var lastSyncTimeMs = 0L
    @Volatile
    private var lastSyncAttemptMs = 0L
    @Volatile
    private var isSyncing = false
    private var lastSyncedLat = 0.0
    private var lastSyncedLon = 0.0

    private fun isInternetAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.isConnected
            }
        } catch (e: Exception) {
            true // Fallback if system check fails
        }
    }

    @Volatile
    private var hasDoneInitialSync = false

    fun onLocationUpdate(location: Location, speedKmh: Float) {
        if (isSyncing) return
        val isInitialSyncNeeded = !hasDoneInitialSync
        if (speedKmh <= 0f && !isInitialSyncNeeded) return

        val now = System.currentTimeMillis()
        if (lastSyncAttemptMs != 0L && now - lastSyncAttemptMs < RETRY_PAUSE_MS) {
            return
        }

        val distanceMoved = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, lastSyncedLat, lastSyncedLon, distanceMoved)

        if (!isInitialSyncNeeded && lastSyncTimeMs != 0L && now - lastSyncTimeMs < SYNC_THROTTLE_MS && distanceMoved[0] < 40000f) {
            return
        }

        isSyncing = true
        executor.execute {
            try {
                performSync(location.latitude, location.longitude)
                hasDoneInitialSync = true
            } finally {
                isSyncing = false
            }
        }
    }

    private fun performSync(lat: Double, lon: Double) {
        if (!isInternetAvailable()) {
            AppLogger.log("OverpassSyncManager", "performSync", false, "No internet connection available. Setting 5m retry pause.")
            lastSyncAttemptMs = System.currentTimeMillis()
            onStatusUpdate("No Internet. Retry in 5m...")
            return
        }

        AppLogger.log("OverpassSyncManager", "performSync", true, "Starting 100x100 km Bounding Box sync for coords: ($lat, $lon)")
        onStatusUpdate("Downloading camera data...")

        val south = lat - 0.45
        val north = lat + 0.45
        val west = lon - 0.45
        val east = lon + 0.45

        val query = """
            [out:json][timeout:25];
            (
              node["highway"="speed_camera"]($south,$west,$north,$east);
              node["enforcement"]($south,$west,$north,$east);
            );
            out body;
        """.trimIndent()

        var success = false
        for (i in MIRRORS.indices) {
            val mirror = MIRRORS[i]
            AppLogger.log("OverpassSyncManager", "executePost", true, "Connecting to mirror [${i + 1}/${MIRRORS.size}]: $mirror")
            val cameras = executePostAndParseStream(mirror, query)
            if (cameras != null) {
                dbHelper.clearCamerasInBox(south, north, west, east)
                dbHelper.insertCameras(cameras)
                lastSyncTimeMs = System.currentTimeMillis()
                lastSyncAttemptMs = 0L
                lastSyncedLat = lat
                lastSyncedLon = lon
                success = true
                val count = dbHelper.getCameraCount()
                AppLogger.log(
                    "OverpassSyncManager",
                    "performSync",
                    true,
                    "NETWORK SYNC SUCCESS: Downloaded ${cameras.size} cameras via JsonReader stream from Overpass ($mirror) for 100x100km box around ($lat, $lon). Total in DB: $count"
                )
                onSyncSuccess(cameras.size, count)
                break
            }

            if (i < MIRRORS.size - 1) {
                AppLogger.log("OverpassSyncManager", "performSync", false, "Mirror failed. Waiting 5s before next mirror attempt...")
                try {
                    Thread.sleep(5000L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }

        if (!success) {
            lastSyncAttemptMs = System.currentTimeMillis()
            AppLogger.log("OverpassSyncManager", "performSync", false, "All Overpass mirrors failed. Setting 5m retry pause.")
            onStatusUpdate("Network error. Retry in 5m...")
        }
    }

    private fun executePostAndParseStream(urlStr: String, body: String): List<Camera>? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write("data=" + java.net.URLEncoder.encode(body, "UTF-8"))
                writer.flush()
            }
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.use { inputStream ->
                    parseOverpassStream(inputStream)
                }
            } else {
                AppLogger.log("OverpassSyncManager", "executePostAndParseStream", false, "HTTP Error code $code from $urlStr")
                null
            }
        } catch (e: Exception) {
            AppLogger.log("OverpassSyncManager", "executePostAndParseStream", false, "Network error on $urlStr: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseOverpassStream(inputStream: java.io.InputStream): List<Camera>? {
        return try {
            val reader = android.util.JsonReader(InputStreamReader(inputStream, "UTF-8"))
            val cameras = ArrayList<Camera>()

            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "elements") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var id = 0L
                        var lat = 0.0
                        var lon = 0.0
                        var dir: Float? = null
                        var isLinear = false

                        while (reader.hasNext()) {
                            val elementName = reader.nextName()
                            when (elementName) {
                                "id" -> id = reader.nextLong()
                                "lat" -> lat = reader.nextDouble()
                                "lon" -> lon = reader.nextDouble()
                                "tags" -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        val tagName = reader.nextName()
                                        val tagValue = reader.nextString()
                                        if (tagName == "direction" || tagName == "camera:direction") {
                                            dir = parseDirection(tagValue)
                                        }
                                        if (tagName == "enforcement" || tagName == "camera:type" || tagName == "enforcement:type") {
                                            if (tagValue.contains("average_speed") || tagValue.contains("section")) {
                                                isLinear = true
                                            }
                                        }
                                    }
                                    reader.endObject()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        cameras.add(Camera(id, lat, lon, dir, isLinear))
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            reader.close()
            cameras
        } catch (e: Exception) {
            AppLogger.log("OverpassSyncManager", "parseOverpassStream", false, "Stream JSON Error: ${e.message}")
            null
        }
    }

    private fun parseDirection(dirStr: String): Float? {
        val trimmed = dirStr.trim()
        val num = trimmed.toFloatOrNull()
        if (num != null) return num

        return when (trimmed.uppercase()) {
            "N" -> 0.0f
            "NNE" -> 22.5f
            "NE" -> 45.0f
            "ENE" -> 67.5f
            "E" -> 90.0f
            "ESE" -> 112.5f
            "SE" -> 135.0f
            "SSE" -> 157.5f
            "S" -> 180.0f
            "SSW" -> 202.5f
            "SW" -> 225.0f
            "WSW" -> 247.5f
            "W" -> 270.0f
            "WNW" -> 292.5f
            "NW" -> 315.0f
            "NNW" -> 337.5f
            else -> null
        }
    }

    fun triggerFullRegionSync(lat: Double, lon: Double) {
        if (isSyncing) return
        isSyncing = true
        executor.execute {
            try {
                performFullRegionSync(lat, lon)
            } finally {
                isSyncing = false
            }
        }
    }

    private fun performFullRegionSync(lat: Double, lon: Double) {
        if (!isInternetAvailable()) {
            onStatusUpdate("No Internet. Cannot load full region.")
            return
        }
        onStatusUpdate("Downloading full region cameras...")
        AppLogger.log("OverpassSyncManager", "performFullRegionSync", true, "Starting Full Region sync around ($lat, $lon)")

        val south = lat - 2.5
        val north = lat + 2.5
        val west = lon - 2.5
        val east = lon + 2.5

        val query = """
            [out:json][timeout:60];
            (
              node["highway"="speed_camera"]($south,$west,$north,$east);
              node["enforcement"]($south,$west,$north,$east);
            );
            out body;
        """.trimIndent()

        var success = false
        for (i in MIRRORS.indices) {
            val mirror = MIRRORS[i]
            val cameras = executePostAndParseStream(mirror, query)
            if (cameras != null) {
                dbHelper.clearCamerasInBox(south, north, west, east)
                dbHelper.insertCameras(cameras)
                success = true
                val count = dbHelper.getCameraCount()
                AppLogger.log("OverpassSyncManager", "performFullRegionSync", true, "FULL REGION SYNC SUCCESS: Downloaded ${cameras.size} cameras. Total in DB: $count")
                onSyncSuccess(cameras.size, count)
                onStatusUpdate("Full region load complete! ($count total in DB)")
                break
            }
        }
        if (!success) {
            onStatusUpdate("Full region load failed. Check network.")
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        AppLogger.log("OverpassSyncManager", "shutdown", true, "Sync executor shutdown.")
    }
}
