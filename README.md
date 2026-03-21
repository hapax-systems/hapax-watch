# hapax-watch

Wear OS companion app for the hapax ecosystem. Streams biometric sensor data (heart rate, HRV, skin temperature, sleep state) to the council cockpit API for perception pipeline integration.

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew assembleDebug
./gradlew test
```

## Architecture

- **SensorService** — Foreground service collecting sensor data via Health Services API. Auto-starts on boot and on app launch. Runs continuously with wake lock.
- **HeartRateCollector** — Heart rate via Health Services passive monitoring with fallback to raw sensor API.
- **HrvCollector** — Heart rate variability (RMSSD) via Health Services.
- **SkinTempCollector** — Skin temperature via Health Services passive monitoring.
- **ActivityCollector** — Activity recognition (walking, running, etc.) via Health Services.
- **SensorBuffer** — Thread-safe ring buffer (max 500 readings per sensor type).
- **HapaxTransport** — OkHttp POST with exponential backoff, sends SensorPayload JSON batches every 30s. Discovers council server via mDNS or falls back to configured IP.
- **MdnsDiscovery** — NSD-based mDNS discovery for `_hapax._tcp` service type. Caches resolved endpoint with TTL refresh.
- **StatusTileService** — Wear OS tile showing connection status, last sync time, and sensor counts.
- **HapticNotificationListener** — Listens for ntfy push notifications from council and delivers haptic patterns (nudges, alerts, health warnings).
- **SettingsActivity** — Compose for Wear UI for server configuration, service toggle, and live status display.

## Data Schema

SensorPayload JSON matches `watch_receiver.py` on the council side. Readings array with typed entries (heart_rate, hrv, skin_temp, sleep, activity).

## Features

- Real sensor data via Health Services API (heart rate, HRV, skin temp, activity)
- mDNS zero-configuration server discovery with manual IP fallback
- Exponential backoff with jitter on transport failures
- DataStore persistence for settings across reboots
- Status tile for quick glance at connection state
- Haptic notification patterns for system alerts
- Auto-start on boot via BootReceiver
- Network security config for cleartext to local server

## Sprint Roadmap

1. Skeleton + Transport (fake data, HTTP POST) — done
2. Real sensors via Health Services API — done
3. mDNS discovery, network resilience, DataStore persistence — done
4. Status tile, haptic notifications, voice trigger — done
5. Complications, watch face integration

## Part of the Hapax Research Project

Research instrument for a project implementing Clark & Brennan's (1991) conversational grounding theory in a voice AI system. Biometric sensor data (heart rate, HRV, skin temperature) is consumed by the council perception pipeline. See [hapax-council](https://github.com/ryanklee/hapax-council) for the research context.

| Repository | Role |
|-----------|------|
| [hapax-council](https://github.com/ryanklee/hapax-council) | Primary research artifact — voice daemon, grounding system, experiment infrastructure |
| [hapax-constitution](https://github.com/ryanklee/hapax-constitution) | Governance specification — axioms, implications, canons |
| [hapax-officium](https://github.com/ryanklee/hapax-officium) | Supporting software — management decision support |
| **hapax-watch** (this repo) | Research instrument — Wear OS biometric companion |
| [cockpit-mcp](https://github.com/ryanklee/cockpit-mcp) | Infrastructure — MCP server for Claude Code |

## License

Apache 2.0 — see [LICENSE](LICENSE).
