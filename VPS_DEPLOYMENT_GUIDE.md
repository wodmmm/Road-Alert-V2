# VPS Setup & Deployment Guide: TomTom -> MQTT Traffic Relay

This service runs on your Ubuntu VPS (`51.195.222.99`), connects to your local Mosquitto broker, and streams live UK traffic incidents into `roadalert/<region>/traffic`.

---

## Step 1: Connect to your VPS via SSH

Open PowerShell or Terminal:
```bash
ssh adam@51.195.222.99
```

---

## Step 2: Create Directory and Install MQTT package

```bash
mkdir -p ~/roadalert_relay
cd ~/roadalert_relay
npm init -y
npm install mqtt
```
*(If Node.js / npm is not installed: `sudo apt update && sudo apt install -y nodejs npm`)*

---

## Step 3: Copy the Relay Script

Create or edit `tomtom_relay.js`:
```bash
nano tomtom_relay.js
```
Paste the contents of `tomtom_relay.js` (from your workspace), and paste your TomTom API Key in line 16 (or use an environment variable):

```javascript
const TOMTOM_API_KEY = process.env.TOMTOM_API_KEY || 'PASTE_YOUR_FULL_KEY_HERE';
```
Save with `Ctrl+O`, `Enter`, and exit with `Ctrl+X`.

---

## Step 4: Test Run the Script

Run it directly to verify it connects to Mosquitto and TomTom:
```bash
node tomtom_relay.js
```

You should see:
```text
Connecting to local MQTT broker at mqtt://localhost:1883...
✅ Connected to Mosquitto MQTT broker.
🚀 Starting TomTom ingestion loop (30s interval)...
[20:20:00] Box 'London & M25': 24 active incidents (24 published)
```

Press `Ctrl+C` once verified.

---

## Step 5: Keep It Running 24/7 with PM2

Install PM2 process manager so it stays alive permanently, auto-restarts on server reboot or crash:
```bash
sudo npm install -g pm2
pm2 start tomtom_relay.js --name tomtom-traffic
pm2 save
pm2 startup
```

### Useful Commands:
* View live logs: `pm2 logs tomtom-traffic`
* Check status: `pm2 status`
* Restart service: `pm2 restart tomtom-traffic`
* Stop service: `pm2 stop tomtom-traffic`
