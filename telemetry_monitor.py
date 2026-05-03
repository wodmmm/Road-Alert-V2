import paho.mqtt.client as mqtt
import json
import time

# --- CONFIGURATION ---
MQTT_HOST = "broker.hivemq.com"
MQTT_PORT = 1883
MQTT_TOPIC = "roadalert/telemetry"

# --- STATE ---
last_seq = {}  # Store last sequence number per device ID
total_received = 0
total_lost = 0

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(f"Connected to MQTT Broker at {MQTT_HOST}")
        client.subscribe(MQTT_TOPIC)
        print(f"Subscribed to {MQTT_TOPIC}")
        print("-" * 60)
        print(f"{'TIME':<10} {'ID':<12} {'SEQ':<5} {'PATH':<6} {'TYPE':<5} {'STATUS'}")
        print("-" * 60)
    else:
        print(f"Failed to connect, return code {rc}")

def on_message(client, userdata, msg):
    global total_received, total_lost
    try:
        data = json.loads(msg.payload.decode())
        device_id = data.get("id", "UNKNOWN")
        seq = data.get("seq", 0)
        path = data.get("path", "???")
        vru_type = data.get("type", "???")
        
        status = "OK"
        
        # Check for sequence gaps
        if device_id in last_seq:
            expected = last_seq[device_id] + 1
            if seq > expected:
                gap = seq - expected
                status = f"!!! LOST {gap} PACKETS"
                total_lost += gap
            elif seq < expected:
                status = "DUPLICATE/REORDERED"
        
        last_seq[device_id] = seq
        total_received += 1
        
        timestamp = time.strftime("%H:%M:%S")
        print(f"{timestamp:<10} {device_id:<12} {seq:<5} {path:<6} {vru_type:<5} {status}")
        
    except Exception as e:
        print(f"Error parsing message: {e}")

def run_monitor():
    client = mqtt.Client()
    client.on_connect = on_connect
    client.on_message = on_message
    
    print("Starting Road Alert Telemetry Monitor...")
    print("Press Ctrl+C to stop and see summary.")
    
    try:
        client.connect(MQTT_HOST, MQTT_PORT, 60)
        client.loop_forever()
    except KeyboardInterrupt:
        print("\n" + "=" * 60)
        print("TEST SUMMARY")
        print("=" * 60)
        print(f"Total Packets Received: {total_received}")
        print(f"Total Packets Lost:     {total_lost}")
        if total_received + total_lost > 0:
            reliability = (total_received / (total_received + total_lost)) * 100
            print(f"Network Reliability:    {reliability:.2f}%")
        print("=" * 60)

if __name__ == "__main__":
    run_monitor()
