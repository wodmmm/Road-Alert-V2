# Road Alert: Technical Blueprint

## Radio Configuration (Proven Stable)
These parameters have been field-tested to provide >2.4 miles of range with 1-hop repeating.
- **Frequency**: 869.525 MHz (Targeted high-power band, away from MeshCore 869.618)
- **Bandwidth**: 62.5 kHz
- **Spreading Factor**: 9
- **Coding Rate**: 5
- **TX Power**: 22 dBm (Max)

## Hardware Pinouts

### Heltec V3 (Gateway / RNode Interface)
- NSS: 8 | DIO1: 14 | RST: 12 | BUSY: 13
- SCLK: 9 | MISO: 11 | MOSI: 10

### Heltec V4 (Beacon / Mobile Node)
- NSS: 8 | DIO1: 14 | RST: 12 | BUSY: 13
- SCLK: 9 | MISO: 11 | MOSI: 10
- GPS RX: 39 | GPS TX: 38 | GPS EN: 34
- OLED SDA: 17 | OLED SCL: 18

## MQTT Integration
- **Host**: broker.hivemq.com
- **Port**: 1883
- **Topic**: roadalert/telemetry
- **JSON Format**:
```json
{
  "id": "RA-XXXX",
  "type": "HOR|CYC|WAL|RUN",
  "lat": 51.34000,
  "lon": -2.97000,
  "snr": 5.2
}
```

## Networking Architecture
- **Stack**: Reticulum Network Stack (RNS)
- **Interface**: RNode (TNC mode) via USB/Serial to Gateway Brain.
- **Gateway Brain**: Raspberry Pi Zero W (running `rnsd` and MQTT Bridge).
