import RNS
import json
import time
import subprocess
import paho.mqtt.client as mqtt
import socket

# --- CONFIGURATION LOADING ---
def load_config():
    try:
        with open("vru_config.json", "r") as f:
            return json.load(f)
    except:
        return {
            "user_id": "BEACON-DEFAULT",
            "user_type": "HOR",
            "mqtt_host": "broker.hivemq.com",
            "mqtt_port": 1883,
            "mqtt_topic": "roadalert/telemetry",
            "app_name": "roadalert",
            "aspect_name": "gateway",
            "gateway_hash": "9bc787cb62812591e8b40e44dc6da123",
            "tx_interval": 10
        }

CONFIG = load_config()

USER_ID = CONFIG["user_id"]
USER_TYPE = CONFIG["user_type"]
MQTT_HOST = CONFIG["mqtt_host"]
MQTT_PORT = CONFIG["mqtt_port"]
MQTT_TOPIC = CONFIG["mqtt_topic"]
APP_NAME = CONFIG["app_name"]
ASPECT_NAME = CONFIG["aspect_name"]
GATEWAY_HASH = bytes.fromhex(CONFIG["gateway_hash"])
TX_INTERVAL = CONFIG["tx_interval"]

# --- STATE ---
sequence_num = 0

def get_location():
    """
    Attempts to get location via Termux API. 
    Falls back to a dummy location if termux-location fails or isn't present.
    """
    try:
        # result = subprocess.run(['termux-location'], capture_output=True, text=True, timeout=5)
        # if result.returncode == 0:
        #     data = json.loads(result.stdout)
        #     return data['latitude'], data['longitude']
        
        # Simplified for robustness: use termux-location -p network (faster)
        proc = subprocess.Popen(['termux-location', '-p', 'network'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stdout, stderr = proc.communicate(timeout=5)
        if proc.returncode == 0:
            data = json.loads(stdout.decode('utf-8'))
            return data['latitude'], data['longitude']
    except Exception as e:
        pass
    
    # Fallback/Test coordinates (Weston-super-Mare area from previous tests)
    return 51.352306, -2.977652

def is_online():
    """Checks if the MQTT broker is reachable."""
    try:
        socket.create_connection((MQTT_HOST, MQTT_PORT), timeout=2)
        return True
    except:
        return False

def run_beacon():
    global sequence_num
    print(f"--- Road Alert VRU Beacon ({USER_ID}) ---")
    print(f"Type: {USER_TYPE}")
    
    # Initialize Reticulum
    print("Initializing Reticulum...")
    rns = RNS.Reticulum()
    dest = RNS.Destination(None, RNS.Destination.OUT, RNS.Destination.PLAIN, APP_NAME, ASPECT_NAME)
    dest.hash = GATEWAY_HASH
    
    # Initialize MQTT
    mqtt_client = mqtt.Client()
    
    try:
        while True:
            sequence_num += 1
            lat, lon = get_location()
            
            payload = {
                "id": USER_ID,
                "type": USER_TYPE,
                "lat": lat,
                "lon": lon,
                "seq": sequence_num,
                "ts": int(time.time())
            }
            
            sent = False
            path = "NONE"
            
            # TRY PATH A: MQTT (4G)
            if is_online():
                try:
                    payload["path"] = "4G"
                    mqtt_client.connect(MQTT_HOST, MQTT_PORT, 10)
                    mqtt_client.publish(MQTT_TOPIC, json.dumps(payload), retain=True)
                    mqtt_client.disconnect()
                    path = "4G"
                    sent = True
                except Exception as e:
                    print(f"MQTT Path Failed: {e}")
            
            # TRY PATH B: RNS (LoRa) - Fallback if MQTT failed or offline
            if not sent:
                try:
                    payload["path"] = "LoRa"
                    packet = RNS.Packet(dest, json.dumps(payload).encode("utf-8"))
                    packet.send()
                    path = "LoRa"
                    sent = True
                except Exception as e:
                    print(f"LoRa Path Failed: {e}")
            
            if sent:
                print(f"[{time.strftime('%H:%M:%S')}] Sent #{sequence_num} via {path} at {lat}, {lon}")
            else:
                print(f"[{time.strftime('%H:%M:%S')}] FAILED to send #{sequence_num}")
                
            time.sleep(TX_INTERVAL)  # Use interval from config
            
    except KeyboardInterrupt:
        print("\nBeacon stopped.")

if __name__ == "__main__":
    run_beacon()
