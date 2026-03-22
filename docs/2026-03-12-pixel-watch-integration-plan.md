# Pixel Watch 4 Integration — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Integrate the Google Pixel Watch 4 as a physiological sensor array, haptic notification endpoint, and auxiliary voice surface for the hapax system. Watch data enriches the operator profile, improves agent interruption timing, and extends presence verification beyond the desk mic.

**Architecture:** Three data paths at different latency tiers: real-time sensor streaming (WiFi POST to FastAPI receiver, JSON on filesystem-as-bus), notification delivery (ntfy to mako to KDE Connect to phone to watch), and historical health data (Health Connect SQLite backup to Google Drive to gdrive_sync to Qdrant RAG pipeline). All agent integration is additive: agents check for watch data presence and fall back gracefully when absent.

**Tech Stack:** Kotlin + Jetpack Compose for Wear OS (Phase 3), FastAPI + uvicorn (receiver), Health Connect SQLite parser (Python), KDE Connect (notification bridge), pytest (tests), systemd user services

**Design doc:** `docs/plans/2026-03-12-pixel-watch-integration-design.md`

**Codebase context:**
- `~/projects/hapax-council/agents/health_monitor.py` — Health check suite (2,217 LOC, `check_group` decorator, `Status` enum: HEALTHY/DEGRADED/FAILED)
- `~/projects/hapax-council/agents/ingest.py` — RAG ingest pipeline (watches `~/documents/rag-sources/`, frontmatter enrichment keys, auto-detects `source_service` from path)
- `~/projects/hapax-council/agents/gdrive_sync.py` — Google Drive sync (918 LOC, incremental via changes API)
- `~/projects/hapax-council/agents/profiler.py` — Profile extraction (ProfileFact model, dimension-keyed, authority levels)
- `~/projects/hapax-council/agents/profiler_sources.py` — Source discovery (BRIDGED_SOURCE_TYPES, SOURCE_TYPE_CHUNK_CAPS)
- `~/projects/hapax-council/agents/briefing.py` — Daily briefing generator (consumes activity + health snapshot)
- `~/projects/hapax-council/agents/hapax_voice/__main__.py` — Voice daemon (AudioInputStream, ContextGate, PresenceDetector, SessionManager)
- `~/projects/hapax-council/agents/hapax_voice/context_gate.py` — VetoChain-based gate (5 layers, deny-wins)
- `~/projects/hapax-council/shared/config.py` — Canonical paths (HAPAX_HOME, RAG_SOURCES_DIR, HAPAX_CACHE_DIR, SYSTEMD_USER_DIR)
- `~/projects/hapax-council/shared/dimensions.py` — DimensionDef registry (11 dimensions, `energy_and_attention` is dimension 7)

---

## Phase 1: Passive Integration

---

## Task 1: KDE Connect Setup and Notification Chain Verification

**Files:**
- Create: `~/projects/distro-work/docs/watch-kdeconnect-setup.md` (setup notes)

**Context:** This is a manual setup task. KDE Connect on the phone bridges workstation notifications to the paired phone, and Wear OS natively mirrors phone notifications to the watch. No code required; this task documents the setup and verifies the chain.

**Step 1: Install KDE Connect on workstation**

```bash
pacman -S kdeconnect
```

Enable the firewall rules for KDE Connect (UDP 1714-1764, TCP 1714-1764):

```bash
sudo ufw allow 1714:1764/tcp
sudo ufw allow 1714:1764/udp
```

**Step 2: Install KDE Connect on Android phone**

Install from Google Play Store. Open both apps, pair workstation with phone. Enable notification sync plugin on both ends.

**Step 3: Verify the notification chain**

Send a test notification via ntfy:

```bash
curl -d "Test watch notification" http://localhost:8090/hapax
```

Expected chain: ntfy publish -> mako desktop notification -> KDE Connect -> phone -> watch haptic.

**Step 4: Document the setup**

Write setup notes to `~/projects/distro-work/docs/watch-kdeconnect-setup.md` documenting: pairing steps, enabled plugins, firewall rules, the verified notification chain, and any troubleshooting steps encountered.

**Step 5: Commit**

```bash
cd ~/projects/distro-work
git add docs/watch-kdeconnect-setup.md
git commit -m "docs(watch): KDE Connect setup and notification chain verification"
```

---

## Phase 2: Historical Health Data

---

## Task 2: Health Connect SQLite Parser

**Files:**
- Create: `~/projects/hapax-council/agents/health_connect_parser.py`
- Test: `~/projects/hapax-council/tests/test_health_connect_parser.py`

**Context:** Health Connect on the paired phone backs up nightly to Google Drive as a zip containing a SQLite database. `gdrive_sync.py` already downloads files from Drive to `~/documents/rag-sources/gdrive/`. This parser extracts daily health summaries from the SQLite DB and writes markdown files with YAML frontmatter to `~/documents/rag-sources/health-connect/`, where the existing `ingest.py` file watcher picks them up automatically.

**Step 1: Write the failing tests**

Create `~/projects/hapax-council/tests/test_health_connect_parser.py`:

