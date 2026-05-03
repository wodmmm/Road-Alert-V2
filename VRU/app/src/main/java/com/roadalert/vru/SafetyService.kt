package com.roadalert.vru

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID

/**
 * SafetyService — Broadcasts VRU telemetry to MQTT with Ghost-prevention.
 */
class SafetyService : Service() {

    private val CHANNEL_ID = "SafetyServiceChannel"
    private val NOTIFICATION_ID = 1
    private val TAG = "SafetyService"

    private var userType: String = "HOR"
    private var isPaused = false
    private lateinit var sessionId: String
    private lateinit var myTopic: String

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var mqttClient: MqttClient? = null

    override fun onCreate() {
        super.onCreate()
        sessionId = "VRU-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
        myTopic = "roadalert/telemetry/$sessionId"
        Log.d(TAG, "Session ID: $sessionId | Topic: $myTopic")
        
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        setupMqtt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userType = intent?.getStringExtra("USER_TYPE") ?: "HOR"
        startForeground(NOTIFICATION_ID, createNotification())
        startLocationUpdates()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "VRU Safety Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Road Alert Active")
            .setContentText("Broadcasting $userType location.")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (isPaused) return
                locationResult.lastLocation?.let { publishLocationToMqtt(it.latitude, it.longitude) }
            }
        }
    }

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        try { fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper()) }
        catch (e: SecurityException) { Log.e(TAG, "Permissions missing", e) }
    }

    private fun setupMqtt() {
        Thread {
            try {
                Log.d(TAG, "Connecting to MQTT: $sessionId")
                mqttClient = MqttClient("tcp://broker.hivemq.com:1883", sessionId, MemoryPersistence())
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    setWill(myTopic, ByteArray(0), 1, true)
                }

                mqttClient?.connect(opts)
                Log.d(TAG, "MQTT Connected successfully")
                updateStatus(null, "Connected")
            } catch (e: Exception) { 
                Log.e(TAG, "MQTT Connection Error: ${e.message}")
                updateStatus(null, "Error") 
            }
        }.start()
    }

    private fun publishLocationToMqtt(lat: Double, lon: Double) {
        Thread {
            try {
                if (mqttClient?.isConnected == true) {
                    val json = JSONObject().apply {
                        put("id", sessionId)
                        put("type", userType)
                        put("lat", lat)
                        put("lon", lon)
                        put("ts", System.currentTimeMillis() / 1000L)
                        put("path", "PHONE")
                    }
                    val msg = MqttMessage(json.toString().toByteArray()).apply {
                        qos = 1
                        isRetained = true
                    }
                    mqttClient?.publish(myTopic, msg)
                    updateStatus("Locked", null)
                }
            } catch (e: Exception) { Log.e(TAG, "MQTT Fail", e) }
        }.start()
    }

    override fun onDestroy() {
        Thread {
            try {
                if (mqttClient?.isConnected == true) {
                    mqttClient?.publish(myTopic, MqttMessage(ByteArray(0)).apply { isRetained = true })
                    mqttClient?.disconnect()
                }
            } catch (e: Exception) { Log.e(TAG, "Stop fail", e) }
        }.start()
        
        fusedLocationClient.removeLocationUpdates(locationCallback)
        updateStatus("Stopped", "Off")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun updateStatus(gps: String?, mqtt: String?) {
        val intent = Intent("com.roadalert.vru.STATUS_UPDATE").apply {
            setPackage(packageName)
            gps?.let { putExtra("gps", it) }
            mqtt?.let { putExtra("mqtt", it) }
        }
        sendBroadcast(intent)
    }
}
