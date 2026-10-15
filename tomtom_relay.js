/**
 * TomTom -> MQTT Traffic Ingestion Relay
 * Foresight Driver Safety Assistant
 * 
 * Runs as a background service on Ubuntu VPS (51.195.222.99)
 * - Queries TomTom Incident Details v5 across 8 UK boxes
 * - Runs 1 query every 30 seconds (staggered = 2,400 calls/day, strictly within 2,500 free tier)
 * - Assigns incidents to regional MQTT topics: roadalert/<region>/traffic
 * - Tracks active incidents and immediately publishes status: "CLEARED" when resolved!
 */

const mqtt = require('mqtt');
const https = require('https');

// --- Configuration ---
const TOMTOM_API_KEY = process.env.TOMTOM_API_KEY || '9c8SlnlBhDq2uLuSUKufAXtUEffPw5HN';
const MQTT_BROKER_URL = 'mqtt://localhost:1883';
const MQTT_OPTIONS = {
    username: 'adam',
    password: '338Locking',
    clientId: 'TomTom-Relay-Daemon'
};

// --- 8 Strategic UK Bounding Boxes (Each strictly < 10,000 km²) ---
// minLon, minLat, maxLon, maxLat
const BOXES = [
    { id: 'london_m25',       bbox: '-0.75,51.20,0.55,51.80',   name: 'London & M25' },
    { id: 'm4_corridor',      bbox: '-3.30,51.35,-0.75,51.75',  name: 'M4 Corridor & S.Wales' },
    { id: 'm5_southwest',     bbox: '-3.30,50.80,-2.20,51.65',  name: 'M5 South West & Bristol' },
    { id: 'midlands_south',   bbox: '-2.50,51.65,-1.45,52.45',  name: 'M5 North, Worcester & Birmingham' },
    { id: 'midlands_north',   bbox: '-2.55,52.45,-1.80,53.20',  name: 'M6 Corridor' },
    { id: 'm1_spine',         bbox: '-1.45,51.80,-0.60,52.95',  name: 'M1 Spine & East Midlands' },
    { id: 'm62_yorkshire',    bbox: '-2.65,53.20,-1.35,53.85',  name: 'M62, Manchester & Leeds' },
    { id: 'central_scotland', bbox: '-4.50,55.55,-3.10,56.10',  name: 'Central Scotland (M8/M74)' }
];

// --- Regional Mapping Helper ---
// Maps a coordinate into user-friendly MQTT regional topic
function getRegionForCoordinate(lat, lon) {
    if (lat >= 55.0) return 'scotland';
    if (lon < -2.7 && lat >= 51.4 && lat < 53.4) return 'wales';
    if (lat < 51.7 && lon < -1.7) return 'southwest';
    if (lat < 51.9 && lon >= -1.7) return 'southeast';
    if (lat >= 51.7 && lat < 53.2 && lon < -1.1) return 'midlands';
    if (lat >= 53.0 && lon < -2.0) return 'northwest';
    if (lat >= 53.0 && lon >= -2.0) return 'northeast';
    return 'midlands'; // default fallback
}

// Memory map of currently active incidents per box: Map<boxId, Set<incidentId>>
const activeIncidentsPerBox = new Map();
BOXES.forEach(b => activeIncidentsPerBox.set(b.id, new Set()));

// Connect to MQTT
console.log(`Connecting to local MQTT broker at ${MQTT_BROKER_URL}...`);
const client = mqtt.connect(MQTT_BROKER_URL, MQTT_OPTIONS);

client.on('connect', () => {
    console.log('✅ Connected to Mosquitto MQTT broker.');
    startScheduler();
});

client.on('error', (err) => {
    console.error('❌ MQTT Connection Error:', err.message);
});

// --- Scheduler: Query 1 box every 30 seconds ---
let currentBoxIndex = 0;

function startScheduler() {
    console.log('🚀 Starting TomTom ingestion loop (30s interval)...');
    
    // Poll first box immediately
    pollCurrentBox();

    // Stagger every 30 seconds
    setInterval(() => {
        pollCurrentBox();
    }, 30000);
}

function pollCurrentBox() {
    const box = BOXES[currentBoxIndex];
    currentBoxIndex = (currentBoxIndex + 1) % BOXES.length;

    queryTomTomBox(box);
}

