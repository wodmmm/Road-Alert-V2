package com.roadalert.garminbridge;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.UUID;

/**
 * BridgeService — runs as a persistent foreground service.
 */
public class BridgeService extends Service {

    private static final String TAG         = "GarminBridge";
    private static final String CHANNEL_ID  = "garmin_bridge";
    private static final int    HTTP_PORT   = 8080;

    private static final String MQTT_BROKER  = "tcp://broker.hivemq.com:1883";
    private static final String MQTT_BASE_TOPIC = "roadalert/telemetry";
    private static final String DEFAULT_TYPE = "CYC";

    private Thread      _serverThread;
    private ServerSocket _serverSocket;
    private MqttClient  _mqttClient;
    private String      _sessionId;
    private String      _myTopic;
    private volatile boolean _running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        _sessionId = "GARMIN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        _myTopic = MQTT_BASE_TOPIC + "/" + _sessionId;
        Log.i(TAG, "Session ID: " + _sessionId + " | Topic: " + _myTopic);

        createNotificationChannel();
        startForeground(1, buildNotification("Starting…"));

        connectMqtt();
        startHttpServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Stopping service and clearing MQTT alert...");
        _running = false;
        
        clearRetainedMessage();
        
        try { if (_serverSocket != null) _serverSocket.close(); } catch (IOException ignored) {}
        try {
            if (_mqttClient != null && _mqttClient.isConnected())
                _mqttClient.disconnect();
        } catch (MqttException e) { Log.e(TAG, "MQTT disconnect error", e); }
        
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void connectMqtt() {
        try {
            _mqttClient = new MqttClient(MQTT_BROKER, _sessionId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setWill(_myTopic, new byte[0], 1, true);

            _mqttClient.connect(opts);
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
            updateNotification("Last fix: " + payload.optDouble("lat", 0) + ", " + payload.optDouble("lon", 0));
        } catch (MqttException e) { Log.e(TAG, "Publish failed", e); }
    }

    private void clearRetainedMessage() {
        try {
            if (_mqttClient != null && _mqttClient.isConnected()) {
                MqttMessage emptyMsg = new MqttMessage(new byte[0]);
                emptyMsg.setRetained(true);
                _mqttClient.publish(_myTopic, emptyMsg);
            }
        } catch (MqttException e) { Log.e(TAG, "Failed to clear retained message", e); }
    }

    private void startHttpServer() {
        _running = true;
        _serverThread = new Thread(() -> {
            try {
                _serverSocket = new ServerSocket(HTTP_PORT);
                while (_running) {
                    try {
                        Socket client = _serverSocket.accept();
                        new Thread(() -> handleRequest(client)).start();
                    } catch (IOException e) { if (_running) Log.e(TAG, "Accept error", e); }
                }
            } catch (IOException e) { Log.e(TAG, "Server failed", e); }
        });
        _serverThread.setDaemon(true);
        _serverThread.start();
    }

    private void handleRequest(Socket client) {
        OutputStream out = null;
        try {
            InputStream inStream = client.getInputStream();
            out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(inStream));
            
            String requestLine = in.readLine();
            if (requestLine == null) return;

            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }

            if (!requestLine.startsWith("POST /beacon")) {
                writeResponse(out, 404, "Not Found", "text/plain", "Not found");
                return;
            }

            char[] bodyChars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = in.read(bodyChars, read, contentLength - read);
                if (r == -1) break;
                read += r;
            }
            
            JSONObject incoming = new JSONObject(new String(bodyChars));
            JSONObject payload = new JSONObject();
            payload.put("id",   _sessionId);
            payload.put("type", incoming.optString("type", DEFAULT_TYPE));
            payload.put("lat",  incoming.getDouble("lat"));
            payload.put("lon",  incoming.getDouble("lon"));
            payload.put("ts",   System.currentTimeMillis() / 1000L);
            payload.put("path", "GARMIN");

            publishBeacon(payload);
            writeResponse(out, 200, "OK", "application/json", "{\"status\":\"ok\"}");

        } catch (Exception e) {
            Log.e(TAG, "Error handling request", e);
            if (out != null) {
                try { writeResponse(out, 500, "Internal Error", "text/plain", e.getMessage()); } catch (IOException ignored) {}
            }
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void writeResponse(OutputStream out, int code, String status,
                               String contentType, String body) throws IOException {
        String response = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n\r\n" + body;
        out.write(response.getBytes());
        out.flush();
    }

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
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(1, buildNotification(text));
    }
}
