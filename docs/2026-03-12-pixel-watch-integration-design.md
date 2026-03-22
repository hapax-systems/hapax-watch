# Hapax Watch — Pixel Watch 4 Integration Design

**Goal:** Integrate the Google Pixel Watch 4 as a physiological sensor array, haptic notification endpoint, and auxiliary voice surface for the hapax system — enriching the operator profile with ground-truth biometric data, improving agent interruption timing, and extending presence verification beyond the desk mic.

**Architecture:** Custom Wear OS app streams real-time sensor data over LAN WiFi to a lightweight receiver on the workstation. Data lands on the filesystem-as-bus for consumption by existing agents. Historical health data flows through the existing Google Drive → RAG pipeline. Notification delivery extends the existing ntfy → mako chain with wrist haptics via KDE Connect.

**Tech Stack:** Kotlin + Jetpack Compose for Wear OS, Health Services API (MeasureClient, PassiveMonitoringClient), FastAPI receiver endpoint, KDE Connect (notification bridge), Health Connect SQLite backup → Google Drive → gdrive_sync, PipeWire audio relay (stretch).

**Hardware:** Google Pixel Watch 4 (Wear OS 6.1), paired Android phone (relay + KDE Connect host).

---

## 1. System Architecture Overview

Three data paths at different latency tiers, plus a dual-radio presence layer:

```
Real-Time Path (~1-5s, WiFi direct)
┌──────────────┐   HTTP POST    ┌─────────────────┐   file write   ┌──────────────────┐
│ Pixel Watch 4 │ ─────────────→│ watch-receiver   │ ─────────────→│ filesystem-as-bus │
│ Custom App    │   (LAN WiFi)  │ FastAPI :8042    │               │ ~/hapax-state/    │
└──────────────┘                └─────────────────┘               │   watch/          │
                                                                   └────────┬─────────┘
                                                                            │ consumed by
                                                              ┌─────────────┼──────────────┐
                                                              │             │              │
                                                         voice daemon  profiler    briefing agent
                                                        (context gate) (dim 7)   (timing)

Presence Layer (dual-radio: WiFi + BLE)
┌──────────────┐   WiFi POST    ┌─────────────────┐
│ Pixel Watch 4 │ ─────────────→│ connection.json  │──→ is_watch_connected()
│              │                │ (last_seen_epoch)│         │
│              │   BLE passive  ├─────────────────┤         │ WiFi OR BLE
│              │ ←─────────────→│ bluetoothctl     │──→ is_watch_bt_nearby()
└──────────────┘   (proximity)  │ (RSSI / paired) │         │
                                └─────────────────┘    ─────┘
BLE complements WiFi: works when watch WiFi is suspended (screen off),
provides passive proximity without polling, lower power than WiFi keepalive.
bt_mac stored in connection.json by Wear OS app during first handshake.

Notification Path (~1-3s, bidirectional)
┌──────────────┐  notification  ┌──────────┐  KDE Connect  ┌───────────┐
│ Workstation   │ ─────────────→│ Android  │ ─────────────→│ Watch     │
│ ntfy/mako     │               │ Phone    │               │ (haptics) │
└──────────────┘                └──────────┘               └───────────┘

Historical Path (~24hr, batch)
┌──────────────┐  nightly backup  ┌──────────┐  gdrive_sync  ┌──────────┐
│ Health Connect│ ───────────────→│ Google   │ ─────────────→│ Qdrant   │
│ SQLite DB     │                 │ Drive    │  + parser     │ RAG      │
└──────────────┘                  └──────────┘               └──────────┘
```

### Design Principles

- **Filesystem-as-bus:** Watch data lands as JSON files. No new coordination primitives.
- **Additive integration:** No existing subsystem is modified to depend on the watch. Watch data enriches signals that already have defaults — the system works identically without the watch present.
- **Dual-radio presence:** WiFi for data throughput, BLE for passive proximity. `is_watch_connected()` fuses both signals — either is sufficient. BLE works when watch WiFi is suspended (screen off, idle), providing continuous presence without WiFi keepalive battery cost.
- **Battery-aware:** The watch app is the constrained component. All design decisions favor watch battery life over latency.
- **Axiom-compliant:** single_user (personal device), executive_function (less-intrusive haptic nudges), management_governance (surfaces data, never coaches), corporate_boundary (all data stays on LAN or sanctioned Google Drive).

