# Road Alert: Reticulum Migration Summary

## Project Status (As of April 25, 2026)
We have successfully validated the hardware and environment using a "Clean Slate" MeshCore build. The system is confirmed to support bi-directional communication, multi-hop repeating, and MQTT relay.

## Achievements
- **Hardware Stability**: Heltec V3 (Gateway) and V4 (Beacon) hardware confirmed working.
- **Radio Calibration**: Fixed radio parameters (869.618MHz, SF9, BW62.5) proven to provide >2.4 miles of range with 1 repeater hop.
- **Data Pipeline**: Successfully parsed `TYPE,LAT,LON` strings and pushed JSON to HiveMQ MQTT.

## Moving to Reticulum
The goal is to transition from MeshCore to Reticulum to build a "Sovereign" rural mesh network.

### Hardware Plan
- **Gateway Brain**: Raspberry Pi Zero W (running Linux/Python).
- **LoRa Modem**: Heltec V3 (running RNode Firmware).
- **Beacons**: Heltec V4 (running Reticulum-compatible client firmware or RNode standalone).

### Core Radio Specs to Preserve
- **Frequency**: 869.618 MHz
- **Bandwidth**: 62.5 kHz
- **Spreading Factor**: 9
- **Coding Rate**: 5
- **TX Power**: 22 dBm (Maximum)

### MQTT Integration
- **Broker**: broker.hivemq.com
- **Topic**: roadalert/telemetry
- **Format**: JSON `{ "id": "XXXX", "type": "CYC", "lat": 51.xxx, "lon": -2.xxx, "snr": -5.0 }`
