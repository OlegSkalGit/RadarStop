package com.example.radardetector.math

import android.location.Location
import kotlin.math.abs

object RadarMath {

    fun angleDifference(bearing1: Float, bearing2: Float): Float {
        var diff = (bearing1 - bearing2) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }

    /**
     * Calculates geodesic distance in meters between car and camera coordinates.
     */
    fun calculateDistance(carLocation: Location, cameraLat: Double, cameraLon: Double): Float {
        val cameraLocation = Location("").apply {
            latitude = cameraLat
            longitude = cameraLon
        }
        return carLocation.distanceTo(cameraLocation)
    }

    /**
     * Calculates beep delay based on distance in the 300m to -300m zone.
     * Approaching (300m -> 0m) and Departing (0m -> 300m):
     * 200-300m: 2000 ms (2.0s)
     * 100-200m: 1000 ms (1.0s)
     * 0-100m:    500 ms (0.5s)
     */
    fun calculateBeepDelay(distanceMeters: Float): Long {
        val dist = abs(distanceMeters)
        return when {
            dist <= 100f -> 500L
            dist <= 200f -> 1000L
            else -> 2000L
        }
    }

    /**
     * Calculates 10x10 km Bounding Box coordinates (minLat, maxLat, minLon, maxLon)
     * corresponding to +-0.045 degrees around lat/lon.
     */
    fun get10x10BoxCoordinates(lat: Double, lon: Double): DoubleArray {
        return doubleArrayOf(lat - 0.045, lat + 0.045, lon - 0.045, lon + 0.045)
    }

    /**
     * Unified camera loader for 10x10 km RAM cache + linear cameras.
     * Used identically by both RadarForegroundService and RadarMapActivity.
     */
    fun load10x10Cameras(dbHelper: com.example.radardetector.db.DatabaseHelper, lat: Double, lon: Double): CameraLoadResult {
        val box = get10x10BoxCoordinates(lat, lon)
        val minLat = box[0]
        val maxLat = box[1]
        val minLon = box[2]
        val maxLon = box[3]

        val boxCams = dbHelper.getCamerasInBox(minLat, maxLat, minLon, maxLon)
        val linearCams = dbHelper.getAllLinearCameras()
        val totalInDb = dbHelper.getCameraCount()
        val merged = (boxCams + linearCams).distinctBy { it.id }

        return CameraLoadResult(
            cameras = merged,
            boxCameraCount = merged.size,
            totalInDb = totalInDb,
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon
        )
    }

    /**
     * Single authoritative location evaluator function used identically by both
     * RadarForegroundService and RadarMapActivity to produce 100% unified metrics.
     */
    fun evaluateLocationData(
        location: Location,
        speedKmh: Float,
        dbHelper: com.example.radardetector.db.DatabaseHelper,
        ramCacheOverride: CameraLoadResult? = null,
        trajectoryBearing: Float = location.bearing,
        trajectoryPoints: List<Location> = emptyList()
    ): ProcessedLocationMetrics {
        val isAccuracyWeak = location.hasAccuracy() && location.accuracy > 100f

        val gpsStatusStr = if (isAccuracyWeak) {
            "GPS: WEAK (>100m [${location.accuracy.toInt()}m])"
        } else if (location.hasAccuracy()) {
            "GPS: OK (±${location.accuracy.toInt()}m)"
        } else {
            "GPS: ACTIVE"
        }

        val loadResult = ramCacheOverride ?: load10x10Cameras(dbHelper, location.latitude, location.longitude)
        val continuousThresh = if (speedKmh <= 60f) 50f else 100f

        var minDistToAnyCam = Float.MAX_VALUE
        var closestAlertCam: com.example.radardetector.db.Camera? = null
        var minAlertDist = Float.MAX_VALUE

        for (cam in loadResult.cameras) {
            val dist = calculateDistance(location, cam.lat, cam.lon)
            if (dist < minDistToAnyCam) minDistToAnyCam = dist

            if (dist <= 300f) {
                if (dist < minAlertDist) {
                    minAlertDist = dist
                    closestAlertCam = cam
                }
            }
        }

        val inRange3kmCount = loadResult.cameras.count { calculateDistance(location, it.lat, it.lon) <= 3000f }

        return ProcessedLocationMetrics(
            location = location,
            speedKmh = speedKmh,
            isAccuracyWeak = isAccuracyWeak,
            gpsStatusStr = gpsStatusStr,
            cameraLoadResult = loadResult,
            inRange3kmCount = inRange3kmCount,
            minDistToAnyCamera = minDistToAnyCam,
            closestAlertCamera = closestAlertCam,
            minDistanceToAlert = minAlertDist,
            continuousThreshold = continuousThresh,
            trajectoryBearing = trajectoryBearing,
            trajectoryPoints = trajectoryPoints
        )
    }
}

