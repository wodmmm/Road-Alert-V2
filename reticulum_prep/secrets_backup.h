// ============================================================
//  Road Alert Gateway — Credentials
//  Fill in your WiFi details then compile and flash.
// ============================================================
#pragma once

// WiFi — fill these in
#define SECRET_WIFI_SSID   "Flattenburg"
#define SECRET_WIFI_PASS   "338Locking"

// MQTT — public HiveMQ (no auth, no TLS, matches dashboard)
#define SECRET_MQTT_HOST   "broker.hivemq.com"
#define SECRET_MQTT_PORT   1883
#define SECRET_MQTT_USER   ""
#define SECRET_MQTT_PASS   ""
#define SECRET_MQTT_TOPIC  "roadalert/telemetry"

// Admin
#define SECRET_ADMIN_PASS  "admin"
