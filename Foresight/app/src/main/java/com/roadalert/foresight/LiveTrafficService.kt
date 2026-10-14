package com.roadalert.foresight

import android.location.Location
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Represents a road segment's posted speed limit, fetched from OSM.
 */
data class SpeedLimitZone(
    val lat: Double,
    val lon: Double,
    val limitMph: Int,
    val roadRef: String = ""
)

/**
 * Fetches real-time local roadworks, road closures, speed cameras and speed limits from:
 * 1. National Highways (Motorway & trunk network) — UnplannedEvents RSS
 * 2. OpenStreetMap Overpass (Local B-roads, street-level construction within 13km)
 * 3. OpenStreetMap Overpass (Speed camera nodes within 17km)
 * 4. OpenStreetMap Overpass (Road maxspeed tags within 13km)
 */
class LiveTrafficService {

    interface Callback {
        fun onIncidentsUpdated(incidents: List<TrafficIncident>)
    }

    interface SpeedCameraCallback {
        fun onCamerasUpdated(cameras: List<SpeedCamera>)
    }

    interface SpeedLimitCallback {
        fun onSpeedLimitsUpdated(zones: List<SpeedLimitZone>)
    }

    // ─── Traffic Incidents ────────────────────────────────────────────────────

    fun fetchLiveIncidents(loc: Location, callback: Callback) {
        Thread {
            val combinedList = mutableListOf<TrafficIncident>()

            // 1. National Highways events (motorways & major A-roads)
            try {
                val urlStr = "https://m.highwaysengland.co.uk/feeds/rss/UnplannedEvents.xml"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "ForesightRoadAlert/1.0")

                if (conn.responseCode == 200) {
                    val xml = conn.inputStream.bufferedReader().use { it.readText() }
                    val nhIncidents = parseRssIncidents(xml)
                    combinedList.addAll(nhIncidents)
                    Log.d("LiveTrafficService", "National Highways: ${nhIncidents.size} incidents")
                }
            } catch (e: Exception) {
                Log.e("LiveTrafficService", "National Highways fetch error", e)
            }

            // 2. Local roadworks & construction from OSM Overpass
            try {
                val localRoadworks = fetchLocalRoadworksOverpass(loc)
                combinedList.addAll(localRoadworks)
                Log.d("LiveTrafficService", "OSM local roadworks: ${localRoadworks.size} entries")
            } catch (e: Exception) {
                Log.e("LiveTrafficService", "Local roadworks fetch error", e)
            }

            if (combinedList.isNotEmpty()) {
                callback.onIncidentsUpdated(combinedList)
            }
        }.start()
    }

    // ─── Speed Cameras (OSM) ─────────────────────────────────────────────────

    fun fetchLiveSpeedCameras(loc: Location, callback: SpeedCameraCallback) {
        Thread {
            val cameras = mutableListOf<SpeedCamera>()
            try {
                // 17km bounding box around current position
                val delta = 0.15
                val south = loc.latitude - delta
                val north = loc.latitude + delta
                val west  = loc.longitude - delta
                val east  = loc.longitude + delta

                val query = """
                    [out:json][timeout:15];
                    (
                      node["highway"="speed_camera"]($south,$west,$north,$east);
                      node["enforcement"="maxspeed"]($south,$west,$north,$east);
                    );
                    out body;
                """.trimIndent()

                val urlStr = "https://overpass-api.de/api/interpreter?data=" +
                    URLEncoder.encode(query, "UTF-8")
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "ForesightRoadAlert/1.0")

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(resp)
                    val elements = root.optJSONArray("elements")

                    if (elements != null) {
                        for (i in 0 until elements.length()) {
                            val elem = elements.getJSONObject(i)
                            val lat = elem.optDouble("lat", 0.0)
                            val lon = elem.optDouble("lon", 0.0)
                            if (lat == 0.0 || lon == 0.0) continue

                            val tags = elem.optJSONObject("tags") ?: JSONObject()
                            val maxspeedStr = tags.optString("maxspeed", "")
                            val highway = tags.optString("highway", "road")
                            val limitMph = parseMaxspeedMph(maxspeedStr, highway)

                            // Detect variable/smart motorway cameras from OSM tags
                            val isVariable = tags.optString("maxspeed:variable", "") == "yes" ||
                                tags.optString("variable_maxspeed", "") == "yes" ||
                                tags.optString("source:maxspeed", "").contains("variable", ignoreCase = true) ||
                                tags.optString("camera:type", "").contains("variable", ignoreCase = true) ||
                                maxspeedStr.equals("variable", ignoreCase = true)

                            val typeStr = tags.optString("camera:type",
                                tags.optString("enforcement:type", "fixed"))
                            val camType = when {
                                isVariable -> CameraType.VARIABLE_SPEED
                                typeStr.contains("average", ignoreCase = true) -> CameraType.AVERAGE_SPEED
                                typeStr.contains("red_light", ignoreCase = true) -> CameraType.RED_LIGHT
                                typeStr.contains("mobile", ignoreCase = true) -> CameraType.MOBILE_HOTSPOT
                                else -> CameraType.FIXED
                            }

                            // Extract enforcement direction/heading if available in OSM
                            val directionStr = tags.optString("direction",
                                tags.optString("camera:direction", ""))
                            val heading = directionStr.toFloatOrNull()

                            cameras.add(
                                SpeedCamera(
                                    id = "osm-cam-${elem.optLong("id", i.toLong())}",
                                    lat = lat,
                                    lon = lon,
                                    speedLimitMph = limitMph,
                                    type = camType,
                                    headingDeg = heading
                                )
                            )
                        }
                    }
                    Log.d("LiveTrafficService", "OSM speed cameras loaded: ${cameras.size}")
                }
            } catch (e: Exception) {
                Log.e("LiveTrafficService", "Speed camera OSM fetch error", e)
            }

            if (cameras.isNotEmpty()) {
                callback.onCamerasUpdated(cameras)
            }
        }.start()
    }

    // ─── Road Speed Limits (OSM) ─────────────────────────────────────────────

    fun fetchLiveSpeedLimits(loc: Location, callback: SpeedLimitCallback) {
        Thread {
            val zones = mutableListOf<SpeedLimitZone>()
            try {
                val delta = 0.12 // ~13km box
                val south = loc.latitude - delta
                val north = loc.latitude + delta
                val west  = loc.longitude - delta
                val east  = loc.longitude + delta

                val query = """
                    [out:json][timeout:15];
                    way["maxspeed"]["highway"]($south,$west,$north,$east);
                    out center tags;
                """.trimIndent()

                val urlStr = "https://overpass-api.de/api/interpreter?data=" +
                    URLEncoder.encode(query, "UTF-8")
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "ForesightRoadAlert/1.0")

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(resp)
                    val elements = root.optJSONArray("elements")

                    if (elements != null) {
                        for (i in 0 until elements.length()) {
                            val elem = elements.getJSONObject(i)
                            val center = elem.optJSONObject("center") ?: continue
                            val lat = center.optDouble("lat", 0.0)
                            val lon = center.optDouble("lon", 0.0)
                            if (lat == 0.0 || lon == 0.0) continue

                            val tags = elem.optJSONObject("tags") ?: continue
                            val maxspeedStr = tags.optString("maxspeed", "")
                            val highway = tags.optString("highway", "road")
                            val ref = tags.optString("ref", "")
                            val limitMph = parseMaxspeedMph(maxspeedStr, highway)
                            if (limitMph <= 0) continue

                            zones.add(SpeedLimitZone(lat = lat, lon = lon, limitMph = limitMph, roadRef = ref))
                        }
                    }
                    Log.d("LiveTrafficService", "OSM speed limit zones loaded: ${zones.size}")
                }
            } catch (e: Exception) {
                Log.e("LiveTrafficService", "Speed limit OSM fetch error", e)
            }

            if (zones.isNotEmpty()) {
                callback.onSpeedLimitsUpdated(zones)
            }
        }.start()
    }

    // ─── OSM Overpass — Local Roadworks ──────────────────────────────────────

    private fun fetchLocalRoadworksOverpass(loc: Location): List<TrafficIncident> {
        val list = mutableListOf<TrafficIncident>()
        try {
            val delta = 0.12 // ~13km bounding box
            val south = loc.latitude - delta
            val north = loc.latitude + delta
            val west  = loc.longitude - delta
            val east  = loc.longitude + delta

            // Only query for actual construction/roadworks — NOT access:conditional
            // (access:conditional catches bus lanes, permit zones etc. — far too noisy)
            val query = """
                [out:json][timeout:12];
                (
                  way["highway"="construction"]($south,$west,$north,$east);
                  way["hazard"="roadworks"]($south,$west,$north,$east);
                  way["construction"][${"\"highway\""}!="construction"]($south,$west,$north,$east);
                );
                out center tags 100;
            """.trimIndent()

            val urlStr = "https://overpass-api.de/api/interpreter?data=" +
                URLEncoder.encode(query, "UTF-8")
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "ForesightRoadAlert/1.0")

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(resp)
                val elements = root.optJSONArray("elements")

                if (elements != null) {
                    val seen = mutableSetOf<String>()

                    for (i in 0 until elements.length()) {
                        val elem = elements.getJSONObject(i)
                        val tags = elem.optJSONObject("tags") ?: continue
                        val center = elem.optJSONObject("center") ?: continue
                        val lat = center.optDouble("lat", 0.0)
                        val lon = center.optDouble("lon", 0.0)
                        if (lat == 0.0 || lon == 0.0) continue

                        val ref          = tags.optString("ref", "").trim()
                        val name         = tags.optString("name", "").trim()
                        val highway      = tags.optString("highway", "").trim()
                        val construction = tags.optString("construction", "").trim()

                        // Strictly reject non-vehicular ways: footways, cycleways, paths, steps, service alleys
                        val isNonVehicular = highway in listOf("footway", "cycleway", "path", "pedestrian", "steps", "service", "track", "bridleway") ||
                                             construction in listOf("footway", "cycleway", "path", "pedestrian", "steps", "service", "track", "bridleway")
                        if (isNonVehicular) continue

                        // Build a meaningful road name: road number (ref) is best,
                        // then the actual street name, then the OSM highway type
                        val roadName = when {
                            ref.isNotBlank() -> ref
                            name.isNotBlank() -> name
                            construction.isNotBlank() && construction != "yes" ->
                                construction.replace("_", " ").replaceFirstChar { it.uppercase() }
                            highway.isNotBlank() && highway != "construction" ->
                                highway.replace("_", " ").replaceFirstChar { it.uppercase() }
                            else -> "Local road"
                        }

                        // Deduplicate: same road name in same ~1km grid cell
                        val dedupeKey = "$roadName-${(lat * 100).toInt()}-${(lon * 100).toInt()}"
                        if (seen.contains(dedupeKey)) continue
                        seen.add(dedupeKey)

                        // Classify road type using ref number first, then OSM highway tag
                        val roadType = when {
                            ref.startsWith("M") -> RoadClassification.MOTORWAY
                            ref.startsWith("A") -> RoadClassification.A_ROAD
                            ref.startsWith("B") -> RoadClassification.B_ROAD
                            highway in listOf("motorway", "motorway_link") -> RoadClassification.MOTORWAY
                            highway in listOf("trunk", "trunk_link", "primary", "primary_link") ->
                                RoadClassification.A_ROAD
                            highway in listOf("secondary", "secondary_link") -> RoadClassification.B_ROAD
                            else -> RoadClassification.OTHER
                        }

                        val summary = "Roadworks"

                        list.add(
                            TrafficIncident(
                                id = "osm-work-${elem.optLong("id", i.toLong())}",
                                roadName = roadName,
                                roadType = roadType,
                                direction = "",
                                locationDescription = tags.optString("description",
                                    tags.optString("note", "roadworks in progress")),
                                summary = summary,
                                lat = lat,
                                lon = lon
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LiveTrafficService", "Failed to query Overpass roadworks", e)
        }
        return list
    }

    // ─── National Highways RSS Parser ────────────────────────────────────────

    private fun parseRssIncidents(xml: String): List<TrafficIncident> {
        val list = mutableListOf<TrafficIncident>()
        try {
            val itemRegex  = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            val titleRegex = Regex("<title>(.*?)</title>")
            val descRegex  = Regex("<description>(.*?)</description>")
            val pointRegex = Regex("<georss:point>(.*?)</georss:point>")

            var count = 0
            for (match in itemRegex.findAll(xml)) {
                val itemBlock = match.groupValues[1]
                val title = titleRegex.find(itemBlock)?.groupValues?.get(1) ?: continue
                val desc  = descRegex.find(itemBlock)?.groupValues?.get(1) ?: ""
                val point = pointRegex.find(itemBlock)?.groupValues?.get(1) ?: continue

                val parts = point.trim().split(" ")
                if (parts.size != 2) continue
                val lat = parts[0].toDoubleOrNull() ?: continue
                val lon = parts[1].toDoubleOrNull() ?: continue

                val roadMatch = Regex("\\b([MA]\\d+)\\b").find(title)
                val roadName  = roadMatch?.groupValues?.get(1) ?: "Road"
                val roadType  = when {
                    roadName.startsWith("M") -> RoadClassification.MOTORWAY
                    roadName.startsWith("A") -> RoadClassification.A_ROAD
                    else -> RoadClassification.OTHER
                }

                val dir = when {
                    title.contains("southbound", ignoreCase = true) ||
                        desc.contains("southbound", ignoreCase = true) -> "Southbound"
                    title.contains("northbound", ignoreCase = true) ||
                        desc.contains("northbound", ignoreCase = true) -> "Northbound"
                    title.contains("eastbound", ignoreCase = true) ||
                        desc.contains("eastbound", ignoreCase = true) -> "Eastbound"
                    title.contains("westbound", ignoreCase = true) ||
                        desc.contains("westbound", ignoreCase = true) -> "Westbound"
                    else -> ""
                }

                val summary = when {
                    desc.contains("closed", ignoreCase = true) ||
                        title.contains("closed", ignoreCase = true) -> "Road closed"
                    desc.contains("lane closure", ignoreCase = true) ||
                        title.contains("lane closure", ignoreCase = true) -> "Lane closure"
                    desc.contains("accident", ignoreCase = true) ||
                        title.contains("accident", ignoreCase = true) -> "Accident"
                    desc.contains("roadworks", ignoreCase = true) ||
                        title.contains("roadworks", ignoreCase = true) -> "Roadworks"
                    else -> "Incident"
                }

                list.add(
                    TrafficIncident(
                        id = "live-inc-$count",
                        roadName = roadName,
                        roadType = roadType,
                        direction = dir,
                        locationDescription = title.take(80),
                        summary = summary,
                        lat = lat,
                        lon = lon
                    )
                )
                count++
            }
        } catch (e: Exception) {
            Log.e("LiveTrafficService", "Error parsing RSS feed", e)
        }
        return list
    }

    // ─── Speed / Limit Helpers ────────────────────────────────────────────────

    /**
     * Parses an OSM maxspeed tag value into mph for UK roads.
     * Handles: "30", "30 mph", "national", "signals", km/h values.
     */
    private fun parseMaxspeedMph(value: String, highway: String): Int {
        val cleaned = value.trim().lowercase()
        if (cleaned.isEmpty()) return 0
        return when {
            cleaned == "national" -> when {
                highway.contains("motorway") -> 70
                highway.contains("trunk") || highway.contains("primary") -> 60
                else -> 60
            }
            cleaned == "signals" || cleaned == "variable" -> 0
            cleaned.endsWith(" mph") ->
                cleaned.removeSuffix(" mph").trim().toIntOrNull() ?: 0
            cleaned.endsWith("mph") ->
                cleaned.removeSuffix("mph").trim().toIntOrNull() ?: 0
            cleaned.contains("km/h") || cleaned.contains("kmh") -> {
                val kmh = cleaned.replace(Regex("\\s*km/?h"), "").trim().toIntOrNull() ?: 0
                (kmh * 0.621371).toInt()
            }
            // UK: bare integer values in OSM are mph
            else -> cleaned.toIntOrNull() ?: 0
        }
    }
}