/**
 * Trajectory filter maintaining a 10-point sliding buffer with two-subbuffer trend analysis (5+5 points)
 * for both spatial trajectory coordinates and speed estimation.
 * Features adaptive buffer truncation (down to 3 points) upon sharp turns (>= 30 degrees)
 * or sudden speed maneuvers (>= 30% change).
 */
class TrajectoryFilter(
    private val maxBufferSize: Int = 10
) {
    private val buffer = java.util.ArrayDeque<Location>()
    private val rawMotionBuffer = java.util.ArrayDeque<Location>()

    @Synchronized
    fun reset() {
        buffer.clear()
        rawMotionBuffer.clear()
    }

    @Synchronized
    fun getPoints(): List<Location> = buffer.toList()

    @Synchronized
    fun processLocation(location: Location): TrajectoryResult {
        val isWeak = location.hasAccuracy() && location.accuracy > 100f
        if (isWeak) {
            val lm = computeLineMetrics()
            return TrajectoryResult(
                isValid = false,
                isAccuracyWeak = true,
                points = buffer.toList(),
                averageSpeedKmh = lm.speedKmh,
                trajectoryBearing = lm.trajectoryBearing,
                projectedDistanceMeters = lm.projectedDistanceMeters
            )
        }

        if (rawMotionBuffer.size >= maxBufferSize) {
            rawMotionBuffer.removeFirst()
        }
        rawMotionBuffer.addLast(location)

        if (rawMotionBuffer.size >= 3) {
            val firstPt = rawMotionBuffer.first
            val lastPt = rawMotionBuffer.last
            val distMeters = firstPt.distanceTo(lastPt)
            val firstAcc = if (firstPt.hasAccuracy()) firstPt.accuracy else 10f
            val lastAcc = if (lastPt.hasAccuracy()) lastPt.accuracy else 10f
            val doubleAccuracyThreshold = 2f * maxOf(firstAcc, lastAcc)

            if (distMeters <= doubleAccuracyThreshold) {
                return TrajectoryResult(
                    isValid = true,
                    isAccuracyWeak = false,
                    points = buffer.toList(),
                    averageSpeedKmh = 0f,
                    trajectoryBearing = 0f,
                    projectedDistanceMeters = 0f,
                    isStationary = true
                )
            }
        }

        if (buffer.size >= maxBufferSize) {
            buffer.removeFirst()
        }
        buffer.addLast(location)

        val lm = computeLineMetrics()
        return TrajectoryResult(
            isValid = true,
            isAccuracyWeak = false,
            points = buffer.toList(),
            averageSpeedKmh = lm.speedKmh,
            trajectoryBearing = lm.trajectoryBearing,
            projectedDistanceMeters = lm.projectedDistanceMeters,
            projectedLocation = lm.projectedLocation ?: location
        )
    }

    private data class SeriesPoint(val x: Double, val y: Double)

    private fun fitLinearSeries(points: List<SeriesPoint>): Pair<Double, Double> {
        val n = points.size
        if (n == 0) return Pair(0.0, 0.0)
        if (n == 1) return Pair(0.0, points[0].y)

        val meanX = points.sumOf { it.x } / n
        val meanY = points.sumOf { it.y } / n

        var num = 0.0
        var den = 0.0
        for (p in points) {
            val dx = p.x - meanX
            val dy = p.y - meanY
            num += dx * dy
            den += dx * dx
        }

        val slope = if (Math.abs(den) > 1e-6) num / den else 0.0
        val intercept = meanY - slope * meanX
        return Pair(slope, intercept)
    }

    private fun evaluateTwoVectorSeries(
        points: List<SeriesPoint>,
        targetX: Double,
        onSharpChange: () -> Unit
    ): Double {
        if (points.isEmpty()) return 0.0
        if (points.size < 3) return points.map { it.y }.average()

        if (points.size < 6) {
            val (slope, intercept) = fitLinearSeries(points)
            return slope * targetX + intercept
        }

        val mid = points.size / 2
        val head = points.take(mid)
        val tail = points.takeLast(points.size - mid)

        val (slope1, intercept1) = fitLinearSeries(head)
        val (slope2, intercept2) = fitLinearSeries(tail)

        val proj1 = slope1 * targetX + intercept1
        val proj2 = slope2 * targetX + intercept2

        val maxVal = maxOf(Math.abs(proj1), Math.abs(proj2), 1.0)
        val diffRatio = Math.abs(proj2 - proj1) / maxVal

        if (diffRatio < 0.30) {
            return 0.5 * (proj1 + proj2)
        } else {
            onSharpChange()
            return proj2
        }
    }

    private fun computeTwoVectorSpeed(points: List<Location>): Float {
        val validPoints = points.filter { it.hasSpeed() }
        if (validPoints.isEmpty()) {
            return points.lastOrNull()?.let { if (it.hasSpeed()) it.speed * 3.6f else 0f } ?: 0f
        }
        val refTime = validPoints.first().time
        val targetX = (validPoints.last().time - refTime) / 1000.0
        val series = validPoints.map { SeriesPoint((it.time - refTime) / 1000.0, (it.speed * 3.6f).toDouble()) }

        val speed = evaluateTwoVectorSeries(series, targetX) {
            while (buffer.size > 3) {
                buffer.removeFirst()
            }
        }
        return speed.toFloat().coerceAtLeast(0f)
    }

    fun computeLineMetrics(): LineMetrics {
        val points = buffer.toList()
        if (points.isEmpty()) {
            return LineMetrics(speedKmh = 0f, trajectoryBearing = 0f, projectedDistanceMeters = 0f)
        }

        val ref = points.first()
        val last = points.last()
        val refTime = ref.time
        val targetX = (last.time - refTime) / 1000.0

        val radLat = Math.toRadians(ref.latitude)
        val metersPerDegLat = 111139.0
        val metersPerDegLon = 111139.0 * Math.cos(radLat)

        val seriesX = points.map { SeriesPoint((it.time - refTime) / 1000.0, (it.longitude - ref.longitude) * metersPerDegLon) }
        val seriesY = points.map { SeriesPoint((it.time - refTime) / 1000.0, (it.latitude - ref.latitude) * metersPerDegLat) }

        val projX = evaluateTwoVectorSeries(seriesX, targetX) {
            while (buffer.size > 3) {
                buffer.removeFirst()
            }
        }
        val projY = evaluateTwoVectorSeries(seriesY, targetX) {
            while (buffer.size > 3) {
                buffer.removeFirst()
            }
        }

        val avgSpeedKmh = computeTwoVectorSpeed(points)

        val projLat = ref.latitude + (projY / metersPerDegLat)
        val projLon = ref.longitude + (projX / metersPerDegLon)

        val projectedLoc = Location(last).apply {
            latitude = projLat
            longitude = projLon
        }

        val projDistMeters = ref.distanceTo(last)

        return LineMetrics(
            speedKmh = avgSpeedKmh,
            trajectoryBearing = last.bearing,
            projectedDistanceMeters = projDistMeters,
            projectedLocation = projectedLoc
        )
    }
}

data class LineMetrics(
    val speedKmh: Float,
    val trajectoryBearing: Float,
    val projectedDistanceMeters: Float,
    val projectedLocation: Location? = null
)

data class TrajectoryResult(
    val isValid: Boolean,
    val isAccuracyWeak: Boolean,
    val points: List<Location>,
    val averageSpeedKmh: Float,
    val trajectoryBearing: Float,
    val projectedDistanceMeters: Float = 0f,
    val isStationary: Boolean = false,
    val projectedLocation: Location? = null
)

data class CameraLoadResult(
    val cameras: List<com.example.radardetector.db.Camera>,
    val boxCameraCount: Int,
    val totalInDb: Int,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

data class ProcessedLocationMetrics(
    val location: Location,
    val speedKmh: Float,
    val isAccuracyWeak: Boolean,
    val gpsStatusStr: String,
    val cameraLoadResult: CameraLoadResult,
    val inRange3kmCount: Int,
    val minDistToAnyCamera: Float,
    val closestAlertCamera: com.example.radardetector.db.Camera?,
    val minDistanceToAlert: Float,
    val continuousThreshold: Float,
    val trajectoryBearing: Float = 0f,
    val trajectoryPoints: List<Location> = emptyList()
)
