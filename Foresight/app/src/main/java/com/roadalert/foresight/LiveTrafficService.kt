package com.roadalert.foresight

import android.location.Location
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches real-time local roadworks, road closures, and construction from:
 * 1. National Highways (Motorway & trunk network)
 * 2. OpenStreetMap Overpass (Local council B-roads, street-level construction & closures within 15km)
 */
class LiveTrafficService {

    interface Callback {
        fun onIncidentsUpdated(incidents: List<TrafficIncident>)
    }

    fun fetchLiveIncidents(loc: Location, callback: Callback) {
        Thread {
            val combinedList = mutableListOf<TrafficIncident>()

            // 1. Fetch National Highways events
            try {
                val urlStr = "https://m.highwaysengland.co.uk/feeds/rss/UnplannedEvents.xml"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "ForesightRoadAlert/1.0")

                if (conn.responseCode == 200) {
                    val xml = conn.inputStream.bufferedReader().use { it.readText() }
                    combinedList.addAll(parseRssIncidents(xml))
                }
            } catch (e: Exception) {
                Log.e("LiveTrafficService", "National Highways fetch error", e)
            }

            // 2. Fetch Local Roadworks & Road Closures (OSM Overpass in a 15km bounding box around driver)
            try {
                val localRoadworks = fetchLocalRoadworksOverpass(loc)
                combinedList.addAll(localRoadworks)
                Log.d("LiveTrafficService", "Loaded ${localRoadworks.size} local street roadworks/closures")
            } catch (e: Exception) {
                Log.e("LiveTrafficService", "Local roadworks fetch error", e)
            }

            if (combinedList.isNotEmpty()) {
                callback.onIncidentsUpdated(combinedList)
            }
        }.start()
    }

    /**
     * Queries OpenStreetMap Overpass for active road closures & road construction
     * within a 0.15 degree (~15km) bounding box around the vehicle.
     */
    private fun fetchLocalRoadworksOverpass(loc: Location): List<TrafficIncident> {
        val list = mutableListOf<TrafficIncident>()
        try {
            val delta = 0.12 // ~13km box
            val south = loc.latitude - delta
            val north = loc.latitude + delta
            val west = loc.longitude - delta
            val east = loc.longitude + delta

            // Overpass query for construction or conditional closures on roads
            val query = """
                [out:json][timeout:10];
                (
                  way["highway"="construction"]($south,$west,$north,$east);
                  way["hazard"="roadworks"]($south,$west,$north,$east);
                  way["highway"]["access:conditional"]($south,$west,$north,$east);
                );
                out center 40;
            """.trimIndent()

            val urlStr = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "ForesightRoadAlert/1.0")

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(resp)
                val elements = root.optJSONArray("elements")

                if (elements != null) {
                    for (i in 0 until elements.length()) {
                        val elem = elements.getJSONObject(i)
                        val tags = elem.optJSONObject("tags") ?: continue
                        val center = elem.optJSONObject("center") ?: continue
                        val lat = center.optDouble("lat", 0.0)
                        val lon = center.optDouble("lon", 0.0)
                        if (lat == 0.0 || lon == 0.0) continue

                        val name = tags.optString("name", tags.optString("ref", "Local Road"))
                        val highway = tags.optString("highway", "")
                        val construction = tags.optString("construction", "")

                        val roadType = when {
                            name.startsWith("A") -> RoadClassification.A_ROAD
                            name.startsWith("B") -> RoadClassification.B_ROAD
                            else -> RoadClassification.OTHER
                        }

                        val summary = when {
                            tags.has("access:conditional") -> "Road closed"
                            construction.isNotBlank() || highway == "construction" -> "Roadworks"
                            else -> "Road hazard"
                        }

                        val description = tags.optString("description", "roadworks in progress")

                        list.add(
                            TrafficIncident(
                                id = "osm-work-${elem.optLong("id", i.toLong())}",
                                roadName = name,
                                roadType = roadType,
                                direction = "",
                                locationDescription = description,
                                summary = summary,
                                lat = lat,
                                lon = lon
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LiveTrafficService", "Failed to query local Overpass roadworks", e)
        }
        return list
    }

    private fun parseRssIncidents(xml: String): List<TrafficIncident> {
        val list = mutableListOf<TrafficIncident>()
        try {
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

                        val roadMatch = Regex("\\b([MA]\\d+)\\b").find(title)
                        val roadName = roadMatch?.groupValues?.get(1) ?: "Road"
                        val roadType = when {
                            roadName.startsWith("M") -> RoadClassification.MOTORWAY
                            roadName.startsWith("A") -> RoadClassification.A_ROAD
                            else -> RoadClassification.OTHER
                        }

                        val dir = when {
                            title.contains("southbound", ignoreCase = true) || desc.contains("southbound", ignoreCase = true) -> "Southbound"
                            title.contains("northbound", ignoreCase = true) || desc.contains("northbound", ignoreCase = true) -> "Northbound"
                            title.contains("eastbound", ignoreCase = true) || desc.contains("eastbound", ignoreCase = true) -> "Eastbound"
                            title.contains("westbound", ignoreCase = true) || desc.contains("westbound", ignoreCase = true) -> "Westbound"
                            else -> ""
                        }

                        val summary = when {
                            desc.contains("closed", ignoreCase = true) || title.contains("closed", ignoreCase = true) -> "Road closed"
                            desc.contains("lane closure", ignoreCase = true) || title.contains("lane closure", ignoreCase = true) -> "Lane closure"
                            desc.contains("accident", ignoreCase = true) || title.contains("accident", ignoreCase = true) -> "Accident"
                            desc.contains("roadworks", ignoreCase = true) || title.contains("roadworks", ignoreCase = true) -> "Roadworks"
                            else -> "Incident"
                        }

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
