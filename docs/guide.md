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
- Device/account identity is represented in persistent DB records (`accounts`, `devices`) with signing/encryption key metadata.
- Metadata minimization is desirable but not a strict v1 blocker.
- Mobile push/background behavior is required eventually (implementation deferred).

### Current protocol/model status
- Core protocol models currently in `commonMain` include:
  - `BinaryEnvelope` (`PacketId`, `PacketType`, route + payload wrapper),
  - `WebRtcSignalEnvelope` (session/kind/source/target + security scheme + protected payload),
  - `FileEnvelope` with typed payload codecs (`Offer/Chunk/Ack/Complete/Cancel`),
  - `SignalSecurityScheme` (`PLAINTEXT_TEST_ONLY`, `SIGNED`, `ENCRYPTED_AND_SIGNED`).
- Routing metadata visibility policy is explicit via `EnvelopeObservability` profiles (`binary-envelope-v1`, `file-envelope-v1`, `webrtc-signal-envelope-v1`).
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
  - AV contracts/types are implemented end-to-end at transport API level (session request/accept/reject/state/control flow), with backend media-track handling still not complete.
- Test note: some live/native WebRTC tests can be flaky with thread attach warnings from webrtc-java.

### Crypto + identity + persistence status
- Implemented:
  - `KmpCryptoProvider` with Ed25519 detached signatures, X25519 keypair generation, and SHA-256 hashing.
  - `DefaultIdentityKeyService` for local identity key generation/load and peer key resolution.
  - `DefaultSignatureProvider` and `SignedWebRtcSignalProtection` for signed WebRTC signaling envelopes.
  - SQLDelight-backed `DefaultIdentityPublicKeyRepository` storing per-device signing/encryption key metadata.
  - JVM key stores:
    - `JvmMasterKeyProvider` (OS keyring-backed DB master key retrieval/creation),
    - `JvmPrivateKeyStore` for local private key persistence.
  - Encrypted DB initialization and reopen path validated by sprint integration test (`Sprint0IntegrationTest`).
- Not yet implemented:
  - payload encryption/decryption pipeline (scheme value exists, concrete envelope encryption does not),
  - ratcheting session crypto and key/session rotation lifecycle.

### What is complete vs pending
- Complete enough:
  - transport adapters/interfaces,
  - signaling path over Tor,
  - envelope foundations (binary/signal/file),
  - baseline unit/integration tests for transport/protocol behavior,
  - sprint-0 integration coverage for encrypted DB key reuse + signed signal verification/tamper rejection.
- Not complete yet:
  - routing/core layer orchestration (`Router`/`Inbox`/`StateSync` concrete services are not present yet),
  - end-to-end file transfer orchestration (offer/chunk/ack/complete lifecycle through routing/storage),
  - full AV media-track lifecycle implementation inside backend engine,
  - full cryptography implementation for content protection (encryption/decryption/session ratchet),
  - persistence/state machine integration for retries/resume/relay policies.

### Immediate next steps (recommended order)
1. Implement routing/core orchestration (own lifecycle, transport choice, fallback, retries, dedup policy).
2. Add payload encryption/decryption and session lifecycle (expand from current signature-only signaling protection).
3. Wire file transfer lifecycle through router + storage (using `FileEnvelope` codecs already present).
4. Expand AV implementation from transport/control flow to full media-track handling.
5. Add stronger integration tests across router + crypto + storage + transports (including resume/retry/relay eviction paths).