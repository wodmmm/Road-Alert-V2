package com.roadalert.foresight

import android.location.Location
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Periodically fetches live, anonymous regional road incidents & closures
 * from UK National Highways Open Data / Traffic England RSS & JSON feeds.
 */
class LiveTrafficService {

    interface Callback {
        fun onIncidentsUpdated(incidents: List<TrafficIncident>)
    }

    /**
     * Polls National Highways RSS/JSON feed for active closures & unplanned events.
     * Keeps calculations on-device.
     */
    fun fetchLiveIncidents(loc: Location, callback: Callback) {
        Thread {
            try {
                // National Highways open data unplanned events feed
                val urlStr = "https://m.highwaysengland.co.uk/feeds/rss/UnplannedEvents.xml"
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "ForesightRoadAlert/1.0")

                if (conn.responseCode == 200) {
                    val xml = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = parseRssIncidents(xml)
                    if (parsed.isNotEmpty()) {
                        callback.onIncidentsUpdated(parsed)
                    }
                }
            } catch (e: Exception) {
                Log.e("LiveTrafficService", "Failed to fetch National Highways live feed", e)
            }
        }.start()
    }

    private fun parseRssIncidents(xml: String): List<TrafficIncident> {
        val list = mutableListOf<TrafficIncident>()
        try {
            // Basic lightweight XML parsing for <item> blocks
            val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            val titleRegex = Regex("<title>(.*?)</title>")
            val descRegex = Regex("<description>(.*?)</description>")
            val pointRegex = Regex("<georss:point>(.*?)</georss:point>")

            var count = 0
            for (match in itemRegex.findAll(xml)) {
                val itemBlock = match.groupValues[1]
                val title = titleRegex.find(itemBlock)?.groupValues?.get(1) ?: continue
                val desc = descRegex.find(itemBlock)?.groupValues?.get(1) ?: ""
                val point = pointRegex.find(itemBlock)?.groupValues?.get(1)

                if (point != null) {
                    val parts = point.trim().split(" ")
                    if (parts.size == 2) {
                        val lat = parts[0].toDoubleOrNull() ?: continue
                        val lon = parts[1].toDoubleOrNull() ?: continue

                        // Extract road name (e.g. "M5", "M4", "A38", "A30")
                        val roadMatch = Regex("\\b([MA]\\d+)\\b").find(title)
                        val roadName = roadMatch?.groupValues?.get(1) ?: "Road"
                        val roadType = when {
                            roadName.startsWith("M") -> RoadClassification.MOTORWAY
                            roadName.startsWith("A") -> RoadClassification.A_ROAD
                            else -> RoadClassification.OTHER
                        }

                        // Extract direction if present
                        val dir = when {
                            title.contains("southbound", ignoreCase = true) || desc.contains("southbound", ignoreCase = true) -> "Southbound"
                            title.contains("northbound", ignoreCase = true) || desc.contains("northbound", ignoreCase = true) -> "Northbound"
                            title.contains("eastbound", ignoreCase = true) || desc.contains("eastbound", ignoreCase = true) -> "Eastbound"
                            title.contains("westbound", ignoreCase = true) || desc.contains("westbound", ignoreCase = true) -> "Westbound"
                            else -> ""
                        }

                        // Short summary
                        val summary = when {
                            desc.contains("lane closure", ignoreCase = true) || title.contains("lane closure", ignoreCase = true) -> "Lane closure"
                            desc.contains("accident", ignoreCase = true) || title.contains("accident", ignoreCase = true) -> "Accident"
                            desc.contains("roadworks", ignoreCase = true) || title.contains("roadworks", ignoreCase = true) -> "Roadworks"
                            else -> "Incident"
                        }

                        // Clean title for location context
                        val cleanLoc = title.replace(Regex("^[A-Z0-9]+\\s*"), "").take(60)

                        list.add(
                            TrafficIncident(
                                id = "live-inc-$count",
                                roadName = roadName,
                                roadType = roadType,
                                direction = dir,
                                locationDescription = cleanLoc,
                                summary = summary,
                                lat = lat,
                                lon = lon
                            )
                        )
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LiveTrafficService", "Error parsing RSS feed", e)
        }
        return list
    }
}
