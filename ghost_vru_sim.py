import paho.mqtt.client as mqtt
import json
import time

# --- CONFIGURATION ---
MQTT_HOST = "51.195.222.99"
MQTT_PORT = 1883
MQTT_USER = "adam"
MQTT_PASS = "338Locking"
MQTT_BASE_TOPIC = "roadalert/telemetry"

# --- TEST SCENARIO ---
# Set these to a location you will actually drive past
TEST_LOCATIONS = [
    {"id": "GHOST-HORSE-01", "type": "HOR", "lat": 51.248881, "lon": -2.839580, "msg": "Stationary Test Horse"},
    {"id": "GHOST-RUNNER-01", "type": "RUN", "lat": 51.33127930563823, "lon": -2.9552712722625265, "msg": "Stationary Test Footballer"},
    {"id": "GHOST-WALKER-01", "type": "WAL", "lat": 51.34508694987692, "lon": -2.9805999311453855, "msg": "Stationary Test Walker"},
    {"id": "GHOST-HORSE-02", "type": "HOR", "lat": 51.27298784411265, "lon": -2.9089703674081218, "msg": "Stationary Test Horse"},
    {"id": "GHOST-HORSE-03", "type": "HOR", "lat": 51.269919738144225, "lon": -2.889446358418097, "msg": "Stationary Test Horse"},
    {"id": "GHOST-HORSE-04", "type": "HOR", "lat": 51.289434671822725, "lon": -2.834411289818256, "msg": "Stationary Test Horse"},
    {"id": "GHOST-HORSE-05", "type": "HOR", "lat": 51.358811905128526, "lon": -2.8957418149293996, "msg": "Stationary Test Horse"},
    {"id": "GHOST-HORSE-06", "type": "HOR", "lat": 51.171429, "lon": -2.780731, "msg": "Stationary Test Horse"},
    # Add more here if you want multiple test points
]

def run_simulator():
    client = mqtt.Client()
    if MQTT_USER and MQTT_PASS:
        client.username_pw_set(MQTT_USER, MQTT_PASS)
    
    print(f"Connecting to {MQTT_HOST}...")
    try:
        client.connect(MQTT_HOST, MQTT_PORT, 60)
        client.loop_start()
        import uuid
        session_suffix = uuid.uuid4().hex[:4].upper()
        last_rotation = time.time()
        
        print(f"Simulator Connected [Session: {session_suffix}]. Sending updates every 5s.")
        
        seq = 0
        while True:
            # Rotate session ID every hour (3600s) to allow re-alerting in mobile app
            if time.time() - last_rotation > 3600:
                session_suffix = uuid.uuid4().hex[:4].upper()
                last_rotation = time.time()
                seq = 0
                print(f"--- Session Rotated: New Suffix {session_suffix} ---")

            seq += 1
            for loc in TEST_LOCATIONS:
                # Add suffix to ID so app sees it as a "new" VRU every hour
                rolling_id = f"{loc['id']}-{session_suffix}"
                payload = {
                    "id": rolling_id,
                    "type": loc["type"],
                    "lat": loc["lat"],
                    "lon": loc["lon"],
                    "seq": seq,
                    "ts": int(time.time()),
                    "path": "SIMULATED"
                }
                
                # Publish to unique sub-topic for the rolling ID
                topic = f"{MQTT_BASE_TOPIC}/{rolling_id}"
                client.publish(topic, json.dumps(payload), qos=1, retain=True)
                print(f"[{time.strftime('%H:%M:%S')}] Published {rolling_id} (seq {seq}) to {topic}")
            
            time.sleep(5)
            
    except KeyboardInterrupt:
        print("\nSimulator stopped.")
        client.loop_stop()
        client.disconnect()
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    run_simulator()