---

## 2. Real-Time Sensor Streaming

### Wear OS App: `hapax-watch`

Kotlin app using Jetpack Compose for Wear OS. Three components:

**A. Foreground Sensor Service**

Reads sensors via Health Services API and batches readings for transmission.

| Sensor | API | Sample Rate | Batch Interval |
|--------|-----|-------------|----------------|
| Heart rate | `MeasureClient` | 1 Hz | 30s |
| Heart rate variability (RMSSD) | `MeasureClient` | Per-beat | 60s |
| EDA / skin conductance | `PassiveMonitoringClient` | Event-driven | On-change |
| Skin temperature | `PassiveMonitoringClient` | ~1/min | 60s |
| Activity state (still/walk/run) | `PassiveMonitoringClient` | Event-driven | On-change |
| Accelerometer | `MeasureClient` | 10 Hz | 30s (when active) |

Runs as an Android foreground service with persistent notification ("Hapax connected"). Uses `ConnectivityManager.requestNetwork()` to hold WiFi when the workstation is reachable.

**B. WiFi Transport**

- POST JSON batches to `http://<workstation-ip>:8042/watch/sensors`
- Payload: `{ "ts": <epoch_ms>, "device_id": "pw4", "readings": [...] }`
- If workstation unreachable: buffer up to 5 min in-memory, discard oldest on overflow
- No retry storm — single attempt per batch, exponential backoff on failure (30s → 60s → 2min → stop until next batch)
- mDNS discovery for workstation address (`hapax-workstation.local`) to avoid hardcoded IPs

**C. Hapax Tile**

Single Wear OS Tile (ProtoLayout, Material 3 Expressive) showing:
- Connection status (green dot = streaming, amber = buffering, red = disconnected)
- Last system notification summary (from workstation via KDE Connect)
- Quick action: tap to open voice session (sends HTTP trigger to hapax-voice)

### Workstation Receiver: `watch-receiver`

Minimal FastAPI service running as a systemd user service.

```python
# Endpoints
POST /watch/sensors       # Ingest sensor batch
POST /watch/voice-trigger # Signal hapax-voice to open wrist session
GET  /watch/status        # Watch polls to confirm connectivity

# Output: filesystem-as-bus
~/hapax-state/watch/heartrate.json      # Rolling 1-hour window
~/hapax-state/watch/hrv.json            # Rolling 1-hour window
~/hapax-state/watch/eda.json            # Rolling 1-hour window
~/hapax-state/watch/skin_temp.json      # Rolling 1-hour window
~/hapax-state/watch/activity.json       # Current activity state
~/hapax-state/watch/connection.json     # Last-seen timestamp, battery %
```

Each file is a complete JSON document (not append-only). Atomically replaced on each write via rename. Agents read the file; they never need to parse a stream.

**File schema (example, heartrate.json):**

```json
{
  "source": "pixel_watch_4",
  "updated_at": "2026-03-12T14:30:00-05:00",
  "current": { "bpm": 72, "confidence": "HIGH", "ts": "2026-03-12T14:29:55-05:00" },
  "window_1h": {
    "min": 58, "max": 95, "mean": 71, "readings": 120
  }
}
```

**Systemd unit:** `hapax-watch-receiver.service` (user service, WantedBy=default.target).

**UFW rule:** Allow TCP 8042 from LAN only (`192.168.68.0/24`).

---

## 3. Agent Consumption

No agent is modified to *require* watch data. Each integration point checks for file existence and recency, falling back to current behavior if absent.

### 3.1 Voice Daemon — Context Gate Enhancement

**Current gate stack** (cheapest first):
1. Active voice session? → never interrupt
2. PipeWire output volume high? → media playing
3. Studio processes active? → recording
4. PANNs ambient classification → dominant sound

**New layer inserted at position 2:**
2. **Stress elevated?** → Read `~/hapax-state/watch/eda.json` + `hrv.json`. If EDA spike detected or HRV dropped >30% from 1-hour mean in last 5 min → suppress non-urgent interruptions.

