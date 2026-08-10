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
        val deltaLat = 0.045
        val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val deltaLon = (0.045 / cosLat).coerceIn(0.045, 0.5)
        return doubleArrayOf(lat - deltaLat, lat + deltaLat, lon - deltaLon, lon + deltaLon)
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
        isStationary: Boolean = false,
        instantSpeedKmh: Float = 0f,
        olsSpeedKmh: Float = 0f
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
            isStationary = isStationary,
            instantSpeedKmh = instantSpeedKmh,
            olsSpeedKmh = olsSpeedKmh
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
    private var lastBufferPushTimeMs: Long = 0L

    @Synchronized
    fun reset() {
        buffer.clear()
        lastBufferPushTimeMs = 0L
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

        // Додаємо до буфера не частіше ніж раз на 1 секунду, із захистом від часових стрибків
        val locTime = if (location.time > 0L) location.time else System.currentTimeMillis()
        val timeDiff = locTime - lastBufferPushTimeMs
        if (timeDiff >= 1000L || timeDiff <= 0L || buffer.isEmpty()) {
            if (buffer.size >= maxBufferSize) {
                buffer.removeFirst()
            }
            buffer.addLast(location)
            lastBufferPushTimeMs = locTime
        }

        val instantSpeed = if (location.hasSpeed() && location.speed > 0f) location.speed * 3.6f else 0f
        val isHighSpeedOver300 = instantSpeed > 300f
        val hasHighAcc = location.hasAccuracy() && location.accuracy <= 15f

        val derivedSpeed = if (buffer.size >= 2) {
            val first = buffer.first()
            val last = buffer.last()
            val dtSec = (last.time - first.time) / 1000.0
            if (dtSec > 0.2) (first.distanceTo(last) / dtSec * 3.6).toFloat() else 0f
        } else 0f

        val effectiveSpeed = maxOf(instantSpeed, derivedSpeed)

        if (hasHighAcc || isHighSpeedOver300) {
            // Усікаємо до 3 останніх точок для високої точності або швидкості > 300 км/год
            while (buffer.size > 3) {
                buffer.removeFirst()
            }

            return TrajectoryResult(
                isValid = true,
                isAccuracyWeak = false,
                points = buffer.toList(),
                averageSpeedKmh = effectiveSpeed,
                trajectoryBearing = location.bearing,
                projectedDistanceMeters = if (buffer.size >= 2) buffer.first().distanceTo(buffer.last()) else 0f,
                isStationary = false,
                projectedLocation = location
            )
        }

        // 2. Якщо точність 15м - 100м — перевіряємо зигзаги по єдиному буферу
        if (buffer.size >= 3) {
            val pts = buffer.toList()
            val firstPt = pts.first()
            val lastPt = pts.last()
            val distExtreme = firstPt.distanceTo(lastPt)

            var distSumNeighboring = 0f
            for (i in 0 until pts.size - 1) {
                distSumNeighboring += pts[i].distanceTo(pts[i + 1])
            }

            val ratio = if (distSumNeighboring > 0f) distExtreme / distSumNeighboring else 0f
            val maxStationaryDistThreshold = if (lastPt.hasAccuracy() && lastPt.accuracy > 0f) maxOf(2f * lastPt.accuracy, 15f) else 15f
            val isStationaryCheck = (ratio <= 0.5f) && (distExtreme < maxStationaryDistThreshold)

            if (isStationaryCheck) {
                return TrajectoryResult(
                    isValid = true,
                    isAccuracyWeak = false,
                    points = buffer.toList(),
                    averageSpeedKmh = 0f,
                    trajectoryBearing = 0f,
                    projectedDistanceMeters = 0f,
                    isStationary = true,
                    projectedLocation = location
                )
            }
        }

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

    @Synchronized
    fun computeLineMetrics(): LineMetrics {
        val points = buffer.toList()
        if (points.isEmpty()) {
            return LineMetrics(speedKmh = 0f, trajectoryBearing = 0f, projectedDistanceMeters = 0f)
        }

        val ref = points.first()
        val last = points.last()
        if (points.size < 3) {
            val rawSpeed = if (last.hasSpeed() && last.speed > 0f) last.speed * 3.6f else 0f
            val dtSec = (last.time - ref.time) / 1000.0
            val derivedSpeed = if (dtSec > 0.2) (ref.distanceTo(last) / dtSec * 3.6).toFloat() else 0f
            val speed = maxOf(rawSpeed, derivedSpeed)
            return LineMetrics(
                speedKmh = speed,
                trajectoryBearing = last.bearing,
                projectedDistanceMeters = ref.distanceTo(last),
                projectedLocation = last
            )
        }

        val refTime = ref.time
        val targetT = (last.time - refTime) / 1000.0

        val radLat = Math.toRadians(ref.latitude)
        val metersPerDegLat = 111139.0
        val metersPerDegLon = 111139.0 * Math.cos(radLat)

        val seriesX = points.map { SeriesPoint((it.time - refTime) / 1000.0, (it.longitude - ref.longitude) * metersPerDegLon) }
        val seriesY = points.map { SeriesPoint((it.time - refTime) / 1000.0, (it.latitude - ref.latitude) * metersPerDegLat) }

        val projX: Double
        val projY: Double
        val avgSpeedKmh: Float
        val trajectoryBearing: Float

        val PROJECTION_LOOKAHEAD_SEC = 2.0

        if (points.size < 6) {
            val (slopeX, interceptX) = fitLinearSeries(seriesX)
            val (slopeY, interceptY) = fitLinearSeries(seriesY)

            avgSpeedKmh = (Math.hypot(slopeX, slopeY) * 3.6).toFloat().coerceAtLeast(0f)
            val lookaheadSec = if (avgSpeedKmh >= 30.0f) PROJECTION_LOOKAHEAD_SEC else 0.0
            val futureT = targetT + lookaheadSec

            projX = slopeX * futureT + interceptX
            projY = slopeY * futureT + interceptY

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
                val speed1 = Math.hypot(slopeX1, slopeY1)
                val speed2 = Math.hypot(slopeX2, slopeY2)
                avgSpeedKmh = (0.5 * (speed1 + speed2) * 3.6).toFloat().coerceAtLeast(0f)

                val lookaheadSec = if (avgSpeedKmh >= 30.0f) PROJECTION_LOOKAHEAD_SEC else 0.0
                val futureT = targetT + lookaheadSec

                val px1 = slopeX1 * futureT + interceptX1
                val px2 = slopeX2 * futureT + interceptX2
                val py1 = slopeY1 * futureT + interceptY1
                val py2 = slopeY2 * futureT + interceptY2

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
                avgSpeedKmh = (Math.hypot(slopeX2, slopeY2) * 3.6).toFloat().coerceAtLeast(0f)

                val lookaheadSec = if (avgSpeedKmh >= 30.0f) PROJECTION_LOOKAHEAD_SEC else 0.0
                val futureT = targetT + lookaheadSec

                projX = slopeX2 * futureT + interceptX2
                projY = slopeY2 * futureT + interceptY2
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

        if (avgSpeedKmh > 300f) {
            while (buffer.size > 3) {
                buffer.removeFirst()
            }
        }

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
    val isStationary: Boolean = false,
    val instantSpeedKmh: Float = 0f,
    val olsSpeedKmh: Float = 0f
)
