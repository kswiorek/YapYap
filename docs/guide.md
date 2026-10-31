## YapYap Backend Guide (handoff summary)

### Product and architecture goals

- Kotlin-first backend/core, embedded as a library on user devices, runnable headless on relay servers.
- Scale target: small trusted network (about 10-20 users), prioritize correctness, resilience, maintainability over
  hyperscale optimization.
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
- Device/account identity is represented in persistent DB records (`accounts`, `devices`) with signing/encryption key
  metadata.
- Metadata minimization is desirable but not a strict v1 blocker.
- Mobile push/background behavior is required eventually (implementation deferred).

### Current protocol/model status

- Core protocol models currently in `commonMain` include:
  - `BinaryEnvelope` (`PacketId`, `PacketType`, route + payload wrapper),
  - `WebRtcSignalEnvelope` (session/kind/source/target + security scheme + protected payload),
  - `FileEnvelope` with typed payload codecs (`Offer/Chunk/Ack/Complete/Cancel`),
  - `SignalSecurityScheme` (`PLAINTEXT_TEST_ONLY`, `SIGNED`, `ENCRYPTED_AND_SIGNED`).
- Routing metadata visibility policy is explicit via `EnvelopeObservability` profiles (`binary-envelope-v1`,
  `file-envelope-v1`, `webrtc-signal-envelope-v1`).
- File transfer container was generalized:
  - `FileEnvelope` (single envelope family for both small and large file control plane),
  - kind-specific payload codecs (`Offer/Chunk/Ack/Complete/Cancel`),
  - control payload includes transfer class and preferred transport.

### Transport layer implementation status

- Sessions ("links"): Tor delivers over long-lived streams, not one connection per packet. Tor runs many
  streams concurrently, but setting one up to an onion takes seconds, and to an offline peer it can hang for
  minutes. So connections are kept open in the background, and sends only use connections that are already
  open.
    - Both session transports implement one contract (`SessionTransport<K>` in `transport/session`): per-remote
      `sessionStates` where `CONNECTED` holds iff `hasSession`, plus idempotent, asynchronous
      `openSession`/`closeSession`. WebRTC keys sessions by device id, Tor by onion endpoint.
    - The router sees them per device through `PeerLinks` (`routing/link`). Transport choice (`OutboundPolicy`)
      takes the most preferred open link (`RouterConfig.linkPreference`: WebRTC, then Tor). With no link, outbox
      entries wait (deferred without counting an attempt) and relay deposits plus the pull path cover delivery;
      best-effort system traffic falls back to a one-shot Tor dial. Any link coming up wakes the peer's outbox
      entries.
    - `LinkKeeper` keeps links open to a target set over any session transport, with per-peer exponential
      backoff, capped concurrent opens, prompt reopen when a link drops, and a readiness gate. The router runs one
      for Tor (all active peers, gated on `TorHealth.Ready`). WebRTC sessions stay on-demand (`ProactiveSessionOpener`).
    - Pings go only to linked peers. A successful link open pings the peer, which syncs frontiers on connect. A
      failed open counts as an unanswered probe for availability scoring.