This is a pure gate signal — it suppresses delivery, never forces it. The voice daemon already queues suppressed notifications with TTL and retry, so nothing is lost.

### 3.2 Voice Daemon — Haptic Presence Verification

**Current verification:** Play audio chime → wait 2-3s for audio response → deliver or queue.

**New alternative path (when watch connected):**

1. Check `~/hapax-state/watch/connection.json` — watch seen in last 60s?
2. If yes: send haptic tap via KDE Connect notification (high priority, custom vibration pattern)
3. Watch accelerometer detects wrist-raise within 3s → watch POSTs `/watch/voice-trigger`
4. Voice daemon receives trigger → deliver notification via speech
5. If no wrist-raise → queue, same as current audio-chime failure path

**Advantage:** Works silently in meetings, shared spaces, or when music is playing. Doesn't require the desk mic to detect a response.

### 3.3 Operator Profile — Dimension 7 (Health/Wellness)

**Current state:** Dimension 7 populated from behavioral signals only (shell activity patterns, commit times, Langfuse session frequency).

**New data source:** Profiler agent (Tier 2) reads watch JSON files during its 6-hourly extraction cycle:

| Signal | Profile Fact | Authority |
|--------|-------------|-----------|
| Resting heart rate trend | `health.resting_hr` | Observation |
| HRV daily mean | `health.hrv_baseline` | Observation |
| EDA spike frequency | `health.stress_events_per_day` | Observation |
| Skin temperature deviation | `health.temp_deviation` | Observation |
| Activity minutes per day | `health.active_minutes` | Observation |
| Sleep detection (accel stillness 11pm-7am) | `health.sleep_window` | Observation |

Authority level is "Observation" (lowest) — these can be overridden by interview or config facts per the existing authority hierarchy.

**Derived insights the profiler can surface:**
- "Stress elevated 2-5pm on meeting-heavy days" → feeds briefing agent calendar optimization
- "Resting HR trending up over 2 weeks" → health monitor flags (data surfaced, no coaching per management_governance)
- "Peak HRV consistently at 9-11am" → correlates with existing "peak productivity window" from shell activity data

### 3.4 Briefing Agent — Activity-Aware Timing

**Current:** Fires at ~07:00 via systemd timer.

**Enhancement:** Timer still fires at 07:00, but delivery is gated:

1. Read `~/hapax-state/watch/activity.json`
2. If activity state transitioned from `STILL` (sleep-like) to `WALKING`/`ACTIVE` in the last 15 min → deliver immediately (operator just woke up)
3. If still `STILL` at 07:00 → wait, poll every 5 min until activity detected or 09:00 hard deadline
4. If watch data absent → deliver at 07:00 as before (graceful degradation)

This prevents the briefing from firing while the operator is still asleep, without requiring any configuration.

### 3.5 Health Monitor — Watch Connectivity Check

