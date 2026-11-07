# Transport & links

How devices hold connections to each other and how bytes cross Tor and WebRTC. The layer rules (what the transport may
and may not know) are in [`guide.md`](guide.md); this file covers how it works.

## Sessions (links)

Tor delivers over long-lived streams, not one connection per packet. Tor runs many streams concurrently, but
setting one up to an onion takes seconds, and to an offline peer it can hang for minutes. So connections are
kept open in the background, and sends only use connections that are already open.

- Both session transports implement one contract (`SessionTransport<K>` in `transport/session`): per-remote
  `sessionStates` where `CONNECTED` holds iff `hasSession`, plus idempotent, asynchronous
  `openSession`/`closeSession`. WebRTC keys sessions by device id, Tor by onion endpoint.
- The router sees them per device through `PeerLinks` (`routing/link`). Transport choice (`OutboundPolicy`)
  takes the most preferred open link (`RouterConfig.linkPreference`: WebRTC, then Tor). With no link, outbox
  entries wait (deferred without counting an attempt) and relay deposits plus the pull path cover delivery;
  system traffic has Tor dial a stream on demand. Any link coming up wakes the peer's outbox entries.
- Relays (store-and-forward copies of a message, so delivery doesn't need sender and recipient online at the
  same time) are used only when the direct path looks doubtful: no open link when the message is sent or when
  it comes due, no ACK within `relayBackupAfter` of a send over a link, or a retry. Each outbox entry records
  its backup state (`relay_backup`), so copies are made at most once, across restarts too. While a link
  delivers, relays carry nothing.
- `LinkKeeper` keeps links open to a target set over any session transport, with per-peer exponential
  backoff, capped concurrent opens, prompt reopen when a link drops, and a readiness gate. The router runs one
  for Tor (all active peers, gated on `TorHealth.Ready`). WebRTC sessions stay on-demand (`ProactiveSessionOpener`).
- Pings go only to linked peers. A successful link open pings the peer, which exchanges frontiers on connect.
  A failed open counts as an unanswered probe for availability scoring.

## Tor

### Wire

- A connection starts with a magic int. Every connection we dial to a peer is a stream (`TOR_STREAM_MAGIC`):
  a hello naming the dialer's endpoint, then records (`ACCEPT`, `DATA`, `KEEPALIVE`; unknown types are
  skipped). The self-reachability probe (`TOR_PROBE_MAGIC`) is a connection to our own onion whose nonce our
  listener echoes back. One-shot frames (`TOR_TRANSPORT_FRAME_MAGIC`: one frame, then close) are no longer
  sent but still accepted from older builds; receiving them can be dropped once no such builds remain. Older
  builds can't accept streams, so to them mixed-version devices rely on relays and the pull path.

### Streams

- A send with no session dials a stream on demand (bootstrap targets, system traffic to an unlinked peer).
  Streams nobody asked to keep — dialed on demand, or inbound and not adopted — are transient: they close after
  `transientLinkIdleTimeout` without envelopes. Unadopted inbound streams have their own cap (`maxUnadoptedInbound`).
- A stream we dialed is a session once the far end's app sends `ACCEPT`. A stream someone else dialed only
  carries replies (ACK/NACK go back on the stream the packet arrived on) until the router adopts it. It adopts
  a stream once an authenticated ping from the claimed device arrives on it: pings are never relayed, so this
  proves who dialed. When both sides dial each other, the side with the higher onion closes its own dialed
  stream after adopting the other.
- Idle streams exchange keepalives, and a stream with nothing received for `sessionIdleTimeout` is closed:
  Tor gives no timely signal when the far end disappears. A stream carrying our envelopes is held to the much
  shorter `sessionResponseTimeout`: each burst of envelopes ends with an `ECHO` record the far end answers at
  once, so a peer that vanished is noticed within seconds of sending to it.

### Backend lifecycle and health

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
- Status path to the GUI: `TorHealth` (incl. `NoNetwork` from the monitor), `TorPublishState` and the current
  `TorRecoveryStep` go from the transport to the router, which lifts them into its own local link-status
  vocabulary; the orchestrator maps that to `LocalTransportStatus`. Tor types stop at the router. Tor's stall
  reason travels as `TransportProblem` detail, never as headline.

### Recovery

- `TorSupervisor` (inside `KmpTorBackend`) recovers a Tor that is stuck while the network is fine: no bootstrap
  progress for `bootstrapStallTimeout` (2 min; Tor can stall silently and also recovers from many stalls on its
  own), or publication DEGRADED. It escalates one `TorRecoveryStep` per incident — cycle DisableNetwork, restart
  the daemon, restart with a purged directory cache (`tor/cache/cached-*`), restart with reset guard state
  (`tor/work/data/state`, at most once per `guardResetMinInterval`, recorded in `tor/guard_reset_at`) — and
  starts over once Tor is up and published. Past the ladder it keeps restarting with a backed-off stall
  timeout. While healthy it silently re-proves reachability every `reachabilityRecheckInterval`. No step
  touches the onion key. The current step reaches the GUI as `LocalTransportStatus.Recovering`.
- Stale onion lookups: Tor remembers "descriptor not found" for minutes, and a directory relay that never
  answers holds a stream for Tor's full two-minute SOCKS timeout. The backend tracks unanswered `HS_DESC`
  fetches per onion (`DescriptorFetchTracker`) and ends a send attempt or self-probe stuck on one for
  `descriptorFetchStallTimeout` (20 s). SOCKS code 4 surfaces as `TorException.OnionNotFound`. When a Tor link
  open fails this way for a peer the availability registry says is online, the router asks
  `TorTransport.refreshOnionLookups()` to drop the cached miss and retries after `onionRefreshRetryDelay`.
  NEWNYM is global (it also discards warm circuits to every other peer), so refreshes requested from outside
  the backend's own recovery are throttled (`onionLookupRefreshMinInterval`, 30 s).

## WebRTC

- `JvmWebRtcBackend` uses webrtc-java for offer/answer/ICE and data channels.
- Signaling goes over Tor: the transport emits outgoing signals and accepts incoming ones; the router
  protects them and carries them as envelopes. The WebRTC transport never talks to Tor itself.
- Session state contract: the shared `SessionTransport` one (`CONNECTED` iff the envelope data channel is
  open); the backend is the single writer of state, so subscribers may treat replayed states as ground truth.
  `ProactiveSessionOpener.awaitSession` is event-driven on this stream (with a slow re-open safety net for
  silently lost signaling).
- AV session contracts (request/accept/reject/state/control) exist at the transport API; media-track handling
  in the backend is not complete.

## Testing

- Integration tests include a live loopback Tor test, a local two-peer WebRTC test and a live Tor + WebRTC
  test (opt-in via `-PintegrationTests=true`).
- Some live/native WebRTC tests can be flaky with thread-attach warnings from webrtc-java.
