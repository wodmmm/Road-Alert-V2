import React, { useState, useEffect } from 'react';
import LiveMap from './components/LiveMap';
import mqtt from 'mqtt';
import './App.css';

import type { DevicePos, Beacon } from './types';

// Configuration for the MQTT broker
const MQTT_BROKER = 'wss://broker.hivemq.com:8884/mqtt';
const MQTT_TOPIC = 'roadalert/telemetry/#'; // Wildcard for all sessions
const EXPIRATION_TIMEOUT_MS = 120000; // 2 minutes

const App: React.FC = () => {
  const [userPos, setUserPos] = useState<DevicePos | null>(null);
  const [beacons, setBeacons] = useState<Beacon[]>([]);
  const [audioEnabled, setAudioEnabled] = useState(false);
  const [hasCentered, setHasCentered] = useState(false);
  const [isStarted, setIsStarted] = useState(false);

  // 1. MQTT Listener
  useEffect(() => {
    if (!isStarted) return;

    const client = mqtt.connect(MQTT_BROKER);

    client.on('connect', () => {
      console.log('Connected to MQTT Broker via WebSockets');
      client.subscribe(MQTT_TOPIC);
    });

    client.on('message', (topic, message) => {
      try {
        const msgStr = message.toString();
        const data = JSON.parse(msgStr);

        if (!data || isNaN(data.lat) || isNaN(data.lon)) return;

        // Use the ID from payload or the sub-topic suffix
        const beaconId = data.id || topic.split('/').pop() || 'unknown';

        setBeacons(prev => {
          const now = Date.now();
          const existingIdx = prev.findIndex(b => b.id === beaconId);
          
          if (existingIdx >= 0) {
            const updated = [...prev];
            updated[existingIdx] = {
              ...updated[existingIdx],
              lat: data.lat,
              lng: data.lon,
              type: data.type || updated[existingIdx].type,
              rssi: data.rssi || updated[existingIdx].rssi,
              lastSeen: now
            };
            return updated;
          } else {
            return [...prev, {
              id: beaconId,
              type: data.type || 'VRU',
              lat: data.lat,
              lng: data.lon,
              rssi: data.rssi || 0,
              lastSeen: now
            }];
          }
        });
      } catch (e) {
        console.error("Failed to process MQTT message:", e);
      }
    });

    return () => {
      client.end();
    };
  }, [isStarted]);

  // 2. Expiration Timer (Cleanup stale beacons)
  useEffect(() => {
    const interval = setInterval(() => {
      const now = Date.now();
      setBeacons(prev => prev.filter(b => now - b.lastSeen < EXPIRATION_TIMEOUT_MS));
    }, 5000);

    return () => clearInterval(interval);
  }, []);

  // 3. Watch user position
  useEffect(() => {
    if (!isStarted || !navigator.geolocation) return;

    const watchId = navigator.geolocation.watchPosition(
      (pos) => {
        const { latitude, longitude } = pos.coords;
        setUserPos({ lat: latitude, lng: longitude, heading: 0 });
      },
      (err) => console.error("Geolocation error:", err),
      { enableHighAccuracy: true, timeout: 5000, maximumAge: 0 }
    );

    return () => navigator.geolocation.clearWatch(watchId);
  }, [isStarted]);

  const startApp = () => {
    setIsStarted(true);
    // Request notification permission or audio if needed
  };

  return (
    <div className="app-container">
      <div className="status-bar">
        {userPos ? 'GPS Locked' : 'Locating...'} | {beacons.length} Active Beacons
      </div>

      <LiveMap
        userPos={userPos}
        beacons={beacons}
        shouldCenter={!hasCentered}
        onCentered={() => setHasCentered(true)}
      />

      {!isStarted && (
        <div className="start-overlay">
            <button className="start-btn" onClick={startApp}>
                START TRACKER
            </button>
        </div>
      )}

      <div className="controls-overlay">
        <button 
            className={`audio-btn ${audioEnabled ? 'active' : ''}`}
            onClick={() => setAudioEnabled(!audioEnabled)}
        >
            {audioEnabled ? '🔊' : '🔇'}
        </button>
        <button 
            className="recenter-btn"
            onClick={() => setHasCentered(false)}
        >
            🎯
        </button>
      </div>
    </div>
  );
};

export default App;
