package com.example.radardetector.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testExtractVersionNumbers() {
        val ver1 = AppUpdateManager.extractVersionNumbers("RadarStop_26.08.02_1644.apk")
        assertEquals(listOf(26, 8, 2, 1644), ver1)

        val ver2 = AppUpdateManager.extractVersionNumbers("26.08.02_1644")
        assertEquals(listOf(26, 8, 2, 1644), ver2)

        val ver3 = AppUpdateManager.extractVersionNumbers("RadarStop_26.08.03_2100.apk")
        assertEquals(listOf(26, 8, 3, 2100), ver3)

        val ver4 = AppUpdateManager.extractVersionNumbers("v1.2.3")
        assertEquals(listOf(1, 2, 3), ver4)
    }

    @Test
    fun testIsVersionNewer() {
        val remoteNewer = listOf(26, 8, 3, 2100)
        val localOlder = listOf(26, 8, 2, 1644)
        assertTrue(AppUpdateManager.isVersionNewer(remoteNewer, localOlder))

        val remoteEqual = listOf(26, 8, 2, 1644)
        val localEqual = listOf(26, 8, 2, 1644)
        assertFalse(AppUpdateManager.isVersionNewer(remoteEqual, localEqual))

        val remoteOlder = listOf(26, 8, 1, 1000)
        assertFalse(AppUpdateManager.isVersionNewer(remoteOlder, localOlder))

        val remoteLonger = listOf(26, 8, 2, 1644, 1)
        assertTrue(AppUpdateManager.isVersionNewer(remoteLonger, localEqual))
    }
}
