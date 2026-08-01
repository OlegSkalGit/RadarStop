package com.example.radardetector.network

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import com.example.radardetector.db.Camera
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.util.AppLogger
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class OverpassSyncManager(
    context: Context,
    private val dbHelper: DatabaseHelper,
    private val onStatusUpdate: (String) -> Unit = {},
    private val onSyncSuccess: (downloadedCount: Int, totalInDb: Int) -> Unit = { _, _ -> }
) {
    private val context: Context = context.applicationContext

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = context.getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)
    @Volatile
    private var lastSyncTimeMs = prefs.getLong("last_sync_time_ms", 0L)
    @Volatile
    private var lastSyncAttemptMs = 0L

    private val isSyncing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastSyncedLat = prefs.getFloat("last_synced_lat", 0f).toDouble()
    private var lastSyncedLon = prefs.getFloat("last_synced_lon", 0f).toDouble()

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
    private var hasDoneInitialSync = (lastSyncTimeMs != 0L || dbHelper.getCameraCount() > 0)

    fun onLocationUpdate(location: Location, speedKmh: Float) {
        if (isSyncing.get()) return
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

        if (!isSyncing.compareAndSet(false, true)) return
        executor.execute {
            try {
                performSync(location.latitude, location.longitude)
            } finally {
                hasDoneInitialSync = true
                isSyncing.set(false)
            }
        }
    }

    private fun performSync(lat: Double, lon: Double) {
        if (!isInternetAvailable()) {
            AppLogger.log("OverpassSyncManager", "performSync", false, "No internet connection available. Setting 5m retry pause.")
            lastSyncAttemptMs = System.currentTimeMillis()
            mainHandler.post { onStatusUpdate("No Internet. Retry in 5m...") }
            return
        }

        AppLogger.log("OverpassSyncManager", "performSync", true, "Starting 100x100 km Bounding Box sync for coords: ($lat, $lon)")
        mainHandler.post { onStatusUpdate("Downloading camera data...") }

        val south = lat - 0.45
        val north = lat + 0.45
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val deltaLon = (0.45 / cosLat).coerceIn(0.45, 0.9)
        val west = lon - deltaLon
        val east = lon + deltaLon

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
                dbHelper.replaceCamerasInBox(south, north, west, east, cameras)
                lastSyncTimeMs = System.currentTimeMillis()
                lastSyncAttemptMs = 0L
                lastSyncedLat = lat
                lastSyncedLon = lon
                prefs.edit()
                    .putLong("last_sync_time_ms", lastSyncTimeMs)
                    .putFloat("last_synced_lat", lat.toFloat())
                    .putFloat("last_synced_lon", lon.toFloat())
                    .apply()
                success = true
                val count = dbHelper.getCameraCount()
                AppLogger.log(
                    "OverpassSyncManager",
                    "performSync",
                    true,
                    "NETWORK SYNC SUCCESS: Downloaded ${cameras.size} cameras via JsonReader stream from Overpass ($mirror) for 100x100km box around ($lat, $lon). Total in DB: $count"
                )
                mainHandler.post { onSyncSuccess(cameras.size, count) }
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
            mainHandler.post { onStatusUpdate("Network error. Retry in 5m...") }
        }
    }

    private fun executePostAndParseStream(urlStr: String, body: String, readTimeoutMs: Int = 10000): List<Camera>? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                this.readTimeout = readTimeoutMs
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

    fun triggerCountryCameraSync(countryCode: String, countryName: String) {
        if (!isSyncing.compareAndSet(false, true)) return
        executor.execute {
            try {
                performCountryCameraSync(countryCode, countryName)
            } finally {
                isSyncing.set(false)
            }
        }
    }

    private fun performCountryCameraSync(countryCode: String, countryName: String) {
        if (!isInternetAvailable()) {
            mainHandler.post { onStatusUpdate("No Internet. Cannot load $countryName cameras.") }
            return
        }
        mainHandler.post { onStatusUpdate("Downloading $countryName speed cameras...") }
        AppLogger.log("OverpassSyncManager", "performCountryCameraSync", true, "Starting Overpass sync for country: $countryName ($countryCode)")

        val query = """
            [out:json][timeout:120];
            area["ISO3166-1"="$countryCode"][admin_level=2]->.searchArea;
            (
              node["highway"="speed_camera"](area.searchArea);
              node["enforcement"](area.searchArea);
            );
            out body;
        """.trimIndent()

        var success = false
        for (i in MIRRORS.indices) {
            val mirror = MIRRORS[i]
            val cameras = executePostAndParseStream(mirror, query, readTimeoutMs = 120000)
            if (cameras != null) {
                dbHelper.insertCameras(cameras)
                success = true
                val count = dbHelper.getCameraCount()
                AppLogger.log("OverpassSyncManager", "performCountryCameraSync", true, "COUNTRY SYNC SUCCESS: Downloaded ${cameras.size} cameras for $countryName. Total in DB: $count")
                mainHandler.post { onSyncSuccess(cameras.size, count) }
                mainHandler.post { onStatusUpdate("$countryName cameras loaded! (${cameras.size} added, $count total in DB)") }
                break
            }
        }
        if (!success) {
            mainHandler.post { onStatusUpdate("Failed to load $countryName cameras. Check network.") }
        }
    }

    fun fetchOrGetCachedCountries(onResult: (List<Pair<String, String>>) -> Unit) {
        executor.execute {
            val now = System.currentTimeMillis()
            val cached = dbHelper.getCountries()
            val lastCountrySyncTimeMs = prefs.getLong("last_country_sync_ms", 0L)
            if (cached.isNotEmpty() && (now - lastCountrySyncTimeMs < 24 * 60 * 60 * 1000L)) {
                AppLogger.log("OverpassSyncManager", "fetchOrGetCachedCountries", true, "Returning ${cached.size} cached countries from SQLite DB.")
                onResult(cached)
                return@execute
            }

            if (!isInternetAvailable()) {
                AppLogger.log("OverpassSyncManager", "fetchOrGetCachedCountries", false, "No internet connection. Returning ${cached.size} cached countries.")
                onResult(cached)
                return@execute
            }

            val query = """
                [out:json][timeout:30];
                area["ISO3166-1"]["admin_level"="2"];
                out tags;
            """.trimIndent()

            var fetched: List<Pair<String, String>>? = null
            for (mirror in MIRRORS) {
                fetched = executePostAndParseCountriesStream(mirror, query)
                if (fetched != null && fetched.isNotEmpty()) {
                    dbHelper.insertCountries(fetched)
                    prefs.edit().putLong("last_country_sync_ms", System.currentTimeMillis()).apply()
                    AppLogger.log("OverpassSyncManager", "fetchOrGetCachedCountries", true, "Fetched ${fetched.size} countries from Overpass ($mirror) and cached to SQLite DB.")
                    break
                }
            }
            val resultList = if (fetched != null && fetched.isNotEmpty()) fetched else cached
            onResult(resultList)
        }
    }

    private fun executePostAndParseCountriesStream(urlStr: String, query: String): List<Pair<String, String>>? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "RadarStop/1.0")
            }

            val postData = "data=" + URLEncoder.encode(query, "UTF-8")
            conn.outputStream.use { os ->
                os.write(postData.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code == 200) {
                parseCountriesStream(conn.inputStream)
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.log("OverpassSyncManager", "executePostAndParseCountriesStream", false, "Error fetching countries from $urlStr: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseCountriesStream(inputStream: InputStream): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))
        reader.use {
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "elements") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var countryCode: String? = null
                        var countryName: String? = null
                        while (reader.hasNext()) {
                            val elementName = reader.nextName()
                            if (elementName == "tags") {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val tagKey = reader.nextName()
                                    when (tagKey) {
                                        "ISO3166-1" -> countryCode = reader.nextString()
                                        "name:en" -> countryName = reader.nextString()
                                        "name" -> {
                                            if (countryName == null) countryName = reader.nextString()
                                            else reader.skipValue()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (countryCode != null && countryName != null) {
                            list.add(Pair(countryName, countryCode))
                        }
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
        list.sortBy { it.first }
        return list
    }

    fun shutdown() {
        executor.shutdownNow()
        AppLogger.log("OverpassSyncManager", "shutdown", true, "Sync executor shutdown.")
    }
}