```python
"""Tests for Health Connect SQLite parser — extracts daily summaries."""
from __future__ import annotations

import sqlite3
import tempfile
import zipfile
from pathlib import Path
from unittest.mock import patch

import pytest

from agents.health_connect_parser import (
    extract_zip,
    parse_health_db,
    format_daily_summary,
    write_rag_documents,
    run_parse,
)


class TestExtractZip:
    """Extracts Health Connect backup zip to temp directory."""

    def test_extracts_sqlite_db(self, tmp_path):
        """Finds and extracts the SQLite database from the zip."""
        zip_path = tmp_path / "Health Connect.zip"
        db_data = _create_test_db()
        with zipfile.ZipFile(zip_path, "w") as zf:
            zf.writestr("health_connect.db", db_data)
        result = extract_zip(zip_path, tmp_path / "extracted")
        assert result is not None
        assert result.exists()
        assert result.suffix == ".db"

    def test_returns_none_for_missing_db(self, tmp_path):
        """Returns None when zip contains no SQLite database."""
        zip_path = tmp_path / "empty.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            zf.writestr("readme.txt", "nothing here")
        result = extract_zip(zip_path, tmp_path / "extracted")
        assert result is None


class TestParseHealthDb:
    """Extracts daily aggregates from Health Connect SQLite."""

    def test_extracts_heart_rate_daily(self, health_db_path):
        """Aggregates heart rate readings into daily min/max/mean."""
        days = parse_health_db(health_db_path)
        assert len(days) >= 1
        day = days[0]
        assert "resting_hr" in day
        assert isinstance(day["resting_hr"], (int, float))

    def test_extracts_sleep_sessions(self, health_db_path):
        """Parses sleep session start/end and stage durations."""
        days = parse_health_db(health_db_path)
        day = days[0]
        assert "sleep_start" in day or "sleep_duration_min" in day

    def test_extracts_steps(self, health_db_path):
        """Sums step count per day."""
        days = parse_health_db(health_db_path)
        day = days[0]
        assert "steps" in day
        assert day["steps"] >= 0

    def test_handles_empty_db(self, tmp_path):
        """Returns empty list for database with no health records."""
        db_path = tmp_path / "empty.db"
        conn = sqlite3.connect(str(db_path))
        conn.close()
        days = parse_health_db(db_path)
        assert days == []


class TestFormatDailySummary:
    """Formats daily data as markdown with YAML frontmatter."""

    def test_includes_frontmatter(self):
        """Output has YAML frontmatter with required keys."""
        day = _sample_day_data()
        md = format_daily_summary(day)
        assert md.startswith("---\n")
        assert "content_type: daily_health_summary" in md
        assert "source_service: health_connect" in md
        assert "device: pixel_watch_4" in md

    def test_includes_all_metrics(self):
        """Summary body contains all available metrics."""
        day = _sample_day_data()
        md = format_daily_summary(day)
        assert "Resting HR" in md
        assert "Steps" in md
        assert "Sleep" in md


class TestWriteRagDocuments:
    """Writes daily summaries to rag-sources/health-connect/."""

    def test_writes_markdown_files(self, tmp_path):
        """Creates one .md file per day in output directory."""
        days = [_sample_day_data(), _sample_day_data("2026-03-11")]
        write_rag_documents(days, tmp_path)
        files = list(tmp_path.glob("*.md"))
        assert len(files) == 2

    def test_skips_existing_unchanged(self, tmp_path):
        """Does not overwrite files if content unchanged."""
        days = [_sample_day_data()]
        write_rag_documents(days, tmp_path)
        first_mtime = (tmp_path / "health-2026-03-12.md").stat().st_mtime
        import time; time.sleep(0.05)
        write_rag_documents(days, tmp_path)
        second_mtime = (tmp_path / "health-2026-03-12.md").stat().st_mtime
        assert first_mtime == second_mtime


# ── Fixtures & Helpers ──────────────────────────────────────────────────────

def _create_test_db() -> bytes:
    """Create a minimal Health Connect SQLite database as bytes."""
    import io
    buf = io.BytesIO()
    conn = sqlite3.connect(buf)
    # Schema will match Health Connect export format
    conn.execute("""CREATE TABLE IF NOT EXISTS heart_rate_record (
        uid TEXT, time INTEGER, bpm REAL
    )""")
    conn.execute("INSERT INTO heart_rate_record VALUES ('1', 1741795200, 72.0)")
    conn.execute("INSERT INTO heart_rate_record VALUES ('2', 1741795260, 68.0)")
    conn.commit()
    conn.close()
    buf.seek(0)
    return buf.read()


@pytest.fixture
def health_db_path(tmp_path):
    """Create a test Health Connect SQLite database."""
    db_path = tmp_path / "health_connect.db"
    conn = sqlite3.connect(str(db_path))
    conn.execute("""CREATE TABLE heart_rate_record (
        uid TEXT, time INTEGER, bpm REAL
    )""")
    conn.execute("""CREATE TABLE steps_record (
        uid TEXT, start_time INTEGER, end_time INTEGER, count INTEGER
    )""")
    conn.execute("""CREATE TABLE sleep_session_record (
        uid TEXT, start_time INTEGER, end_time INTEGER
    )""")
    # Populate with test data for 2026-03-12
    base_ts = 1741795200  # epoch for a test date
    for i in range(24):
        conn.execute("INSERT INTO heart_rate_record VALUES (?, ?, ?)",
                     (f"hr-{i}", base_ts + i * 3600, 65 + i % 10))
    conn.execute("INSERT INTO steps_record VALUES ('s1', ?, ?, 8234)",
                 (base_ts, base_ts + 86400))
    conn.execute("INSERT INTO sleep_session_record VALUES ('sl1', ?, ?)",
                 (base_ts - 3600, base_ts + 25200))
    conn.commit()
    conn.close()
    return db_path


def _sample_day_data(date: str = "2026-03-12") -> dict:
    return {
        "date": date,
        "resting_hr": 62,
        "steps": 8234,
        "sleep_start": "23:15",
        "sleep_end": "06:48",
        "sleep_duration_min": 453,
        "active_minutes": 42,
    }
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_health_connect_parser.py -v`
Expected: ImportError — `agents.health_connect_parser` does not exist yet.

**Step 3: Implement the parser**

Create `~/projects/hapax-council/agents/health_connect_parser.py`:

The parser should:
- `extract_zip(zip_path, dest_dir)` -- unzip, find `.db` file, return its path
- `parse_health_db(db_path)` -- open SQLite, query heart_rate_record, steps_record, sleep_session_record tables (gracefully skip missing tables), aggregate per day, return list of day dicts
- `format_daily_summary(day_data)` -- render markdown with YAML frontmatter containing: `content_type: daily_health_summary`, `source_service: health_connect`, `device: pixel_watch_4`, `date: <date>`, `timestamp: <iso>`, `modality_tags: [health, biometric, wearable]`
- `write_rag_documents(days, output_dir)` -- write `health-YYYY-MM-DD.md` files, skip if content unchanged (compare hash)
- `run_parse(zip_path, output_dir)` -- orchestrate: extract -> parse -> format -> write
- CLI: `--parse <zip_path>` (one-shot), `--watch` (scan `~/documents/rag-sources/gdrive/` for new Health Connect zips)

Path constants: `OUTPUT_DIR = RAG_SOURCES_DIR / "health-connect"` (so ingest.py auto-detects `source_service: health_connect`)

**Step 4: Run tests to verify they pass**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_health_connect_parser.py -v`
Expected: All PASS.

**Step 5: Commit**

```bash
cd ~/projects/hapax-council
git add agents/health_connect_parser.py tests/test_health_connect_parser.py
git commit -m "feat(watch): Health Connect SQLite parser with daily summary extraction"
```

---

## Task 3: Health Connect RAG Integration + Ingest Wiring

**Files:**
- Modify: `~/projects/hapax-council/agents/ingest.py` (add `health-connect` to `_SERVICE_PATH_PATTERNS`)
- Modify: `~/projects/hapax-council/agents/profiler_sources.py` (add `health-connect` to BRIDGED_SOURCE_TYPES and SOURCE_TYPE_CHUNK_CAPS)
- Test: `~/projects/hapax-council/tests/test_health_connect_parser.py` (add integration test)

**Context:** The ingest pipeline auto-detects `source_service` from path patterns. Adding `rag-sources/health-connect` to the pattern map ensures health summaries get the correct metadata in Qdrant. The profiler needs a bridged source type so it can load health facts at zero LLM cost.

**Step 1: Add path pattern to ingest.py**

In `~/projects/hapax-council/agents/ingest.py`, add to `_SERVICE_PATH_PATTERNS` dict (around line 390):

```python
"rag-sources/health-connect": "health_connect",
```

**Step 2: Add source type to profiler_sources.py**

In `~/projects/hapax-council/agents/profiler_sources.py`:

Add `"health-connect"` to `BRIDGED_SOURCE_TYPES` set.

Add to `SOURCE_TYPE_CHUNK_CAPS`:
```python
"health-connect": 50,
```

**Step 3: Write integration test**

Add to `tests/test_health_connect_parser.py`:

```python
class TestRagIntegration:
    """Verify health-connect documents are recognized by ingest pipeline."""

    def test_source_service_auto_detected(self):
        """Ingest auto-detects source_service from health-connect path."""
        from agents.ingest import enrich_payload
        payload = {"source": str(Path.home() / "documents/rag-sources/health-connect/health-2026-03-12.md")}
        result = enrich_payload(payload, {})
        assert result.get("source_service") == "health_connect"
```

**Step 4: Run tests**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_health_connect_parser.py::TestRagIntegration -v`
Expected: PASS.

**Step 5: Commit**

```bash
cd ~/projects/hapax-council
git add agents/ingest.py agents/profiler_sources.py tests/test_health_connect_parser.py
git commit -m "feat(watch): wire Health Connect into RAG ingest and profiler source registry"
```

---

## Task 4: Health Connect Systemd Timer

**Files:**
- Create: `~/.config/systemd/user/health-connect-parse.service`
- Create: `~/.config/systemd/user/health-connect-parse.timer`

**Context:** Runs the Health Connect parser daily after gdrive-sync completes, scanning for new Health Connect backup zips in the gdrive output directory.

**Step 1: Create the service unit**

Create `~/.config/systemd/user/health-connect-parse.service`:

