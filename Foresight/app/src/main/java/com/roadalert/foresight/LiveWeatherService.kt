package com.roadalert.foresight

import android.location.Location
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Periodically fetches live, anonymous regional road weather alerts
 * using Open-Meteo's open public API (completely free, no API keys, zero user tracking).
 */
class LiveWeatherService {

    interface Callback {
        fun onWeatherZonesUpdated(zones: List<WeatherHazardZone>)
    }

    fun fetchLiveWeather(loc: Location, callback: Callback) {
        Thread {
            try {
                // Query current weather & hourly forecast for regional area (rounded to 1 decimal for privacy)
                val latApprox = (loc.latitude * 10.0).roundToInt() / 10.0
                val lonApprox = (loc.longitude * 10.0).roundToInt() / 10.0

                val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$latApprox&longitude=$lonApprox&current=temperature_2m,precipitation,wind_gusts_10m,weather_code&hourly=temperature_2m,precipitation_probability&forecast_days=1"
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    val current = json.optJSONObject("current")

                    val zones = mutableListOf<WeatherHazardZone>()

                    if (current != null) {
                        val temp = current.optDouble("temperature_2m", 10.0)
                        val precip = current.optDouble("precipitation", 0.0)
                        val gusts = current.optDouble("wind_gusts_10m", 0.0) // km/h
                        val code = current.optInt("weather_code", 0)

                        // 1. Black Ice / Sub-zero Surface Hazard (<= 2°C)
                        if (temp <= 2.0) {
                            zones.add(
                                WeatherHazardZone(
                                    id = "live-freeze-alert",
                                    condition = WeatherCondition.FREEZING_ICE,
                                    severity = if (temp <= 0.0) WeatherSeverity.SEVERE else WeatherSeverity.WARNING,
                                    description = "Freezing road temperatures ($temp°C)",
                                    spokenWarning = "Warning: near freezing road conditions detected. Risk of surface ice.",
                                    lat = loc.latitude,
                                    lon = loc.longitude,
                                    radiusMeters = 8000.0
                                )
                            )
                        }

                        // 2. High Crosswinds Hazard (> 65 km/h / ~40 mph)
                        if (gusts >= 65.0) {
                            val gustsMph = (gusts * 0.621371).roundToInt()
                            zones.add(
                                WeatherHazardZone(
                                    id = "live-wind-alert",
                                    condition = WeatherCondition.HIGH_CROSSWINDS,
                                    severity = WeatherSeverity.WARNING,
                                    description = "Severe wind gusts ($gustsMph mph)",
                                    spokenWarning = "Caution: severe wind gusts of $gustsMph miles per hour reported in this area. Drive with care.",
                                    lat = loc.latitude,
                                    lon = loc.longitude,
                                    radiusMeters = 10000.0
                                )
                            )
                        }

                        // 3. Dense Fog (WMO weather codes 45, 48)
                        if (code == 45 || code == 48) {
                            zones.add(
                                WeatherHazardZone(
                                    id = "live-fog-alert",
                                    condition = WeatherCondition.DENSE_FOG,
                                    severity = WeatherSeverity.WARNING,
                                    description = "Dense fog reducing visibility",
                                    spokenWarning = "Warning: dense fog reported in this area. Visibility severely reduced.",
                                    lat = loc.latitude,
                                    lon = loc.longitude,
                                    radiusMeters = 8000.0
                                )
                            )
                        }

                        // 4. Heavy Standing Water / Downpour (> 5 mm/hr)
                        if (precip >= 5.0) {
                            zones.add(
                                WeatherHazardZone(
                                    id = "live-rain-alert",
                                    condition = WeatherCondition.HEAVY_RAIN_SPRAY,
                                    severity = WeatherSeverity.WARNING,
                                    description = "Heavy rainfall causing road spray and standing water",
                                    spokenWarning = "Caution: heavy surface spray and standing water reported ahead.",
                                    lat = loc.latitude,
                                    lon = loc.longitude,
                                    radiusMeters = 6000.0
                                )
                            )
                        }
                    }

                    callback.onWeatherZonesUpdated(zones)
                }
            } catch (e: Exception) {
                Log.e("LiveWeatherService", "Failed to fetch live weather", e)
            }
        }.start()
    }
}
