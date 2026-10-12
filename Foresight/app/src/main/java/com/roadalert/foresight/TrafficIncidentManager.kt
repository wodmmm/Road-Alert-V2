package com.roadalert.foresight

import android.location.Location
import kotlin.math.*

enum class RoadClassification {
    MOTORWAY,   // M roads (e.g. M5, M4)
    A_ROAD,     // Major A roads (e.g. A38, A361)
    B_ROAD,     // B roads
    OTHER
}

enum class IncidentType {
    LANE_CLOSURE,
    ACCIDENT,
    ROADWORKS,
    CONGESTION,
    HAZARD
}

data class TrafficIncident(
    val id: String,
    val roadName: String,               // e.g. "M5", "A361"
    val roadType: RoadClassification,
    val direction: String,              // e.g. "Southbound", "Northbound", "Eastbound"
    val locationDescription: String,    // e.g. "near Junction 21", "near Weston-super-Mare"
    val summary: String,                // e.g. "Lane closure", "Accident causing 30 minute delays"
    val lat: Double,
    val lon: Double,
    val headingDeg: Float? = null,      // Expected travel direction along the road
    var stage: Int = 0                  // 0 = unalerted, 1 = 10km motorway alert issued, 2 = 2km alert issued
)

class TrafficIncidentManager {

    private val incidents = mutableListOf<TrafficIncident>()

    fun updateIncidents(newList: List<TrafficIncident>) {
        val existingStages = incidents.associate { it.id to it.stage }
        incidents.clear()
        for (inc in newList) {
            inc.stage = existingStages[inc.id] ?: 0
            incidents.add(inc)
        }
    }

    /**
     * Evaluates current vehicle position against active traffic incidents.
     * Returns an incident and its alert tier if an alert should be triggered:
     * - Tier 1: 10km Motorway alert
     * - Tier 2: 2km Final alert (Motorways & A/B roads)
     */
    fun evaluate(currentLoc: Location): Pair<TrafficIncident, Int>? {
        for (inc in incidents) {
            val dist = calculateDistance(currentLoc.latitude, currentLoc.longitude, inc.lat, inc.lon)

            // Bearing cone: wider for fast roads (motorway/A-road), tighter for local streets.
            // Tight cone on local roads prevents parallel-street false positives.
            val bearingToIncident = calculateBearing(currentLoc.latitude, currentLoc.longitude, inc.lat, inc.lon)
            val headingDelta = abs(bearingDifference(currentLoc.bearing, bearingToIncident.toFloat()))
            val bearingCone = when (inc.roadType) {
                RoadClassification.MOTORWAY -> 65.0f
                RoadClassification.A_ROAD   -> 55.0f
                else                        -> 35.0f  // B-road / local — must be directly ahead
            }
            val isAhead = headingDelta <= bearingCone

            if (!isAhead) continue

            when (inc.roadType) {
                RoadClassification.MOTORWAY -> {
                    // Motorway 10km primary alert
                    if (dist <= 10000.0 && dist > 3000.0 && inc.stage == 0) {
                        inc.stage = 1
                        return Pair(inc, 1) // Primary warning
                    }
                    // Motorway 2km secondary alert
                    if (dist <= 2000.0 && inc.stage <= 1) {
                        inc.stage = 2
                        return Pair(inc, 2) // Final warning
                    }
                }
                RoadClassification.A_ROAD -> {
                    // A-roads 2km alert
                    if (dist <= 2000.0 && inc.stage == 0) {
                        inc.stage = 2
                        return Pair(inc, 2)
                    }
                }
                RoadClassification.B_ROAD, RoadClassification.OTHER -> {
                    // Local roads / residential / B-roads: Alert at 800m
                    if (dist <= 800.0 && inc.stage == 0) {
                        inc.stage = 3
                        return Pair(inc, 3)
                    }
                }
            }

            // Reset stage if vehicle has moved far away
            if (dist > 15000.0) {
                inc.stage = 0
            }
        }
        return null
    }

    /**
     * Generates clear spoken text for TTS.
     */
    fun buildSpokenAlert(incident: TrafficIncident, tier: Int): String {
        val distanceDesc = when (tier) {
            1 -> "6 miles ahead"
            2 -> "1 mile ahead"
            3 -> "500 yards ahead"
            else -> "ahead"
        }
        val dirPart = if (incident.direction.isNotBlank()) " ${incident.direction}" else ""
        return "${incident.summary} on ${incident.roadName}$dirPart, $distanceDesc."
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