```ini
[Unit]
Description=Parse Health Connect backup from Google Drive
After=gdrive-sync.service

[Service]
Type=oneshot
WorkingDirectory=%h/projects/hapax-council
ExecStart=%h/projects/hapax-council/.venv/bin/python -m agents.health_connect_parser --watch
Environment=HAPAX_HOME=%h

[Install]
WantedBy=default.target
```

**Step 2: Create the timer unit**

Create `~/.config/systemd/user/health-connect-parse.timer`:

```ini
[Unit]
Description=Daily Health Connect parsing

[Timer]
OnBootSec=30min
OnUnitActiveSec=24h
Persistent=true

[Install]
WantedBy=timers.target
```

**Step 3: Enable the timer**

```bash
systemctl --user daemon-reload
systemctl --user enable --now health-connect-parse.timer
systemctl --user status health-connect-parse.timer
```

Expected: Timer active, next trigger ~24h from now.

**Step 4: Verify manual run**

```bash
systemctl --user start health-connect-parse.service
journalctl --user -u health-connect-parse.service --no-pager -n 20
```

Expected: Either "No Health Connect backups found" (if no zip yet) or successful parse.

**Step 5: Commit**

```bash
cd ~/projects/distro-work
git add docs/watch-kdeconnect-setup.md  # if not committed yet
git commit -m "ops(watch): systemd timer for daily Health Connect parsing"
```

---

## Phase 3: Real-Time Sensor Streaming

---

## Task 5: Watch Receiver FastAPI Service

**Files:**
- Create: `~/projects/hapax-council/agents/watch_receiver.py`
- Test: `~/projects/hapax-council/tests/test_watch_receiver.py`

**Context:** Minimal FastAPI service that receives batched sensor data from the Wear OS app and writes atomic JSON files to `~/hapax-state/watch/`. Uses the filesystem-as-bus pattern: each file is a complete JSON document, atomically replaced via rename.

**Step 1: Write the failing tests**

Create `~/projects/hapax-council/tests/test_watch_receiver.py`:

```python
"""Tests for watch-receiver FastAPI service."""
from __future__ import annotations

import json
import time
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from agents.watch_receiver import create_app, WATCH_STATE_DIR


@pytest.fixture
def state_dir(tmp_path):
    """Override WATCH_STATE_DIR for tests."""
    with patch("agents.watch_receiver.WATCH_STATE_DIR", tmp_path):
        yield tmp_path


@pytest.fixture
def client(state_dir):
    app = create_app()
    return TestClient(app)


class TestSensorIngestion:
    """POST /watch/sensors writes atomic JSON files."""

    def test_heartrate_creates_file(self, client, state_dir):
        """Heart rate readings create heartrate.json."""
        payload = {
            "ts": int(time.time() * 1000),
            "device_id": "pw4",
            "readings": [
                {"type": "heart_rate", "bpm": 72, "confidence": "HIGH",
                 "ts": "2026-03-12T14:29:55-05:00"}
            ],
        }
        resp = client.post("/watch/sensors", json=payload)
        assert resp.status_code == 200
        hr_file = state_dir / "heartrate.json"
        assert hr_file.exists()
        data = json.loads(hr_file.read_text())
        assert data["current"]["bpm"] == 72

    def test_activity_state_creates_file(self, client, state_dir):
        """Activity state readings create activity.json."""
        payload = {
            "ts": int(time.time() * 1000),
            "device_id": "pw4",
            "readings": [
                {"type": "activity", "state": "WALKING",
                 "ts": "2026-03-12T14:30:00-05:00"}
            ],
        }
        resp = client.post("/watch/sensors", json=payload)
        assert resp.status_code == 200
        act_file = state_dir / "activity.json"
        assert act_file.exists()
        data = json.loads(act_file.read_text())
        assert data["state"] == "WALKING"

    def test_connection_updated_on_any_post(self, client, state_dir):
        """Every sensor POST updates connection.json with last_seen."""
        payload = {
            "ts": int(time.time() * 1000),
            "device_id": "pw4",
            "readings": [],
        }
        resp = client.post("/watch/sensors", json=payload)
        assert resp.status_code == 200
        conn = state_dir / "connection.json"
        assert conn.exists()
        data = json.loads(conn.read_text())
        assert "last_seen_epoch" in data

    def test_atomic_write(self, client, state_dir):
        """Files are written atomically (via tmp + rename)."""
        # Post twice; second should not corrupt first
        for bpm in (65, 80):
            client.post("/watch/sensors", json={
                "ts": int(time.time() * 1000),
                "device_id": "pw4",
                "readings": [{"type": "heart_rate", "bpm": bpm,
                              "confidence": "HIGH", "ts": "2026-03-12T14:30:00-05:00"}],
            })
        data = json.loads((state_dir / "heartrate.json").read_text())
        assert data["current"]["bpm"] == 80

    def test_rejects_unknown_device(self, client, state_dir):
        """Rejects payloads from unrecognized device_id."""
        resp = client.post("/watch/sensors", json={
            "ts": int(time.time() * 1000),
            "device_id": "unknown",
            "readings": [],
        })
        assert resp.status_code == 403


class TestStatusEndpoint:
    """GET /watch/status returns connectivity info."""

    def test_returns_ok(self, client):
        resp = client.get("/watch/status")
        assert resp.status_code == 200
        assert resp.json()["status"] == "ok"


class TestVoiceTrigger:
    """POST /watch/voice-trigger signals the voice daemon."""

    def test_writes_trigger_file(self, client, state_dir):
        """Creates a trigger file for the voice daemon to detect."""
        resp = client.post("/watch/voice-trigger", json={"device_id": "pw4"})
        assert resp.status_code == 200
        trigger = state_dir / "voice_trigger.json"
        assert trigger.exists()


class TestRollingWindow:
    """Heart rate and HRV maintain 1-hour rolling windows."""

    def test_heartrate_window_stats(self, client, state_dir):
        """Window tracks min/max/mean/readings count."""
        for bpm in [60, 70, 80, 90]:
            client.post("/watch/sensors", json={
                "ts": int(time.time() * 1000),
                "device_id": "pw4",
                "readings": [{"type": "heart_rate", "bpm": bpm,
                              "confidence": "HIGH", "ts": "2026-03-12T14:30:00-05:00"}],
            })
        data = json.loads((state_dir / "heartrate.json").read_text())
        assert data["window_1h"]["min"] == 60
        assert data["window_1h"]["max"] == 90
        assert data["window_1h"]["readings"] == 4
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_watch_receiver.py -v`
Expected: ImportError.

**Step 3: Add fastapi/uvicorn dependencies if not present**

Check `pyproject.toml` for fastapi — it is likely already present (used by other agents). If not:

```bash
cd ~/projects/hapax-council && uv add fastapi uvicorn
```

**Step 4: Implement watch_receiver.py**

Create `~/projects/hapax-council/agents/watch_receiver.py`:

Key implementation details:
- `WATCH_STATE_DIR = HAPAX_HOME / "hapax-state" / "watch"` (add this path constant)
- `ALLOWED_DEVICE_IDS = {"pw4"}` — single-user, hardcoded device ID
- `create_app()` returns FastAPI instance with routes
- `_atomic_write(path, data)` -- write to `.tmp` sibling, `os.rename()` over target
- Heart rate, HRV, EDA, skin_temp maintain in-memory rolling 1-hour deques, serialized to JSON on each write
- Activity state is a simple current-value file
- Connection.json updated on every POST with `last_seen_epoch` and optional `battery_pct`
- Voice trigger writes a timestamped trigger file
- CLI: `uvicorn agents.watch_receiver:app --host 0.0.0.0 --port 8042`

