package com.roadalert.foresight

import android.location.Location

enum class WeatherSeverity {
    ADVISORY,   // e.g. Light rain, damp surface
    WARNING,    // e.g. Freezing conditions, dense fog, standing water
    SEVERE      // e.g. Black ice, severe storm gusts
}

enum class WeatherCondition {
    FREEZING_ICE,       // Road surface freeze hazard
    DENSE_FOG,          // Visibility hazard
    HEAVY_RAIN_SPRAY,   // Standing water / aquaplaning
    HIGH_CROSSWINDS     // Bridge / high vehicle stability risk
}

data class WeatherHazardZone(
    val id: String,
    val condition: WeatherCondition,
    val severity: WeatherSeverity,
    val description: String,        // e.g. "Freezing road temperatures and risk of black ice"
    val spokenWarning: String,      // e.g. "Caution: freezing road temperatures and risk of surface ice"
    val lat: Double,
    val lon: Double,
    val radiusMeters: Double,       // Geo-fence radius of affected area
    var alerted: Boolean = false
)

class WeatherAlertManager {

    private val activeWeatherZones = mutableListOf<WeatherHazardZone>()

    fun updateZones(zones: List<WeatherHazardZone>) {
        val alertedIds = activeWeatherZones.filter { it.alerted }.map { it.id }.toSet()
        activeWeatherZones.clear()
        for (z in zones) {
            z.alerted = alertedIds.contains(z.id)
            activeWeatherZones.add(z)
        }
    }

    /**
     * Checks if current vehicle position enters any active hazard weather zone.
     * Returns an unalerted zone if matched.
     */
    fun evaluate(currentLoc: Location): WeatherHazardZone? {
        for (zone in activeWeatherZones) {
            val dist = calculateDistance(currentLoc.latitude, currentLoc.longitude, zone.lat, zone.lon)
            if (dist <= zone.radiusMeters) {
                if (!zone.alerted) {
                    zone.alerted = true
                    return zone
                }
            } else if (dist > zone.radiusMeters * 1.5) {
                // Reset when vehicle drives well outside the zone
                zone.alerted = false
            }
        }
        return null
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
}
