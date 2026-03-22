# Watch Voice Relay — Design Spike

> **Status:** DEFERRED — implement after Phase 3 (real-time streaming) and Phase 4 (agent consumption) are proven stable.

## Rationale for Deferral

The voice relay requires bidirectional audio streaming between the Pixel Watch 4 and the workstation. This is significantly more complex than the sensor data path (one-way JSON POST) and involves:

- Low-latency WebSocket audio transport
- Opus encode/decode on both ends
- PipeWire virtual source injection on the workstation
- Session management with handoff between watch mic and desk mic
- Battery impact optimization on the watch

The sensor streaming and agent consumption layers (Phases 3-4) must be stable before adding this complexity. The voice relay depends on proven watch connectivity and reliable real-time data flow.

## Architecture

```
Watch mic → Opus encode → WebSocket → watch-receiver
    → Opus decode → PipeWire virtual source → voice daemon AudioInputStream

TTS output → Opus encode → WebSocket → watch speaker
```

## Subtasks (When Ready)

### 1. WebSocket Endpoint in Watch Receiver

Add `/watch/voice` WebSocket upgrade to `watch_receiver.py`. Binary frames carry Opus packets with a 4-byte header (sequence number + flags). Connection lifecycle tied to session management.

### 2. Opus Encode in Wear OS App

Use Android `MediaCodec` API with Opus codec. Target: 16kHz mono, 24kbps CBR. Frame size: 20ms (320 samples). Buffer 3 frames before sending to amortize WebSocket overhead.

### 3. PipeWire Virtual Source Creation

On the workstation, create a PipeWire virtual source node (`pw-cli create-node`) that appears as a microphone to the voice daemon. Inject decoded PCM audio into this node. The voice daemon's `AudioInputStream` selects between desk mic and watch mic based on presence signals.

### 4. Opus Decode + TTS Capture

Decode Opus frames from WebSocket. For TTS playback, capture PipeWire monitor output, encode to Opus, and stream back to the watch speaker via the same WebSocket.

### 5. Session Management

- **Trigger:** Wrist-raise + voice trigger button on watch Tile
- **Handoff:** When desk mic detects voice activity, gracefully transition from watch mic
- **Timeout:** 30s inactivity → close session, return to sensor-only mode
- **Concurrency:** Only one voice session at a time (watch or desk, not both)

### 6. Battery Impact Testing

Measure watch battery drain with continuous WebSocket + Opus encode. Target: <5% per hour of active voice relay. Implement adaptive quality: reduce bitrate when battery < 20%.

## Dependencies

- Stable watch-receiver service (Task 5-6) ✓
- Stable sensor streaming from Wear OS app (Task 8-9)
- Voice daemon presence detection working with watch signals (Task 12)
- PipeWire configuration for virtual sources

## Open Questions

1. **Latency budget:** What's the acceptable round-trip latency? Voice interactions feel natural below 300ms.
2. **Noise cancellation:** Should the watch app apply noise reduction before encoding, or handle it on the workstation?
3. **Wake word:** Can the watch run a lightweight wake word detector locally to avoid streaming audio continuously?
4. **Opus vs. raw PCM:** At 16kHz mono, raw PCM is 256kbps. Opus at 24kbps is 10x smaller. Worth the encode/decode complexity?
