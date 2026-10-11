package com.roadalert.garminbridge;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;

public class GarminBleService extends Service {

    private static final String TAG           = "GarminBleService";
    private static final String CHANNEL_ID    = "garmin_ble_bridge";

    // Must match the app ID in the Garmin Data Field manifest.xml
    private static final String CIQ_APP_ID    = "480eb7be-c6a0-4ed1-be42-2b343d62620d";

    private static final String MQTT_BROKER   = "tcp://broker.hivemq.com:1883";
    private static final String MQTT_TOPIC_BASE = "roadalert/telemetry";

    private ConnectIQ  _connectIQ;
    private IQDevice   _device;
    private IQApp      _myApp;
    private MqttClient _mqttClient;
    private String     _sessionId;
    private String     _myTopic;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        _sessionId = "GARMIN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        _myTopic = MQTT_TOPIC_BASE + "/" + _sessionId;
        Log.i(TAG, "Session ID: " + _sessionId);

        createNotificationChannel();
        startForeground(1, buildNotification("Starting..."));

        new Thread(this::connectMqtt).start();
        initConnectIQ();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (_connectIQ != null) _connectIQ.shutdown(this);
            clearRetainedMessage();
            if (_mqttClient != null && _mqttClient.isConnected()) _mqttClient.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error", e);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── ConnectIQ (Garmin BLE) ────────────────────────────────────────────────

    private void initConnectIQ() {
        _connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS);

        _connectIQ.initialize(this, true, new ConnectIQ.ConnectIQListener() {

            @Override
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus errStatus) {
                Log.e(TAG, "ConnectIQ init error: " + errStatus);
                updateNotification("Garmin init failed");
            }

            @Override
            public void onSdkReady() {
                Log.i(TAG, "ConnectIQ SDK ready");
                updateNotification("Waiting for Garmin...");
                listenForDevices();
            }

            @Override
            public void onSdkShutDown() {
                Log.i(TAG, "ConnectIQ SDK shutdown");
            }
        });
    }

    private void listenForDevices() {
        try {
            List<IQDevice> devices = _connectIQ.getConnectedDevices();
            if (devices != null && !devices.isEmpty()) {
                _device = devices.get(0); // take first connected Garmin
                registerForMessages();

                // Listen for device status changes
                _connectIQ.registerForDeviceEvents(_device, (device, status) -> {
                    Log.i(TAG, "Device status: " + status);
                    if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                        registerForMessages();
                    }
                });
            } else {
                updateNotification("No Garmin device found");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting devices", e);
        }
    }

    private void registerForMessages() {
        try {
            _myApp = new IQApp(CIQ_APP_ID);

            _connectIQ.registerForAppEvents(_device, _myApp, (device, app, messageList, status) -> {
                if (status == ConnectIQ.IQMessageStatus.SUCCESS && messageList != null) {
                    for (Object message : messageList) {
                        handleMessage(message);
                    }
                }
            });

            updateNotification("Connected — listening for GPS");
            Log.i(TAG, "Registered for messages from Garmin app");

        } catch (Exception e) {
            Log.e(TAG, "Error registering for messages", e);
        }
    }

    private void handleMessage(Object message) {
        try {
            // ConnectIQ messages arrive as a List or Map
            // We send a simple list [lat, lon] from the Garmin to keep it lightweight
            if (message instanceof java.util.List) {
                java.util.List data = (java.util.List) message;
                double lat = ((Number) data.get(0)).doubleValue();
                double lon = ((Number) data.get(1)).doubleValue();

                JSONObject payload = new JSONObject();
                payload.put("id",   _sessionId);
                payload.put("type", "CYC");
                payload.put("lat",  lat);
                payload.put("lon",  lon);
                payload.put("ts",   System.currentTimeMillis() / 1000L);
                payload.put("path", "GARMIN");

                publishMqtt(payload);
                updateNotification("Last: " + String.format("%.4f", lat)
                        + ", " + String.format("%.4f", lon));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling message", e);
        }
    }

    // ── MQTT ─────────────────────────────────────────────────────────────────

    private void connectMqtt() {
        try {
            _mqttClient = new MqttClient(MQTT_BROKER, _sessionId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setWill(_myTopic, new byte[0], 1, true);
            _mqttClient.connect(opts);
            Log.i(TAG, "MQTT connected");
        } catch (MqttException e) {
            Log.e(TAG, "MQTT connection failed", e);
        }
    }

    private void publishMqtt(JSONObject payload) {
        try {
            if (_mqttClient == null || !_mqttClient.isConnected()) return;
            MqttMessage msg = new MqttMessage(payload.toString().getBytes());
            msg.setQos(1);
            msg.setRetained(true);
            _mqttClient.publish(_myTopic, msg);
            Log.i(TAG, "Published: " + payload);
        } catch (MqttException e) {
            Log.e(TAG, "MQTT publish failed", e);
        }
    }

    private void clearRetainedMessage() {
        try {
            if (_mqttClient != null && _mqttClient.isConnected()) {
                MqttMessage empty = new MqttMessage(new byte[0]);
                empty.setRetained(true);
                _mqttClient.publish(_myTopic, empty);
            }
        } catch (MqttException e) {
            Log.e(TAG, "Clear retained failed", e);
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Garmin BLE Bridge", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Garmin BLE Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(1, buildNotification(text));
    }
}