**Step 5: Run tests to verify they pass**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_watch_receiver.py -v`
Expected: All PASS.

**Step 6: Commit**

```bash
cd ~/projects/hapax-council
git add agents/watch_receiver.py tests/test_watch_receiver.py
git commit -m "feat(watch): FastAPI sensor receiver with atomic filesystem-as-bus output"
```

---

## Task 6: Watch Receiver Systemd Service + Firewall

**Files:**
- Create: `~/.config/systemd/user/hapax-watch-receiver.service`

**Context:** The receiver runs as a persistent systemd user service, listening on port 8042. Firewall rule restricts access to LAN only.

**Step 1: Create the service unit**

Create `~/.config/systemd/user/hapax-watch-receiver.service`:

```ini
[Unit]
Description=Hapax Watch Sensor Receiver
After=network.target

[Service]
Type=simple
WorkingDirectory=%h/projects/hapax-council
ExecStart=%h/projects/hapax-council/.venv/bin/uvicorn agents.watch_receiver:app --host 0.0.0.0 --port 8042
Environment=HAPAX_HOME=%h
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

**Step 2: Enable and start**

```bash
systemctl --user daemon-reload
systemctl --user enable --now hapax-watch-receiver.service
systemctl --user status hapax-watch-receiver.service
```

**Step 3: Add firewall rule**

```bash
sudo ufw allow from 192.168.68.0/24 to any port 8042 proto tcp comment "watch-receiver LAN only"
sudo ufw status | grep 8042
```

Expected: Rule allowing TCP 8042 from LAN.

**Step 4: Verify endpoint responds**

```bash
curl -s http://localhost:8042/watch/status | python -m json.tool
```

Expected: `{"status": "ok", ...}`

**Step 5: Create state directory**

```bash
mkdir -p ~/hapax-state/watch
ls -la ~/hapax-state/watch/
```

**Step 6: Commit**

```bash
cd ~/projects/distro-work
git commit -m "ops(watch): systemd service and UFW rule for watch-receiver on port 8042"
```

---

## Task 7: Wear OS App — Project Scaffold (Android Studio)

**Files:**
- Create: `~/projects/hapax-watch/` (new Android project)

**Context:** This task is done in Android Studio, not the Python ecosystem. The Wear OS app (`hapax-watch`) is a Kotlin project using Jetpack Compose for Wear OS, targeting API 34 (Wear OS 5+). This scaffold sets up the project structure, Gradle dependencies, and a "hello world" that compiles and deploys to the watch.

**Step 1: Create project in Android Studio**

- New Project -> Wear OS -> Blank Activity (Compose)
- Package: `dev.hapax.watch`
- Min SDK: API 34 (Wear OS 5)
- Target SDK: API 35
- Kotlin, Gradle Kotlin DSL

**Step 2: Add Gradle dependencies**

In `app/build.gradle.kts`, add:

```kotlin
// Health Services
implementation("androidx.health:health-services-client:1.1.0-alpha05")

// Tiles
implementation("androidx.wear.tiles:tiles:1.4.1")
implementation("androidx.wear.tiles:tiles-material3:1.4.1")
implementation("androidx.wear.protolayout:protolayout:1.3.0")
implementation("androidx.wear.protolayout:protolayout-material3:1.3.0")
implementation("androidx.wear.protolayout:protolayout-expression:1.3.0")

// Network
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

// mDNS/NSD
// (android.net.nsd.NsdManager is part of the platform SDK, no extra dep)

// WorkManager for background scheduling
implementation("androidx.work:work-runtime-ktx:2.10.0")

// Foreground service
implementation("androidx.core:core-ktx:1.15.0")
```

**Step 3: Configure AndroidManifest.xml permissions**

```xml
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

**Step 4: Build and deploy**

```bash
cd ~/projects/hapax-watch
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: App installs on watch, shows Compose "Hello" screen.

**Step 5: Initialize git repo**

```bash
cd ~/projects/hapax-watch
git init
echo -e "build/\n.gradle/\n*.apk\nlocal.properties\n.idea/" > .gitignore
git add .
git commit -m "feat(watch): scaffold Wear OS project with Health Services and Tiles deps"
```

---

## Task 8: Wear OS App — Foreground Sensor Service

**Files:**
- Create: `app/src/main/java/dev/hapax/watch/service/SensorService.kt`
- Create: `app/src/main/java/dev/hapax/watch/data/SensorReading.kt`
- Create: `app/src/main/java/dev/hapax/watch/data/SensorBatch.kt`
- Test: `app/src/test/java/dev/hapax/watch/service/SensorServiceTest.kt`

**Context:** Foreground service that reads heart rate, HRV, EDA, skin temperature, and activity state via the Health Services API. Batches readings in memory and exposes them for the transport layer (Task 9).

**Step 1: Define data models**

Create `SensorReading.kt` and `SensorBatch.kt` with kotlinx.serialization annotations. `SensorReading` has: type (enum: HEART_RATE, HRV, EDA, SKIN_TEMP, ACTIVITY), value (Double or String for activity state), confidence (String), timestamp (Long epoch_ms). `SensorBatch` has: ts (Long), device_id (String = "pw4"), readings (List<SensorReading>).

**Step 2: Implement SensorService**

- Extends `LifecycleService` (foreground service)
- Registers `MeasureClient` callbacks for heart_rate (1 Hz) and HRV
- Registers `PassiveMonitoringClient` for EDA, skin_temp, activity_state
- Batches readings into in-memory list
- Exposes `fun drainBatch(): SensorBatch` (returns current batch, clears buffer)
- Persistent notification: "Hapax connected" with connection status icon
- `ConnectivityManager.requestNetwork()` to hold WiFi

**Step 3: Write unit tests**

Test that: SensorReading serializes correctly, SensorBatch drainBatch returns readings and clears buffer, batch size limits work (5-min max buffer).

**Step 4: Build and verify**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Expected: Tests pass, APK builds. Service can be started on watch (sensor readings logged to logcat).

**Step 5: Commit**

```bash
cd ~/projects/hapax-watch
git add app/src/
git commit -m "feat(watch): foreground sensor service with Health Services API"
```

---

## Task 9: Wear OS App — WiFi Transport with mDNS Discovery

**Files:**
- Create: `app/src/main/java/dev/hapax/watch/network/WorkstationDiscovery.kt`
- Create: `app/src/main/java/dev/hapax/watch/network/SensorTransport.kt`
- Test: `app/src/test/java/dev/hapax/watch/network/SensorTransportTest.kt`

**Context:** Discovers the workstation via mDNS (`hapax-workstation.local`), then POSTs sensor batches to the receiver on port 8042 at configurable intervals (30s default). Implements exponential backoff on failure.

**Step 1: Implement mDNS discovery**

`WorkstationDiscovery.kt` uses `NsdManager` to discover `_hapax._tcp` service type (or falls back to hardcoded hostname). Caches resolved IP for 5 minutes.

**Step 2: Implement transport**

`SensorTransport.kt`:
- Runs on a coroutine scope tied to SensorService lifecycle
- Every 30s: calls `sensorService.drainBatch()`, POST to `http://<workstation>:8042/watch/sensors`
- On success: reset backoff
- On failure: exponential backoff (30s -> 60s -> 2min -> stop until next batch)
- In-memory buffer capped at 5 min of readings; discard oldest on overflow
- Logs connection state changes (connected/buffering/disconnected)

**Step 3: Register mDNS on workstation**

On the workstation, advertise via avahi:

```bash
# Create /etc/avahi/services/hapax.service
sudo tee /etc/avahi/services/hapax.service << 'EOF'
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name>hapax-workstation</name>
  <service>
    <type>_hapax._tcp</type>
    <port>8042</port>
  </service>
</service-group>
EOF
sudo systemctl restart avahi-daemon
```

