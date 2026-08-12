package com.example.radardetector.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.radardetector.util.AppLogger
import com.example.radardetector.util.bindCamera
import com.example.radardetector.util.runInTransaction

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "radar_detector.db"
        private const val DATABASE_VERSION = 4

        const val TABLE_CAMERAS = "cameras"
        const val COLUMN_ID = "id"
        const val COLUMN_LAT = "lat"
        const val COLUMN_LON = "lon"
        const val COLUMN_IS_LINEAR = "is_linear"

        const val TABLE_COUNTRIES = "countries"
        const val COLUMN_COUNTRY_CODE = "code"
        const val COLUMN_COUNTRY_NAME = "name"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CAMERAS (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_LAT REAL NOT NULL,
                $COLUMN_LON REAL NOT NULL,
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
        AppLogger.log("DatabaseHelper", "onCreate", true, "Database tables ($TABLE_CAMERAS, $TABLE_COUNTRIES) and spatial index created.")
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
        writableDatabase.runInTransaction("DatabaseHelper", "replaceCamerasInBox") { db ->
            db.execSQL(
                "DELETE FROM $TABLE_CAMERAS WHERE $COLUMN_LAT BETWEEN ? AND ? AND $COLUMN_LON BETWEEN ? AND ?",
                arrayOf(minLat, maxLat, minLon, maxLon)
            )
            db.compileStatement("INSERT OR REPLACE INTO $TABLE_CAMERAS ($COLUMN_ID, $COLUMN_LAT, $COLUMN_LON, $COLUMN_IS_LINEAR) VALUES (?, ?, ?, ?)").use { stmt ->
                for (cam in cameras) {
                    stmt.bindCamera(cam)
                    stmt.executeInsert()
                }
            }
        }
    }

    fun insertCameras(cameras: List<Camera>) {
        cachedCameraCount = -1
        writableDatabase.runInTransaction("DatabaseHelper", "insertCameras") { db ->
            db.compileStatement("INSERT OR REPLACE INTO $TABLE_CAMERAS ($COLUMN_ID, $COLUMN_LAT, $COLUMN_LON, $COLUMN_IS_LINEAR) VALUES (?, ?, ?, ?)").use { stmt ->
                for (cam in cameras) {
                    stmt.bindCamera(cam)
                    stmt.executeInsert()
                }
            }
        }
    }

    fun getCamerasInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Camera> {
        val list = ArrayList<Camera>()
        val db = readableDatabase
        db.query(
            TABLE_CAMERAS,
            arrayOf(COLUMN_ID, COLUMN_LAT, COLUMN_LON, COLUMN_IS_LINEAR),
            "$COLUMN_LAT BETWEEN ? AND ? AND $COLUMN_LON BETWEEN ? AND ?",
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()),
            null, null, null
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val latIdx = cursor.getColumnIndexOrThrow(COLUMN_LAT)
            val lonIdx = cursor.getColumnIndexOrThrow(COLUMN_LON)
            val isLinearIdx = cursor.getColumnIndexOrThrow(COLUMN_IS_LINEAR)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val lat = cursor.getDouble(latIdx)
                val lon = cursor.getDouble(lonIdx)
                val isLinear = cursor.getInt(isLinearIdx) == 1
                list.add(Camera(id, lat, lon, isLinear))
            }
        }
        return list
    }

    fun getAllLinearCameras(): List<Camera> {
        val list = ArrayList<Camera>()
        val db = readableDatabase
        db.query(
            TABLE_CAMERAS,
            arrayOf(COLUMN_ID, COLUMN_LAT, COLUMN_LON, COLUMN_IS_LINEAR),
            "$COLUMN_IS_LINEAR = 1",
            null, null, null, null
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val latIdx = cursor.getColumnIndexOrThrow(COLUMN_LAT)
            val lonIdx = cursor.getColumnIndexOrThrow(COLUMN_LON)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val lat = cursor.getDouble(latIdx)
                val lon = cursor.getDouble(lonIdx)
                list.add(Camera(id, lat, lon, true))
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
        writableDatabase.runInTransaction("DatabaseHelper", "insertCountries") { db ->
            db.compileStatement("INSERT OR REPLACE INTO $TABLE_COUNTRIES ($COLUMN_COUNTRY_CODE, $COLUMN_COUNTRY_NAME) VALUES (?, ?)").use { stmt ->
                for ((name, code) in countries) {
                    stmt.clearBindings()
                    stmt.bindString(1, code)
                    stmt.bindString(2, name)
                    stmt.executeInsert()
                }
            }
        }
    }

    fun getCountries(): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        val db = readableDatabase
        db.rawQuery("SELECT $COLUMN_COUNTRY_NAME, $COLUMN_COUNTRY_CODE FROM $TABLE_COUNTRIES ORDER BY $COLUMN_COUNTRY_NAME ASC", null).use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(COLUMN_COUNTRY_NAME)
            val codeIdx = cursor.getColumnIndexOrThrow(COLUMN_COUNTRY_CODE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)
                val code = cursor.getString(codeIdx)
                list.add(Pair(name, code))
            }
        }
        return list
    }
}
