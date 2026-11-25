## Transport

The Tor and WebRTC transports. This file derives from docs/transport.md (the authoritative transport design document)
and docs/router.md.

### One SessionTransport contract; sends use only open sessions

Both session transports implement one contract (`SessionTransport<K>` in transport/session): per-remote `sessionStates`
where CONNECTED holds iff `hasSession`, plus idempotent, asynchronous `openSession`/`closeSession` (WebRTC keys sessions
by device id, Tor by onion endpoint). Connections are kept open in the background and sends only use connections that
are already open; with no link, outbox entries wait (deferred without counting an attempt).

Sources: docs (transport.md; guide.md §2) · confidence 88.
Evidence:

- transport.md (Sessions): "Both session transports implement one contract (`SessionTransport<K>` in
  `transport/session`): per-remote `sessionStates` where `CONNECTED` holds iff `hasSession`, plus idempotent,
  asynchronous `openSession`/`closeSession`."
- transport.md: "So connections are kept open in the background, and sends only use connections that are already open."
- guide.md §2 (Transports): "Implement `SessionTransport<K>`: per-remote session states, idempotent open/close, send on
  an open session."

### Links are never closed by a keeper; pings only to linked peers

`LinkKeeper` keeps links open to a target set but never closes them: each transport owns its sessions' lifetime. Pings
go only to linked peers; a successful link open pings the peer, exchanging frontiers on connect; a failed open counts as
an unanswered probe for availability scoring. The WebRTC transport never talks to Tor itself — signaling goes over Tor
via the router's protected envelopes.

Sources: docs (transport.md) · confidence 87.
Evidence:

- transport.md (Sessions): "Links are never closed by a keeper: each transport owns its sessions' lifetime."
- transport.md: "Pings go only to linked peers. A successful link open pings the peer, which exchanges frontiers on
  connect. A failed open counts as an unanswered probe for availability scoring."
- transport.md (WebRTC): "Signaling goes over Tor: the transport emits outgoing signals and accepts incoming ones; the
  router protects them and carries them as envelopes. The WebRTC transport never talks to Tor itself."

### Backend is the single writer of session state; observers subscribed in start ()

The WebRTC backend is the single writer of session state, so subscribers may treat replayed states as ground truth.
Observers bound to a backend instance are subscribed in `start()` and removed in `stop()` — never registered in the
builder (kmp-tor returns the same runtime per data directory for the life of the process).

Sources: docs (transport.md) · confidence 87.
Evidence:

- transport.md (WebRTC): "the backend is the single writer of state, so subscribers may treat replayed states as ground
  truth."
- transport.md (Backend lifecycle and health): "kmp-tor returns the same runtime per data directory for the life of the
  process, so observers bound to a backend instance are subscribed in `start()` and removed in `stop()`, never
  registered in the builder."

### Never dial an unpublished onion; publish localTorEndpoint once

Callers must not dial an onion (including their own) before it is published — Tor caches a failed descriptor lookup and
holds or refuses the stream for minutes. The transport publishes its own onion endpoint as `localTorEndpoint` (null
before start, cleared on stop); the router's `Presence.localTorEndpoint()` reads it there instead of caching a second
copy (`DefaultRouter` likewise reads from `TorTransport.localTorEndpoint` and caches no copy). No Tor recovery step
touches the onion key.

Sources: docs (transport.md; router.md) · confidence 87.
Evidence:

- transport.md (Backend lifecycle and health): "Callers must not dial an onion (including their own) before it is
  published: Tor caches a failed descriptor lookup and holds or refuses the stream for minutes."
- transport.md: "The transport publishes its own onion endpoint as `localTorEndpoint` (null before `start`, cleared on
  `stop`); the router's `Presence.localTorEndpoint()` reads it there instead of caching a second copy."
- transport.md (Recovery): "No step touches the onion key." / router.md: "read the Tor endpoint from
  `TorTransport.localTorEndpoint`; it caches no copy."