**Step 4: Write unit tests**

Test serialization, backoff logic, buffer overflow discard policy. Mock OkHttp for transport tests.

**Step 5: Integration test**

Deploy to watch, verify sensor data appears in `~/hapax-state/watch/heartrate.json`:

```bash
watch -n 2 cat ~/hapax-state/watch/heartrate.json
```

Expected: File updates every ~30s with current heart rate.

**Step 6: Commit**

```bash
cd ~/projects/hapax-watch
git add app/src/
git commit -m "feat(watch): WiFi transport with mDNS discovery and exponential backoff"
```

---

## Task 10: Wear OS App — Hapax Tile

**Files:**
- Create: `app/src/main/java/dev/hapax/watch/tile/HapaxTileService.kt`
- Create: `app/src/main/java/dev/hapax/watch/tile/HapaxTileRenderer.kt`

**Context:** Wear OS Tile showing connection status (green/amber/red dot), last system notification summary, and a voice trigger quick action. Uses ProtoLayout Material 3 Expressive.

**Step 1: Implement TileService**

`HapaxTileService.kt` extends `TileService`:
- Reads connection state from SensorTransport (connected/buffering/disconnected)
- Reads last notification text (stored by notification listener, Task 13)
- Voice trigger button sends POST to `/watch/voice-trigger`

**Step 2: Implement TileRenderer**

`HapaxTileRenderer.kt`:
- Green circle when connected, amber when buffering, red when disconnected
- Single-line last notification text (truncated to 40 chars)
- Mic icon button for voice trigger

**Step 3: Register in AndroidManifest.xml**

```xml
<service android:name=".tile.HapaxTileService"
    android:exported="true"
    android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
    <intent-filter>
        <action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" />
    </intent-filter>
</service>
```

**Step 4: Build and test on watch**

Deploy APK, add tile to watch face carousel, verify status dot updates and voice trigger button sends POST.

**Step 5: Commit**

```bash
cd ~/projects/hapax-watch
git add app/src/
git commit -m "feat(watch): Hapax Tile with connection status, notification, and voice trigger"
```

---

## Phase 4: Agent Consumption

---

## Task 11: Voice Daemon — Stress-Aware Context Gate Layer

**Files:**
- Modify: `~/projects/hapax-council/agents/hapax_voice/context_gate.py`
- Create: `~/projects/hapax-council/agents/hapax_voice/watch_signals.py`
- Test: `~/projects/hapax-council/tests/hapax_voice/test_watch_signals.py`

**Context:** Adds a new veto to the ContextGate's VetoChain at position 2 (after active-session check, before PipeWire volume). Reads EDA and HRV JSON files from `~/hapax-state/watch/`. If EDA spike detected or HRV dropped >30% from 1-hour mean in last 5 minutes, suppresses non-urgent interruptions. Falls back to no-op if watch files absent or stale (>5 min).

**Step 1: Write the failing tests**

Create `~/projects/hapax-council/tests/hapax_voice/test_watch_signals.py`:

```python
"""Tests for watch signal reading and stress detection."""
from __future__ import annotations

import json
import time
from pathlib import Path
from unittest.mock import patch

import pytest

from agents.hapax_voice.watch_signals import (
    read_watch_signal,
    is_stress_elevated,
    WatchSignalReader,
)


class TestReadWatchSignal:
    """Reading JSON files from hapax-state/watch/."""

    def test_reads_valid_file(self, tmp_path):
        """Returns parsed JSON for a valid, fresh file."""
        f = tmp_path / "heartrate.json"
        f.write_text(json.dumps({
            "current": {"bpm": 72},
            "updated_at": "2026-03-12T14:30:00-05:00",
            "window_1h": {"min": 58, "max": 95, "mean": 71, "readings": 120},
        }))
        result = read_watch_signal(f, max_age_seconds=300)
        assert result is not None
        assert result["current"]["bpm"] == 72

    def test_returns_none_for_missing_file(self, tmp_path):
        """Returns None when file does not exist."""
        result = read_watch_signal(tmp_path / "nonexistent.json", max_age_seconds=300)
        assert result is None

    def test_returns_none_for_stale_file(self, tmp_path):
        """Returns None when file is older than max_age_seconds."""
        f = tmp_path / "heartrate.json"
        f.write_text(json.dumps({"current": {"bpm": 72}}))
        import os
        old_time = time.time() - 600
        os.utime(f, (old_time, old_time))
        result = read_watch_signal(f, max_age_seconds=300)
        assert result is None


class TestStressDetection:
    """Composite stress signal from EDA + HRV."""

    def test_elevated_when_hrv_dropped(self, tmp_path):
        """Stress elevated when HRV dropped >30% from 1h mean."""
        hrv = tmp_path / "hrv.json"
        hrv.write_text(json.dumps({
            "current": {"rmssd_ms": 20},
            "window_1h": {"mean": 45},
            "updated_at": "2026-03-12T14:30:00-05:00",
        }))
        assert is_stress_elevated(watch_dir=tmp_path) is True

    def test_not_elevated_normal_hrv(self, tmp_path):
        """Stress not elevated with normal HRV."""
        hrv = tmp_path / "hrv.json"
        hrv.write_text(json.dumps({
            "current": {"rmssd_ms": 42},
            "window_1h": {"mean": 45},
            "updated_at": "2026-03-12T14:30:00-05:00",
        }))
        eda = tmp_path / "eda.json"
        eda.write_text(json.dumps({
            "current": {"eda_event": False},
            "updated_at": "2026-03-12T14:30:00-05:00",
        }))
        assert is_stress_elevated(watch_dir=tmp_path) is False

    def test_elevated_when_eda_spike(self, tmp_path):
        """Stress elevated on EDA spike event."""
        eda = tmp_path / "eda.json"
        eda.write_text(json.dumps({
            "current": {"eda_event": True, "duration_seconds": 180},
            "updated_at": "2026-03-12T14:30:00-05:00",
        }))
        hrv = tmp_path / "hrv.json"
        hrv.write_text(json.dumps({
            "current": {"rmssd_ms": 40},
            "window_1h": {"mean": 45},
            "updated_at": "2026-03-12T14:30:00-05:00",
        }))
        assert is_stress_elevated(watch_dir=tmp_path) is True

    def test_not_elevated_when_no_watch_data(self, tmp_path):
        """Returns False (graceful degradation) when no watch data."""
        assert is_stress_elevated(watch_dir=tmp_path) is False
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/hapax-council && uv run pytest tests/hapax_voice/test_watch_signals.py -v`
Expected: ImportError.

**Step 3: Implement watch_signals.py**

Create `~/projects/hapax-council/agents/hapax_voice/watch_signals.py`:

- `WATCH_STATE_DIR = HAPAX_HOME / "hapax-state" / "watch"` (or parameterized)
- `read_watch_signal(path, max_age_seconds=300)` -- read JSON, check mtime, return dict or None
- `is_stress_elevated(watch_dir=None)` -- read EDA + HRV, return True if either: HRV current < 70% of 1h mean, or EDA event with duration > 120s. Both conditions require sustained signal (not single-reading spikes). Returns False if no data (graceful degradation).
- `WatchSignalReader` class for cached reads (avoids re-reading files on every gate check)

**Step 4: Wire into ContextGate**

In `~/projects/hapax-council/agents/hapax_voice/context_gate.py`:

- Import `is_stress_elevated` from `watch_signals`
- Add a new `Veto` to the VetoChain: `_check_stress_elevated()` that calls `is_stress_elevated()` and vetoes if True with reason "stress elevated (watch EDA/HRV)"
- Insert after the active-session check (position 2 in the chain)
- The veto should only suppress non-urgent notifications (check notification priority if available)