Add a lightweight check (check #37) to the existing health monitor:

```python
def check_watch_connected():
    """Check if Pixel Watch is streaming (non-critical, informational)."""
    path = Path.home() / "hapax-state/watch/connection.json"
    if not path.exists():
        return Status.SKIP  # Watch integration not active
    data = json.loads(path.read_text())
    age = time.time() - data["last_seen_epoch"]
    if age > 300:  # 5 minutes
        return Status.WARN, f"Watch last seen {age//60:.0f}m ago"
    return Status.OK, f"Watch connected, battery {data['battery_pct']}%"
```

Non-critical — WARN only, never FAIL. Watch being offline is expected (charging, out of range, etc.).

---

## 4. Notification Path (Workstation → Watch)

Leverages existing ntfy infrastructure + KDE Connect as the bridge.

### Setup

1. **KDE Connect** installed on Android phone, paired with workstation
2. Phone receives workstation notifications via KDE Connect
3. Wear OS natively bridges phone notifications to watch
4. Result: `ntfy publish → mako notification → KDE Connect → phone → watch haptics`

### Notification Priority Mapping

| Hapax Priority | ntfy Priority | Watch Behavior |
|----------------|---------------|----------------|
| Urgent (meeting in 5 min) | `urgent` | Long haptic burst + persistent notification |
| Normal (briefing ready) | `default` | Single haptic tap + notification |
| Low (informational) | `low` | Silent notification (no haptic) |

### Custom Haptic Patterns (via Wear OS app)

The `hapax-watch` app registers as a notification listener and intercepts KDE Connect notifications tagged with `hapax-*` channels. It applies custom vibration patterns:

- `hapax-presence-check`: Two short taps (tap-pause-tap), 100ms each
- `hapax-urgent`: Long buzz (500ms), pause, long buzz
- `hapax-briefing`: Three gentle taps (50ms each)
- `hapax-voice-ready`: Single strong tap (200ms) — voice session opened, speak now

---

## 5. Historical Data — Health Connect → RAG

### Data Flow

1. Health Connect on the paired phone accumulates watch data continuously
2. Nightly automatic backup creates `Health Connect.zip` containing SQLite DB on Google Drive
3. `gdrive_sync.py` detects new backup during its incremental sync cycle
4. New parser extracts structured health summaries from the SQLite DB
5. Daily health summary documents are embedded into Qdrant `documents` collection

### Health Connect SQLite Parser

Extracts daily summaries (not raw readings — those are in the real-time path):

```
Date: 2026-03-11
Resting HR: 62 bpm
HRV (RMSSD): 45ms
Sleep: 11:15pm - 6:48am (7h33m), 2h12m deep, 1h45m REM
Steps: 8,234
Active minutes: 42
SpO2 overnight mean: 97%
Skin temp deviation: +0.2°C from baseline
EDA events: 3 (14:20, 15:45, 16:30)
```

These summaries become searchable in the RAG pipeline. The briefing agent can query "how did I sleep last week" or "stress pattern on meeting days" against historical data in Qdrant.

### Schema in Qdrant

```json
{
  "collection": "documents",
  "metadata": {
    "source": "health_connect",
    "type": "daily_health_summary",
    "date": "2026-03-11",
    "device": "pixel_watch_4"
  },
  "text": "Daily health summary for 2026-03-11: ..."
}
```

---

## 6. On-Wrist Voice Relay (Stretch Goal)

Lowest priority, highest complexity. Enables voice interaction with hapax-voice while away from the desk but on the LAN.

### Architecture

```
┌──────────────┐  WebSocket audio  ┌─────────────────┐  PipeWire virtual  ┌─────────────┐
│ Pixel Watch   │ ────────────────→│ watch-receiver   │  source injection  │ hapax-voice  │
│ mic → opus    │                  │ :8042/voice      │ ────────────────→ │ daemon       │
│ speaker ← opus│ ←────────────────│                  │                   │              │
└──────────────┘                   └─────────────────┘                    └─────────────┘
```

- Watch records audio, encodes as Opus, streams over WebSocket to workstation
- Receiver injects into PipeWire as a virtual source (replacing Yeti mic for that session)
- TTS output captured from PipeWire, encoded as Opus, streamed back to watch speaker
- Session triggered by wrist-raise gesture or Tile tap

**Challenges:**
- Watch speaker/mic quality is adequate but not great — Gemini Live path preferred over local cascade
- WiFi latency + encoding adds ~200-400ms to the existing voice pipeline latency
- Battery impact is significant during active voice streaming (~15-20% per hour estimated)
- Requires PipeWire virtual source management in watch-receiver (new complexity)

**Decision:** Defer until real-time sensor streaming (section 2) is proven stable. The desk mic + speaker remain the primary voice interface. This is valuable mainly for "just woke up, still in bed, want the briefing" or "in the kitchen" scenarios.

---

## 7. Axiom Compliance Review

| Axiom | Assessment | Notes |
|-------|-----------|-------|
| **single_user** | Pass | Personal device, no multi-user code. Watch serial hardcoded, no pairing flow. |
| **executive_function** | Strong alignment | Haptic nudges less intrusive than audio. Stress-aware gating prevents bad-timing interruptions. Activity-aware briefing timing adapts to actual wake time. All zero-config after initial app install. |
| **management_governance** | Pass | Watch data surfaces in profile as facts. Agents report "HRV dropped 30% at 3pm" not "you seem stressed, take a break." No coaching language in any consumer. |
| **corporate_boundary** | Pass | Real-time path: pure LAN, no cloud. Historical path: Google Drive (already sanctioned, existing gdrive_sync). KDE Connect: LAN only. No new external service dependencies. |

---

## 8. Implementation Plan

### Phase 1: Passive Integration (no custom app, ~1 day)

1. Install KDE Connect on Android phone + workstation
2. Pair phone with workstation, enable notification sync
3. Verify ntfy → mako → KDE Connect → phone → watch notification chain
4. Result: Hapax notifications reach the watch immediately

### Phase 2: Historical Health Data (extend existing pipeline, ~2 days)

5. Enable Health Connect backup to Google Drive on phone
6. Write Health Connect SQLite parser for `gdrive_sync.py`
7. Test daily health summaries appearing in Qdrant
8. Extend briefing agent RAG queries to include health summaries
9. Result: "How did I sleep this week?" answerable in briefings

### Phase 3: Real-Time Sensor Streaming (~1-2 weeks)

10. Scaffold Wear OS app (`hapax-watch`) with Jetpack Compose
11. Implement Health Services sensor reads (HR, HRV, EDA, activity)
12. Implement WiFi batched POST transport with mDNS discovery
13. Build `watch-receiver` FastAPI service + systemd unit
14. Write filesystem-as-bus JSON output (atomic file replacement)
15. Add UFW rule for port 8042 (LAN only)
16. Build Hapax Tile (connection status + last notification + voice trigger)
17. Result: Real-time HR/HRV/EDA/activity on the filesystem-as-bus

### Phase 4: Agent Consumption (~3-5 days, after Phase 3)

18. Voice daemon: add stress-aware gate layer (read EDA/HRV files)
19. Voice daemon: add haptic presence verification path
20. Profiler: add watch data extraction for dimension 7
21. Briefing agent: add activity-aware delivery gating
22. Health monitor: add watch connectivity check (#37)
23. Custom haptic patterns in `hapax-watch` notification listener
24. Result: Agents adapt behavior based on physiological state

### Phase 5: Voice Relay (stretch, ~1-2 weeks, after Phase 4 stable)

25. WebSocket audio transport in `hapax-watch`
26. PipeWire virtual source injection in `watch-receiver`
27. Opus encode/decode on both ends
28. Session management (wrist-raise trigger, timeout, handoff to desk mic)
29. Result: Voice interaction away from desk

### Dependencies

- Phases 1-2 require only the phone + existing infrastructure
- Phase 3 requires Android Studio + Wear OS development environment
- Phase 4 depends on Phase 3 (needs real-time data flowing)
- Phase 5 depends on Phase 4 (needs stable bidirectional comms)

### VRAM/Resource Impact

- `watch-receiver`: Negligible (~20MB RAM, no GPU)
- No new models, no new GPU allocation
- Health Connect parser runs inside existing `gdrive_sync` process
- All watch integration is CPU-only on the workstation side

---

## 9. Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| WiFi power management kills watch background streaming | High | Sensor gaps | Foreground service + ConnectivityManager network request. Accept gaps — agents handle stale data gracefully. |
| Watch battery drain from continuous streaming | Medium | Watch dies by evening | Adaptive batching: increase interval to 120s when HR stable. Disable accelerometer streaming when activity=STILL. Target: <10% battery/day for sensor service. |
| EDA sensor noise / unreliable readings | Medium | Bad stress signals | Use EDA as one signal among several (HRV + EDA + activity together). Never gate on EDA alone. Require sustained signal (>2 min) not spikes. |
| KDE Connect notification chain breaks silently | Medium | Watch stops receiving alerts | Health monitor check: test KDE Connect ping every 15 min. Fall back to ntfy direct if phone relay down. |
| Watch data files go stale (watch offline) | Expected | Agents use stale data | All consumers check file mtime. >5 min stale → fall back to non-watch behavior. Connection.json mtime is the authority. |
| Wear OS app review rejection | Low | Can't distribute via Play Store | Sideload via `adb install` — single-user system, no store distribution needed. |
