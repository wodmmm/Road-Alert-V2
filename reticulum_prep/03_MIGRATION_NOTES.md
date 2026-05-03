# Road Alert: Migration Notes

## Why the move from MeshCore to Reticulum?

1. **Protocol Sovereignty**: MeshCore is an application-layer library. Reticulum is a network-layer stack. For a safety critical network, we need the robustness of a true networking stack that handles cryptography and routing as core features.
2. **Standardization**: Reticulum allows our hardware to talk to any other Reticulum-enabled device (Sideband, Nomad Network, etc.) out of the box.
3. **Infrastructure Control**: By using RNode firmware on our Heltec boards, we turn them into high-performance radio interfaces that can be managed by a more powerful "Brain" (like a Raspberry Pi).
4. **Encryption by Default**: Every packet in Reticulum is signed and encrypted, preventing spoofing of VRU locations.

## Summary of the "Final" MeshCore Test
- **Date**: April 22, 2026
- **Result**: Successfully confirmed that 869.618MHz/SF9/BW62.5 can reach 2.4 miles via a 1-hop repeater to an indoor gateway behind stone walls.
- **Root Cause of failures**: Discovered that `radio_init()` was missing in earlier builds, and TX power was too low (10dBm). Setting TX to 22dBm and ensuring hardware init solved all connectivity issues.