**Step 5: Run tests to verify they pass**

Run: `cd ~/projects/hapax-council && uv run pytest tests/hapax_voice/test_watch_signals.py -v`
Expected: All PASS.

Run: `cd ~/projects/hapax-council && uv run pytest tests/hapax_voice/ -v -x`
Expected: All existing tests still pass (no regressions).

**Step 6: Commit**

```bash
cd ~/projects/hapax-council
git add agents/hapax_voice/watch_signals.py agents/hapax_voice/context_gate.py tests/hapax_voice/test_watch_signals.py
git commit -m "feat(voice): stress-aware context gate layer from watch EDA and HRV signals"
```

---

## Task 12: Voice Daemon — Haptic Presence Verification Path

**Files:**
- Modify: `~/projects/hapax-council/agents/hapax_voice/presence.py`
- Modify: `~/projects/hapax-council/agents/hapax_voice/watch_signals.py` (add `is_watch_connected`, `send_haptic_tap`)
- Test: `~/projects/hapax-council/tests/hapax_voice/test_watch_signals.py` (add presence tests)

**Context:** Alternative to the audio chime for presence verification. When watch is connected, sends a haptic tap via KDE Connect, then waits 3s for a wrist-raise (watch POSTs `/watch/voice-trigger`). Falls back to audio chime if watch not connected or no response.

**Step 1: Add watch presence tests**

Add to `tests/hapax_voice/test_watch_signals.py`:

```python
class TestWatchPresence:
    """Haptic presence verification via watch."""

    def test_watch_connected_when_fresh_data(self, tmp_path):
        conn = tmp_path / "connection.json"
        conn.write_text(json.dumps({
            "last_seen_epoch": time.time(),
            "battery_pct": 85,
        }))
        assert is_watch_connected(watch_dir=tmp_path) is True

    def test_watch_disconnected_when_stale(self, tmp_path):
        conn = tmp_path / "connection.json"
        conn.write_text(json.dumps({
            "last_seen_epoch": time.time() - 600,
            "battery_pct": 85,
        }))
        assert is_watch_connected(watch_dir=tmp_path) is False

    def test_watch_disconnected_when_no_file(self, tmp_path):
        assert is_watch_connected(watch_dir=tmp_path) is False
```

**Step 2: Implement watch connection check and haptic tap**

In `watch_signals.py`:
- `is_watch_connected(watch_dir=None)` -- check `connection.json` mtime < 60s
- `send_haptic_tap(pattern="hapax-presence-check")` -- send KDE Connect notification with the right tag (uses `kdeconnect-cli --ping-msg` or notification API)

**Step 3: Wire into PresenceDetector**

In `presence.py`:
- Add alternative verification path: if `is_watch_connected()`, try haptic first
- Watch for `voice_trigger.json` file appearing within 3s timeout
- If trigger received -> presence confirmed
- If timeout -> fall back to audio chime (existing path)

**Step 4: Run tests**

Run: `cd ~/projects/hapax-council && uv run pytest tests/hapax_voice/test_watch_signals.py -v`
Expected: All PASS.

**Step 5: Commit**

```bash
cd ~/projects/hapax-council
git add agents/hapax_voice/watch_signals.py agents/hapax_voice/presence.py tests/hapax_voice/test_watch_signals.py
git commit -m "feat(voice): haptic presence verification via watch with audio chime fallback"
```

---

## Task 13: Wear OS App — Custom Haptic Patterns + Notification Listener

**Files:**
- Create: `app/src/main/java/dev/hapax/watch/notification/HapaxNotificationListener.kt`
- Create: `app/src/main/java/dev/hapax/watch/haptics/HapticPatterns.kt`

**Context:** The watch app listens for KDE Connect notifications tagged with `hapax-*` channels and applies custom vibration patterns instead of the system default. Also stores last notification text for the Tile.

**Step 1: Implement HapticPatterns**

```kotlin
object HapticPatterns {
    val PRESENCE_CHECK = longArrayOf(0, 100, 150, 100)      // tap-pause-tap
    val URGENT = longArrayOf(0, 500, 200, 500)               // long-pause-long
    val BRIEFING = longArrayOf(0, 50, 100, 50, 100, 50)     // three gentle taps
    val VOICE_READY = longArrayOf(0, 200)                     // single strong tap
}
```

**Step 2: Implement NotificationListener**

`HapaxNotificationListener` extends `NotificationListenerService`:
- Filters for notifications from KDE Connect package
- Parses notification text for `hapax-*` channel tags
- Routes to appropriate haptic pattern
- Stores last notification summary for Tile access

**Step 3: Register in AndroidManifest.xml**

```xml
<service android:name=".notification.HapaxNotificationListener"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

**Step 4: Test on watch**

Send tagged notifications from workstation:

```bash
curl -H "Title: hapax-presence-check" -d "Presence check" http://localhost:8090/hapax
```

Verify custom haptic pattern fires on watch.

**Step 5: Commit**

```bash
cd ~/projects/hapax-watch
git add app/src/
git commit -m "feat(watch): custom haptic patterns and notification listener for hapax channels"
```

---

## Task 14: Profiler — Watch Data Extraction for Dimension 7

**Files:**
- Modify: `~/projects/hapax-council/agents/profiler_sources.py` (add watch source reader)
- Modify: `~/projects/hapax-council/shared/dimensions.py` (add watch to `energy_and_attention` primary_sources)
- Test: `~/projects/hapax-council/tests/test_profiler_watch.py`

**Context:** The profiler's 6-hourly extraction cycle should read watch JSON files and produce Observation-authority facts for the `energy_and_attention` dimension. This is a bridged source (zero LLM cost) — deterministic fact extraction from structured data.

**Step 1: Write the failing tests**

Create `~/projects/hapax-council/tests/test_profiler_watch.py`:

```python
"""Tests for profiler watch data extraction."""
from __future__ import annotations

import json
import time
from pathlib import Path

import pytest


class TestWatchSourceReader:
    """Extracts profile facts from watch state files."""

    def test_extracts_resting_hr(self, watch_state_dir):
        from agents.profiler_sources import read_watch_facts
        facts = read_watch_facts(watch_state_dir)
        hr_facts = [f for f in facts if f["key"] == "health.resting_hr"]
        assert len(hr_facts) == 1
        assert hr_facts[0]["dimension"] == "energy_and_attention"
        assert hr_facts[0]["authority"] == "observation"

    def test_extracts_hrv_baseline(self, watch_state_dir):
        from agents.profiler_sources import read_watch_facts
        facts = read_watch_facts(watch_state_dir)
        hrv_facts = [f for f in facts if f["key"] == "health.hrv_baseline"]
        assert len(hrv_facts) == 1

    def test_extracts_active_minutes(self, watch_state_dir):
        from agents.profiler_sources import read_watch_facts
        facts = read_watch_facts(watch_state_dir)
        active = [f for f in facts if f["key"] == "health.active_minutes"]
        assert len(active) == 1

    def test_returns_empty_when_no_watch_data(self, tmp_path):
        from agents.profiler_sources import read_watch_facts
        facts = read_watch_facts(tmp_path)
        assert facts == []

    def test_facts_are_observation_authority(self, watch_state_dir):
        from agents.profiler_sources import read_watch_facts
        facts = read_watch_facts(watch_state_dir)
        for fact in facts:
            assert fact["authority"] == "observation"


