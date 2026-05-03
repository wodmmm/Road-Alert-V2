/*
 * Road Alert: Technical Reference for Reticulum Phase
 * This file contains the "Working State" configuration to be carried over.
 */

// --- RADIO PARAMETERS ---
// These parameters are proven to work with your current hardware and environment.
#define RA_LORA_FREQ       869.618f
#define RA_LORA_BW         62.5f
#define RA_LORA_SF         9
#define RA_LORA_CR         5
#define RA_LORA_TX_POWER   22

// --- HELTEC V3 PINS (Gateway) ---
#define V3_LORA_NSS        8
#define V3_LORA_DIO_1      14
#define V3_LORA_RESET      12
#define V3_LORA_BUSY       13
#define V3_LORA_SCLK       9
#define V3_LORA_MISO       11
#define V3_LORA_MOSI       10

// --- HELTEC V4 PINS (Beacon) ---
#define V4_LORA_NSS        8
#define V4_LORA_DIO_1      14
#define V4_LORA_RESET      12
#define V4_LORA_BUSY       13
#define V4_LORA_SCLK       9
#define V4_LORA_MISO       11
#define V4_LORA_MOSI       10
#define V4_GPS_RX          39
#define V4_GPS_TX          38
#define V4_GPS_EN          34
#define V4_OLED_SDA        17
#define V4_OLED_SCL        18

// --- MQTT CREDENTIALS ---
#define MQTT_HOST          "broker.hivemq.com"
#define MQTT_PORT          1883
#define MQTT_TOPIC         "roadalert/telemetry"

// --- LEGACY PARSING LOGIC (For reference) ---
/*
  char* typeStr = strtok(data, ",");
  char* latStr = strtok(NULL, ",");
  char* lonStr = strtok(NULL, ",");
  if (typeStr && latStr && lonStr) {
      float lat = atof(latStr);
      float lon = atof(lonStr);
      // Relay to MQTT...
  }
*/
