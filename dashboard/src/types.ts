export type NavMode = 'standard' | 'driving';

export interface DevicePos {
  lat: number;
  lng: number;
  heading?: number;
}

export interface Beacon {
  id: string;
  type: string;
  lat: number;
  lng: number;
  rssi: number;
  lastSeen: number;
}