@pytest.fixture
def watch_state_dir(tmp_path):
    """Create a watch state directory with sample data."""
    (tmp_path / "heartrate.json").write_text(json.dumps({
        "source": "pixel_watch_4",
        "updated_at": "2026-03-12T14:30:00-05:00",
        "current": {"bpm": 72, "confidence": "HIGH"},
        "window_1h": {"min": 58, "max": 95, "mean": 71, "readings": 120},
    }))
    (tmp_path / "hrv.json").write_text(json.dumps({
        "current": {"rmssd_ms": 42},
        "window_1h": {"mean": 45},
        "updated_at": "2026-03-12T14:30:00-05:00",
    }))
    (tmp_path / "activity.json").write_text(json.dumps({
        "state": "STILL",
        "active_minutes_today": 35,
        "updated_at": "2026-03-12T14:30:00-05:00",
    }))
    (tmp_path / "connection.json").write_text(json.dumps({
        "last_seen_epoch": time.time(),
        "battery_pct": 78,
    }))
    return tmp_path
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_profiler_watch.py -v`
Expected: ImportError on `read_watch_facts`.

**Step 3: Implement read_watch_facts**

In `~/projects/hapax-council/agents/profiler_sources.py`:

Add `read_watch_facts(watch_dir=None)` function:
- Default `watch_dir` to `HAPAX_HOME / "hapax-state" / "watch"`
- Read heartrate.json, hrv.json, activity.json, eda.json, skin_temp.json
- Skip missing/stale files gracefully
- Generate facts: `health.resting_hr`, `health.hrv_baseline`, `health.stress_events_per_day`, `health.temp_deviation`, `health.active_minutes`, `health.sleep_window`
- All facts have `dimension: "energy_and_attention"`, `authority: "observation"`, `source: "watch:pixel_watch_4"`

Add `"watch"` to `BRIDGED_SOURCE_TYPES`.

**Step 4: Update dimensions.py**

In `~/projects/hapax-council/shared/dimensions.py`, add `"watch"` to the `primary_sources` tuple of the `energy_and_attention` dimension.

**Step 5: Run tests to verify they pass**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_profiler_watch.py -v`
Expected: All PASS.

Run: `cd ~/projects/hapax-council && uv run pytest tests/ -v -x --ignore=tests/hapax_voice/test_audio_hardware.py`
Expected: No regressions.

**Step 6: Commit**

```bash
cd ~/projects/hapax-council
git add agents/profiler_sources.py shared/dimensions.py tests/test_profiler_watch.py
git commit -m "feat(profiler): watch data extraction for energy_and_attention dimension"
```

---

## Task 15: Briefing Agent — Activity-Aware Delivery Gating

**Files:**
- Modify: `~/projects/hapax-council/agents/briefing.py`
- Test: `~/projects/hapax-council/tests/test_briefing_watch.py`

**Context:** The briefing timer fires at 07:00 but delivery should be gated on watch activity state. If the operator is still asleep (STILL state), wait and poll every 5 minutes until activity detected or 09:00 hard deadline. Degrades gracefully to immediate delivery when watch data absent.

**Step 1: Write the failing tests**

Create `~/projects/hapax-council/tests/test_briefing_watch.py`:

```python
"""Tests for activity-aware briefing delivery gating."""
from __future__ import annotations

import json
import time
from pathlib import Path
from unittest.mock import patch, AsyncMock

import pytest

from agents.hapax_voice.watch_signals import read_watch_signal


class TestActivityGating:
    """Briefing delivery gated on watch activity state."""

    def test_delivers_immediately_when_active(self, tmp_path):
        """Delivers when activity state shows WALKING."""
        from agents.briefing import should_deliver_briefing
        activity = tmp_path / "activity.json"
        activity.write_text(json.dumps({
            "state": "WALKING",
            "updated_at": "2026-03-12T07:05:00-05:00",
        }))
        assert should_deliver_briefing(watch_dir=tmp_path) is True

    def test_waits_when_still(self, tmp_path):
        """Defers when activity state is STILL (asleep)."""
        from agents.briefing import should_deliver_briefing
        activity = tmp_path / "activity.json"
        activity.write_text(json.dumps({
            "state": "STILL",
            "updated_at": "2026-03-12T07:00:00-05:00",
        }))
        assert should_deliver_briefing(watch_dir=tmp_path) is False

    def test_delivers_when_no_watch_data(self, tmp_path):
        """Delivers immediately (graceful degradation) when no watch data."""
        from agents.briefing import should_deliver_briefing
        assert should_deliver_briefing(watch_dir=tmp_path) is True

    def test_delivers_at_hard_deadline(self, tmp_path):
        """Delivers at 09:00 regardless of activity state."""
        from agents.briefing import should_deliver_briefing
        activity = tmp_path / "activity.json"
        activity.write_text(json.dumps({
            "state": "STILL",
            "updated_at": "2026-03-12T09:01:00-05:00",
        }))
        assert should_deliver_briefing(
            watch_dir=tmp_path, current_hour=9, current_minute=1
        ) is True
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_briefing_watch.py -v`
Expected: ImportError on `should_deliver_briefing`.

**Step 3: Implement should_deliver_briefing**

In `~/projects/hapax-council/agents/briefing.py`:

Add `should_deliver_briefing(watch_dir=None, current_hour=None, current_minute=None)`:
- Read `activity.json` from watch_dir (default `~/hapax-state/watch/`)
- If no file or stale -> return True (deliver immediately, graceful degradation)
- If state is WALKING, ACTIVE, or any non-STILL -> return True
- If state is STILL and before 09:00 -> return False (wait)
- If 09:00 or later -> return True (hard deadline)

Wire into the main briefing flow: after timer fires, loop `should_deliver_briefing()` with 5-min sleeps until True.

**Step 4: Run tests to verify they pass**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_briefing_watch.py -v`
Expected: All PASS.

**Step 5: Commit**

```bash
cd ~/projects/hapax-council
git add agents/briefing.py tests/test_briefing_watch.py
git commit -m "feat(briefing): activity-aware delivery gating via watch state"
```

---

## Task 16: Health Monitor — Watch Connectivity Check (#37)

**Files:**
- Modify: `~/projects/hapax-council/agents/health_monitor.py`
- Test: `~/projects/hapax-council/tests/test_health_monitor_watch.py`

**Context:** Add check #37 to the existing health monitor. Non-critical (WARN only, never FAIL). Checks that watch data files exist and are recent. SKIP if watch integration not active (no files at all).

**Step 1: Write the failing tests**

Create `~/projects/hapax-council/tests/test_health_monitor_watch.py`:

```python
"""Tests for watch connectivity health check."""
from __future__ import annotations

import json
import time
from pathlib import Path
from unittest.mock import patch

import pytest

from agents.health_monitor import Status