- Tor:
    - Wire: a connection starts with a magic int. One-shot frames (`TOR_TRANSPORT_FRAME_MAGIC`: one frame, then
      close) are still accepted and still used for bootstrap targets, peers without a session and the self-probe.
      Streams (`TOR_STREAM_MAGIC`) start with a hello naming the dialer's endpoint, then carry records (`ACCEPT`,
      `DATA`, `KEEPALIVE`; unknown types are skipped). Peers running a build without streams can't
      accept sessions, so mixed-version devices fall back to relays and the pull path.
    - A stream we dialed is a session once the far end's app sends `ACCEPT`. A stream someone else dialed only
      carries replies (ACK/NACK go back on the stream the packet arrived on) until the router adopts it. It adopts
      a stream once an authenticated ping from the claimed device arrives on it: pings are never relayed, so this
      proves who dialed. When both sides dial each other, the side with the higher onion closes its own dialed
      stream after adopting the other.
    - Idle streams exchange keepalives, and a stream with nothing received for `sessionIdleTimeout` is closed:
      Tor gives no timely signal when the far end disappears.
  - Tor backend + Tor transport abstraction implemented.
  - Integration tests exist (including live loopback Tor test).
  - `TorBackend.start()` returns once the daemon's SOCKS listener is up and the onion is added; it does not wait
    for bootstrap. Bootstrap progress, Tor's own stall reason (`STATUS_CLIENT` `REASON`/`HOSTADDR`) and network
    liveness are reported through `TorBackend.health` (`TorHealth`). Callers must not dial an onion (including
    their own) before it is published: Tor caches a failed descriptor lookup and holds or refuses the stream for
    minutes. kmp-tor returns the same runtime per data directory for the life of the process, so observers bound
    to a backend instance are subscribed in `start()` and removed in `stop()`, never registered in the builder.
  - Network changes (desktop: `JvmNetworkMonitor`, which polls interfaces and detects sleep from wall-clock gaps;
    Android/iOS: none yet) reach Tor two ways. Online/offline goes through kmp-tor's `NetworkObserver`
    (`TorNetworkObserver`, process-wide) as `DisableNetwork`. A switch or wake-up keeps the device online, so
    `KmpTorBackend` cycles `DisableNetwork` itself, drops cached onion lookups and re-verifies publication.
    Setting `DisableNetwork` closes Tor's SOCKS listener and re-enabling reopens it on a new port; the backend
    re-reads the port (`GETINFO net/listeners/socks`) when a local SOCKS connect fails.
  - Status path to the GUI: `TorHealth` (incl. `NoNetwork` from the monitor) + `TorPublishState` → `Router`
    (`localTransportHealth`, `localPublishState`) → `Orchestrator.transportStatus` (`LocalTransportStatus`, mapped
    in `TransportStatusMapping.kt`). Tor's stall reason travels as `TransportProblem` detail, never as headline.
  - `TorSupervisor` (inside `KmpTorBackend`) recovers a Tor that is stuck while the network is fine: no bootstrap
    progress for `bootstrapStallTimeout` (2 min; Tor can stall silently and also recovers from many stalls on its
    own), or publication DEGRADED. It escalates one `TorRecoveryStep` per incident — cycle DisableNetwork, restart
    the daemon, restart with a purged directory cache (`tor/cache/cached-*`), restart with reset guard state
    (`tor/work/data/state`, at most once per `guardResetMinInterval`, recorded in `tor/guard_reset_at`) — and starts
    over once Tor is up and published. Past the ladder it keeps restarting with a backed-off stall timeout. While
    healthy it silently re-proves reachability every `reachabilityRecheckInterval`. No step touches the onion key.
    The current step reaches the GUI as `LocalTransportStatus.Recovering`.
  - Stale onion lookups: Tor remembers "descriptor not found" for minutes, and a directory relay that never
    answers holds a stream for Tor's full two-minute SOCKS timeout. The backend tracks unanswered `HS_DESC`
    fetches per onion (`DescriptorFetchTracker`) and ends a send attempt or self-probe stuck on one for
    `descriptorFetchStallTimeout` (20 s). SOCKS code 4 surfaces as `TorException.OnionNotFound`. When a Tor link
    open fails this way for a peer the availability registry says is online, the router asks
    `TorTransport.refreshOnionLookups()` to drop the cached miss and retries after `onionRefreshRetryDelay`. NEWNYM
    is global (it also discards warm circuits to every other peer), so refreshes requested from outside the
    backend's own recovery are throttled (`onionLookupRefreshMinInterval`, 30 s).
- WebRTC:
  - `JvmWebRtcBackend` implemented with webrtc-java adapter for offer/answer/ICE/data channel basics.
  - WebRTC signaling over Tor routed transport implemented (`TorRoutedWebRtcTransport`).
  - Local two-peer WebRTC integration test implemented.
  - Live Tor + WebRTC integration test implemented.
  - AV contracts/types are implemented end-to-end at transport API level (session request/accept/reject/state/control
    flow), with backend media-track handling still not complete.
  - Session state contract: the shared `SessionTransport` one (`CONNECTED` iff the envelope data channel is
    open); the backend is the single writer of state, so subscribers may treat replayed states as ground truth.
    `ProactiveSessionOpener.awaitSession` is event-driven on this stream (with a slow re-open safety net for
    silently lost signaling).
- Test note: some live/native WebRTC tests can be flaky with thread attach warnings from webrtc-java.

### Crypto + identity + persistence status

