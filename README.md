<!-- hapax-sdlc:preamble:begin -->

# hapax-watch

This repository is a constituent of the Hapax operating environment. It is not a product, not a service, and not seeking contributors. It is research infrastructure published as artifact.

Authorship is indeterminate by design: this codebase is co-produced by Hapax (the system itself), Claude Code, and the operator (Oudepode The Operator). Per the Hapax Manifesto, unsettled contribution is a feature of the work, not a concealment.

## What this is, not what it does

Constituent of the Hapax operating environment. Wear OS companion that streams biometric sensor data (heart rate, HRV, skin temperature, activity state) to the council watch receiver and reads an awareness summary from the council logos API. Single operator, single device.

## Constitutional position

- Single-operator system; no auth, no roles, no contributor onboarding (axiom: `single_user`)
- No issues, no discussions, no PRs accepted; refusal is the artifact (see `CONTRIBUTING.md`)
- License: PolyForm Strict 1.0.0 (source-available, non-distribution, non-modification)
- Citation: see `CITATION.cff`; archival DOI: see `.zenodo.json`

## Linked artifacts

- Manifesto: https://hapax.weblog.lol/hapax-manifesto-v0
- Refusal Brief: https://hapax.weblog.lol/refusal-brief
- Cohort Disparity Disclosure: https://hapax.weblog.lol/cohort-disparity-disclosure
- Constitution: https://github.com/hapax-systems/hapax-constitution

## Inter-repo position

Wear OS biometric source. Streams to council watch receiver (`:8042`); reads awareness summary from council logos API (`:8051`).

<!-- hapax-sdlc:preamble:end -->

## Description

Wear OS app for Pixel Watch 4. Single Gradle module (no mobile/wear split). Two responsibilities:

1. **Sensor streaming** — Health Services API collectors batch readings and POST to the council watch receiver every 30 s.
2. **Awareness tile** — A Wear OS Tile reads `GET /api/awareness/watch-summary` from the council logos API every 60 s and renders a glance-only three-field summary.

