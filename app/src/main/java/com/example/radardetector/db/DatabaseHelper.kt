package com.example.radardetector.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.radardetector.util.AppLogger

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "radar_detector.db"
        private const val DATABASE_VERSION = 3

        const val TABLE_CAMERAS = "cameras"
        const val COLUMN_ID = "id"
        const val COLUMN_LAT = "lat"
        const val COLUMN_LON = "lon"
        const val COLUMN_DIR = "dir"
        const val COLUMN_IS_LINEAR = "is_linear"

        const val TABLE_COUNTRIES = "countries"
        const val COLUMN_COUNTRY_CODE = "code"
        const val COLUMN_COUNTRY_NAME = "name"
        private val PRESET_COUNTRIES = arrayOf(
            "Afghanistan" to "AF", "Albania" to "AL", "Algeria" to "DZ", "Andorra" to "AD", "Angola" to "AO",
            "Argentina" to "AR", "Armenia" to "AM", "Australia" to "AU", "Austria" to "AT", "Azerbaijan" to "AZ",
            "Bahamas" to "BS", "Bahrain" to "BH", "Bangladesh" to "BD", "Belarus" to "BY", "Belgium" to "BE",
            "Belize" to "BZ", "Benin" to "BJ", "Bhutan" to "BT", "Bolivia" to "BO", "Bosnia and Herzegovina" to "BA",
            "Botswana" to "BW", "Brazil" to "BR", "Brunei" to "BN", "Bulgaria" to "BG", "Burkina Faso" to "BF",
            "Burundi" to "BI", "Cambodia" to "KH", "Cameroon" to "CM", "Canada" to "CA", "Chile" to "CL",
            "China" to "CN", "Colombia" to "CO", "Costa Rica" to "CR", "Croatia" to "HR", "Cuba" to "CU",
            "Cyprus" to "CY", "Czechia" to "CZ", "Denmark" to "DK", "Dominican Republic" to "DO", "Ecuador" to "EC",
            "Egypt" to "EG", "El Salvador" to "SV", "Estonia" to "EE", "Ethiopia" to "ET", "Finland" to "FI",
            "France" to "FR", "Georgia" to "GE", "Germany" to "DE", "Ghana" to "GH", "Greece" to "GR",
            "Guatemala" to "GT", "Honduras" to "HN", "Hungary" to "HU", "Iceland" to "IS", "India" to "IN",
            "Indonesia" to "ID", "Iran" to "IR", "Iraq" to "IQ", "Ireland" to "IE", "Israel" to "IL",
            "Italy" to "IT", "Jamaica" to "JM", "Japan" to "JP", "Jordan" to "JO", "Kazakhstan" to "KZ",
            "Kenya" to "KE", "Kuwait" to "KW", "Kyrgyzstan" to "KG", "Laos" to "LA", "Latvia" to "LV",
            "Lebanon" to "LB", "Libya" to "LY", "Liechtenstein" to "LI", "Lithuania" to "LT", "Luxembourg" to "LU",
            "Madagascar" to "MG", "Malaysia" to "MY", "Maldives" to "MV", "Mali" to "ML", "Malta" to "MT",
            "Mexico" to "MX", "Moldova" to "MD", "Monaco" to "MC", "Mongolia" to "MN", "Montenegro" to "ME",
            "Morocco" to "MA", "Mozambique" to "MZ", "Myanmar" to "MM", "Namibia" to "NA", "Nepal" to "NP",
            "Netherlands" to "NL", "New Zealand" to "NZ", "Nicaragua" to "NI", "Nigeria" to "NG", "North Macedonia" to "MK",
            "Norway" to "NO", "Oman" to "OM", "Pakistan" to "PK", "Panama" to "PA", "Paraguay" to "PY",
            "Peru" to "PE", "Philippines" to "PH", "Poland" to "PL", "Portugal" to "PT", "Qatar" to "QA",
            "Romania" to "RO", "Russia" to "RU", "Rwanda" to "RW", "San Marino" to "SM", "Saudi Arabia" to "SA",
            "Senegal" to "SN", "Serbia" to "RS", "Singapore" to "SG", "Slovakia" to "SK", "Slovenia" to "SI",
            "South Africa" to "ZA", "South Korea" to "KR", "Spain" to "ES", "Sri Lanka" to "LK", "Sweden" to "SE",
            "Switzerland" to "CH", "Taiwan" to "TW", "Tajikistan" to "TJ", "Tanzania" to "TZ", "Thailand" to "TH",
            "Tunisia" to "TN", "Turkey" to "TR", "Turkmenistan" to "TM", "Uganda" to "UG", "Ukraine" to "UA",
            "United Arab Emirates" to "AE", "United Kingdom" to "GB", "United States" to "US", "Uruguay" to "UY",
            "Uzbekistan" to "UZ", "Vatican City" to "VA", "Venezuela" to "VE", "Vietnam" to "VN", "Yemen" to "YE",
            "Zambia" to "ZM", "Zimbabwe" to "ZW"
        )
    }

    private fun seedPresetCountries(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.compileStatement("INSERT OR REPLACE INTO $TABLE_COUNTRIES ($COLUMN_COUNTRY_CODE, $COLUMN_COUNTRY_NAME) VALUES (?, ?)").use { stmt ->
                for ((name, code) in PRESET_COUNTRIES) {
                    stmt.clearBindings()
                    stmt.bindString(1, code)
                    stmt.bindString(2, name)
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db.endTransaction()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CAMERAS (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_LAT REAL NOT NULL,
                $COLUMN_LON REAL NOT NULL,
                $COLUMN_DIR REAL,
                $COLUMN_IS_LINEAR INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX idx_coords ON $TABLE_CAMERAS($COLUMN_LAT, $COLUMN_LON)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_COUNTRIES (
                $COLUMN_COUNTRY_CODE TEXT PRIMARY KEY,
                $COLUMN_COUNTRY_NAME TEXT NOT NULL
            )
            """.trimIndent()
        )
        seedPresetCountries(db)
        AppLogger.log("DatabaseHelper", "onCreate", true, "Database tables ($TABLE_CAMERAS, $TABLE_COUNTRIES) pre-populated with preset countries.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CAMERAS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_COUNTRIES")
        onCreate(db)
        AppLogger.log("DatabaseHelper", "onUpgrade", true, "Upgraded DB from v$oldVersion to v$newVersion.")
    }

    @Volatile
    private var cachedCameraCount: Int = -1

    fun clearCameras() {
        cachedCameraCount = -1
        try {
            writableDatabase.execSQL("DELETE FROM $TABLE_CAMERAS")
            AppLogger.log("DatabaseHelper", "clearCameras", true, "Cleared all camera records from SQLite DB.")
        } catch (e: Exception) {
            AppLogger.log("DatabaseHelper", "clearCameras", false, "Error clearing cameras: ${e.message}")
        }
    }

    fun clearCamerasInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) {
        cachedCameraCount = -1
        try {
            writableDatabase.execSQL(
                "DELETE FROM $TABLE_CAMERAS WHERE $COLUMN_LAT BETWEEN ? AND ? AND $COLUMN_LON BETWEEN ? AND ?",
                arrayOf(minLat, maxLat, minLon, maxLon)
            )
            AppLogger.log("DatabaseHelper", "clearCamerasInBox", true, "Targeted clear of cameras in bounding box [$minLat, $maxLat, $minLon, $maxLon].")
        } catch (e: Exception) {
            AppLogger.log("DatabaseHelper", "clearCamerasInBox", false, "Error clearing cameras in box: ${e.message}")
        }
    }

    fun replaceCamerasInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cameras: List<Camera>) {
        cachedCameraCount = -1
        val startMs = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM $TABLE_CAMERAS WHERE $COLUMN_LAT BETWEEN ? AND ? AND $COLUMN_LON BETWEEN ? AND ?",
                arrayOf(minLat, maxLat, minLon, maxLon)
            )
            db.compileStatement("INSERT OR REPLACE INTO $TABLE_CAMERAS ($COLUMN_ID, $COLUMN_LAT, $COLUMN_LON, $COLUMN_DIR, $COLUMN_IS_LINEAR) VALUES (?, ?, ?, ?, ?)").use { stmt ->
                for (cam in cameras) {
                    stmt.clearBindings()
                    stmt.bindLong(1, cam.id)
                    stmt.bindDouble(2, cam.lat)
                    stmt.bindDouble(3, cam.lon)
                    if (cam.dir != null) {
                        stmt.bindDouble(4, cam.dir.toDouble())
                    } else {
                        stmt.bindNull(4)
                    }
                    stmt.bindLong(5, if (cam.isLinear) 1L else 0L)
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
            val duration = System.currentTimeMillis() - startMs
            AppLogger.log("DatabaseHelper", "replaceCamerasInBox", true, "Atomic replace of ${cameras.size} cameras in box [$minLat, $maxLat, $minLon, $maxLon] in ${duration}ms.")
        } catch (e: Exception) {
            AppLogger.log("DatabaseHelper", "replaceCamerasInBox", false, "Error during atomic replace: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    fun insertCameras(cameras: List<Camera>) {
        cachedCameraCount = -1
        val startMs = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("INSERT OR REPLACE INTO $TABLE_CAMERAS ($COLUMN_ID, $COLUMN_LAT, $COLUMN_LON, $COLUMN_DIR, $COLUMN_IS_LINEAR) VALUES (?, ?, ?, ?, ?)").use { stmt ->
                for (cam in cameras) {
                    stmt.clearBindings()
                    stmt.bindLong(1, cam.id)
                    stmt.bindDouble(2, cam.lat)
                    stmt.bindDouble(3, cam.lon)
                    if (cam.dir != null) {
                        stmt.bindDouble(4, cam.dir.toDouble())
                    } else {
                        stmt.bindNull(4)
                    }
                    stmt.bindLong(5, if (cam.isLinear) 1L else 0L)
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
            val duration = System.currentTimeMillis() - startMs
            AppLogger.log("DatabaseHelper", "insertCameras", true, "Batch inserted ${cameras.size} cameras into SQLite DB in ${duration}ms.")
        } catch (e: Exception) {
            AppLogger.log("DatabaseHelper", "insertCameras", false, "Error inserting cameras: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    fun getCamerasInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Camera> {
        val list = ArrayList<Camera>()
        val db = readableDatabase
        db.query(
            TABLE_CAMERAS,
            arrayOf(COLUMN_ID, COLUMN_LAT, COLUMN_LON, COLUMN_DIR, COLUMN_IS_LINEAR),
            "$COLUMN_LAT BETWEEN ? AND ? AND $COLUMN_LON BETWEEN ? AND ?",
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()),
            null, null, null
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val latIdx = cursor.getColumnIndexOrThrow(COLUMN_LAT)
            val lonIdx = cursor.getColumnIndexOrThrow(COLUMN_LON)
            val dirIdx = cursor.getColumnIndexOrThrow(COLUMN_DIR)
            val isLinearIdx = cursor.getColumnIndexOrThrow(COLUMN_IS_LINEAR)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val lat = cursor.getDouble(latIdx)
                val lon = cursor.getDouble(lonIdx)
                val dir = if (cursor.isNull(dirIdx)) null else cursor.getFloat(dirIdx)
                val isLinear = cursor.getInt(isLinearIdx) == 1
                list.add(Camera(id, lat, lon, dir, isLinear))
            }
        }
        return list
    }

    fun getAllLinearCameras(): List<Camera> {
        val list = ArrayList<Camera>()
        val db = readableDatabase
        db.query(
            TABLE_CAMERAS,
            arrayOf(COLUMN_ID, COLUMN_LAT, COLUMN_LON, COLUMN_DIR, COLUMN_IS_LINEAR),
            "$COLUMN_IS_LINEAR = 1",
            null, null, null, null
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val latIdx = cursor.getColumnIndexOrThrow(COLUMN_LAT)
            val lonIdx = cursor.getColumnIndexOrThrow(COLUMN_LON)
            val dirIdx = cursor.getColumnIndexOrThrow(COLUMN_DIR)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val lat = cursor.getDouble(latIdx)
                val lon = cursor.getDouble(lonIdx)
                val dir = if (cursor.isNull(dirIdx)) null else cursor.getFloat(dirIdx)
                list.add(Camera(id, lat, lon, dir, true))
            }
        }
        return list
    }

    fun getCameraCount(): Int {
        val current = cachedCameraCount
        if (current >= 0) {
            return current
        }
        val count = try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_CAMERAS", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
        cachedCameraCount = count
        return count
    }

    fun insertCountries(countries: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("INSERT OR REPLACE INTO $TABLE_COUNTRIES ($COLUMN_COUNTRY_CODE, $COLUMN_COUNTRY_NAME) VALUES (?, ?)").use { stmt ->
                for ((name, code) in countries) {
                    stmt.clearBindings()
                    stmt.bindString(1, code)
                    stmt.bindString(2, name)
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
            AppLogger.log("DatabaseHelper", "insertCountries", true, "Inserted ${countries.size} countries into SQLite DB.")
        } catch (e: Exception) {
            AppLogger.log("DatabaseHelper", "insertCountries", false, "Error inserting countries: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    fun getCountries(): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_COUNTRY_NAME, $COLUMN_COUNTRY_CODE FROM $TABLE_COUNTRIES ORDER BY $COLUMN_COUNTRY_NAME ASC", null)
        cursor.use {
            val nameIdx = cursor.getColumnIndexOrThrow(COLUMN_COUNTRY_NAME)
            val codeIdx = cursor.getColumnIndexOrThrow(COLUMN_COUNTRY_CODE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)
                val code = cursor.getString(codeIdx)
                list.add(Pair(name, code))
            }
        }
        if (list.isEmpty()) {
            val preset = PRESET_COUNTRIES.map { Pair(it.first, it.second) }
            insertCountries(preset)
            return preset.sortedBy { it.first }
        }
        return list
    }
}