- Implemented:
  - `KmpCryptoProvider` with Ed25519 detached signatures, X25519 keypair generation, ChaCha20-Poly1305 AEAD (with
    AAD), and SHA-256 hashing.
  - `DefaultIdentityKeyService` for local identity key generation/load and peer key resolution.
  - `DefaultSignatureProvider` and `SignedWebRtcSignalProtection` for signed WebRTC signaling envelopes.
  - 1-on-1 E2EE: X3DH handshake, Double Ratchet, `DefaultCryptoSessionManager`,
    `SignedAndEncryptedMessageProtection` (see [`e2ee.md`](e2ee.md)).
  - SQLDelight-backed `DefaultIdentityPublicKeyRepository` storing per-device signing/encryption key metadata.
  - SQLDelight-backed `DefaultCryptoSessionStore` for ratchet session persistence.
  - JVM key stores:
    - `DefaultMasterKeyProvider` over `DefaultKeyStore` (OS keyring-backed DB master key
      retrieval/creation; the keyring service is `org.yapyap`, suffixed per `keyringNamespace`
      when the factory sets one),
    - `DefaultKeyStore` for local private key persistence (same keyring, well-known refs).
  - Encrypted DB initialization and reopen path validated by `JvmEncryptedDriverFactoryIntegrationTest`
    (opt-in via `-PintegrationTests=true`).
- Not yet implemented / in progress:
  - SPK rotation and handshake edge cases during key rollover.
  - Epoch lifecycle management (`SUPERSEDED` sessions, handshake-until-acked).
  - Simultaneous-init policy and remaining P0/P1 items documented in [`e2ee.md`](e2ee.md).

### Delivery model & retry/NACK contract (Sprint 4)

Effectively-once delivery rests on the pull path, not the push path: every sent message is
appended to the local DAG first, ping frontiers advertise DAG tips, and peers pull what they
miss via sync requests. The outbox push (dispatch + ACK/NACK retry) is a latency
optimization — a deferred or expired push is a latency problem, never a loss.

Protection verdicts (`ProtectionDisposition` in `ProtectionException.kt`) are two-valued:

- `PERMANENT` — these bytes are dead (bad signature, replay, violation, corrupt).
- `DEFER` — prerequisites missing (session/identity not ready). There is no "retryable"
  protection verdict: retry policy is a transport concern owned by the outbox.

Inbound/outbound disposition contract:

- `DEFER` is silent by design: no ACK, no NACK, dedup row cleared (`inboundResultForProtectionFailure`), so the sender's
  resend is reprocessed fresh once
  the prerequisite lands. A NACK here would either replay a stale verdict for later
  duplicates or falsely ACK the packet via the duplicate-replay path.
- `ACK` means processed — never sent before successful decrypt-and-handle.
- The sender owns a message until it decrypts on the receiver: there is no inbound hold
  queue; the sender's outbox retry is the only recovery path for undecryptable packets.
- A received NACK's meaning is a pure function of its reason (`nackActionFor` in the
  routing layer is the single enforcement point): terminal reasons (`PERMANENT_PROTECTION_FAILED`, `DECODE_FAILED`,
  `UNSUPPORTED_TYPE`, `EXPIRED`,
  `DECLINED`) delete the outbox row; `WRONG_TARGET` keeps it (endpoint reconciliation
  self-heals; the fast-attempt budget self-limits cadence). An unrecognized reason byte
  never deletes — never act destructively on confusion with a newer peer.
- Outbound `DEFER` (session not ready at protect time) surfaces as a deferred device
  count in `AccountPushReport` (router vocabulary only): the message stays in the
  local DAG and delivery falls back to the pull path. The orchestrator maps it to a
  pull-only member in `FanoutReport` — pending display state, never a failure.
  Deliberately no separate staging table: epoch-1 encrypt self-bootstraps, so the
  remaining defer windows are round-trip-scale races the pull path covers.
- The outbox has no processing round: each due entry is claimed (attempt and next retry recorded
  first) and sent on its own, so a slow send never delays what is queued behind it. In-flight sends are
  capped (`outboxMaxInFlight`, `outboxMaxInFlightPerTarget`), and the retry timer ignores overdue
  entries that can't start yet — finishing sends wake the loop instead. Sends never open a connection:
  an entry whose peer has no open link waits for one (`noLinkRecheckDelay` is only a safety net).
  Bootstrap packets with an explicit endpoint are the exception and go out as one-shot Tor dials.
- Unknown-device policy exception: an ACK/NACK from a device with no row is accepted only when it
  refers to a BOOTSTRAP packet in our outbox addressed to exactly that device (a recovering device
  can't verify its sponsor's signature yet; the unguessable packet id authenticates the reply). It is
  never answered. An accepted ACK is reported on `Router.bootstrapDeliveries`, which moves recovery
  onboarding from `REQUEST_SENT` to `REQUEST_DELIVERED`.
- Sync rows die when their target arrives in any state (`Inserted` or `BecameOrphan`):
  an arrived orphan's fetch is satisfied and its missing parents get fresh rows. This keeps
  out-of-order delivery (normal for store-and-forward) from leaking sync rows.