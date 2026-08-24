package com.example.radardetector.math

import android.location.Location
import com.example.radardetector.util.LocationUtils
import kotlin.math.abs

object RadarMath {

    fun angleDifference(bearing1: Float, bearing2: Float): Float {
        var diff = (bearing1 - bearing2) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }

    private val distanceResults = object : ThreadLocal<FloatArray>() {
        override fun initialValue(): FloatArray = FloatArray(1)
    }

    private val distanceBearingResults = object : ThreadLocal<FloatArray>() {
        override fun initialValue(): FloatArray = FloatArray(2)
    }

    /**
     * Calculates geodesic distance in meters between car and camera coordinates without allocating Location objects.
     */
    fun calculateDistance(carLocation: Location, cameraLat: Double, cameraLon: Double): Float {
        val results = distanceResults.get() ?: FloatArray(1)
        Location.distanceBetween(carLocation.latitude, carLocation.longitude, cameraLat, cameraLon, results)
        return results[0]
    }

    /**
     * Calculates distance (meters) and initial bearing (degrees) simultaneously without object allocations.
     * results[0] = distance in meters
     * results[1] = initial bearing in degrees
     */
    fun calculateDistanceAndBearing(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        outResults: FloatArray? = null
    ): FloatArray {
        val res = outResults ?: (distanceBearingResults.get() ?: FloatArray(2))
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, res)
        return res
    }

    /**
     * Calculates beep delay based on distance in the alert zone.
     * Approaching (300m -> 0m):
     * 200-300m: 2000 ms (2.0s)
     * 100-200m: 1000 ms (1.0s)
     * 0-100m:    500 ms (0.5s)
     * Departing (0m -> 100m):
     * 0-100m:   2000 ms (2.0s)
     * > 100m: ignored (no alert)
     */
    fun calculateBeepDelay(distanceMeters: Float, isDeparting: Boolean = false): Long {
        val dist = abs(distanceMeters)
        return if (!isDeparting) {
            when {
                dist <= 100f -> 500L
                dist <= 200f -> 1000L
                else -> 2000L
            }
        } else {
            2000L
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
        olsSpeedKmh: Float = 0f,
        isHighSpeedMode: Boolean = false,
        isGpsDisabled: Boolean = false,
        isDeepSleep: Boolean = false,
        isMotionSensorActive: Boolean = false,
        notificationOverride: String? = null
    ): ProcessedLocationMetrics {
        val isAccuracyWeak = location.hasAccuracy() && location.accuracy > 100f
        val isAccGood = location.hasAccuracy() && location.accuracy <= 15f
        val isSearchingGps = (notificationOverride == "Searching for GPS...") || (location.latitude == 0.0 && location.longitude == 0.0 && !isGpsDisabled && !isDeepSleep)

        val radarState = when {
            isGpsDisabled -> RadarState.GPS_DISABLED
            isDeepSleep -> RadarState.DEEP_SLEEP
            isSearchingGps -> RadarState.SEARCHING_GPS
            isAccuracyWeak -> RadarState.WEAK_GPS
            isStationary || speedKmh <= 3.0f -> RadarState.STOPPED
            speedKmh < 30.0f -> RadarState.LOW_SPEED
            isAccGood -> RadarState.ACCURATE_SPEED
            else -> RadarState.REGULAR_SPEED
        }

        val accInt = if (location.hasAccuracy()) location.accuracy.toInt() else 0

        val gpsStatusStr = when (radarState) {
            RadarState.GPS_DISABLED -> "GPS: DISABLED IN SETTINGS"
            RadarState.SEARCHING_GPS -> "GPS: SEARCHING SATELLITES"
            RadarState.DEEP_SLEEP -> if (isMotionSensorActive) "GPS: DEEP SLEEP [MOTION SENSOR]" else "GPS: DEEP SLEEP [ACCELEROMETER]"
            RadarState.WEAK_GPS -> "GPS: WEAK (>100m [${accInt}m])"
            else -> if (location.hasAccuracy()) "GPS: OK (±${accInt}m)" else "GPS: ACTIVE"
        }

        val loadResult = ramCacheOverride ?: load10x10Cameras(dbHelper, location.latitude, location.longitude)
        val defaultNotif = when (radarState) {
            RadarState.GPS_DISABLED -> radarState.baseNotificationText
            RadarState.SEARCHING_GPS -> radarState.baseNotificationText
            RadarState.DEEP_SLEEP -> "Deep Sleep: Stationed (>3m). ${if (isMotionSensorActive) "Motion sensor" else "Accelerometer"} active."
            RadarState.WEAK_GPS -> "Weak GPS signal (>100m [${accInt}m])"
            else -> "Active. Cameras: ${loadResult.cameras.size} in 10x10km / ${loadResult.totalInDb} total in DB"
        }
        val notifText = notificationOverride ?: defaultNotif
        val continuousThresh = if (isHighSpeedMode || speedKmh > 70f) 100f else 50f

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
            notificationText = notifText,
            isGpsDisabled = isGpsDisabled,
            isDeepSleep = isDeepSleep,
            radarState = radarState,
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

        // Add to buffer no more than once per second, with protection against timestamp jumps
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
            LocationUtils.calculateSpeedKmh(first, last, dtSec)
        } else 0f

        val effectiveSpeed = maxOf(instantSpeed, derivedSpeed)
        val isStationary = effectiveSpeed < 15.0f || (buffer.size >= 2 && buffer.first().distanceTo(buffer.last()) < 5.0f)

        if (isStationary && !isHighSpeedOver300) {
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

        if (hasHighAcc || isHighSpeedOver300) {
            // Truncate to the last 3 points for high accuracy or speed > 300 km/h
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

        // 2. If accuracy is 15m - 100m, check for zigzags on the single buffer
        if (buffer.size >= 3) {
            val pts = buffer.toList()
            val lastPt = pts.last()
            // Find maximum distance from the last point to any previous point in the buffer
            val distExtreme = pts.subList(0, pts.size - 1).maxOf { it.distanceTo(lastPt) }

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
        val isLmStationary = lm.speedKmh < 15.0f
        return TrajectoryResult(
            isValid = true,
            isAccuracyWeak = false,
            points = buffer.toList(),
            averageSpeedKmh = if (isLmStationary) 0f else lm.speedKmh,
            trajectoryBearing = lm.trajectoryBearing,
            projectedDistanceMeters = lm.projectedDistanceMeters,
            isStationary = isLmStationary,
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
            val derivedSpeed = LocationUtils.calculateSpeedKmh(ref, last, dtSec)
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

enum class RadarState(
    val stateName: String,
    val baseNotificationText: String,
    val mapSpeedText: String,
    val mapSubLabelText: String,
    val mapColorHex: String
) {
    GPS_DISABLED(
        stateName = "GPS_DISABLED",
        baseNotificationText = "GPS is Disabled in System Settings",
        mapSpeedText = "GPS off",
        mapSubLabelText = "Please enable GPS",
        mapColorHex = "#FF1744"
    ),
    SEARCHING_GPS(
        stateName = "SEARCHING_GPS",
        baseNotificationText = "Searching for GPS...",
        mapSpeedText = "Waiting GPS",
        mapSubLabelText = "Searching satellites...",
        mapColorHex = "#FF1744"
    ),
    WEAK_GPS(
        stateName = "WEAK_GPS",
        baseNotificationText = "Weak GPS signal (>100m)",
        mapSpeedText = "0",
        mapSubLabelText = "GPS bad",
        mapColorHex = "#FF9100"
    ),
    DEEP_SLEEP(
        stateName = "DEEP_SLEEP",
        baseNotificationText = "Deep Sleep: Stationed (>3m). Motion sensor active.",
        mapSpeedText = "0",
        mapSubLabelText = "Deep sleep",
        mapColorHex = "#FF9100"
    ),
    STOPPED(
        stateName = "STOPPED",
        baseNotificationText = "Active.",
        mapSpeedText = "0",
        mapSubLabelText = "Stopped",
        mapColorHex = "#FFFFFF"
    ),
    LOW_SPEED(
        stateName = "LOW_SPEED",
        baseNotificationText = "Active.",
        mapSpeedText = "",
        mapSubLabelText = "LOW speed",
        mapColorHex = "#FFFFFF"
    ),
    ACCURATE_SPEED(
        stateName = "ACCURATE_SPEED",
        baseNotificationText = "Active.",
        mapSpeedText = "",
        mapSubLabelText = "GPS good",
        mapColorHex = "#00E5FF"
    ),
    REGULAR_SPEED(
        stateName = "REGULAR_SPEED",
        baseNotificationText = "Active.",
        mapSpeedText = "",
        mapSubLabelText = "",
        mapColorHex = "#00E676"
    )
}

data class ProcessedLocationMetrics(
    val location: Location,
    val speedKmh: Float,
    val isAccuracyWeak: Boolean,
    val gpsStatusStr: String,
    val notificationText: String = "",
    val isGpsDisabled: Boolean = false,
    val isDeepSleep: Boolean = false,
    val radarState: RadarState = RadarState.SEARCHING_GPS,
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
