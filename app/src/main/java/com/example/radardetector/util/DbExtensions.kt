package com.example.radardetector.util

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.example.radardetector.db.Camera

/**
 * Extension functions for SQLite transactions and statement bindings.
 */
inline fun SQLiteDatabase.runInTransaction(tag: String, actionName: String, block: (SQLiteDatabase) -> Unit) {
    val startMs = System.currentTimeMillis()
    beginTransaction()
    try {
        block(this)
        setTransactionSuccessful()
        val duration = System.currentTimeMillis() - startMs
        AppLogger.log(tag, actionName, true, "Transaction executed successfully in ${duration}ms.")
    } catch (e: Exception) {
        AppLogger.log(tag, actionName, false, "Error during transaction: ${e.message}")
    } finally {
        endTransaction()
    }
}

fun SQLiteStatement.bindCamera(camera: Camera) {
    clearBindings()
    bindLong(1, camera.id)
    bindDouble(2, camera.lat)
    bindDouble(3, camera.lon)
    bindLong(4, if (camera.isLinear) 1L else 0L)
}
