## YapYap Backend Guide (handoff summary)

### Product and architecture goals
- Kotlin-first backend/core, embedded as a library on user devices, runnable headless on relay servers.
- Scale target: small trusted network (about 10-20 users), prioritize correctness, resilience, maintainability over hyperscale optimization.
- Main flows:
  - offline text + small images via Tor store-and-forward,
  - low-latency/direct text path when available,
  - large files + AV via WebRTC P2P.
- Optional always-on Raspberry Pi nodes act as relays/availability anchors.
- GUI target for now: Kotlin Compose Multiplatform.

### Core decisions already agreed
- Tor signaling and store-and-forward use a custom binary envelope (`BinaryEnvelope`), not JSON.
- WebRTC signaling goes over Tor; media/data flows go over WebRTC.
- Message delivery semantics target effectively-once (IDs + dedup + ACK strategy).
- Cache/retention is config-driven; relays may evict with LRU when storage limits are hit.
- Devices should propagate known peer descriptors through the network (no manual per-device setup for every peer).
- Metadata minimization is desirable but not a strict v1 blocker.
- Mobile push/background behavior is required eventually (implementation deferred).

### Current protocol/model status
- `PeerDescriptor` has been upgraded to include:
  - identity keys and key IDs,
  - capability advertisement,
  - descriptor freshness/signature fields,
  - relay profile hints (advertised storage/retention/availability class).
- `SignalSecurityScheme` is in shared protocol model.
- WebRTC signaling envelope supports security scheme and protected payload.
- File transfer container was generalized:
  - `FileEnvelope` (single envelope family for both small and large file control plane),
  - kind-specific payload codecs (`Offer/Chunk/Ack/Complete/Cancel`),
  - control payload includes transfer class and preferred transport.

### Transport layer implementation status
- Tor:
  - Tor backend + Tor transport abstraction implemented.
  - Integration tests exist (including live loopback Tor test).
- WebRTC:
  - `JvmWebRtcBackend` implemented with webrtc-java adapter for offer/answer/ICE/data channel basics.
  - WebRTC signaling over Tor routed transport implemented (`TorRoutedWebRtcTransport`).
  - Local two-peer WebRTC integration test implemented.
  - Live Tor + WebRTC integration test implemented.
  - AV contracts/types added (session options/state/control), with scaffold-level backend behavior.
- Test note: some live/native WebRTC tests can be flaky with thread attach warnings from webrtc-java.

### What is complete vs pending
- Complete enough:
  - transport adapters/interfaces,
  - signaling path over Tor,
  - envelope foundations (binary/signal/file),
  - baseline unit/integration tests for transport/protocol behavior.
- Not complete yet:
  - routing/core layer (`Router`, `Inbox`, `StateSync`) orchestration,
  - end-to-end file transfer orchestration (offer/chunk/ack/complete lifecycle through routing/storage),
  - full AV media-track lifecycle implementation (beyond API scaffolding),
  - cryptography implementation (sign/verify/encrypt/decrypt engine and key/session management),
  - persistence/state machine integration for retries/resume/relay policies.

### Immediate next steps (recommended order)
1. Implement routing/core orchestration (own lifecycle, transport choice, fallback, retries, dedup policy).
2. Implement cryptography interfaces and concrete protection for signaling + payload envelopes.
3. Wire file transfer lifecycle through router + storage (using `FileEnvelope` codecs already present).
4. Expand AV implementation from control-plane scaffolding to full media-track handling.
5. Add stronger integration tests across router + crypto + storage + transports.