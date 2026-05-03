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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MotoristService : Service(), TextToSpeech.OnInitListener {

    private val CHANNEL_ID = "MotoristServiceChannel"
    private val ALERT_CHANNEL_ID = "MotoristAlertChannel"
    
    // GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var myLocation: Location? = null
    
    // MQTT
    private var mqttClient: MqttClient? = null
    private val BROKER_URL = "tcp://broker.hivemq.com:1883"
    private val TOPIC = "roadalert/telemetry/#"
    
    // TTS & Audio
    private var tts: TextToSpeech? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    
    // State
    data class VRUState(val id: String, var lat: Double, var lon: Double, var type: String, var ts: Long, var lastDist: Double?, var alertStage: Int)
    private val vruStore = mutableMapOf<String, VRUState>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        
        setupGPS()
        Thread { connectMQTT() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, createPersistentNotification())
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
        }
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
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun connectMQTT() {
        try {
            val clientId = "motorist-" + System.currentTimeMillis()
            mqttClient = MqttClient(BROKER_URL, clientId, org.eclipse.paho.client.mqttv3.persist.MemoryPersistence())
            
            val options = MqttConnectOptions()
            options.isCleanSession = true
            
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
            if (now - ts > 90000) {
                Log.d("MotoristService", "Ignoring stale message from $id (${(now - ts) / 1000}s old)")
                return
            }

            val existing = vruStore[id]
            if (existing != null) {
                existing.lat = lat
                existing.lon = lon
                existing.ts = ts
                existing.type = type
            } else {
                vruStore[id] = VRUState(id, lat, lon, type, ts, null, 0)
            }
            updateProximity()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateProximity() {
        val loc = myLocation ?: return
        val now = System.currentTimeMillis()
        
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
            if (dist <= 500 && dist > 250 && vru.alertStage < 1) {
                shouldSpeak = isGettingCloser
                if (shouldSpeak) vru.alertStage = 1
            } else if (dist <= 250 && dist > 100 && vru.alertStage < 2) {
                shouldSpeak = isGettingCloser
                if (shouldSpeak) vru.alertStage = 2
            } else if (dist <= 100 && vru.alertStage < 3) {
                shouldSpeak = true // Failsafe, always alert when this close
                vru.alertStage = 3
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
            triggerAlert(it, Math.round(minAlertDist).toInt())
        }
    }

    private fun triggerAlert(vru: VRUState, dist: Int) {
        val label = when (vru.type) {
            "HOR" -> "Horse"
            "CYC" -> "Cyclist"
            "RUN" -> "Runner"
            "WAL" -> "Walker"
            else -> "User"
        }
        val message = "Warning. $label ahead. $dist meters."
        
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
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "AlertID")
                
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
            .setContentTitle("Road Alert: Active")
            .setContentText("Monitoring for nearby VRUs...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        try { mqttClient?.disconnect() } catch (e: Exception) {}
        tts?.shutdown()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
