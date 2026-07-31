package com.example.radardetector.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.radardetector.util.AppLogger

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "radar_detector.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_CAMERAS = "cameras"
        const val COLUMN_ID = "id"
        const val COLUMN_LAT = "lat"
        const val COLUMN_LON = "lon"
        const val COLUMN_DIR = "dir"
        const val COLUMN_IS_LINEAR = "is_linear"
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
        AppLogger.log("DatabaseHelper", "onCreate", true, "Database table ($TABLE_CAMERAS) and spatial index created.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CAMERAS")
        onCreate(db)
        AppLogger.log("DatabaseHelper", "onUpgrade", true, "Upgraded DB from v$oldVersion to v$newVersion.")
    }

    fun clearCameras() {
        writableDatabase.execSQL("DELETE FROM $TABLE_CAMERAS")
        AppLogger.log("DatabaseHelper", "clearCameras", true, "Cleared all camera records from SQLite DB.")
    }

    fun insertCameras(cameras: List<Camera>) {
        val startMs = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR REPLACE INTO $TABLE_CAMERAS ($COLUMN_ID, $COLUMN_LAT, $COLUMN_LON, $COLUMN_DIR, $COLUMN_IS_LINEAR) VALUES (?, ?, ?, ?, ?)")
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

    fun getCameraCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_CAMERAS", null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }
}
