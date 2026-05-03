import paho.mqtt.client as mqtt
import json
import time

# --- CONFIGURATION ---
MQTT_HOST = "broker.hivemq.com"
MQTT_PORT = 1883
MQTT_TOPIC = "roadalert/telemetry"

# --- TEST SCENARIO ---
# Set these to a location you will actually drive past
TEST_LOCATIONS = [
    {"id": "GHOST-HORSE-01", "type": "HOR", "lat": 51.352306, "lon": -2.977652, "msg": "Stationary Test Horse"},
    {"id": "GHOST-RUNNER-01", "type": "RUN", "lat": 51.33127930563823, "lon": -2.9552712722625265, "msg": "Stationary Test Footballer"},
    {"id": "GHOST-WALKER-01", "type": "WAL", "lat": 51.34508694987692, "lon": -2.9805999311453855, "msg": "Stationary Test Walker"},
    {"id": "GHOST-HORSE-02", "type": "HOR", "lat": 51.27298784411265, "lon": -2.9089703674081218, "msg": "Stationary Test Horse"},
    {"id": "GHOST-HORSE-03", "type": "HOR", "lat": 51.269919738144225, "lon": -2.889446358418097, "msg": "Stationary Test Horse"},
    {"id": "GHOST-HORSE-04", "type": "HOR", "lat": 51.289434671822725, "lon": -2.834411289818256, "msg": "Stationary Test Horse"},
    {"id": "GHOST-HORSE-05", "type": "HOR", "lat": 51.358811905128526, "lon": -2.8957418149293996, "msg": "Stationary Test Horse"},

    # Add more here if you want multiple test points
]

def run_simulator():
    client = mqtt.Client()
    
    print(f"Connecting to {MQTT_HOST}...")
    try:
        client.connect(MQTT_HOST, MQTT_PORT, 60)
        client.loop_start()
        print("Simulator Connected. Sending Ghost VRUs every 60 seconds.")
        
        while True:
            for loc in TEST_LOCATIONS:
                payload = loc.copy()
                payload["ts"] = int(time.time())
                payload["path"] = "SIMULATED"
                
                client.publish(MQTT_TOPIC, json.dumps(payload), retain=True)
                print(f"[{time.strftime('%H:%M:%S')}] Published {payload['id']} at {payload['lat']}, {payload['lon']}")
            
            time.sleep(60)
            
    except KeyboardInterrupt:
        print("\nSimulator stopped.")
        client.loop_stop()
        client.disconnect()
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    run_simulator()