class TestWatchConnectivityCheck:
    """Check #37: watch connectivity."""

    @pytest.mark.asyncio
    async def test_healthy_when_connected(self, tmp_path):
        from agents.health_monitor import check_watch_connected
        conn = tmp_path / "connection.json"
        conn.write_text(json.dumps({
            "last_seen_epoch": time.time(),
            "battery_pct": 78,
        }))
        with patch("agents.health_monitor.WATCH_STATE_DIR", tmp_path):
            results = await check_watch_connected()
        assert len(results) == 1
        assert results[0].status == Status.HEALTHY
        assert "battery 78%" in results[0].message

    @pytest.mark.asyncio
    async def test_degraded_when_stale(self, tmp_path):
        from agents.health_monitor import check_watch_connected
        conn = tmp_path / "connection.json"
        conn.write_text(json.dumps({
            "last_seen_epoch": time.time() - 600,
            "battery_pct": 78,
        }))
        with patch("agents.health_monitor.WATCH_STATE_DIR", tmp_path):
            results = await check_watch_connected()
        assert results[0].status == Status.DEGRADED
        assert "last seen" in results[0].message

    @pytest.mark.asyncio
    async def test_skip_when_not_configured(self, tmp_path):
        from agents.health_monitor import check_watch_connected
        with patch("agents.health_monitor.WATCH_STATE_DIR", tmp_path):
            results = await check_watch_connected()
        assert results[0].status == Status.HEALTHY
        assert "not configured" in results[0].message
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_health_monitor_watch.py -v`
Expected: ImportError on `check_watch_connected`.

**Step 3: Implement check_watch_connected**

In `~/projects/hapax-council/agents/health_monitor.py`:

Add constant: `WATCH_STATE_DIR = HAPAX_HOME / "hapax-state" / "watch"` (import HAPAX_HOME from shared.config)

Add check function following existing pattern:

```python
@check_group("connectivity")
async def check_watch_connected() -> list[CheckResult]:
    """Check if Pixel Watch is streaming (non-critical, informational)."""
    t = time.monotonic()
    conn_file = WATCH_STATE_DIR / "connection.json"
    if not conn_file.exists():
        return [CheckResult(
            name="connectivity.watch",
            group="connectivity",
            status=Status.HEALTHY,
            message="not configured",
            duration_ms=_timed(t),
            tier=3,  # optional
        )]
    data = json.loads(conn_file.read_text())
    age = time.time() - data["last_seen_epoch"]
    battery = data.get("battery_pct", "?")
    if age > 300:
        return [CheckResult(
            name="connectivity.watch",
            group="connectivity",
            status=Status.DEGRADED,
            message=f"Watch last seen {age/60:.0f}m ago (battery {battery}%)",
            duration_ms=_timed(t),
            tier=3,
        )]
    return [CheckResult(
        name="connectivity.watch",
        group="connectivity",
        status=Status.HEALTHY,
        message=f"Watch connected, battery {battery}%",
        duration_ms=_timed(t),
        tier=3,
    )]
```

**Step 4: Run tests to verify they pass**

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_health_monitor_watch.py -v`
Expected: All PASS.

Run: `cd ~/projects/hapax-council && uv run pytest tests/test_agents.py -v -x`
Expected: No regressions in existing health monitor tests.

**Step 5: Commit**

```bash
cd ~/projects/hapax-council
git add agents/health_monitor.py tests/test_health_monitor_watch.py
git commit -m "feat(health): add watch connectivity check #37 (non-critical, tier 3)"
```

---

## Phase 5: Voice Relay (Stretch / Deferred)

---

## Task 17: Voice Relay — Design Spike (DEFERRED)

**Files:**
- Create: `~/projects/distro-work/docs/plans/watch-voice-relay-design.md` (design notes only)

**Context:** This task is deferred until Phase 3 and 4 are proven stable. The voice relay requires WebSocket audio transport from the watch, Opus encode/decode on both ends, PipeWire virtual source injection on the workstation, and session management. Estimated 1-2 weeks of implementation.

**When ready to implement, the subtasks are:**

1. WebSocket endpoint in watch-receiver (`/watch/voice` upgrade to WebSocket)
2. Opus encode in Wear OS app (MediaCodec API, Opus codec)
3. PipeWire virtual source creation and audio injection in watch-receiver
4. Opus decode + TTS capture + stream back to watch
5. Session management (wrist-raise trigger, timeout, handoff to desk mic)
6. Battery impact testing and adaptive quality

**This task is a placeholder.** When the time comes, write a full design doc and decompose into a separate implementation plan.

**Step 1: Document decision to defer**

Write `~/projects/distro-work/docs/plans/watch-voice-relay-design.md` with the rationale for deferral and the high-level subtask breakdown above.

**Step 2: Commit**

```bash
cd ~/projects/distro-work
git add docs/plans/watch-voice-relay-design.md
git commit -m "docs(watch): voice relay design spike — deferred until Phase 3/4 stable"
```

---

## Task 18: End-to-End Integration Verification

**Files:** None (verification only)

**Step 1: Verify Phase 1 — notification chain**

```bash
curl -H "Title: hapax-presence-check" -d "Integration test" http://localhost:8090/hapax
```

Expected: Notification on watch with custom haptic pattern.

**Step 2: Verify Phase 2 — health data in RAG**

```bash
cd ~/projects/hapax-council
uv run python -m agents.health_connect_parser --watch
# Check Qdrant for health documents
curl -s http://localhost:6333/collections/documents/points/scroll -H 'Content-Type: application/json' \
  -d '{"filter": {"must": [{"key": "source_service", "match": {"value": "health_connect"}}]}, "limit": 5}' | python -m json.tool
```

Expected: Health summary documents in Qdrant with correct metadata.

**Step 3: Verify Phase 3 — real-time data flowing**

```bash
cat ~/hapax-state/watch/heartrate.json | python -m json.tool
cat ~/hapax-state/watch/connection.json | python -m json.tool
systemctl --user status hapax-watch-receiver.service
```

Expected: Fresh JSON files, service healthy.

**Step 4: Verify Phase 4 — agent consumption**

```bash
# Health monitor shows watch check
cd ~/projects/hapax-council && uv run python -m agents.health_monitor --check connectivity --json | python -m json.tool | grep watch

# Full test suite
uv run pytest tests/ -v -x --ignore=tests/hapax_voice/test_audio_hardware.py
```

Expected: Watch connectivity check appears. All tests pass.

**Step 5: Run profiler with watch data**

```bash
cd ~/projects/hapax-council && uv run python -m agents.profiler --source watch --show
```

Expected: Health dimension facts from watch data at Observation authority.

---

## Summary

| Task | Phase | Description | Repo | Est. |
|------|-------|-------------|------|------|
| 1 | 1 | KDE Connect setup + notification chain | distro-work | Setup |
| 2 | 2 | Health Connect SQLite parser | hapax-council | Core |
| 3 | 2 | RAG ingest + profiler source wiring | hapax-council | Wiring |
| 4 | 2 | Health Connect systemd timer | distro-work | Operations |
| 5 | 3 | Watch receiver FastAPI service | hapax-council | Core |
| 6 | 3 | Receiver systemd service + UFW | distro-work | Operations |
| 7 | 3 | Wear OS app scaffold | hapax-watch | Setup |
| 8 | 3 | Foreground sensor service (Kotlin) | hapax-watch | Core |
| 9 | 3 | WiFi transport + mDNS discovery | hapax-watch | Core |
| 10 | 3 | Hapax Tile | hapax-watch | Feature |
| 11 | 4 | Voice gate — stress-aware layer | hapax-council | Integration |
| 12 | 4 | Voice — haptic presence verification | hapax-council | Integration |
| 13 | 4 | Custom haptic patterns (Kotlin) | hapax-watch | Feature |
| 14 | 4 | Profiler — dimension 7 extraction | hapax-council | Integration |
| 15 | 4 | Briefing — activity-aware gating | hapax-council | Integration |
| 16 | 4 | Health monitor — watch check #37 | hapax-council | Integration |
| 17 | 5 | Voice relay design spike (DEFERRED) | distro-work | Deferred |
| 18 | — | End-to-end integration verification | All | Verification |

---

### Critical Files for Implementation

- `/home/operator/projects/hapax-council/agents/hapax_voice/context_gate.py` - Core file to modify for stress-aware gate layer (VetoChain insertion point)
- `/home/operator/projects/hapax-council/agents/health_monitor.py` - Add watch connectivity check #37 following existing `check_group` decorator pattern
- `/home/operator/projects/hapax-council/agents/ingest.py` - Wire `health-connect` path pattern into `_SERVICE_PATH_PATTERNS` for RAG auto-detection
- `/home/operator/projects/hapax-council/agents/profiler_sources.py` - Add watch source reader (`read_watch_facts`) and register in `BRIDGED_SOURCE_TYPES`
- `/home/operator/projects/hapax-council/shared/dimensions.py` - Add `"watch"` to `energy_and_attention` dimension's `primary_sources` tuple
