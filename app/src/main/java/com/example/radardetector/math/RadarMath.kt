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
        trajectoryPoints: List<Location> = emptyList(),
        isStationary: Boolean = false
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
            trajectoryPoints = trajectoryPoints,
            isStationary = isStationary
        )
    }
}

/**
 * Trajectory filter maintaining a 10-point sliding buffer with parametric 2D time-series OLS
 * (X(t) and Y(t)) and two-subbuffer vector trend analysis (Head 5 + Tail 5 points).
 * Evaluates spatial direction vector azimuths and performs adaptive buffer truncation (down to 3 points)
 * only upon sharp spatial turns (angle difference >= 30 degrees).
 * Derives smoothed vector speed directly from spatial velocity components sqrt(vx^2 + vy^2).
 */
class TrajectoryFilter(
    private val maxBufferSize: Int = 10
) {
    private val buffer = java.util.ArrayDeque<Location>()

    @Synchronized
    fun reset() {
        buffer.clear()
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

        if (buffer.isNotEmpty()) {
            val prevPt = buffer.last
            val distMeters = prevPt.distanceTo(location)
            val prevAcc = if (prevPt.hasAccuracy()) prevPt.accuracy else 10f
            val newAcc = if (location.hasAccuracy()) location.accuracy else 10f
            val doubleAccuracyThreshold = 2f * maxOf(prevAcc, newAcc)

            if (distMeters <= doubleAccuracyThreshold) {
                return TrajectoryResult(
                    isValid = true,
                    isAccuracyWeak = false,
                    points = buffer.toList(),
                    averageSpeedKmh = 0f,
                    trajectoryBearing = 0f,
                    projectedDistanceMeters = 0f,
                    isStationary = true,
                    projectedLocation = prevPt
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

    private fun computeOlsSpeedFromGps(points: List<Location>, refTime: Long, targetT: Double): Float {
        val validPoints = points.filter { it.hasSpeed() }
        if (validPoints.isEmpty()) {
            return points.lastOrNull()?.let { if (it.hasSpeed()) it.speed * 3.6f else 0f } ?: 0f
        }
        if (validPoints.size < 3) {
            return (validPoints.map { it.speed * 3.6f }.average()).toFloat().coerceAtLeast(0f)
        }

        val seriesV = validPoints.map {
            SeriesPoint((it.time - refTime) / 1000.0, (it.speed * 3.6f).toDouble())
        }

        if (seriesV.size < 6) {
            val (slope, intercept) = fitLinearSeries(seriesV)
            val speed = slope * targetT + intercept
            return speed.toFloat().coerceAtLeast(0f)
        }

        val mid = seriesV.size / 2
        val headV = seriesV.take(mid)
        val tailV = seriesV.takeLast(seriesV.size - mid)

        val (slope1, intercept1) = fitLinearSeries(headV)
        val (slope2, intercept2) = fitLinearSeries(tailV)

        val v1 = slope1 * targetT + intercept1
        val v2 = slope2 * targetT + intercept2

        val maxVal = maxOf(Math.abs(v1), Math.abs(v2), 1.0)
        val diffRatio = Math.abs(v2 - v1) / maxVal

        val finalSpeed = if (diffRatio < 0.30) {
            0.5 * (v1 + v2)
        } else {
            v2
        }

        return finalSpeed.toFloat().coerceAtLeast(0f)
    }

    fun computeLineMetrics(): LineMetrics {
        val points = buffer.toList()
        if (points.isEmpty()) {
            return LineMetrics(speedKmh = 0f, trajectoryBearing = 0f, projectedDistanceMeters = 0f)
        }

        val ref = points.first()
        val last = points.last()
        val refTime = ref.time
        val targetT = (last.time - refTime) / 1000.0

        if (points.size < 3) {
            val speed = if (last.hasSpeed()) last.speed * 3.6f else 0f
            return LineMetrics(
                speedKmh = speed,
                trajectoryBearing = last.bearing,
                projectedDistanceMeters = ref.distanceTo(last),
                projectedLocation = last
            )
        }

        val radLat = Math.toRadians(ref.latitude)
        val metersPerDegLat = 111139.0
        val metersPerDegLon = 111139.0 * Math.cos(radLat)

        val seriesX = points.map { SeriesPoint((it.time - refTime) / 1000.0, (it.longitude - ref.longitude) * metersPerDegLon) }
        val seriesY = points.map { SeriesPoint((it.time - refTime) / 1000.0, (it.latitude - ref.latitude) * metersPerDegLat) }

        val projX: Double
        val projY: Double
        val trajectoryBearing: Float
        val avgSpeedKmh = computeOlsSpeedFromGps(points, refTime, targetT)

        if (points.size < 6) {
            val (slopeX, interceptX) = fitLinearSeries(seriesX)
            val (slopeY, interceptY) = fitLinearSeries(seriesY)

            projX = slopeX * targetT + interceptX
            projY = slopeY * targetT + interceptY

            var az = (90.0 - Math.toDegrees(Math.atan2(slopeY, slopeX))).toFloat()
            if (az < 0f) az += 360f
            if (az >= 360f) az -= 360f
            trajectoryBearing = if (Math.hypot(slopeX, slopeY) > 0.1) az else last.bearing
        } else {
            val mid = points.size / 2
            val headX = seriesX.take(mid)
            val tailX = seriesX.takeLast(seriesX.size - mid)
            val headY = seriesY.take(mid)
            val tailY = seriesY.takeLast(seriesY.size - mid)

            val (slopeX1, interceptX1) = fitLinearSeries(headX)
            val (slopeX2, interceptX2) = fitLinearSeries(tailX)
            val (slopeY1, interceptY1) = fitLinearSeries(headY)
            val (slopeY2, interceptY2) = fitLinearSeries(tailY)

            var az1 = (90.0 - Math.toDegrees(Math.atan2(slopeY1, slopeX1))).toFloat()
            if (az1 < 0f) az1 += 360f
            if (az1 >= 360f) az1 -= 360f

            var az2 = (90.0 - Math.toDegrees(Math.atan2(slopeY2, slopeX2))).toFloat()
            if (az2 < 0f) az2 += 360f
            if (az2 >= 360f) az2 -= 360f

            val diffAngle = Math.abs(RadarMath.angleDifference(az1, az2))

            if (diffAngle < 30f) {
                val px1 = slopeX1 * targetT + interceptX1
                val px2 = slopeX2 * targetT + interceptX2
                val py1 = slopeY1 * targetT + interceptY1
                val py2 = slopeY2 * targetT + interceptY2

                projX = 0.5 * (px1 + px2)
                projY = 0.5 * (py1 + py2)

                var medianAz = az1 + 0.5f * RadarMath.angleDifference(az2, az1)
                if (medianAz < 0f) medianAz += 360f
                if (medianAz >= 360f) medianAz -= 360f
                trajectoryBearing = medianAz
            } else {
                while (buffer.size > 3) {
                    buffer.removeFirst()
                }
                projX = slopeX2 * targetT + interceptX2
                projY = slopeY2 * targetT + interceptY2
                trajectoryBearing = az2
            }
        }

        val projLat = ref.latitude + (projY / metersPerDegLat)
        val projLon = ref.longitude + (projX / metersPerDegLon)

        val projectedLoc = Location(last).apply {
            latitude = projLat
            longitude = projLon
        }

        val projDistMeters = ref.distanceTo(last)

        return LineMetrics(
            speedKmh = avgSpeedKmh,
            trajectoryBearing = trajectoryBearing,
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
    val trajectoryPoints: List<Location> = emptyList(),
    val isStationary: Boolean = false
)
