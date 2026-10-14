package com.roadalert.foresight

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.google.android.gms.location.*
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.util.Locale
import android.os.Handler
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

class ForesightService : Service(), TextToSpeech.OnInitListener {

    private val CHANNEL_ID = "ForesightServiceChannel"
    private val ALERT_CHANNEL_ID = "ForesightAlertChannel"
    
    companion object {
        const val ACTION_START = "com.roadalert.foresight.START"
        const val ACTION_STOP = "com.roadalert.foresight.STOP"
    }
    
    // GPS
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private lateinit var locationCallback: LocationCallback
    private var myLocation: Location? = null
    
    // MQTT
    private var mqttClient: MqttClient? = null
    private val BROKER_URL = "tcp://51.195.222.99:1883"
    private val TOPIC = "roadalert/telemetry/#"
    private val MQTT_USER = "adam"
    private val MQTT_PASS = "338Locking"
    
    // TTS & Audio
    private var tts: TextToSpeech? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    
    // Preferences & Modules
    private lateinit var prefs: AlertPreferences
    private val speedCameraManager = SpeedCameraManager()
    private val trafficIncidentManager = TrafficIncidentManager()
    private val weatherAlertManager = WeatherAlertManager()
    private val liveTrafficService = LiveTrafficService()
    private val liveWeatherService = LiveWeatherService()
    private var lastLiveFetchTime = 0L
    private var isSpeedingAlertActive = false
    private var currentSpeedLimitMph: Int? = null
    private val speedLimitZones = mutableListOf<SpeedLimitZone>()

    // Speed limit stability filter — require 3 consecutive matching readings before applying
    private var pendingSpeedLimit: Int = -1
    private var pendingSpeedLimitCount: Int = 0

    // Cooldowns to prevent alert spam
    private var lastOvershootAlertTime = 0L   // 60s between overshoot chimes
    private var lastCameraAlertTime    = 0L   // 30s between camera alerts

