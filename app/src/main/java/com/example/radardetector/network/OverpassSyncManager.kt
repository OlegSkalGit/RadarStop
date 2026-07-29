package com.example.radardetector.network

import android.content.Context
import android.location.Location
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.util.AppLogger
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class OverpassSyncManager(
    private val context: Context,
    private val dbHelper: DatabaseHelper,
    private val onStatusUpdate: (String) -> Unit
) {

    companion object {
        private val MIRRORS = arrayOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://api.openstreetmap.fr/oapi/interpreter"
        )
        private const val SYNC_THROTTLE_MS = 10 * 60 * 1000L
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var lastSyncTimeMs = 0L
    @Volatile
    private var isSyncing = false
    private var lastSyncedLat = 0.0
    private var lastSyncedLon = 0.0

    fun onLocationUpdate(location: Location, speedKmh: Float, isStationaryFor3Hours: Boolean) {
        if (isSyncing) return
        if (isStationaryFor3Hours && speedKmh <= 30f) return

        val now = System.currentTimeMillis()
        val distanceMoved = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, lastSyncedLat, lastSyncedLon, distanceMoved)

        if (now - lastSyncTimeMs < SYNC_THROTTLE_MS && distanceMoved[0] < 40000f && lastSyncTimeMs != 0L) {
            return
        }

        isSyncing = true
        executor.execute {
            try {
                performSync(location.latitude, location.longitude)
            } finally {
                isSyncing = false
            }
        }
    }

    private fun performSync(lat: Double, lon: Double) {
        AppLogger.log("OverpassSyncManager", "performSync", true, "Starting sync for coords: ($lat, $lon)")
        onStatusUpdate("Downloading camera data...")

        val currentCountry = detectCountry(lat, lon) ?: dbHelper.getLastCountry() ?: "DEFAULT"
        val lastCountry = dbHelper.getLastCountry()

        AppLogger.log("OverpassSyncManager", "detectCountry", true, "Detected country: $currentCountry (Last: $lastCountry)")

        val isFreshOrNewCountry = (lastCountry == null || lastCountry != currentCountry)
        if (isFreshOrNewCountry) {
            dbHelper.clearCameras()
            dbHelper.setLastCountry(currentCountry)
            AppLogger.log("OverpassSyncManager", "performSync", true, "Country changed/fresh install. Wiped camera table.")
        }

        val south = lat - 0.45
        val north = lat + 0.45
        val west = lon - 0.45
        val east = lon + 0.45

        val query = """
            [out:json][timeout:25];
            (
              node["highway"="speed_camera"]($south,$west,$north,$east);
              node["enforcement"="maxspeed"]($south,$west,$north,$east);
            );
            out body;
        """.trimIndent()

        var success = false
        for (mirror in MIRRORS) {
            AppLogger.log("OverpassSyncManager", "executePost", true, "Connecting to mirror: $mirror")
            val response = executePost(mirror, query)
            if (response != null) {
                val cameras = parseOverpassJson(response)
                if (cameras != null) {
                    dbHelper.insertCameras(cameras)
                    lastSyncTimeMs = System.currentTimeMillis()
                    lastSyncedLat = lat
                    lastSyncedLon = lon
                    success = true
                    val count = dbHelper.getCameraCount()
                    AppLogger.log("OverpassSyncManager", "performSync", true, "Downloaded and saved ${cameras.size} cameras from $mirror. Total DB count: $count")
                    onStatusUpdate("Active. Cameras in DB: $count")
                    break
                }
            }
        }

        if (!success) {
            AppLogger.log("OverpassSyncManager", "performSync", false, "All Overpass mirrors failed or timed out.")
            onStatusUpdate("Network error. Retrying...")
        }
    }

    private fun detectCountry(lat: Double, lon: Double): String? {
        val query = "[out:json][timeout:15];is_in($lat,$lon);area._[admin_level=2];out tags;"
        for (mirror in MIRRORS) {
            val response = executePost(mirror, query)
            if (response != null) {
                try {
                    val root = JSONObject(response)
                    val elements = root.getJSONArray("elements")
                    for (i in 0 until elements.length()) {
                        val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                        val iso = tags.optString("ISO3166-1", "")
                        if (iso.isNotEmpty()) return iso
                        val nameEn = tags.optString("name:en", "")
                        if (nameEn.isNotEmpty()) return nameEn
                    }
                } catch (e: Exception) {
                    AppLogger.log("OverpassSyncManager", "detectCountry", false, "Parse error: ${e.message}")
                }
            }
        }
        return null
    }

    private fun executePost(urlStr: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write("data=" + java.net.URLEncoder.encode(body, "UTF-8"))
                writer.flush()
            }
            val code = conn.responseCode
            if (code == 200) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { br ->
                    br.readText()
                }
            } else {
                AppLogger.log("OverpassSyncManager", "executePost", false, "HTTP Error code $code from $urlStr")
                null
            }
        } catch (e: Exception) {
            AppLogger.log("OverpassSyncManager", "executePost", false, "Network error on $urlStr: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseOverpassJson(jsonStr: String): List<Camera>? {
        return try {
            val root = JSONObject(jsonStr)
            val elements = root.getJSONArray("elements")
            val cameras = ArrayList<Camera>()

            for (i in 0 until elements.length()) {
                val elem = elements.getJSONObject(i)
                val id = elem.getLong("id")
                val lat = elem.getDouble("lat")
                val lon = elem.getDouble("lon")

                var dir: Float? = null
                if (elem.has("tags")) {
                    val tags = elem.getJSONObject("tags")
                    if (tags.has("direction")) {
                        dir = parseDirection(tags.getString("direction"))
                    } else if (tags.has("camera:direction")) {
                        dir = parseDirection(tags.getString("camera:direction"))
                    }
                }
                cameras.add(Camera(id, lat, lon, dir))
            }
            cameras
        } catch (e: Exception) {
            AppLogger.log("OverpassSyncManager", "parseOverpassJson", false, "JSON Error: ${e.message}")
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

    fun shutdown() {
        executor.shutdownNow()
        AppLogger.log("OverpassSyncManager", "shutdown", true, "Sync executor shutdown.")
    }
}
