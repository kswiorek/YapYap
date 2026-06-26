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
  - `KmpCryptoProvider` with Ed25519 detached signatures, X25519 keypair generation, ChaCha20-Poly1305 AEAD (with AAD), and SHA-256 hashing.
  - `DefaultIdentityKeyService` for local identity key generation/load and peer key resolution.
  - `DefaultSignatureProvider` and `SignedWebRtcSignalProtection` for signed WebRTC signaling envelopes.
  - 1-on-1 E2EE: X3DH handshake, Double Ratchet, `DefaultCryptoSessionManager`, `SignedAndEncryptedMessageProtection` (see [`e2ee.md`](e2ee.md)).
  - SQLDelight-backed `DefaultIdentityPublicKeyRepository` storing per-device signing/encryption key metadata.
  - SQLDelight-backed `DefaultCryptoSessionStore` for ratchet session persistence.
  - JVM key stores:
    - `JvmMasterKeyProvider` (OS keyring-backed DB master key retrieval/creation),
    - `JvmPrivateKeyStore` for local private key persistence.
  - Encrypted DB initialization and reopen path validated by sprint integration test (`Sprint0IntegrationTest`).
- Not yet implemented / in progress:
  - SPK rotation and handshake edge cases during key rollover.
  - Epoch lifecycle management (`SUPERSEDED` sessions, handshake-until-acked).
  - Simultaneous-init policy and remaining P0/P1 items documented in [`e2ee.md`](e2ee.md).