    // State
    data class VRUState(val id: String, var lat: Double, var lon: Double, var type: String, var ts: Long, var lastDist: Double?, var alertStage: Int, var lastLogTime: Long = 0L)
    private val vruStore = mutableMapOf<String, VRUState>()
    private var isMonitoringActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AlertPreferences(this)
        createNotificationChannels()
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        
        loadOfflineDatasets()
        setupActivityRecognition()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            Log.d("ForesightService", "Start Journey requested by user.")
            startMonitoring()
        } else if (intent?.action == ACTION_STOP) {
            Log.d("ForesightService", "Stop Journey requested by user.")
            stopMonitoring()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (intent != null && ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent)!!
            for (event in result.transitionEvents) {
                handleActivityTransition(event.activityType, event.transitionType)
            }
        }
        
        startForeground(2, createPersistentNotification())
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            // Set attributes to Navigation Guidance to ensure it can duck music
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(attributes)
            }
        }
    }

    private fun setupActivityRecognition() {
        val transitions = mutableListOf<ActivityTransition>()
        
        val types = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING)
        for (type in types) {
            transitions.add(ActivityTransition.Builder()
                .setActivityType(type)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build())
            transitions.add(ActivityTransition.Builder()
                .setActivityType(type)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build())
        }

        val request = ActivityTransitionRequest(transitions)
        val intent = Intent(this, ForesightService::class.java)
        val pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener { Log.d("ForesightService", "Activity Recognition registered") }
                .addOnFailureListener { e -> Log.e("ForesightService", "Activity Recognition failed", e) }
        }
    }

    private fun handleActivityTransition(type: Int, transition: Int) {
        Log.d("MotoristService", "Transition: $type, $transition")
        
        if (type == DetectedActivity.IN_VEHICLE && transition == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            startMonitoring()
        } else if (transition == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            // Some other activity started (STILL, WALKING, etc)
            val bufferMillis = if (type == DetectedActivity.STILL) 300000L else 120000L // 5m for still, 2m for walk/run
            scheduleStop(bufferMillis)
        }
    }

    private fun startMonitoring() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        
        if (!isMonitoringActive) {
            isMonitoringActive = true
            setupGPS()
            Thread { connectMQTT() }.start()
            updateNotification()
            logJourneyEvent("MONITORING_START", null)
            Log.d("MotoristService", "Monitoring Started")
        }
    }

    private fun scheduleStop(delayMillis: Long) {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = Runnable {
            stopMonitoring()
        }
        handler.postDelayed(stopRunnable!!, delayMillis)
        Log.d("MotoristService", "Monitoring scheduled to stop in ${delayMillis/1000}s")
    }

    private fun stopMonitoring() {
        if (isMonitoringActive) {
            isMonitoringActive = false
            fusedLocationClient?.removeLocationUpdates(locationCallback)
            try { mqttClient?.disconnect() } catch (e: Exception) {}
            vruStore.clear()
            updateNotification()
            logJourneyEvent("MONITORING_STOP", null)
            Log.d("MotoristService", "Monitoring Stopped")
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, createPersistentNotification())
    }

    private fun setupGPS() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    myLocation = it
                    if (prefs.vruAlertsEnabled) {
                        updateProximity()
                    }
                    evaluateSpeedAndCameras(it)
                    evaluateTrafficAndRoadworks(it)
                    evaluateWeather(it)
                    triggerLiveFeedUpdateIfNeeded(it)
                }
            }
        }
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun connectMQTT() {
        try {
            val clientId = "motorist-" + System.currentTimeMillis()
            mqttClient = MqttClient(BROKER_URL, clientId, org.eclipse.paho.client.mqttv3.persist.MemoryPersistence())
            
            val options = MqttConnectOptions()
            options.isCleanSession = true
            options.userName = MQTT_USER
            options.password = MQTT_PASS.toCharArray()
            
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Thread.sleep(5000)
                    Thread { connectMQTT() }.start()
                }
                
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let { processTelemetry(String(it.payload)) }
                }
                
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            
            mqttClient?.connect(options)
            mqttClient?.subscribe(TOPIC)
        } catch (e: Exception) {
            e.printStackTrace()
            Thread.sleep(5000)
            Thread { connectMQTT() }.start()
        }
    }

    private fun processTelemetry(payload: String) {
        try {
            val json = JSONObject(payload)
            val id = json.getString("id")
            val lat = json.getDouble("lat")
            val lon = json.getDouble("lon")
            val type = json.optString("type", "VRU")
            
            val tsSeconds = json.optLong("ts", 0L)
            if (tsSeconds == 0L) {
                Log.d("MotoristService", "Ignoring message with missing timestamp")
                return
            }
            val ts = tsSeconds * 1000L
            
            val now = System.currentTimeMillis()
            if (Math.abs(now - ts) > 300000) { // 5 minutes stale/skew window
                Log.d("MotoristService", "Ignoring stale or misaligned message from $id (${Math.abs(now - ts) / 1000}s delta)")
                return
            }

            val existing = vruStore[id]
            if (existing != null) {
                existing.lat = lat
                existing.lon = lon
                existing.ts = ts
                existing.type = type
            } else {
                vruStore[id] = VRUState(id, lat, lon, type, ts, null, 0, 0L)
            }
            updateProximity()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateProximity() {
        val loc = myLocation ?: return
        val now = System.currentTimeMillis()
        
        // Determine speed in m/s (fallback to 15.0 m/s if speed is not available/unset)
        val speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 15.0
        
        // Calculate speed-based trigger distance: 15 seconds of reaction time, bounded between 150m and 600m
        val triggerDistance = (speedMps * 15.0).coerceIn(150.0, 600.0)
        
        val toRemove = mutableListOf<String>()
        var highestPriorityAlert: VRUState? = null
        var minAlertDist = Double.MAX_VALUE
        
        for ((id, vru) in vruStore) {
            if (now - vru.ts > 90000) { // 90 seconds stale logic
                toRemove.add(id)
                continue
            }
            
            val dist = getDistance(loc.latitude, loc.longitude, vru.lat, vru.lon)
            val isGettingCloser = vru.lastDist == null || dist < vru.lastDist!!
            vru.lastDist = dist
            
            var shouldSpeak = false
            if (dist <= triggerDistance && dist > 100.0 && vru.alertStage == 0) {
                shouldSpeak = isGettingCloser
                if (shouldSpeak) {
                    vru.alertStage = 1
                    logJourneyEvent("ALERT_VRU", loc, "${vru.type} id:${vru.id}", dist)
                }
            } else if (dist <= 100.0 && vru.alertStage == 0) {
                shouldSpeak = true // Failsafe fallback, always alert if not already warned
                vru.alertStage = 1
                logJourneyEvent("ALERT_VRU_FALLBACK", loc, "${vru.type} id:${vru.id}", dist)
            }
            
            if (dist <= 800.0 && (now - vru.lastLogTime > 4000)) {
                vru.lastLogTime = now
                logJourneyEvent("VRU_PROXIMITY", loc, "${vru.type} id:${vru.id} dist:${dist?.toInt()}m", dist)
            }
            
            if (shouldSpeak) {
                val isMoreVulnerable = highestPriorityAlert == null || (vru.type == "HOR" && highestPriorityAlert.type != "HOR")
                if (isMoreVulnerable || dist < minAlertDist) {
                    highestPriorityAlert = vru
                    minAlertDist = dist
                }
            }
        }
        
        toRemove.forEach { vruStore.remove(it) }
        
        highestPriorityAlert?.let {
            triggerAlert(it)
        }
    }

    private fun evaluateSpeedAndCameras(loc: Location) {
        val speedMps = if (loc.hasSpeed()) loc.speed else 0f
        val speedMph = (speedMps * 2.23694f).toInt()
        val now = System.currentTimeMillis()

        // 1. Determine current speed limit from nearest OSM zone with stability filter
        updateCurrentSpeedLimit(loc)

        // 2. Speed Limit & Overshoot Chime (with 60-second cooldown)
        currentSpeedLimitMph?.let { limit ->
            if (prefs.speedAlertsEnabled) {
                val toleranceFactor = 1.0 + (prefs.speedTolerancePercent / 100.0)
                val thresholdMph = (limit * toleranceFactor).toInt()

                if (speedMph > thresholdMph) {
                    if (!isSpeedingAlertActive && (now - lastOvershootAlertTime > 60000L)) {
                        isSpeedingAlertActive = true
                        lastOvershootAlertTime = now
                        logJourneyEvent("ALERT_SPEED_OVERSHOOT", loc,
                            "Speed: ${speedMph}mph, Limit: ${limit}mph")
                        if (prefs.audioChimeEnabled) {
                            AudioChimeGenerator.playSpeedOvershootChime()
                        }
                    }
                } else if (speedMph <= limit) {
                    isSpeedingAlertActive = false
                }
            }
        }

        // 3. Speed Camera Detection (with 30-second cooldown to avoid gantry spam)
        if (prefs.speedCamerasEnabled) {
            val cameraMatch = speedCameraManager.evaluate(loc)
            cameraMatch?.let { (camera, dist) ->
                if (now - lastCameraAlertTime > 30000L) {
                    lastCameraAlertTime = now
                    val typeDesc = when (camera.type) {
                        CameraType.VARIABLE_SPEED -> "Variable speed camera"
                        CameraType.AVERAGE_SPEED  -> "Average speed camera"
                        CameraType.RED_LIGHT      -> "Red light camera"
                        CameraType.MOBILE_HOTSPOT -> "Mobile speed camera hotspot"
                        else                      -> "Speed camera"
                    }
                    val spokenMsg = when {
                        camera.type == CameraType.VARIABLE_SPEED -> "Variable speed camera, check overhead signs"
                        camera.speedLimitMph > 0 -> "$typeDesc, ${camera.speedLimitMph} miles per hour"
                        else -> typeDesc
                    }
                    logJourneyEvent("ALERT_SPEED_CAMERA", loc,
                        "$typeDesc at ${dist.toInt()}m (limit: ${camera.speedLimitMph}mph)", dist)

                    if (prefs.audioChimeEnabled) {
                        AudioChimeGenerator.playDualToneChime {
                            if (prefs.voiceAlertsEnabled) {
                                playAudioAlert(spokenMsg)
                            }
                        }
                    } else if (prefs.voiceAlertsEnabled) {
                        playAudioAlert(spokenMsg)
                    }
                }
            }
        }
    }

    /**
     * Finds the nearest road segment with a known speed limit within 150m.
     * Ignores private/car-park limits under 15mph.
     * Requires 3 consecutive updates with the same limit to stabilize before applying.
     */
    private fun updateCurrentSpeedLimit(loc: Location) {
        if (speedLimitZones.isEmpty()) return

        var nearestDist = Double.MAX_VALUE
        var nearestLimit = -1

        synchronized(speedLimitZones) {
            for (zone in speedLimitZones) {
                // Ignore private accessways, car parks, and walking speed limits (UK legal min public road limit is 20mph)
                if (zone.limitMph < 20) continue

                val dist = getDistance(loc.latitude, loc.longitude, zone.lat, zone.lon)
                if (dist < nearestDist) {
                    nearestDist = dist
                    nearestLimit = zone.limitMph
                }
            }
        }

        // Tightened from 300m down to 150m to avoid picking up parallel streets or slip roads
        if (nearestDist <= 150.0 && nearestLimit >= 20) {
            if (nearestLimit == pendingSpeedLimit) {
                pendingSpeedLimitCount++
            } else {
                pendingSpeedLimit = nearestLimit
                pendingSpeedLimitCount = 1
            }

            // Require 3 consecutive GPS readings (~6 seconds) agreeing on the new limit
            if (pendingSpeedLimitCount >= 3 && currentSpeedLimitMph != pendingSpeedLimit) {
                val prev = currentSpeedLimitMph
                currentSpeedLimitMph = pendingSpeedLimit
                if (prev != null) {
                    logJourneyEvent("SPEED_LIMIT_CHANGE", loc,
                        "Limit changed: ${prev}mph → ${pendingSpeedLimit}mph")
                }
            }
        } else {
            // Reset pending counter if we are far from known limits
            pendingSpeedLimitCount = 0
        }
    }



    private fun evaluateTrafficAndRoadworks(loc: Location) {
        if (!prefs.roadworksTrafficEnabled) return

        val incidentMatch = trafficIncidentManager.evaluate(loc)
        incidentMatch?.let { (incident, tier) ->
            val spokenAlert = trafficIncidentManager.buildSpokenAlert(incident, tier)

            logJourneyEvent("ALERT_TRAFFIC", loc,
                "${incident.summary} on ${incident.roadName} ${incident.direction} " +
                "(tier $tier, ${incident.locationDescription.take(60)})")

            // Android Auto notification
            sendAutoNotification(incident.roadName, spokenAlert)

            // Play dual-tone chime followed by spoken voice announcement
            if (prefs.audioChimeEnabled) {
                AudioChimeGenerator.playDualToneChime {
                    if (prefs.voiceAlertsEnabled) {
                        playAudioAlert(spokenAlert)
                    }
                }
            } else if (prefs.voiceAlertsEnabled) {
                playAudioAlert(spokenAlert)
            }
        }
    }

    private fun evaluateWeather(loc: Location) {
        if (!prefs.weatherAlertsEnabled) return

        val zoneMatch = weatherAlertManager.evaluate(loc)
        zoneMatch?.let { zone ->
            logJourneyEvent("ALERT_WEATHER", loc,
                "${zone.condition} - ${zone.description.take(80)}")

            sendAutoNotification("Weather Warning", zone.spokenWarning)

            if (prefs.audioChimeEnabled) {
                AudioChimeGenerator.playDualToneChime {
                    if (prefs.voiceAlertsEnabled) {
                        playAudioAlert(zone.spokenWarning)
                    }
                }
            } else if (prefs.voiceAlertsEnabled) {
                playAudioAlert(zone.spokenWarning)
            }
        }
    }


    private fun loadOfflineDatasets() {
        Thread {
            try {
                // 1. Load Speed Cameras
                assets.open("speed_cameras.json").bufferedReader().use { reader ->
                    val jsonArray = org.json.JSONArray(reader.readText())
                    val list = mutableListOf<SpeedCamera>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        list.add(
                            SpeedCamera(
                                id = obj.getString("id"),
                                lat = obj.getDouble("lat"),
                                lon = obj.getDouble("lon"),
                                speedLimitMph = obj.getInt("speedLimitMph"),
                                type = CameraType.valueOf(obj.getString("type")),
                                headingDeg = if (obj.has("headingDeg")) obj.getDouble("headingDeg").toFloat() else null
                            )
                        )
                    }
                    speedCameraManager.loadCameras(list)
                }

                // 2. Load Traffic Incidents
                assets.open("traffic_incidents.json").bufferedReader().use { reader ->
                    val jsonArray = org.json.JSONArray(reader.readText())
                    val list = mutableListOf<TrafficIncident>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        list.add(
                            TrafficIncident(
                                id = obj.getString("id"),
                                roadName = obj.getString("roadName"),
                                roadType = RoadClassification.valueOf(obj.getString("roadType")),
                                direction = obj.getString("direction"),
                                locationDescription = obj.getString("locationDescription"),
                                summary = obj.getString("summary"),
                                lat = obj.getDouble("lat"),
                                lon = obj.getDouble("lon"),
                                headingDeg = if (obj.has("headingDeg")) obj.getDouble("headingDeg").toFloat() else null
                            )
                        )
                    }
                    trafficIncidentManager.updateIncidents(list)
                }

                // 3. Load Weather Hazards
                assets.open("weather_hazards.json").bufferedReader().use { reader ->
                    val jsonArray = org.json.JSONArray(reader.readText())
                    val list = mutableListOf<WeatherHazardZone>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        list.add(
                            WeatherHazardZone(
                                id = obj.getString("id"),
                                condition = WeatherCondition.valueOf(obj.getString("condition")),
                                severity = WeatherSeverity.valueOf(obj.getString("severity")),
                                description = obj.getString("description"),
                                spokenWarning = obj.getString("spokenWarning"),
                                lat = obj.getDouble("lat"),
                                lon = obj.getDouble("lon"),
                                radiusMeters = obj.getDouble("radiusMeters")
                            )
                        )
                    }
                    weatherAlertManager.updateZones(list)
                }
            } catch (e: Exception) {
                Log.e("ForesightService", "Error loading offline datasets", e)
            }
        }.start()
    }

    private fun triggerLiveFeedUpdateIfNeeded(loc: Location) {
        val now = System.currentTimeMillis()
        // Refresh live feeds every 5 minutes (300,000 ms) or immediately upon first GPS lock
        if (now - lastLiveFetchTime > 300000L) {
            lastLiveFetchTime = now
            Log.d("ForesightService", "Refreshing live feeds (traffic, cameras, speed limits, weather)...")

            // 1. Live UK traffic incidents (National Highways RSS + OSM local roadworks)
            if (prefs.roadworksTrafficEnabled) {
                liveTrafficService.fetchLiveIncidents(loc, object : LiveTrafficService.Callback {
                    override fun onIncidentsUpdated(incidents: List<TrafficIncident>) {
                        Log.d("ForesightService", "Traffic feed updated: ${incidents.size} incidents")
                        trafficIncidentManager.updateIncidents(incidents)
                    }
                })
            }

            // 2. Live speed cameras from OSM (real UK camera locations)
            if (prefs.speedCamerasEnabled) {
                liveTrafficService.fetchLiveSpeedCameras(loc, object : LiveTrafficService.SpeedCameraCallback {
                    override fun onCamerasUpdated(cameras: List<SpeedCamera>) {
                        Log.d("ForesightService", "Speed cameras updated: ${cameras.size} cameras")
                        speedCameraManager.loadCameras(cameras)
                    }
                })
            }

            // 3. Road speed limits from OSM (enables overshoot chime)
            if (prefs.speedAlertsEnabled) {
                liveTrafficService.fetchLiveSpeedLimits(loc, object : LiveTrafficService.SpeedLimitCallback {
                    override fun onSpeedLimitsUpdated(zones: List<SpeedLimitZone>) {
                        Log.d("ForesightService", "Speed limit zones updated: ${zones.size} segments")
                        synchronized(speedLimitZones) {
                            speedLimitZones.clear()
                            speedLimitZones.addAll(zones)
                        }
                    }
                })
            }

            // 4. Live adverse weather hazards (Open-Meteo, no API key)
            if (prefs.weatherAlertsEnabled) {
                liveWeatherService.fetchLiveWeather(loc, object : LiveWeatherService.Callback {
                    override fun onWeatherZonesUpdated(zones: List<WeatherHazardZone>) {
                        Log.d("ForesightService", "Weather hazards updated: ${zones.size} active warnings")
                        weatherAlertManager.updateZones(zones)
                    }
                })
            }
        }
    }


    private fun triggerAlert(vru: VRUState) {
        val label = when (vru.type) {
            "HOR" -> "Horse"
            "CYC" -> "Cyclist"
            "RUN" -> "Runner"
            "WAL" -> "Walker"
            else -> "User"
        }
        val message = "$label ahead"
        
        // Android Auto Heads Up Notification
        sendAutoNotification(label, message)
        
        // Audio Chime then TTS
        if (prefs.audioChimeEnabled) {
            AudioChimeGenerator.playDualToneChime {
                if (prefs.voiceAlertsEnabled) {
                    playAudioAlert(message)
                }
            }
        } else if (prefs.voiceAlertsEnabled) {
            playAudioAlert(message)
        }
    }

    private fun playAudioAlert(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
                
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener {}
                .build()
                
            val res = audioManager.requestAudioFocus(focusRequest!!)
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                val params = android.os.Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "AlertID")
                
                // Release focus after a reasonable time for TTS to finish
                Thread {
                    Thread.sleep(4000)
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }.start()
            }
        }
    }

    private fun sendAutoNotification(sender: String, message: String) {
        val person = Person.Builder().setName(sender).build()
        val messagingStyle = NotificationCompat.MessagingStyle(person)
            .addMessage(message, System.currentTimeMillis(), person)
            
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
            
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun getDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3
        val phi1 = lat1 * Math.PI / 180
        val phi2 = lat2 * Math.PI / 180
        val dphi = (lat2 - lat1) * Math.PI / 180
        val dlambda = (lon2 - lon1) * Math.PI / 180
        val a = sin(dphi / 2) * sin(dphi / 2) + cos(phi1) * cos(phi2) * sin(dlambda / 2) * sin(dlambda / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Motorist Background Service", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(serviceChannel)
            
            val alertChannel = NotificationChannel(ALERT_CHANNEL_ID, "Safety Alerts", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun createPersistentNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Road Alert")
            .setContentText("Service Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Writes a structured event row to a date-stamped CSV journey log.
     * File: /sdcard/Android/data/com.roadalert.foresight/files/journey_log_YYYY-MM-DD.csv
     * One file per day — export from Files app or ADB for post-journey review.
     *
     * EventTypes: MONITORING_START, MONITORING_STOP, ALERT_VRU, VRU_PROXIMITY_UPDATE,
     *             ALERT_SPEED_CAMERA, ALERT_SPEED_OVERSHOOT, SPEED_LIMIT_CHANGE,
     *             ALERT_TRAFFIC, ALERT_WEATHER
     */
    private fun logJourneyEvent(
        eventType: String,
        loc: Location?,
        details: String = "",
        distanceMeters: Double? = null
    ) {
        try {
            val fileDir = getExternalFilesDir(null) ?: return

            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            dateFmt.timeZone = TimeZone.getTimeZone("UTC")
            val dateStr = dateFmt.format(Date())

            val file = File(fileDir, "journey_log_$dateStr.csv")
            val isNewFile = !file.exists()

            val fw = FileWriter(file, true)
            if (isNewFile) {
                fw.write("Timestamp,EventType,Lat,Lon,SpeedMph,SpeedLimitMph,Details,DistanceMeters\n")
            }

            val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            tsFmt.timeZone = TimeZone.getTimeZone("UTC")
            val timestamp = tsFmt.format(Date())

            val lat      = loc?.latitude?.toString() ?: ""
            val lon      = loc?.longitude?.toString() ?: ""
            val speedMph = loc?.speed?.let { (it * 2.23694f).toInt().toString() } ?: ""
            val limitMph = currentSpeedLimitMph?.toString() ?: ""
            val distStr  = distanceMeters?.let { String.format(Locale.US, "%.0f", it) } ?: ""
            val safeDetails = details.replace(",", ";")

            fw.write("$timestamp,$eventType,$lat,$lon,$speedMph,$limitMph,$safeDetails,$distStr\n")
            fw.close()
        } catch (e: Exception) {
            Log.e("ForesightService", "Failed to write journey log", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isMonitoringActive) {
            fusedLocationClient?.removeLocationUpdates(locationCallback)
        }
        try { mqttClient?.disconnect() } catch (e: Exception) {}
        tts?.shutdown()
    }

    override fun onBind(intent: Intent): IBinder? = null
}

