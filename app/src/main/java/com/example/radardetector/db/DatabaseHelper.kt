package com.example.radardetector.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "radar_detector.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_CAMERAS = "cameras"
        const val COLUMN_ID = "id"
        const val COLUMN_LAT = "lat"
        const val COLUMN_LON = "lon"
        const val COLUMN_DIR = "dir"

        const val TABLE_CONFIG = "config"
        const val COLUMN_KEY = "key"
        const val COLUMN_VALUE = "value"
        const val KEY_LAST_COUNTRY = "last_country"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CAMERAS (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_LAT REAL NOT NULL,
                $COLUMN_LON REAL NOT NULL,
                $COLUMN_DIR REAL
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX idx_coords ON $TABLE_CAMERAS($COLUMN_LAT, $COLUMN_LON)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_CONFIG (
                $COLUMN_KEY TEXT PRIMARY KEY,
                $COLUMN_VALUE TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CAMERAS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CONFIG")
        onCreate(db)
    }

    fun getLastCountry(): String? {
        val db = readableDatabase
        db.query(
            TABLE_CONFIG,
            arrayOf(COLUMN_VALUE),
            "$COLUMN_KEY = ?",
            arrayOf(KEY_LAST_COUNTRY),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    fun setLastCountry(country: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_KEY, KEY_LAST_COUNTRY)
            put(COLUMN_VALUE, country)
        }
        db.insertWithOnConflict(TABLE_CONFIG, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearCameras() {
        writableDatabase.execSQL("DELETE FROM $TABLE_CAMERAS")
    }

    fun insertCameras(cameras: List<Camera>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR REPLACE INTO $TABLE_CAMERAS ($COLUMN_ID, $COLUMN_LAT, $COLUMN_LON, $COLUMN_DIR) VALUES (?, ?, ?, ?)")
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
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getCamerasInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Camera> {
        val list = ArrayList<Camera>()
        val db = readableDatabase
        db.query(
            TABLE_CAMERAS,
            arrayOf(COLUMN_ID, COLUMN_LAT, COLUMN_LON, COLUMN_DIR),
            "$COLUMN_LAT BETWEEN ? AND ? AND $COLUMN_LON BETWEEN ? AND ?",
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()),
            null, null, null
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
                list.add(Camera(id, lat, lon, dir))
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
