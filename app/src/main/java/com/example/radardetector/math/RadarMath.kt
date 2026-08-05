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

            // Always add incoming valid raw point to rawMotionBuffer (max 10 points)
            if (rawMotionBuffer.size >= maxBufferSize) {
                rawMotionBuffer.removeFirst()
            }
            rawMotionBuffer.addLast(location)

            // Distance drift check BEFORE vector projection: Check motion distance across raw points buffer (>= 3 points)
            if (rawMotionBuffer.size >= 3) {
                val firstPt = rawMotionBuffer.first
                val lastPt = rawMotionBuffer.last
                val distMeters = firstPt.distanceTo(lastPt)
                val firstAcc = if (firstPt.hasAccuracy()) firstPt.accuracy else 10f
                val lastAcc = if (lastPt.hasAccuracy()) lastPt.accuracy else 10f
                val doubleAccuracyThreshold = 2f * maxOf(firstAcc, lastAcc)

                if (distMeters <= doubleAccuracyThreshold) {
                    // Distance is within GPS drift noise (<= 2 * accuracy): speed set to 0, clear buffer to prevent phantom stationary tail points
                    buffer.clear()
                    return TrajectoryResult(
                        isValid = true,
                        isAccuracyWeak = false,
                        points = emptyList(),
                        averageSpeedKmh = 0f,
                        trajectoryBearing = 0f,
                        projectedDistanceMeters = 0f,
                        isStationary = true
                    )
                }
            }

            // Points are NOT rejected! Always add incoming point to buffer
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
                projectedDistanceMeters = lm.projectedDistanceMeters
            )
        }

        private data class SubLineFit(
            val ux: Double,
            val uy: Double,
            val azimuth: Float
        )

        private data class ActiveTrend(
            val ux: Double,
            val uy: Double,
            val azimuth: Float
        )

        private fun fitSubBufferLine(points: List<Location>, ref: Location): SubLineFit {
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
                    val bearing = points.last().bearing
                    val rad = Math.toRadians((90.0 - bearing).toDouble())
                    return SubLineFit(Math.cos(rad), Math.sin(rad), bearing)
                }
            }

            var azimuth = (90.0 - Math.toDegrees(Math.atan2(uy, ux))).toFloat()
            if (azimuth < 0f) azimuth += 360f
            if (azimuth >= 360f) azimuth -= 360f

            return SubLineFit(ux, uy, azimuth)
        }

        private fun computeActiveTrendLine(): ActiveTrend {
            val points = buffer.toList()
            if (points.isEmpty()) return ActiveTrend(1.0, 0.0, 0f)
            val ref = points.first()

            if (points.size < 10) {
                val subPoints = points.takeLast(points.size)
                val fit = fitSubBufferLine(subPoints, ref)
                return ActiveTrend(fit.ux, fit.uy, fit.azimuth)
            } else {
                val headPoints = points.take(5)
                val tailPoints = points.takeLast(5)
                val fit1 = fitSubBufferLine(headPoints, ref)
                val fit2 = fitSubBufferLine(tailPoints, ref)

                val diffAngle = Math.abs(angleDifference(fit1.azimuth, fit2.azimuth))
                if (diffAngle < 30f) {
                    var medianAzimuth = fit1.azimuth + 0.5f * angleDifference(fit2.azimuth, fit1.azimuth)
                    if (medianAzimuth < 0f) medianAzimuth += 360f
                    if (medianAzimuth >= 360f) medianAzimuth -= 360f

                    val rad = Math.toRadians((90.0 - medianAzimuth).toDouble())
                    return ActiveTrend(Math.cos(rad), Math.sin(rad), medianAzimuth)
                } else {
                    // Turn detected (>= 30 deg)! Truncate buffer to keep ONLY the last 3 points, return tail vector (fit2)
                    while (buffer.size > 3) {
                        buffer.removeFirst()
                    }
                    return ActiveTrend(fit2.ux, fit2.uy, fit2.azimuth)
                }
            }
        }

        fun computeLineMetrics(): LineMetrics {
            val points = buffer.toList()
            if (points.isEmpty()) {
                return LineMetrics(speedKmh = 0f, trajectoryBearing = 0f, projectedDistanceMeters = 0f)
            }

            val activeTrend = computeActiveTrendLine()

            // Calculate average speed from sensor speeds across all points in buffer
            val speedSum = points.sumOf { if (it.hasSpeed()) (it.speed * 3.6f).toDouble() else 0.0 }
            val avgSpeedKmh = (speedSum / points.size).toFloat()

            val ref = points.first()
            val last = points.last()
            val projDistMeters = ref.distanceTo(last)

            return LineMetrics(
                speedKmh = avgSpeedKmh,
                trajectoryBearing = activeTrend.azimuth,
                projectedDistanceMeters = projDistMeters
            )
        }
    }

    /**
     * Checks if trajectory bearing matches camera direction within +-30 degrees.
     */
    fun isCameraDirectionMatched(trajectoryBearing: Float, cameraDir: Float?): Boolean {
        if (cameraDir == null) return true // Omnidirectional
        val diff = abs(angleDifference(trajectoryBearing, cameraDir))
        return diff <= 30f
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
        trajectoryBearing: Float = location.bearing,
        trajectoryPoints: List<Location> = emptyList()
    ): ProcessedLocationMetrics {
        val effectiveSpeedKmh = currentEffectiveSpeedKmh
        val rawSpeedKmh = currentEffectiveSpeedKmh
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
            continuousThreshold = continuousThresh,
            trajectoryBearing = trajectoryBearing,
            trajectoryPoints = trajectoryPoints
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
    val projectedDistanceMeters: Float = 0f,
    val isStationary: Boolean = false
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
    val continuousThreshold: Float,
    val trajectoryBearing: Float = 0f,
    val trajectoryPoints: List<Location> = emptyList()
)
