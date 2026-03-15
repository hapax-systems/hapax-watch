# hapax-watch

Wear OS companion app for the hapax ecosystem. Streams biometric sensor data (heart rate, HRV, skin temperature, sleep state) to the council/officium cockpit.

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew assembleDebug
./gradlew test
```

## Architecture

- **SensorService** — Foreground service collecting sensor data via Health Services API (Sprint 1 uses fake data)
- **SensorBuffer** — Thread-safe ring buffer (max 500 readings)
- **HapaxTransport** — OkHttp POST with exponential backoff, sends SensorPayload JSON batches every 30s
- **SettingsActivity** — Compose for Wear UI for server IP and service toggle

## Data Schema

SensorPayload JSON matches `watch_receiver.py` on the council side. Readings array with typed entries (heart_rate, hrv, skin_temp, sleep).

## Sprint Roadmap

1. Skeleton + Transport (fake data, HTTP POST) -- current
2. Real sensors via Health Services API
3. mDNS discovery, DataStore persistence
4. Tiles, complications
