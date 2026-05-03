# Road Alert: Project Vision & Goals

## Mission Statement
To protect Vulnerable Road Users (VRUs)—horse riders, cyclists, walkers, and runners—by providing motorists with an "around-the-corner" early warning system. Road Alert ensures that a driver knows a VRU is around a blind bend before they are visible, allowing for safe speed adjustment.

## The Strategy: Hybrid Connectivity
- **Primary (4G/LTE)**: A lightweight smartphone app relays location data to an MQTT safety cloud when mobile coverage is available.
- **Fallback (LoRa/Reticulum)**: In rural "dead zones," users carry a compact LoRa device running **RNode** firmware. This creates a "sovereign" mesh network using the **Reticulum** stack, functioning entirely without cellular infrastructure.

## Infrastructure: Community Gateways
- Local hubs (stables, farms, yards) host "Safety Gateways" on rooftops.
- These gateways bridge the local LoRa mesh to the global MQTT dashboard.
- This creates a community-powered safety net that doesn't rely on third-party network providers.

## The Driver App
- **Zero-Config**: Runs in the background on motorists' phones.
- **Proximity Alerts**: Triggers immediate audible and visual warnings when close to a VRU.
- **Goal**: Free-of-charge safety standard for corporate fleets, eventually leading to integration with major navigation providers (Google/Apple).

## Current Status
- Hardware (Heltec V3/V4) validated.
- Radio parameters optimized for rural range.
- Transitioning to Reticulum for infrastructure-grade networking.
