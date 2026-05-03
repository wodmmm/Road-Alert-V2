package com.example.garminmqttbridge;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import fi.iki.elonen.NanoHTTPD;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.*;
import java.io.*;
import java.util.UUID;

public class BridgeService extends Service {

    private static final String TAG          = "GarminBridge";
    private static final String CHANNEL_ID   = "garmin_bridge";
    private static final int    PORT         = 8080;
    private static final String MQTT_BROKER  = "tcp://your-broker:1883";  // ← change this
    private static final String MQTT_TOPIC   = "vru/beacons";             // ← change this
    private static final String MQTT_USER    = "your-user";               // ← change this
    private static final String MQTT_PASS    = "your-password";           // ← change this

    private BeaconServer  _httpServer;
    private MqttClient    _mqttClient;
    private String        _sessionId;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        _sessionId = "GARMIN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Log.i(TAG, "Session ID: " + _sessionId);

        createNotificationChannel();
        startForeground(1, buildNotification("Starting…"));

        connectMqtt();
        startHttpServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // restart if killed
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (_httpServer != null) _httpServer.stop();
        try {
            if (_mqttClient != null && _mqttClient.isConnected())
                _mqttClient.disconnect();
        } catch (MqttException e) { Log.e(TAG, "MQTT disconnect error", e); }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── MQTT ─────────────────────────────────────────────────────────────────

    private void connectMqtt() {
        try {
            _mqttClient = new MqttClient(MQTT_BROKER, _sessionId, new MemoryPersistence());

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setUserName(MQTT_USER);
            opts.setPassword(MQTT_PASS.toCharArray());
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);

            _mqttClient.connect(opts);
            Log.i(TAG, "MQTT connected");
            updateNotification("Connected — waiting for Garmin");

        } catch (MqttException e) {
            Log.e(TAG, "MQTT connection failed", e);
            updateNotification("MQTT connection failed");
        }
    }

    private void publishBeacon(JSONObject payload) {
        try {
            if (!_mqttClient.isConnected()) {
                Log.w(TAG, "MQTT not connected, skipping publish");
                return;
            }
            MqttMessage msg = new MqttMessage(payload.toString().getBytes());
            msg.setQos(1);
            msg.setRetained(true);  // retain so drivers see position immediately on join
            _mqttClient.publish(MQTT_TOPIC, msg);
            Log.i(TAG, "Published: " + payload);
            updateNotification("Last publish: " + payload.optDouble("lat", 0)
                    + ", " + payload.optDouble("lon", 0));
        } catch (MqttException e) {
            Log.e(TAG, "Publish failed", e);
        }
    }

    // ── HTTP Server ───────────────────────────────────────────────────────────

    private void startHttpServer() {
        try {
            _httpServer = new BeaconServer(PORT);
            _httpServer.start();
            Log.i(TAG, "HTTP server listening on port " + PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
            updateNotification("HTTP server failed to start");
        }
    }

    private class BeaconServer extends NanoHTTPD {

        BeaconServer(int port) { super(port); }

        @Override
        public Response serve(IHTTPSession session) {
            if (!session.getMethod().equals(Method.POST)
                    || !session.getUri().equals("/beacon")) {
                return newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
            }

            try {
                // Read body
                int contentLength = Integer.parseInt(
                        session.getHeaders().getOrDefault("content-length", "0"));
                byte[] buf = new byte[contentLength];
                session.getInputStream().read(buf, 0, contentLength);
                String body = new String(buf);

                JSONObject incoming = new JSONObject(body);

                // Build outgoing payload matching your schema
                JSONObject payload = new JSONObject();
                payload.put("id",   _sessionId);
                payload.put("type", incoming.optString("type", "CYC"));
                payload.put("lat",  incoming.getDouble("lat"));
                payload.put("lon",  incoming.getDouble("lon"));
                payload.put("ts",   System.currentTimeMillis() / 1000L); // unix epoch
                payload.put("path", "GARMIN");

                publishBeacon(payload);

                return newFixedLengthResponse(
                        Response.Status.OK, "application/json", "{\"status\":\"ok\"}");

            } catch (Exception e) {
                Log.e(TAG, "Error handling request", e);
                return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Garmin MQTT Bridge", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Garmin Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(1, buildNotification(text));
    }
}