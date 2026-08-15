package com.example.radardetector.network

import android.content.Context
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
import com.example.radardetector.util.AppPrefs
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
            "https://z.overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        private const val SYNC_THROTTLE_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val RETRY_PAUSE_MS = 5 * 60 * 1000L // 5 minutes retry delay on network failure
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastSyncTimeMs = AppPrefs.getLastSyncTimeMs(context)
    @Volatile
    private var lastSyncAttemptMs = 0L

    private val isSyncing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastSyncedLat = AppPrefs.getLastSyncedLat(context)
    private var lastSyncedLon = AppPrefs.getLastSyncedLon(context)

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
        if (isSyncing.get()) return

        val now = System.currentTimeMillis()
        if (lastSyncAttemptMs != 0L && now - lastSyncAttemptMs < RETRY_PAUSE_MS) {
            return
        }

        val distanceMoved = FloatArray(1)
        if (lastSyncedLat != 0.0 || lastSyncedLon != 0.0) {
            Location.distanceBetween(location.latitude, location.longitude, lastSyncedLat, lastSyncedLon, distanceMoved)
        } else {
            distanceMoved[0] = Float.MAX_VALUE
        }

        val isInitialSyncNeeded = !hasDoneInitialSync
        val isDistanceExpired = distanceMoved[0] >= 40000f
        val isTimeExpired = lastSyncTimeMs == 0L || (now - lastSyncTimeMs >= SYNC_THROTTLE_MS)

        val isSyncNeeded = isInitialSyncNeeded || isDistanceExpired || isTimeExpired

        if (!isSyncNeeded) return

        if (!isSyncing.compareAndSet(false, true)) return
        executor.execute {
            try {
                performSync(location.latitude, location.longitude)
                hasDoneInitialSync = true
            } finally {
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
        AppUpdateManager.checkAndDownloadUpdate(context)
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

        val cameras = fetchCamerasFromMirrors(query, readTimeoutMs = 25000)
        if (cameras != null) {
            dbHelper.replaceCamerasInBox(south, north, west, east, cameras)
            lastSyncTimeMs = System.currentTimeMillis()
            lastSyncAttemptMs = 0L
            lastSyncedLat = lat
            lastSyncedLon = lon
            AppPrefs.setLastSyncData(context, lastSyncTimeMs, lat, lon)
            val count = dbHelper.getCameraCount()
            AppLogger.log(
                "OverpassSyncManager",
                "performSync",
                true,
                "NETWORK SYNC SUCCESS: Downloaded ${cameras.size} cameras via JsonReader stream from Overpass for 100x100km box around ($lat, $lon). Total in DB: $count"
            )
            mainHandler.post { onSyncSuccess(cameras.size, count) }
        } else {
            lastSyncAttemptMs = System.currentTimeMillis()
            AppLogger.log("OverpassSyncManager", "performSync", false, "All Overpass mirrors failed. Setting 5m retry pause.")
            mainHandler.post { onStatusUpdate("Network error. Retry in 5m...") }
        }
    }

    private fun fetchCamerasFromMirrors(query: String, readTimeoutMs: Int = 30000): List<Camera>? {
        for (i in MIRRORS.indices) {
            val mirror = MIRRORS[i]
            AppLogger.log("OverpassSyncManager", "fetchCamerasFromMirrors", true, "Connecting to mirror [${i + 1}/${MIRRORS.size}]: $mirror")
            val cameras = executePostAndParseStream(mirror, query, readTimeoutMs = readTimeoutMs)
            if (cameras != null) {
                return cameras
            }

            if (i < MIRRORS.size - 1) {
                AppLogger.log("OverpassSyncManager", "fetchCamerasFromMirrors", false, "Mirror failed. Waiting 5s before next mirror attempt...")
                try {
                    Thread.sleep(5000L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        return null
    }

    private fun <T> executePostRequest(
        urlStr: String,
        body: String,
        readTimeoutMs: Int = 30000,
        parseBlock: (InputStream) -> T
    ): T? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                this.readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("User-Agent", "RadarStop/1.0")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write("data=" + URLEncoder.encode(body, "UTF-8"))
                writer.flush()
            }
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.use { inputStream ->
                    parseBlock(inputStream)
                }
            } else {
                AppLogger.log("OverpassSyncManager", "executePostRequest", false, "HTTP Error code $code from $urlStr")
                null
            }
        } catch (e: Exception) {
            AppLogger.log("OverpassSyncManager", "executePostRequest", false, "Network error on $urlStr: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun executePostAndParseStream(urlStr: String, body: String, readTimeoutMs: Int = 30000): List<Camera>? {
        return executePostRequest(urlStr, body, readTimeoutMs) { parseOverpassStream(it) }
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
                        cameras.add(Camera(id, lat, lon, isLinear))
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

    fun triggerCountryCameraSync(countryCode: String, countryName: String) {
        executor.execute {
            isSyncing.set(true)
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

        val codeUpper = countryCode.uppercase().trim()

        val queries = listOf(
            """
                [out:json][timeout:60];
                area["ISO3166-1"="$codeUpper"]->.searchArea;
                (
                  node["highway"="speed_camera"](area.searchArea);
                  node["enforcement"](area.searchArea);
                );
                out body;
            """.trimIndent(),
            """
                [out:json][timeout:60];
                area["ISO3166-1:alpha2"="$codeUpper"]->.searchArea;
                (
                  node["highway"="speed_camera"](area.searchArea);
                  node["enforcement"](area.searchArea);
                );
                out body;
            """.trimIndent()
        )

        var cameras: List<Camera>? = null
        for (query in queries) {
            cameras = fetchCamerasFromMirrors(query, readTimeoutMs = 60000)
            if (cameras != null) break
        }

        if (cameras != null) {
            dbHelper.insertCameras(cameras)
            val count = dbHelper.getCameraCount()
            AppLogger.log("OverpassSyncManager", "performCountryCameraSync", true, "COUNTRY SYNC SUCCESS: Downloaded ${cameras.size} cameras for $countryName. Total in DB: $count")
            mainHandler.post { onSyncSuccess(cameras.size, count) }
            mainHandler.post { onStatusUpdate("$countryName cameras loaded! (${cameras.size} added, $count total in DB)") }
        } else {
            mainHandler.post { onStatusUpdate("Failed to load $countryName cameras. Check network.") }
        }
    }

    fun fetchOrGetCachedCountries(onResult: (List<Pair<String, String>>) -> Unit) {
        executor.execute {
            val now = System.currentTimeMillis()
            val cached = dbHelper.getCountries()

            if (cached.isNotEmpty()) {
                AppLogger.log("OverpassSyncManager", "fetchOrGetCachedCountries", true, "Returning ${cached.size} cached countries from SQLite DB immediately.")
                onResult(cached)
            }

            val lastCountrySyncTimeMs = AppPrefs.getLastCountrySyncMs(context)
            if (cached.isNotEmpty() && (now - lastCountrySyncTimeMs < 24 * 60 * 60 * 1000L)) {
                return@execute
            }

            if (!isInternetAvailable()) {
                if (cached.isEmpty()) onResult(emptyList())
                return@execute
            }

            val query = """
                [out:json][timeout:30];
                (
                  relation["admin_level"="2"]["ISO3166-1"];
                  relation["admin_level"="2"]["ISO3166-1:alpha2"];
                );
                out tags;
            """.trimIndent()

            var fetched: List<Pair<String, String>>? = null
            for (mirror in MIRRORS) {
                fetched = executePostAndParseCountriesStream(mirror, query)
                if (fetched != null && fetched.isNotEmpty()) {
                    dbHelper.insertCountries(fetched)
                    AppPrefs.setLastCountrySyncMs(context)
                    AppLogger.log("OverpassSyncManager", "fetchOrGetCachedCountries", true, "Fetched ${fetched.size} countries from Overpass ($mirror) and cached to SQLite DB.")
                    if (cached.isEmpty()) {
                        onResult(fetched)
                    }
                    break
                }
            }
            if (cached.isEmpty() && (fetched == null || fetched.isEmpty())) {
                onResult(emptyList())
            }
        }
    }

    private fun executePostAndParseCountriesStream(urlStr: String, query: String): List<Pair<String, String>>? {
        return executePostRequest(urlStr, query, 30000) { parseCountriesStream(it) }
    }

    private fun parseCountriesStream(inputStream: InputStream): List<Pair<String, String>> {
        val countryMap = LinkedHashMap<String, String>()
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
                                        "ISO3166-1", "ISO3166-1:alpha2" -> {
                                            if (countryCode == null) countryCode = reader.nextString().uppercase().trim()
                                            else reader.skipValue()
                                        }
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
                        if (!countryCode.isNullOrEmpty() && !countryName.isNullOrEmpty() && countryCode.length == 2) {
                            val cleanName = countryName.replace(Regex("""\s*\(.*?\)\s*"""), "").trim()
                            if (!countryMap.containsKey(countryCode) || cleanName.length < countryMap[countryCode]!!.length) {
                                countryMap[countryCode] = cleanName
                            }
                        }
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
        return countryMap.map { Pair(it.value, it.key) }.sortedBy { it.first }
    }

    fun shutdown() {
        executor.shutdownNow()
        AppLogger.log("OverpassSyncManager", "shutdown", true, "Sync executor shutdown.")
    }
}
