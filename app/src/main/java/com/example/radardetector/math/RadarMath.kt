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
     * 300-200m: 2000 ms
     * 200-100m: 1500 ms
     * 100-50m:  1000 ms
     * 50-0m:    500 ms
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculateBeepDelay(distanceMeters: Float, speedKmh: Float = 0f): Long {
        val dist = abs(distanceMeters)
        return when {
            dist <= 50f -> 500L
            dist <= 100f -> 1000L
            dist <= 200f -> 1500L
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
     * Unified speed calculation with smoothing filter (10 km/h delta threshold).
     */
    fun calculateEffectiveSpeed(rawSpeedKmh: Float, currentEffectiveSpeedKmh: Float): Float {
        return rawSpeedKmh
    }

    /**
     * Trajectory filter maintaining a 10-point sliding buffer with linear approximation,
     * forward-projection validation, and auto-reset after 3 consecutive rejections (turn detection).
     */
    class TrajectoryFilter(
        private val maxBufferSize: Int = 10,
        private val maxConsecutiveRejections: Int = 3
    ) {
        private val buffer = java.util.ArrayDeque<Location>()
        private var consecutiveRejections = 0

        @Synchronized
        fun reset() {
            buffer.clear()
            consecutiveRejections = 0
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

            if (buffer.isEmpty()) {
                buffer.addLast(location)
                consecutiveRejections = 0
                return TrajectoryResult(
                    isValid = true,
                    isAccuracyWeak = false,
                    points = buffer.toList(),
                    averageSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f,
                    trajectoryBearing = location.bearing,
                    projectedDistanceMeters = 0f
                )
            }

            val isForward = if (buffer.size >= 2) {
                checkForwardProjection(location)
            } else {
                val prev = buffer.last
                location.time > prev.time && prev.distanceTo(location) > 0.1f
            }

            if (isForward) {
                consecutiveRejections = 0
                if (buffer.size >= maxBufferSize) {
                    buffer.removeFirst()
                }
                buffer.addLast(location)
            } else {
                consecutiveRejections++
                if (consecutiveRejections >= maxConsecutiveRejections) {
                    // Turn detected! Auto-reset buffer and start fresh trajectory from current location
                    buffer.clear()
                    buffer.addLast(location)
                    consecutiveRejections = 0
                    return TrajectoryResult(
                        isValid = true,
                        isAccuracyWeak = false,
                        points = buffer.toList(),
                        averageSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f,
                        trajectoryBearing = location.bearing,
                        projectedDistanceMeters = 0f
                    )
                }
            }

            val lm = computeLineMetrics()
            return TrajectoryResult(
                isValid = isForward,
                isAccuracyWeak = false,
                points = buffer.toList(),
                averageSpeedKmh = lm.speedKmh,
                trajectoryBearing = lm.trajectoryBearing,
                projectedDistanceMeters = lm.projectedDistanceMeters
            )
        }

        private fun checkForwardProjection(candidate: Location): Boolean {
            val points = buffer.toList()
            if (points.isEmpty()) return true
            val ref = points.first()

            val radLat = Math.toRadians(ref.latitude)
            val metersPerDegLat = 111139.0
            val metersPerDegLon = 111139.0 * Math.cos(radLat)

            val n = points.size
            val xs = DoubleArray(n)
            val ys = DoubleArray(n)

            var sumX = 0.0
            var sumY = 0.0
            for (i in 0 until n) {
                xs[i] = (points[i].longitude - ref.longitude) * metersPerDegLon
                ys[i] = (points[i].latitude - ref.latitude) * metersPerDegLat
                sumX += xs[i]
                sumY += ys[i]
            }

            // Centroid (center of mass across all N points in buffer)
            val meanX = sumX / n
            val meanY = sumY / n

            // Calculate covariances across ALL points (Linear Least Squares / PCA)
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - meanX
                val dy = ys[i] - meanY
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }

            val ux: Double
            val uy: Double
            if (Math.abs(sxx) > 1e-4 || Math.abs(syy) > 1e-4 || Math.abs(sxy) > 1e-4) {
                // Angle of line of best fit through all N points
                val angle = 0.5 * Math.atan2(2.0 * sxy, sxx - syy)
                var vx = Math.cos(angle)
                var vy = Math.sin(angle)

                // Align vector orientation chronologically (from start towards latest point)
                val totalDx = xs.last() - xs.first()
                val totalDy = ys.last() - ys.first()
                if (vx * totalDx + vy * totalDy < 0) {
                    vx = -vx
                    vy = -vy
                }
                ux = vx
                uy = vy
            } else {
                val dxTotal = xs.last() - xs.first()
                val dyTotal = ys.last() - ys.first()
                val len = Math.hypot(dxTotal, dyTotal)
                if (len > 0.5) {
                    ux = dxTotal / len
                    uy = dyTotal / len
                } else {
                    return candidate.distanceTo(points.last()) > 0.1f
                }
            }

            val candX = (candidate.longitude - ref.longitude) * metersPerDegLon
            val candY = (candidate.latitude - ref.latitude) * metersPerDegLat
            val candProj = candX * ux + candY * uy
            val lastProj = xs.last() * ux + ys.last() * uy

            return candProj > lastProj + 0.01
        }

        fun computeLineMetrics(): LineMetrics {
            if (buffer.size < 2) {
                val single = buffer.lastOrNull()
                val speed = if (single != null && single.hasSpeed()) single.speed * 3.6f else 0f
                val bearing = single?.bearing ?: 0f
                return LineMetrics(speedKmh = speed, trajectoryBearing = bearing, projectedDistanceMeters = 0f)
            }

            val points = buffer.toList()
            val ref = points.first()

            val radLat = Math.toRadians(ref.latitude)
            val metersPerDegLat = 111139.0
            val metersPerDegLon = 111139.0 * Math.cos(radLat)

            val n = points.size
            val xs = DoubleArray(n)
            val ys = DoubleArray(n)

            var sumX = 0.0
            var sumY = 0.0
            for (i in 0 until n) {
                xs[i] = (points[i].longitude - ref.longitude) * metersPerDegLon
                ys[i] = (points[i].latitude - ref.latitude) * metersPerDegLat
                sumX += xs[i]
                sumY += ys[i]
            }

            val meanX = sumX / n
            val meanY = sumY / n

            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - meanX
                val dy = ys[i] - meanY
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }

            val ux: Double
            val uy: Double
            if (Math.abs(sxx) > 1e-4 || Math.abs(syy) > 1e-4 || Math.abs(sxy) > 1e-4) {
                val angle = 0.5 * Math.atan2(2.0 * sxy, sxx - syy)
                var vx = Math.cos(angle)
                var vy = Math.sin(angle)

                val totalDx = xs.last() - xs.first()
                val totalDy = ys.last() - ys.first()
                if (vx * totalDx + vy * totalDy < 0) {
                    vx = -vx
                    vy = -vy
                }
                ux = vx
                uy = vy
            } else {
                val dxTotal = xs.last() - xs.first()
                val dyTotal = ys.last() - ys.first()
                val len = Math.hypot(dxTotal, dyTotal)
                if (len > 0.5) {
                    ux = dxTotal / len
                    uy = dyTotal / len
                } else {
                    val single = buffer.last()
                    val speed = if (single.hasSpeed()) single.speed * 3.6f else 0f
                    return LineMetrics(speedKmh = speed, trajectoryBearing = single.bearing, projectedDistanceMeters = 0f)
                }
            }

            // 1. Azimuth from trend vector (ux=East, uy=North) in degrees (0..360)
            var azimuth = (90.0 - Math.toDegrees(Math.atan2(uy, ux))).toFloat()
            if (azimuth < 0f) azimuth += 360f
            if (azimuth >= 360f) azimuth -= 360f

            // 2. Projections of first and last points onto trend line
            val firstProj = xs.first() * ux + ys.first() * uy
            val lastProj = xs.last() * ux + ys.last() * uy
            val projDistMeters = (lastProj - firstProj).toFloat()

            // 3. Time delta and speed along line projection
            val timeS = (points.last().time - points.first().time) / 1000.0f
            val speedKmh = if (timeS > 0f && projDistMeters > 0f) {
                (projDistMeters / timeS) * 3.6f
            } else 0f

            return LineMetrics(
                speedKmh = speedKmh,
                trajectoryBearing = azimuth,
                projectedDistanceMeters = projDistMeters
            )
        }
    }

    /**
     * Checks if trajectory bearing matches camera direction within +-15 degrees.
     */
    fun isCameraDirectionMatched(trajectoryBearing: Float, cameraDir: Float?): Boolean {
        if (cameraDir == null) return true // Omnidirectional
        val diff = abs(angleDifference(trajectoryBearing, cameraDir))
        return diff <= 15f
    }

    /**
     * Single authoritative location evaluator function used identically by both
     * RadarForegroundService and RadarMapActivity to produce 100% unified metrics.
     */
    fun evaluateLocationData(
        location: Location,
        currentEffectiveSpeedKmh: Float,
        dbHelper: com.example.radardetector.db.DatabaseHelper,
        ramCacheOverride: CameraLoadResult? = null,
        trajectoryBearing: Float = location.bearing
    ): ProcessedLocationMetrics {
        val rawSpeedKmh = location.speed * 3.6f
        val effectiveSpeedKmh = calculateEffectiveSpeed(rawSpeedKmh, currentEffectiveSpeedKmh)
        val isAccuracyWeak = location.hasAccuracy() && location.accuracy > 100f

        val gpsStatusStr = if (isAccuracyWeak) {
            "GPS: WEAK (>100m [${location.accuracy.toInt()}m])"
        } else if (location.hasAccuracy()) {
            "GPS: OK (±${location.accuracy.toInt()}m)"
        } else {
            "GPS: ACTIVE"
        }

        val loadResult = ramCacheOverride ?: load10x10Cameras(dbHelper, location.latitude, location.longitude)
        val continuousThresh = if (effectiveSpeedKmh <= 60f) 50f else 100f

        var minDistToAnyCam = Float.MAX_VALUE
        var closestAlertCam: com.example.radardetector.db.Camera? = null
        var minAlertDist = Float.MAX_VALUE

        for (cam in loadResult.cameras) {
            val dist = calculateDistance(location, cam.lat, cam.lon)
            if (dist < minDistToAnyCam) minDistToAnyCam = dist

            if (dist <= 300f && isCameraDirectionMatched(trajectoryBearing, cam.dir)) {
                if (dist < minAlertDist) {
                    minAlertDist = dist
                    closestAlertCam = cam
                }
            }
        }

        val inRange3kmCount = loadResult.cameras.count { calculateDistance(location, it.lat, it.lon) <= 3000f }

        return ProcessedLocationMetrics(
            location = location,
            rawSpeedKmh = rawSpeedKmh,
            effectiveSpeedKmh = effectiveSpeedKmh,
            isAccuracyWeak = isAccuracyWeak,
            gpsStatusStr = gpsStatusStr,
            cameraLoadResult = loadResult,
            inRange3kmCount = inRange3kmCount,
            minDistToAnyCamera = minDistToAnyCam,
            closestAlertCamera = closestAlertCam,
            minDistanceToAlert = minAlertDist,
            continuousThreshold = continuousThresh
        )
    }
}

data class LineMetrics(
    val speedKmh: Float,
    val trajectoryBearing: Float,
    val projectedDistanceMeters: Float
)

data class TrajectoryResult(
    val isValid: Boolean,
    val isAccuracyWeak: Boolean,
    val points: List<Location>,
    val averageSpeedKmh: Float,
    val trajectoryBearing: Float,
    val projectedDistanceMeters: Float = 0f
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
    val rawSpeedKmh: Float,
    val effectiveSpeedKmh: Float,
    val isAccuracyWeak: Boolean,
    val gpsStatusStr: String,
    val cameraLoadResult: CameraLoadResult,
    val inRange3kmCount: Int,
    val minDistToAnyCamera: Float,
    val closestAlertCamera: com.example.radardetector.db.Camera?,
    val minDistanceToAlert: Float,
    val continuousThreshold: Float
)