function queryTomTomBox(box) {
    if (!TOMTOM_API_KEY || TOMTOM_API_KEY === 'PASTE_YOUR_KEY_HERE') {
        console.error('❌ Missing TOMTOM_API_KEY. Please set environment variable or edit script.');
        return;
    }

    const fields = '{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,events{description},from,to,length,delay,roadNumbers}}}';
    const url = `https://api.tomtom.com/traffic/services/5/incidentDetails?key=${TOMTOM_API_KEY}&bbox=${box.bbox}&fields=${encodeURIComponent(fields)}&language=en-GB&timeValidityFilter=present`;

    https.get(url, (res) => {
        let data = '';

        res.on('data', chunk => data += chunk);
        res.on('end', () => {
            if (res.statusCode !== 200) {
                console.warn(`[${box.name}] TomTom HTTP ${res.statusCode}: ${data.substring(0, 150)}`);
                return;
            }

            try {
                const json = JSON.parse(data);
                processIncidents(box, json.incidents || []);
            } catch (err) {
                console.error(`[${box.name}] Failed to parse JSON:`, err.message);
            }
        });
    }).on('error', (err) => {
        console.error(`[${box.name}] Request error:`, err.message);
    });
}

function processIncidents(box, incidents) {
    const previousIds = activeIncidentsPerBox.get(box.id) || new Set();
    const currentIds = new Set();

    let publishedCount = 0;

    for (const item of incidents) {
        if (!item || !item.properties) continue;
        const p = item.properties;
        const geom = item.geometry;
        if (!geom || !geom.coordinates) continue;

        const id = p.id;
        currentIds.add(id);

        // Get representative lat/lon (start of geometry or point)
        let lat = 0.0, lon = 0.0;
        if (geom.type === 'Point') {
            lon = geom.coordinates[0];
            lat = geom.coordinates[1];
        } else if (geom.type === 'LineString' && geom.coordinates.length > 0) {
            lon = geom.coordinates[0][0];
            lat = geom.coordinates[0][1];
        }

        if (lat === 0.0 || lon === 0.0) continue;

        // Classify iconCategory to human-friendly type
        // 0: Unknown, 1: Accident, 2: Fog, 3: Dangerous Conditions, 4: Rain, 5: Ice, 6: Jam, 7: Lane Closed, 8: Road Closed, 9: Roadworks, 10: Wind, 11: Flooding
        const categoryMap = {
            1: 'ACCIDENT',
            6: 'JAM',
            7: 'LANE_CLOSED',
            8: 'ROAD_CLOSED',
            9: 'ROADWORKS',
            11: 'FLOODING'
        };
        const category = categoryMap[p.iconCategory] || 'HAZARD';

        // Extract description
        const desc = (p.events && p.events.length > 0) ? p.events[0].description : 'Traffic Incident';
        const road = (p.roadNumbers && p.roadNumbers.length > 0) ? p.roadNumbers[0] : '';
        const region = getRegionForCoordinate(lat, lon);

        const payload = {
            id: id,
            status: 'ACTIVE',
            type: category,
            road: road,
            description: desc,
            from: p.from || '',
            to: p.to || '',
            delaySeconds: p.delay || 0,
            lengthMeters: p.length || 0,
            lat: lat,
            lon: lon,
            ts: Date.now()
        };

        const topic = `roadalert/${region}/traffic`;
        client.publish(topic, JSON.stringify(payload), { qos: 0 });
        publishedCount++;
    }

    // --- Detect Cleared Incidents ---
    // Any incident ID present previously but missing in the latest snapshot has cleared!
    for (const oldId of previousIds) {
        if (!currentIds.has(oldId)) {
            // Find which region it was in, or broadcast clear
            const clearPayload = {
                id: oldId,
                status: 'CLEARED',
                ts: Date.now()
            };

            // Broadcast to all active traffic channels so phone drops it instantly
            const regions = ['southwest', 'wales', 'midlands', 'northwest', 'northeast', 'southeast', 'scotland'];
            for (const r of regions) {
                client.publish(`roadalert/${r}/traffic`, JSON.stringify(clearPayload), { qos: 0 });
            }
            console.log(`🧹 Incident cleared & deleted: ${oldId}`);
        }
    }

    // Update active set
    activeIncidentsPerBox.set(box.id, currentIds);
    console.log(`[${new Date().toLocaleTimeString()}] Box '${box.name}': ${incidents.length} active incidents (${publishedCount} published)`);
}
