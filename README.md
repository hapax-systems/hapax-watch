# hapax-watch

Research instrument for a project implementing Clark & Brennan's (1991) conversational grounding theory in a production voice AI system. See [hapax-council](https://github.com/ryanklee/hapax-council) for the primary research artifact and experiment design.

## Role in the research project

The voice daemon's perception pipeline fuses audio, visual, biometric, and environmental signals into a phenomenal context representation. Biometric data — heart rate, heart rate variability (HRV via RMSSD), and skin temperature — contributes to the SystemStimmung self-regulation model, which in turn modulates the Grounding Quality Index (GQI) through a 10th dimension coupling. GQI calibrates grounding effort per Clark & Brennan's "sufficient for current purposes" criterion.

This application collects biometric sensor data from a Pixel Watch 4 via the Health Services API and streams it to the council logos API (`watch_receiver.py`) over HTTP at 30-second intervals.

## Architecture

- **SensorService** — Foreground service collecting via Health Services API with fallback to raw sensor API. Runs continuously with wake lock, auto-starts on boot.
- **HeartRateCollector** — Heart rate via passive monitoring.
- **HrvCollector** — Heart rate variability (RMSSD).
- **SkinTempCollector** — Skin temperature via passive monitoring.
- **ActivityCollector** — Activity recognition (walking, running, etc.).
- **SensorBuffer** — Thread-safe ring buffer (max 500 readings per sensor type).
- **HapaxTransport** — OkHttp POST with exponential backoff. Discovers council server via mDNS (`_hapax._tcp`) or falls back to configured IP.
- **StatusTileService** — Wear OS tile showing connection status and sensor counts.
- **HapticNotificationListener** — Receives ntfy push notifications from council, delivers haptic patterns.

## Data schema

`SensorPayload` JSON matches `watch_receiver.py` on the council side. Readings array with typed entries (`heart_rate`, `hrv`, `skin_temp`, `sleep`, `activity`).

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew assembleDebug
./gradlew test
```

## Ecosystem

| Repository | Role |
|-----------|------|
| [hapax-council](https://github.com/ryanklee/hapax-council) | Primary research artifact — voice daemon, grounding system, experiment infrastructure |
| [hapax-constitution](https://github.com/ryanklee/hapax-constitution) | Governance specification — axioms, implications, canons, precedents |
| [hapax-officium](https://github.com/ryanklee/hapax-officium) | Supporting software — management decision support |
| **hapax-watch** (this repo) | Research instrument — Wear OS biometric companion |
| [hapax-mcp](https://github.com/ryanklee/hapax-mcp) | Infrastructure — MCP server for Claude Code |

## License

Apache 2.0 — see [LICENSE](LICENSE).
