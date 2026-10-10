package com.roadalert.motorist

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

class MotoristService : Service(), TextToSpeech.OnInitListener {

    private val CHANNEL_ID = "MotoristServiceChannel"
    private val ALERT_CHANNEL_ID = "MotoristAlertChannel"
    
    companion object {
        const val ACTION_START = "com.roadalert.motorist.START"
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
    
    // State
    data class VRUState(val id: String, var lat: Double, var lon: Double, var type: String, var ts: Long, var lastDist: Double?, var alertStage: Int, var lastLogTime: Long = 0L)
    private val vruStore = mutableMapOf<String, VRUState>()
    private var isMonitoringActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        
        setupActivityRecognition()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ACTION_START just arms the service — GPS and MQTT stay off until
        // Activity Recognition confirms the user is IN_VEHICLE.
        if (intent?.action == ACTION_START) {
            Log.d("MotoristService", "Service armed. Waiting for vehicle activity...")
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
        val intent = Intent(this, MotoristService::class.java)
        val pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener { Log.d("MotoristService", "Activity Recognition registered") }
                .addOnFailureListener { e -> Log.e("MotoristService", "Activity Recognition failed", e) }
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
            logEvent("MONITORING_START", null, null, null, null)
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
            logEvent("MONITORING_STOP", null, null, null, null)
            Log.d("MotoristService", "Monitoring Stopped")
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, createPersistentNotification())
    }

    private fun setupGPS() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    myLocation = it
                    updateProximity()
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
                    logEvent("ALERT_DYNAMIC", loc, vru, dist, triggerDistance)
                }
            } else if (dist <= 100.0 && vru.alertStage == 0) {
                shouldSpeak = true // Failsafe fallback, always alert if not already warned
                vru.alertStage = 1
                logEvent("ALERT_FALLBACK", loc, vru, dist, triggerDistance)
            }
            
            if (dist <= 800.0 && (now - vru.lastLogTime > 4000)) {
                vru.lastLogTime = now
                logEvent("VRU_PROXIMITY_UPDATE", loc, vru, dist, triggerDistance)
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
        
        // Audio Ducking & TTS
        playAudioAlert(message)
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

    private fun logEvent(
        eventType: String,
        loc: Location?,
        vru: VRUState?,
        dist: Double?,
        triggerDistance: Double?
    ) {
        try {
            val fileDir = getExternalFilesDir(null) ?: return
            val file = File(fileDir, "safety_log.csv")
            val isNewFile = !file.exists()
            
            val fw = FileWriter(file, true)
            if (isNewFile) {
                fw.write("Timestamp,EventType,MotoristLat,MotoristLon,MotoristSpeed,VruId,VruType,VruLat,VruLon,Distance,TriggerDistance\n")
            }
            
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val timestamp = sdf.format(Date())
            
            val motoristLat = loc?.latitude?.toString() ?: ""
            val motoristLon = loc?.longitude?.toString() ?: ""
            val motoristSpeed = loc?.speed?.toString() ?: ""
            
            val vruId = vru?.id ?: ""
            val vruType = vru?.type ?: ""
            val vruLat = vru?.lat?.toString() ?: ""
            val vruLon = vru?.lon?.toString() ?: ""
            
            val distStr = dist?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            val trigDistStr = triggerDistance?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            
            fw.write("$timestamp,$eventType,$motoristLat,$motoristLon,$motoristSpeed,$vruId,$vruType,$vruLat,$vruLon,$distStr,$trigDistStr\n")
            fw.close()
        } catch (e: Exception) {
            Log.e("MotoristService", "Failed to write log to CSV", e)
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
