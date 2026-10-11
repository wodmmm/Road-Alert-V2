package com.roadalert.garminbridge;

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

/**
 * BridgeService — receives GPS pings from the Garmin bike computer via NanoHTTPD
 * and publishes them to the MQTT broker under a unique sub-topic.
 */
public class BridgeService extends Service {

    private static final String TAG         = "GarminBridge";
    private static final String CHANNEL_ID  = "garmin_bridge";
    private static final int    HTTP_PORT   = 8080;

    private static final String MQTT_BROKER     = "tcp://broker.hivemq.com:1883";
    private static final String MQTT_BASE_TOPIC = "roadalert/telemetry";
    private static final String DEFAULT_TYPE    = "CYC";

    private BeaconServer _httpServer;
    private MqttClient   _mqttClient;
    private String       _sessionId;
    private String       _myTopic;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        _sessionId = "GARMIN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        _myTopic   = MQTT_BASE_TOPIC + "/" + _sessionId;
        Log.i(TAG, "Session: " + _sessionId + " | Topic: " + _myTopic);

        createNotificationChannel();
        startForeground(1, buildNotification("Starting…"));

        // Connect MQTT in background so it doesn't block the HTTP server starting
        new Thread(this::connectMqtt).start();
        startHttpServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Stopping service…");
        if (_httpServer != null) _httpServer.stop();
        clearRetainedMessage();
        try {
            if (_mqttClient != null && _mqttClient.isConnected())
                _mqttClient.disconnect();
        } catch (MqttException e) { Log.e(TAG, "MQTT disconnect error", e); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

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
            updateNotification("MQTT connected — waiting for Garmin data");
        } catch (MqttException e) {
            Log.e(TAG, "MQTT connection failed", e);
            updateNotification("MQTT connection failed");
        }
    }

    private void publishBeacon(JSONObject payload) {
        try {
            if (_mqttClient == null || !_mqttClient.isConnected()) return;
            MqttMessage msg = new MqttMessage(payload.toString().getBytes());
            msg.setQos(1);
            msg.setRetained(true);
            _mqttClient.publish(_myTopic, msg);
            updateNotification("Last fix: " + payload.optDouble("lat", 0)
                    + ", " + payload.optDouble("lon", 0));
        } catch (MqttException e) { Log.e(TAG, "Publish failed", e); }
    }

    private void clearRetainedMessage() {
        try {
            if (_mqttClient != null && _mqttClient.isConnected()) {
                MqttMessage empty = new MqttMessage(new byte[0]);
                empty.setRetained(true);
                _mqttClient.publish(_myTopic, empty);
            }
        } catch (MqttException e) { Log.e(TAG, "Clear retained failed", e); }
    }

    // ── HTTP Server (NanoHTTPD) ───────────────────────────────────────────────

    private void startHttpServer() {
        try {
            _httpServer = new BeaconServer(HTTP_PORT);
            _httpServer.start();
            Log.i(TAG, "HTTP server listening on port " + HTTP_PORT);
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
                int contentLength = Integer.parseInt(
                        session.getHeaders().getOrDefault("content-length", "0"));
                byte[] buf = new byte[contentLength];
                int totalRead = 0;
                InputStream is = session.getInputStream();
                while (totalRead < contentLength) {
                    int r = is.read(buf, totalRead, contentLength - totalRead);
                    if (r == -1) break;
                    totalRead += r;
                }

                JSONObject incoming = new JSONObject(new String(buf, 0, totalRead));

                JSONObject payload = new JSONObject();
                payload.put("id",   _sessionId);
                payload.put("type", incoming.optString("type", DEFAULT_TYPE));
                payload.put("lat",  incoming.getDouble("lat"));
                payload.put("lon",  incoming.getDouble("lon"));
                payload.put("ts",   System.currentTimeMillis() / 1000L);
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
                .setContentTitle("Road Alert — Garmin Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(1, buildNotification(text));
    }
}
