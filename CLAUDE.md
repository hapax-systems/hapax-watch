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

## Network

- **Primary:** mDNS discovery of council API on LAN
- **Fallback:** Tailscale IP `100.117.1.83:8042` (watch receiver endpoint, not logos API)
- **Manual override:** DataStore-persisted IP via SettingsActivity
- **Resilience:** OkHttp with exponential backoff (5s → 120s max), 30s flush interval
- **Device ID:** hardcoded `pw4` in `HapaxTransport.kt`

## Deploy

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires watch connected via ADB (USB or `adb connect <ip>:5555` over WiFi).

## Sprint Roadmap

1. Skeleton + Transport (fake data, HTTP POST) -- done
2. Real sensors via Health Services API -- done
3. mDNS discovery, network resilience, DataStore persistence -- done
4. Status tile, haptic notifications, voice trigger -- done
5. Complications, watch face integration

## Gotchas

- **Battery percentage** not yet captured — TODO exists in `HapaxTransport.kt` (hardcoded `null`).
- **Permissions:** Health Services API requires runtime `BODY_SENSORS` + `ACTIVITY_RECOGNITION`. Foreground service notification prevents OS kill.
- **Wear OS background limits:** Service uses `START_STICKY` to recover from background kills. Battery optimization may still interfere.
- **Haptic patterns** (`HapticPatterns.kt`) use amplitude-modulated waveforms — requires hardware vibrator support (most Wear OS 4+ devices).
