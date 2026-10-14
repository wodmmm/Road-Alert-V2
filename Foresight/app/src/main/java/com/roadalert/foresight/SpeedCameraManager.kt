package com.roadalert.foresight

import android.location.Location
import kotlin.math.*

enum class CameraType {
    FIXED,
    AVERAGE_SPEED,
    RED_LIGHT,
    MOBILE_HOTSPOT,
    VARIABLE_SPEED   // Smart motorway / variable gantry — no fixed limit to announce
}

data class SpeedCamera(
    val id: String,
    val lat: Double,
    val lon: Double,
    val speedLimitMph: Int,
    val type: CameraType,
    val headingDeg: Float? = null // Facing direction if known
)

class SpeedCameraManager {

    // In-memory spatial list of cameras (loaded from local assets/cache)
    private val cameras = mutableListOf<SpeedCamera>()
    private val alertedCameras = mutableSetOf<String>()

    fun loadCameras(cameraList: List<SpeedCamera>) {
        cameras.clear()
        cameras.addAll(cameraList)
    }

    /**
     * Evaluates current vehicle position against known cameras.
     * Returns a camera if alert criteria met (15-second TTTC with 100m fallback + bearing check).
     */
    fun evaluate(currentLoc: Location): Pair<SpeedCamera, Double>? {
        val speedMps = if (currentLoc.hasSpeed()) currentLoc.speed.toDouble() else 0.0
        // Alert distance = max(100m, speed * 15 seconds)
        val alertThresholdMeters = max(100.0, speedMps * 15.0)

        for (cam in cameras) {
            val dist = calculateDistance(currentLoc.latitude, currentLoc.longitude, cam.lat, cam.lon)

            if (dist <= alertThresholdMeters && !alertedCameras.contains(cam.id)) {
                // Direction check: verify camera is ahead within 60 degrees of current heading
                val bearingToCam = calculateBearing(currentLoc.latitude, currentLoc.longitude, cam.lat, cam.lon)
                val headingDelta = abs(bearingDifference(currentLoc.bearing, bearingToCam.toFloat()))

                if (headingDelta <= 60.0f) {
                    // Opposite carriageway check: If camera has an explicit enforcement heading in OSM,
                    // ensure our vehicle heading aligns with the camera's enforcement direction (within ±60°).
                    // If traveling opposite (e.g. heading delta > 90°), ignore camera.
                    val matchesCameraDirection = if (cam.headingDeg != null) {
                        val cameraFacingDelta = abs(bearingDifference(currentLoc.bearing, cam.headingDeg))
                        cameraFacingDelta <= 60.0f
                    } else {
                        true
                    }

                    if (matchesCameraDirection) {
                        alertedCameras.add(cam.id)
                        return Pair(cam, dist)
                    }
                }
            } else if (dist > alertThresholdMeters * 2) {
                // Reset alert status once driven far past
                alertedCameras.remove(cam.id)
            }
        }
        return null
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    private fun bearingDifference(b1: Float, b2: Float): Float {
        var diff = (b2 - b1) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }
}