Sister to [hapax-phone](https://github.com/hapax-systems/hapax-phone). Watch covers continuous heart-rate streaming and on-wrist haptics. Phone covers daily health rollups, ambient activity, and device state.

## Sensor coverage

| Collector | Source | Payload field |
|-----------|--------|---------------|
| `HeartRateCollector` | Health Services `MeasureClient`, active polling | `heart_rate` (`bpm`, `confidence`) |
| `HrvCollector` | Health Services `DeltaDataType("HeartRateVariability")` | `hrv` (`rmssd_ms`) |
| `SkinTempCollector` | Health Services `PassiveMonitoringClient` | `skin_temp` (`temp_c`) |
| `ActivityCollector` | Health Services `PassiveMonitoringClient` user activity | `activity` (`state`: `RUNNING`/`WALKING`/`STILL`) |

`SensorBuffer` is a thread-safe ring buffer (max 500 readings). On transport failure, drained readings are returned to the buffer.

There is no sleep collector. Sleep was a Phase-2 design item (per `docs/2026-03-12-pixel-watch-integration-design.md`) and is not implemented; it is not present in payloads despite earlier README versions referencing it.

## Awareness tile (`OperatorAwarenessTileService`)

`TileService` polling `GET /api/awareness/watch-summary` every 60 s. Renders three fields:

| Position | Field | Source |
|----------|-------|--------|
| 1 | Stance | `WatchSummary.stance` (e.g. `SEEKING`, `THINKING`, `GROUNDED`) |
| 2 | Presence decile | `WatchSummary.presence_decile` (0–10, nullable) |
| 3 | Voice indicator | `WatchSummary.live` (filled = live, hollow = not) |

The tile is glance-only: no clickable affordance, no action button. When the response carries `X-Awareness-State-Stale: true` or returns HTTP 503, the tile renders the last known values at 50 % opacity.

The tile is built with Wear OS Tiles + ProtoLayout (not Compose for Wear OS surfaces). The closed cc-task chain `awareness-watch-tile-001` (skeleton + LogosApiClient) → `-002` (3-field layout) → `-003` (stale visual + unit tests) is merged. The next phase is `awareness-watch-tile-004-on-device-smoke` (operator-action; physical Pixel Watch 4 required).

## Network resolution

| Order | Source |
|-------|--------|
| 1 | Manual IP override (DataStore-persisted via `SettingsActivity`) |
| 2 | Cached mDNS `_hapax._tcp` (5 min TTL) |
| 3 | mDNS discovery (`MdnsDiscovery` is implemented but the resolver currently uses only the cache path; production builds rely on the Tailscale fallback) |
| 4 | Hardcoded fallback `100.117.1.83:8051` (Tailscale IP, logos API for the tile path) |

Sensor payloads target the council watch receiver on port `8042`; tile reads target the logos API on port `8051`. `device_id` is hardcoded as `"pw4"` in `HapaxTransport.kt`. No app-level auth — Tailscale membership is the perimeter.

## Architecture

```
app/src/main/kotlin/dev/hapax/watch/
  sensor/    HeartRateCollector, HrvCollector, SkinTempCollector,
             ActivityCollector, SensorService (foreground), BootReceiver
  network/   HapaxTransport (OkHttp, exponential backoff 5 s → 120 s),
             LogosApiClient, ConnectivityHelper, MdnsDiscovery
  data/      SensorBuffer, SensorReading, SensorPayload, WatchSummary,
             DataStoreExt
  tile/      OperatorAwarenessTileService, StatusTileService,
             StatusTileRenderer
  ui/        SettingsActivity (Wear Compose for IP override, service toggle)
  notification/  HapticNotificationListener (ntfy push → vibrate patterns)
  gesture/   GestureDetector
```

`SensorService` runs as a foreground service (`foregroundServiceType="health"`), `START_STICKY`, restarted on boot via `BootReceiver`. Flush interval: 30 s. `HapticPatterns.kt` requires hardware vibrator (Wear OS 4+).

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew assembleDebug
./gradlew test
adb install app/build/outputs/apk/debug/app-debug.apk
```

Build versions (per `gradle/libs.versions.toml`): AGP 8.7.3, Kotlin 2.3.20, Wear Compose 1.5.0, Tiles 1.5.0, JUnit Jupiter 5.11.4, OkHttp 4.12.0, Java 21. compileSdk 35, minSdk 34, targetSdk 34. No signing config in tree — debug APK only.

ADB connection over USB or `adb connect <ip>:5555`.

## Permissions

`BODY_SENSORS`, `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE` (foreground service notification prevents OS kill). Battery optimization may still throttle background work.

## Tests

JUnit 5 unit tests:

- `data/SensorBufferTest` — ring buffer behavior, drain semantics
- `data/SensorPayloadTest` — JSON serialization
- `data/WatchSummaryTest` — payload parsing
- `tile/OperatorAwarenessTileServiceTest` — three-field layout, stale-visual opacity, no-clickable invariant

No instrumented or screenshot tests yet.

## Counterpart routes on hapax-council

| Endpoint | Implementation | Purpose |
|----------|---------------|---------|
| Sensor batch routes (port 8042) | `agents/watch_receiver.py` | Sensor ingest; persists per-device JSON to `~/hapax-state/watch/` |
| `GET /api/awareness/watch-summary` (port 8051) | `logos/api/routes/awareness.py` | Compact tile-friendly summary; 503 + `X-Awareness-State-Stale: true` when stale |

## Known limitations

- Battery percentage is `null` in payloads — the watch-side battery API used here does not expose it on Wear OS.
- mDNS discovery is implemented (`MdnsDiscovery`) but the resolver currently uses only the cache path; the Tailscale fallback IP is the production carrier.

## Ecosystem

| Repository | Role |
|-----------|------|
| [hapax-council](https://github.com/hapax-systems/hapax-council) | Primary research artifact — voice daemon, grounding system, experiment infrastructure |
| [hapax-constitution](https://github.com/hapax-systems/hapax-constitution) | Governance specification — axioms, implications, canons, precedents |
| [hapax-officium](https://github.com/hapax-systems/hapax-officium) | Supporting software — management decision support |
| **hapax-watch** (this repo) | Wear OS biometric companion |
| [hapax-phone](https://github.com/hapax-systems/hapax-phone) | Android health + context companion |
| [hapax-mcp](https://github.com/hapax-systems/hapax-mcp) | MCP server bridging the logos APIs to Claude Code |

## License

PolyForm Strict 1.0.0 — see [LICENSE](LICENSE).
