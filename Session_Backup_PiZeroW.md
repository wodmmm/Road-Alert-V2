# Road Alert V2 - Session Backup (Pi Zero W Migration)

## Current Status
The radio link between the Heltec V4 (PC beacon) and Heltec V3 (Pi Zero W gateway) is **WORKING**. Both devices are running RNode firmware 1.86 and communicating over LoRa via Reticulum Network Stack.

## What We've Proven
1. **Hardware & Firmware**: Both Heltec V3 and V4 are flashed with RNode firmware 1.86.
2. **Radio Link Confirmed**: PC sends packets, Pi Zero W receives them (246 bytes RX verified).
3. **Pi Zero W**: Running at `192.168.1.204`, SSH as `root` / `bugger`.
   - Python 3, RNS 1.1.9, paho-mqtt all installed.
   - **Autostart Enabled**: Managed by systemd services (`rnsd.service` and `road_alert_gateway.service`).
   - Log files: `/root/rnsd.log` and `/root/gateway.log`.

## CRITICAL: Correct Interface Type
**MUST use `RNodeInterface` — NOT `SerialInterface`.**
SerialInterface sends raw bytes; the RNode firmware ignores them and never transmits.
RNodeInterface speaks the proper RNode protocol, configures the radio, and frames packets correctly.

## Working Radio Settings (both devices must match)
- Frequency: **868.000 MHz** (868000000)
- Bandwidth: **125 kHz** (125000)
- Spread Factor: **7** (spreadingfactor, not spreadfactor)
- Coding Rate: **5**
- TX Power: **7 dBm**

Note: Parameter name is `spreadingfactor` in RNodeInterface (not `spreadfactor`).

## PC Config (`C:\Users\Adam\.reticulum\config`)
```
[reticulum]
  enable_probes = yes

[logging]
  loglevel = 7

[interfaces]
  [[Beacon_Radio]]
    type = RNodeInterface
    enabled = yes
    port = COM8
    frequency = 868000000
    bandwidth = 125000
    txpower = 7
    spreadingfactor = 7
    codingrate = 5
```

## Pi Zero W Config (`/root/.reticulum/config`)
```
[reticulum]
  enable_transport = yes
  enable_probes = yes

[logging]
  loglevel = 7

[interfaces]
  [[LoRa Radio]]
    type = RNodeInterface
    enabled = yes
    port = /dev/ttyUSB0
    frequency = 868000000
    bandwidth = 125000
    txpower = 7
    spreadingfactor = 7
    codingrate = 5
```

## Telemetry Codes (for reference)
- **Horse:** `HOR`
- **Cyclist:** `CYC`
- **Walker:** `WAL`
- **Runner:** `RUN`

## Important Files
- **`scratch/update_all_rnode.py`**: Updates both PC and Pi configs and restarts Pi rnsd.
- **`scratch/test_tx_check.py`**: Sends 3 packets from PC and shows TX byte count.
- **`scratch/diagnose_pi.py`**: SSH into Pi and writes rnstatus + config to `scratch/pi_diagnosis.txt`.
- **`scratch/fix_pi_rnode.py`**: Full clean restart of Pi rnsd with RNodeInterface config.
- **`bridge/mqtt_bridge.py`**: The RNS-to-MQTT gateway script (runs on Pi as `/root/road_alert_gateway.py`).

## Next Steps
1. **Final frequency**: Once stable, move to 869.8 MHz / 62.5 kHz / SF9 (our intended production frequency).
2. **Autostart**: Configure Pi to auto-start rnsd and road_alert_gateway.py on boot.
3. **MQTT verification**: Confirm packets appear on `roadalert/telemetry` topic on HiveMQ.
