import React, { useEffect } from 'react';
import { MapContainer, TileLayer, Marker, Popup, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import StreetSign from './StreetSign';
import { renderToStaticMarkup } from 'react-dom/server';
import type { DevicePos, Beacon } from '../types';

interface LiveMapProps {
  userPos: DevicePos | null;
  beacons: Beacon[];
  shouldCenter: boolean;
  onCentered: () => void;
}

// Child component to handle map centering/view changes
const MapController: React.FC<{ userPos: DevicePos | null; shouldCenter: boolean; onCentered: () => void }> = ({ userPos, shouldCenter, onCentered }) => {
  const map = useMap();

  useEffect(() => {
    if (!userPos || !shouldCenter) return;

    // Center on user once
    map.setView([userPos.lat, userPos.lng], 16, { animate: true });
    onCentered(); // Tell parent we've centered
  }, [userPos, shouldCenter, map, onCentered]);

  return null;
};

const createBeaconIcon = (type: string) => {
  return L.divIcon({
    html: renderToStaticMarkup(
      <div>
        <StreetSign type={type} size={42} />
      </div>
    ),
    className: 'custom-beacon-icon',
    iconSize: [42, 42],
    iconAnchor: [21, 42],
    popupAnchor: [0, -42],
  });
};

const createUserIcon = (color: string = '#3b82f6') => {
  const size = 24;
  const svg = `
    <svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <circle cx="12" cy="12" r="8" fill="${color}" stroke="white" stroke-width="3" />
      <circle cx="12" cy="12" r="10" stroke="${color}" stroke-width="1" opacity="0.3" />
    </svg>
  `;

  return L.divIcon({
    html: svg,
    className: 'user-marker-icon',
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  });
};

const LiveMap: React.FC<LiveMapProps> = ({ userPos, beacons, shouldCenter, onCentered }) => {
  const defaultCenter: [number, number] = [51.3522, -2.9770];

  return (
    <div style={{
      height: '100%',
      width: '100%',
      overflow: 'hidden',
      backgroundColor: '#0f172a'
    }}>
      <MapContainer
        center={defaultCenter}
        zoom={13}
        scrollWheelZoom={true}
        style={{ height: '100%', width: '100%' }}
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />

        {/* User Location Marker */}
        {userPos && (
          <Marker
            position={[userPos.lat, userPos.lng]}
            icon={createUserIcon()}
            zIndexOffset={1000}
          />
        )}

        {/* Beacon Markers */}
        {beacons.map((beacon) => (
          <Marker
            key={beacon.id}
            position={[beacon.lat, beacon.lng]}
            icon={createBeaconIcon(beacon.type)}
          >
            <Popup>
              <div className="p-2">
                <h3 className="font-bold">{beacon.type}</h3>
                <p>RSSI: {beacon.rssi} dBm</p>
                <p>Last seen: {new Date(beacon.lastSeen).toLocaleTimeString()}</p>
              </div>
            </Popup>
          </Marker>
        ))}

        {/* View Control */}
        <MapController userPos={userPos} shouldCenter={shouldCenter} onCentered={onCentered} />
      </MapContainer>
    </div>
  );
};

export default LiveMap;
