# File transfer design

This is the design of record for file transfer (v1, 1:1 sends), agreed in the design sessions of November 2026,
before implementation. It is the base for implementation; [`../sprints.mmd`](../sprints.mmd) stays authoritative
for sprint sequencing at its coarser grain and is not modified by this design.

Related: [`../guide.md`](../guide.md) (layer model, dependency rules, the delivery model in §6),
[`../transport.md`](../transport.md) (links, channels, frame limits), [`../router.md`](../router.md) (outbox and
relay machinery this design reuses), [`../e2ee.md`](../e2ee.md) (sessions — deliberately **not** used for chunk
content, see §4). Diagrams: [`transfer paths.mmd`](transfer%20paths.mmd), [`transfer states.mmd`](transfer%20states.mmd).

## 1. Scope and philosophy

Two transfer classes, one engine:

- **Small files** — at or below `smallFileMaxBytes` (default 10 MB, the Discord limit; the exact value lives in
  config, §15). May traverse relays with store-and-forward. Bounded size, so bounded relay storage.
- **Large files** — above the limit, no upper bound (hundreds of GB must work). WebRTC-only: no session means
  the transfer waits; bytes never go over Tor and never go to relays.

The core philosophy transplants the guide's delivery model (§6) to files: **the push path (OFFER + chunk flow)
is a latency optimization; the pull path (DAG message → REQUEST → serve) is the loss-proof backbone.** A
deferred, expired or partial push is never a loss — the message references the file forever, and the receiver
can always REQUEST the bytes from a holder.

Grounding facts that shape everything below:

- Chunking is forced everywhere: the WebRTC backend carries at most ~250 KiB per envelope
  (`WebRtcBackendConfig.maxPayloadBytes`), Tor at most 4 MB. The chunk size is **derived** (≈220 KiB after
  envelope framing) by the composition root, never independently configured (§15).
- **No new ARQ is built.** Both transports are reliable per frame (WebRTC data channels are SCTP, configured
  ordered + fully reliable; Tor links are framed TCP streams), and the router already gives every envelope
  ACK/NACK + dedup + outbox retry. A chunk sent as an ordinary packet is effectively-once per hop. The
  SACK-style ACK payload is the **progress/resume** protocol, not loss detection.
- Group sends are out of scope for v1, but the design is deliberately encryption-independent: chunk content is
  an opaque AEAD blob keyed per file, so group fan-out (sender keys) is additive later without touching the
  chunk pipeline.

## 2. Identity: `fileId` and `transferId`

- **`fileId` = content identity.** Derived from the whole-file hash (`objectHash`), i.e. content addressing.
  Stable forever, referenced by the DAG message, and the key for the receiver's chunk tracker, `.part` file,
  final file and the permanent attachment store. Free dedup: two senders shipping identical bytes produce the
  same `fileId`, and a receiver who already holds it renders the second message instantly from local storage.
  The identity *is* the integrity check — no separate `objectHash` field exists anywhere.
- **`transferId` = routing session identity.** One push/serve attempt. Keys the relay store, the OFFER, the
  outbox marker tag and chunk envelope addressing. Relays never see or need the `fileId`.
- The per-file key and the chunking parameters are **bound to `fileId`**, so chunk ciphertext is deterministic:
  any holder serving the same `fileId` produces byte-identical chunks. Resume therefore works across transfers
  and across serving devices.
- Receiver state and `.part` files are `fileId`-scoped (valid across transfers); relay state is
  `transferId`-scoped.

## 3. Wire protocol

All FILE-family envelopes are signed by their author (the `source` field). A relay forwarding stored envelopes
replays the original signature verbatim — the relay is never a signature author; the original author's
signature plus deterministic ciphertext make replayed content verifiable (§7).

### 3.1 Message attachments

`MessagePayload.Text` gains an `attachments` list. Each entry is deliberately minimal — everything a receiver
needs to render the message and initiate a REQUEST, nothing more:

| Field         | Notes                                            |
|---------------|--------------------------------------------------|
| `fileId`      | The content hash — also the integrity check      |
| `fileName`    | Display                                          |
| `mimeType`    | Display / preview                                |
| `totalBytes`  | Display and the receiver's expectation           |

Transfer-specific data (chunk parameters, key) arrives only with the OFFER. This is a wire change: codec +
round-trip tests, and the attachment bytes are charged against the message size budget (the `Text`
`ENCODED_HEADER_RESERVE_BYTES` accounting grows; `wiring/MessageLimits` threads it).

### 3.2 FileEnvelope payloads (rework of the existing family)

| Kind       | Fields                                                                 | Author / direction                |
|------------|------------------------------------------------------------------------|-----------------------------------|
| `OFFER`    | public half: `chunkSizeBytes`, `totalBytes`, `startIndex`; sealed half: `fileId`, per-file key, `fileName`, `mimeType` | the serving device → target (or relay, for deposits) |
| `CHUNK`    | `chunkIndex`, ciphertext                                               | pusher → target (replayed by relay) |
| `ACK`      | `fileId`, `highestContiguousChunk`, `missingChunkIndices` (SACK-style)  | target → pusher                    |
| `COMPLETE` | `fileId`                                                               | target → original sender           |
| `REQUEST`  | `fileId`, held-chunks state (what the requester already holds)         | requester → one candidate device   |
| `CANCEL`   | reason                                                                 | sender → relay/target; target → sender (decline) |

Notes on the rework:

- The existing `FileChunk`/`FileControlPayload`/`FileTransferClass`/`FileTransportPreference` wire types are
  dropped. The small/large distinction is a sender-engine policy driven by the config threshold, not wire
  state; transport preference is path policy (§5), not a negotiated field. `maxInFlightChunks` is engine
  config (§15), not wire.
- `CHUNK` loses its `chunkCount` — the covered set is defined by the OFFER (§3.4).
- The existing per-chunk hash idea stays dead: AEAD tags detect corruption at decrypt; whole-file integrity is
  the `fileId` itself.

### 3.3 The OFFER's two halves

- **Public half** — what relays need for their storage contract: `chunkSizeBytes`, `totalBytes`, `startIndex`.
  From these the relay derives the file-level chunk count (`ceil(totalBytes / chunkSizeBytes)`) and the covered
  suffix (§3.4). The `transferId` rides the envelope header.
- **Sealed half** — `fileId`, the per-file key, display metadata; sealed to the target device. Relays can never
  read it, and never learn the `fileId` (two transfers of the same file are not correlatable at a relay).

The receiver derives its completeness oracle — the file-level chunk count — from **any** OFFER, even a partial
one. This matters for bare transfers (no DAG message, §10) whose only OFFER may be a remainder deposit.

Key transport, restated: the per-file key travels **raw inside the message plaintext** (the message path already
E2EEs per device) and **sealed to the addressee inside the OFFER**. The receiver stores the key from whichever
carrier arrives first.

### 3.4 Covered sets are always suffixes

Every transfer covers a **contiguous suffix** of the file's chunk sequence: `[startIndex, fileCount-1]`.

- Initial full transfer: `startIndex = 0`.
- Direct-link break remainder: `startIndex = highestContiguousAckedPrefix + 1` (§6.4).
- REQUEST serve: `startIndex = lowest index the requester is missing` (a few re-sent duplicates are harmless —
  the receiver dedups).
- Large-path resume: the checkpoint is a contiguous prefix, so resumption is a suffix by construction.

This invariant is what keeps the OFFER to a single start index (no lists, no bitmaps) and collapses the sending
engine's per-depot bookkeeping to one number per depot: its resume point.

## 4. Crypto

- **Per-file symmetric key** (AEAD, e.g. ChaCha20-Poly1305), created at send time, bound to the `fileId`.
  Chunk content **never** goes through the Double Ratchet: a long file would explode ratchet state, `MAX_SKIP`
  bounds out-of-order tolerance, and one lost chunk would break the chain for interleaved messages.
- **Deterministic ciphertext**: nonce derived from (`fileId`, `chunkIndex`). This is load-bearing — it makes
  the relay's stored bytes identical to any holder's, so the receiver's `(fileId, chunkIndex)` dedup and resume
  work across all sources.
- WebRTC's DTLS already encrypts the channel; the app-layer AEAD is kept regardless — negligible cost
  (AEAD is GB/s), uniform format across paths, determinism across holders, and independence from
  transport-stack correctness.
- New `FileProtection` implementation (sealing, signing, per-chunk AEAD via `CryptoProvider`) replaces the
  `PlaintextFileProtection` placeholder.

## 5. Path selection

**Small files** mirror the message model exactly: direct over any open link first; relay deposit only when the
direct path looks doubtful. The deposit triggers are the existing `RelayBackup` triggers (no link at claim
time, retry, no ACK in time) **plus** a chunk-level stall trigger (§6.3). The trigger *policy* is extracted into
a shared component that messages and files both consult; payload construction stays per-path.

**Relay choice is `RelaySelectionPolicy`'s, unchanged from messages.** It returns whichever relays its
reliability math picks for the target (targeting `relayTargetSuccessProbability`, floor `minRelayScore`), and
the number it returns varies up to the `maxRelays` cap. The deposit does not choose a count — it offers the
covered set to **every relay the policy returns**. An empty selection means no deposit; the pull path covers
it.

**Large files**: OFFER and chunks are both **WebRTC-only**. The large path therefore never touches the outbox:
the engine holds its IN_FLIGHT state, subscribes to session events, and sends directly when a session exists
(no per-entry transport mask is needed in v1). The receiver learns "a big file is pending" from the DAG
message, which does travel over Tor/relays — that is the "waiting for session" UX.

## 6. The push machinery (routing layer)

One mechanism, two chunk sources. The **pusher** owns: the OFFER-as-marker lifecycle, chunk outbox rows, the
in-flight window, the stall detector, deposit-on-stall, and resume. It speaks pure router vocabulary (packets,
outbox, ACKs, links), so it lives in **routing**, parameterized by a ciphertext source:

- **Sender's push** — source implemented by the orchestrator (reads the app-internal file, AEAD-encrypts on
  the fly) through a `FileChunkSource` port the router declares.
- **Relay's forward** — source implemented by routing itself (reads the `file_relay_chunks` store). The relay
  is simply another sender whose "file" is a DB table (§7).

### 6.1 The marker

The OFFER is enqueued as a normal outbox entry with `OutboxTag.Kind.FILE`, id = `transferId`, and standard
semantics — the delivery contract's "ACK = processed" is untouched:

- ACK (the offer was accepted) → marker removed; **the engine owns everything after**.
- Terminal NACK (DECLINED: relay full, target declined) → marker removed; the engine tries the next relay or
  re-offers later.
- Expiry → marker removed; the engine re-arms from its own state.

The envelope's `expiresAt` carries the file-scale TTL (`fileTransferTtl`) — the outbox honors it natively via
its expiry prune. Claim/lease semantics on the engine side ensure an offer **retry resumes rather than
restarts** an active flow.

The marker is establishment-only. It is never held open until completion: that would need a "processed but
don't ACK yet" inbound result, fight every message-scale outbox assumption, and duplicate state the engine
already owns (see §17, rejected alternatives).

### 6.2 The chunk phase (small path)

Chunks are individual outbox rows (tagged FILE, id = `transferId`) — per-chunk retry, dedup and restart
survival come free. A window (`maxInFlightChunks`) bounds pending rows: each row holds a ~220 KiB blob, so a
window of 16 ≈ 3.5 MB of outbox per transfer. The existing `outboxMaxInFlightPerTarget` already bounds dispatch
concurrency; if chunk throughput ever needs more, a per-tag-kind cap override is the knob (deferred, §18).

Outbox extensions required: the new tag kind, and `packetId` on `OutboxOutcome` so the engine maps a chunk
outcome to its index. Unacked chunks are exactly the tagged rows still queued, so the engine's pending set is
reconstructable from the outbox — no parallel per-chunk table on the send side. The per-chunk **plan**
(offsets, §10) is `fileId`-bound and pre-materialized only for small files.

### 6.3 Stall detection

No chunk ACK within `chunkAckTimeout` (30–60 s) → the transfer is flagged stalled → small path: deposit the
remainder to relays at once (§6.4); large path: pause and await session re-establishment.

Deposit-on-stall is **insurance, not restart-survival**: the DB state (transfer rows, plan, outbox rows)
resumes on boot regardless. What the deposit buys is that the already-pushed prefix never again needs the
sender and the target online simultaneously.

### 6.4 Deposit rules: one jump, parallel redundancy

- **The direct link breaks or stalls → deposit the remainder.** The remainder is the suffix after the
  **highest contiguous ACKed prefix** (`startIndex = prefix + 1`). This is safe by construction: any ACK holes
  below the prefix end are inside the remainder by definition, so the simplification never excludes a hole; it
  may re-send a few chunks the target already got, which the receiver dedups.
- **Parallel redundancy, never sequential.** The covered set goes, identical, to every relay
  `RelaySelectionPolicy` returns (§5). If one relay's deposit stalls, that is the outbox's business — per-chunk
  rows retry at their own cadence, and the reservation TTL eventually cleans what never lands. **The sender
  never chases a failing relay by re-depositing its missing parts elsewhere.**
- **One jump, ever.** Combined with the no-chaining rule for forwarding relays (§7), file ciphertext makes at
  most one relay hop: sender → relay → target. No relay-to-relay spread.
- If every deposit and the direct path all die, the pull path (§9) is the backstop — that is its job.

### 6.5 Completion and teardown

Each relay deposit is an **independent transfer** that runs until *that relay* holds its full covered set, or
TTL. One relay completing never cancels the others — the whole point of multiple deposits is the reliability
probability math behind `RelaySelectionPolicy`.

The send's push phase ends when **every deposit has resolved** (complete at its relay, or expired), or earlier
on the only two legitimate cross-deposit teardowns:

- **Target-confirmed full receipt**: the target's SACK-style progress ACKs are ordinary routable packets
  (target → sender via its own outbox, direct or relayed — the same store-and-forward as anything else), so
  "the target has everything" reaches the sender even when only relays delivered. On that signal the engine
  politely CANCELs outstanding deposits so relays free quota immediately.
- **User cancel**: CANCEL to every active transfer and deposit.

`COMPLETE` (target → original sender) is the explicit form of the first signal.

## 7. The relay

The relay is **dumb to content, smart about its storage contract**. It cannot read chunks (no key), cannot
verify content (the `fileId` hash is over plaintext — count-based wholeness is the ceiling, by design, and
nothing smarter helps any actor), and never learns the `fileId`. It is not a REQUEST candidate (§9). Its
storage is DB tables, not a files directory — transactional admission, eviction and crash recovery for free at
≤10 MB per transfer.

### 7.1 Signature verification at the door

The relay verifies the **original sender's signature** on the OFFER and on every CHUNK before storing (the
inner `FileEnvelope.source` must be a known, non-banned device; its public key comes from the identity views
routing may read). Without this, anyone who learns a `transferId` could stuff junk chunks into the store: they
would fail the target's decrypt later, but they would poison wholeness counting and waste quota. Verified
envelopes only; unverified are dropped.

### 7.2 Lifecycle

1. **Admission**: the OFFER's public half is checked against `relayFileBudgetBytes`; the covered suffix's
   bytes are reserved, or the OFFER is NACKed DECLINED (the existing vocabulary). The relay also stores the
   OFFER's payload blob verbatim — the forward re-uses it without re-signing (§7.4).
2. **Hold**: chunks land in `file_relay_chunks`, PK (`transferId`, `chunkIndex`) — dedup is free, and
   re-deposits after a stall no-op.
3. **Wholeness gate**: every index of the covered suffix present → the transfer is *whole*. **Incomplete
   transfers are never pushed and never offered**: the relay stays silent, and the **reservation TTL** (idle
   expiry) cleans up what the sender never finished. The TTL is load-bearing for budget correctness, not
   hygiene.
4. **Forward = the same pusher, DB source**: once whole, the stored OFFER payload is enqueued to the target as
   a normal outbox marker; ACK → chunk rows stream from the store; per-chunk ACKs from the target delete rows;
   the transfer row is dropped when drained.
   - Outer `BinaryEnvelope.source` = the relay, inner `FileEnvelope.source` = the original sender with its
     signature intact — exactly the message-transit pattern (`MessageInboundHandler`): relay transit is
     unauthenticated at the outer layer, content is authenticated inside.
   - **Blind full-send in v1**: the target ACKs every chunk it processes; a chunk it already holds (another
     relay, or the direct path) returns Success → row deleted. Duplicate waste is bounded; an OFFER-response
     carrying held-state is a later optimization, not a correctness need (§18).
   - **No chaining**: a forwarding relay never deposits its forwarded transfer at other relays. Stall at a
     forwarding relay = park and wait (outbox-native).
5. **Eviction**: whole-transfer LRU over `file_relay_transfers` — never a single row (evicting chunk 37 of 46
   silently kills the transfer).
6. **Forget after delivery** — same as relayed messages: rows die as the target ACKs, the transfer row dies
   when drained.

**Invariant**: *anything a relay pushes is whole.* A forwarded push that stalls is therefore always a link
problem (the forwarder retries from its complete store), never a content problem — the receiver needs no stall
detection on relay pushes.

**Restart recovery is free**: the forward's state is entirely outbox rows + store rows, both in the DB, both
already recovered by boot; wholeness is recomputed from the store.

## 8. The receiver

- An OFFER (or the DAG message) creates a `fileId`-scoped tracker row. The key arrives sealed in the OFFER or
  raw in the message; the receiver stores it from whichever lands first. Transfers merge into one tracker —
  the receiver is transfer-agnostic.
- **Reserve** `<fileId>.part` at `totalBytes` with an **explicit free-space check** (preallocation is sparse
  on most filesystems — stat the volume, not the file). Insufficient space or a user decline → a DECLINED
  response and a clean failure; mid-write ENOSPC fails the transfer cleanly. Auto-accept below a size
  threshold, ask above it (a config knob).
- Chunks: verify signature → decrypt (AEAD) → dedup by (`fileId`, `chunkIndex`) — **duplicates are normal
  traffic** (multiple relay deposits plus the direct path all deliver overlapping sets) → hand to the writer.
- **Small path**: out-of-order chunks go through a bounded in-memory reorder buffer (worst case the file cap,
  ≤10 MB; usually near-zero); the contiguous prefix drains to the `.part` as it advances. Per-chunk tracker
  rows (≤ ~46) record the received set.
- **Large path**: the channel is ordered + reliable, so chunks arrive in order — pure sequential append plus a
  bytes-written **checkpoint**; no per-chunk rows (a 100 GB file would otherwise be ~476k rows).
- All chunks present (file-level count from the OFFER/message) → **verify the hash (`fileId`) → atomic rename
  → status READY**. Never READY without verification.
- Progress ACKs (SACK-style, `fileId`-scoped merged state) flow back to the pusher; each pusher interprets
  them against its covered suffix.

## 9. The pull path: REQUEST

The recovery mechanism for everything the push path failed to deliver, and the only way history-acquired
files get their bytes.

- **Trigger** (orchestrator inbound pipeline, during DAG processing): a message references a `fileId` with no
  completed local file and no active transfer. Guards:
  - not while a transfer is IN_FLIGHT for that `fileId` (the OFFER may simply be behind the message in
    flight);
  - terminal states distinguish **re-requestable** (`EXPIRED`) from **refused** (`DECLINED`/`CANCELLED` —
    never auto-resurrect a file the user declined);
  - dedup by `fileId` (message duplicates create one row);
  - a grace period before the first attempt (the `firstAttemptAt` pattern — the receiver cannot know an OFFER
    is still pending sender-side).
- **One candidate at a time, never parallel** — parallel asks would make N devices each serve the entire file;
  that is the spam/DDoS shape. Candidates: the sender's devices, then own-account siblings (room members
  later), link-availability filtered like sync. An attempt covers the **handshake only** (REQUEST → OFFER or
  DECLINED); the transfer that follows has its own lifecycle. DECLINED rules a device out; expiry demotes it;
  the next candidate is asked at once.
- The candidate-asking loop is **extracted as a shared core with `SyncRetryProcessor`** (the needs now
  coincide; on success the file request hands off to the transfer engine instead of consuming a reply payload
  — a callback seam, not a structural fork).
- The REQUEST carries held-chunks state; the serving device (any holder: original sender, sibling that
  completed) re-runs the push engine with a **fresh `transferId`** for the same `fileId`, serving the suffix
  from the lowest missing index — deterministic ciphertext makes the bytes identical, so resume is exact.
- All candidates declined/expired → "file unavailable" (terminal, but a new sync or re-open can retry).
- Relays are **not** candidates: they are transient push caches with forget-after-delivery semantics; pulling
  from them was simplified away.

## 10. Storage and retention

- **Sender/holder**: the GUI copies a file into **app-internal storage** before sending. This makes the chunk
  source immutable across restarts (resume cannot fail because the user moved the original), is the render
  source for the conversation, and is the re-serve source for REQUESTs. **Keep forever** is the default
  policy — serverlessness forces it (the bytes are on a device or nowhere; there is no lazy-loading when a
  user scrolls back a year). Knob: per-account quota with LRU above it.
- **Receiver**: the same permanent store once READY; `.part` until verified.
- **Relay**: transient ciphertext only — budget-capped, TTL'd, forget-after-delivery.
- **Bare transfers** (no message ever) are a named limitation: they are forgotten when both sides forget
  them. Anything referenced by a message is recoverable forever via REQUEST.

## 11. File I/O: pure kotlinx.io

kotlinx.io is the house standard already (`ConfigStore`, `JvmAppLogger`, `KmpTorBackend`, `DataDirLock`,
`Platform`), and v1 uses **only** it — positioned I/O is on kotlinx.io's roadmap (files-API redesign,
kotlinx-io issue #241) but not shipped, so the design avoids needing it:

- **Large path, both sides, fully sequential**: the sender reads linearly via `source()` and **keeps the
  source open across session drops** (a drop needs no reopen); the receiver appends via `sink(append = true)`
  — append position *is* the checkpoint, no seeking; verification reads linearly.
- **Small-path receiver**: the in-memory reorder buffer (§8) replaces positioned writes.
- **Sender re-reads**: open + linear skip — trivial at ≤10 MB; for a large file the skip to the checkpoint is
  paid only on a **full app restart mid-transfer** (a one-time bounded read of the already-sent prefix).
- The chunk-shaped I/O boundary (`readChunk`/`writeChunk`-style module) still exists as a module, with a
  kotlinx.io-only implementation inside — so when #241 ships positioned read/write, the swap is internal, not
  a design change.

## 12. Layering and data ownership

**Two state homes by role, one shared machinery:**

- `file_transfers` and friends (orchestrator) model **only own traffic**: our sends, receives and
  serves-on-REQUEST. A file we relay for someone else never gets a row there.
- Foreign traffic state lives entirely in routing: `file_relay_*` plus the outbox rows of the forward. The
  relay's forward is driven by routing's own pusher reading routing's own store.
- The shared piece is the pusher (§6), which lives in routing because it speaks packet vocabulary.

The precedent is already in the codebase: the router stores and forwards foreign *messages* purely in routing
tables (`outbox` `is_relay` rows, `dedup`) and the orchestrator never sees them. Files follow that line — so a
headless relay node's orchestrator (file service, REQUEST logic, GUI progress) is completely uninvolved in
forwarding. Serve-on-REQUEST is the one relay-node case that *is* orchestrator business (we hold the file →
own traffic → our `file_transfers` row with the stored descriptor and key, pushed through the same machinery).

### Module layout

| Layer      | Owns                                                                                        |
|------------|---------------------------------------------------------------------------------------------|
| `protocol` | `FileEnvelope` rework (§3.2), `Text.attachments` (§3.1), codecs + round-trip tests           |
| `protection` | real `FileProtection` (§4)                                                                 |
| `routing`  | the pusher (`file/push`); the relay store and forwarder (`file/relay`); real `FileInboundHandler` (foreign target → park; local → emit); `FileCarrier` port on `Router` + an `incomingFileChunks` flow (mirror of `incomingMessages`); the `FileChunkSource` port (declared by routing; orchestrator implements the disk source, routing implements the store source); the file request processor on the shared candidate core; `OutboxTag.Kind.FILE`; `packetId` on `OutboxOutcome` |
| `orchestrator` | `runtime/file` — `FileTransferService`: send API (bare + message-composed), classification, descriptor/key creation, the receive engine (assembly, verify, ready), the REQUEST trigger + coordinator, serve-on-REQUEST; stores: `file_transfers`, `file_chunk_tracker` (receive side), `pending_file_requests`; the transfer enums move out of `persistence/DatabaseEnums.kt` to the owning store (its own TODO says so) |
| `transport/webrtc` | a file channel kind + per-transfer channel labels (the backend already multiplexes by label: envelope and AV channels); flow-control exposure |
| `wiring`   | `FileLimits` (derived chunk size, chained like `MessageLimits`), config wiring               |

The large-path transfer session (offer → accept/reject → active → ended/failed) is designed as a sibling of
the AV session contract, so the planned shared session machinery ("offer retry queues for voice and file",
sprints s6a) plugs in rather than forks.

### Large-path transport detail

A per-**transfer dedicated data channel** (labeled, opened on the existing peer connection — no renegotiation)
carries the stream. This also solves head-of-line blocking: multiplexing a 100 GB stream onto the single
ordered envelope channel would starve messages and ACKs behind file data. Pacing is the channel's own flow
control (`bufferedAmount` / low-threshold backpressure), which **replaces the app-level window for this path**.

## 13. Database sketch

Final DDL lands with implementation; every schema change ships with a migration (guide §4).

```
-- orchestrator (own traffic)
file_transfers (
  transfer_id TEXT PK, file_id TEXT, direction TEXT,        -- SEND | RECEIVE | SERVE
  class TEXT,                                               -- SMALL | LARGE
  peer_account_id TEXT, origin_message_id TEXT NULL,        -- discovered when the message shows up
  status TEXT,                                              -- CREATED | IN_FLIGHT | COMPLETED | EXPIRED |
                                                            -- DECLINED | CANCELLED | FAILED
  chunk_size_bytes INT, chunk_count INT, total_bytes INT, start_index INT,
  key BLOB,                                                 -- our copy: raw (holder) or unsealed (receiver)
  part_path TEXT, final_path TEXT, checkpoint INT,          -- large path: highest contiguous acked/written
  progress …, created_at, updated_at, expires_at
)
file_chunk_tracker (file_id, chunk_index PK, status, …)     -- receive side, small path (≤ ~46 rows)
pending_file_requests (file_id PK, status, next_attempt_at, …)

-- routing (foreign traffic + asking)
file_relay_transfers (
  transfer_id TEXT PK, source_device, target_device,
  chunk_size_bytes INT, total_bytes INT, start_index INT,   -- covered suffix, wholeness derived
  offer_payload BLOB,                                       -- replayed verbatim on forward
  status TEXT,                                              -- RESERVED | WHOLE | FORWARDING | DRAINED | EXPIRED
  reserved_bytes INT, created_at, last_chunk_at             -- last_chunk_at drives the reservation TTL
)
file_relay_chunks (transfer_id, chunk_index PK, blob)
file_request_attempts / file_request_declined_devices       -- sync-shaped
```

The outbox gains only the new tag-kind value. When implementation lands, guide §4's ownership table gets its
rows (`file_transfers`, `file_chunk_tracker`, `pending_file_requests` → orchestrator; `file_relay_*`,
`file_request_*` → routing) in the same change.

## 14. State machines

See [`transfer states.mmd`](transfer%20states.mmd). Compact listing:

- **Sender transfer**: CREATED → IN_FLIGHT (offer accepted) → COMPLETED / EXPIRED / CANCELLED. A large
  transfer's IN_FLIGHT includes waiting for a session (engine loop detail, not a distinct status).
- **Receiver (`fileId`-scoped)**: PENDING → IN_FLIGHT (`.part` reserved, key stored) → VERIFYING (file-level
  count met) → READY (hash verified, renamed) / FAILED / DECLINED. IN_FLIGHT that never progresses expires →
  the REQUEST trigger re-opens.
- **Relay transfer**: RESERVED (admitted) → WHOLE (covered suffix complete) → FORWARDING (offer acked) →
  DRAINED; EXPIRED (reservation TTL or transfer TTL) from RESERVED/WHOLE.
- **Request**: PENDING → ASKING (one candidate) → SATISFIED (OFFER received; the serve transfer takes over) /
  UNAVAILABLE (every candidate declined or expired); ASKING → PENDING on timeout/non-terminal NACK with the
  next candidate.
- **Marker** (outbox row): QUEUED → dispatched → gone on ACK / terminal NACK / expiry; retries resume, never
  restart.

## 15. Config

Per guide §2, each layer's config type lives next to its code:

- `FileServiceConfig` (orchestrator, `runtime/file`): `smallFileMaxBytes = 10 MB`, `maxInFlightChunks`,
  receive directory, retention quota, auto-accept threshold.
- `RouterConfig` additions: `relayFileBudgetBytes`, `fileTransferTtl` (days — marker, relay reservation and
  transfer lifetimes), `chunkAckTimeout`, `fileRequestAttemptTimeout`, `fileRequestFirstAttemptDelay` (the
  grace period).
- Derived in wiring (`FileLimits`, chained from the transport backend configs like `MessageLimits`):
  `chunkSizeBytes ≈ 220 KiB`.
- Reused unchanged: `RelaySelectionPolicy` with `relayTargetSuccessProbability`, `minRelayScore` and the
  `maxRelays` cap (§5); the outbox's own retry/park/expiry behavior.

## 16. Invariants

1. Anything a relay pushes is whole — count-based check over the covered suffix; this is the ceiling by
   design (the relay cannot and must not verify content).
2. One relay hop, ever: deposit-to-relays happens only at the original sender, redundancy is parallel (same
   covered set at every policy-selected relay), forwarding relays never re-deposit, the sender never chases a
   failing relay.
3. Relays are not REQUEST candidates; relays forget after delivery.
4. Never READY without all chunks and hash verification.
5. The per-file key travels raw only inside message E2EE plaintext; sealed everywhere else.
6. Deterministic ciphertext: key per `fileId`, nonce per (`fileId`, `chunkIndex`); chunking parameters are
   `fileId`-bound.
7. Covered sets are contiguous suffixes; per-depot engine state is one resume point.
8. The marker is establishment-only; ACK = processed (the §6 delivery contract is untouched); the engine owns
   everything after.
9. Receiver state is `fileId`-scoped (transfer-agnostic); relay state is `transferId`-scoped.
10. One REQUEST candidate at a time.
11. Terminal states distinguish re-requestable (`EXPIRED`) from refused (`DECLINED`/`CANCELLED`).
12. The relay verifies the original sender's signature on OFFER and CHUNK before storing anything.
13. Bare transfers (no message) are forgettable when both sides forget them — a named limitation.

## 17. Rejected alternatives

Recorded so they are not relitigated:

- **Whole file as one packet** — transport frame caps (250 KiB WebRTC, 4 MB Tor) make it impossible; chunking
  everywhere.
- **Chunk content through the Double Ratchet** — ratchet state explosion, `MAX_SKIP` bounds, chain breakage
  for interleaved messages; per-file symmetric key instead.
- **Relay-held chunks as outbox `is_relay` rows** — the relay cache budget is shared with messages (one 10 MB
  file would consume it entirely), per-row LRU eviction strands transfers, and making it safe would push
  transfer-awareness into the most battle-tested component. Dedicated tables isolate it.
- **Relay chunks on disk** — at ≤10 MB the DB gives transactional admission/eviction/crash recovery for free.
- **Marker held open until transfer completion** — needs a "processed but don't ACK" result, fights every
  message-scale outbox assumption, and duplicates state the engine owns.
- **Parallel REQUEST fan-out** — N devices each serving the entire file is the spam shape; one candidate at a
  time.
- **Relays as REQUEST candidates** — simplified away; relays are pure push caches.
- **Eager per-chunk relay forwarding** — gate on wholeness instead: the latency cost is theoretical (the relay
  path is only used when the direct path already failed), and the invariant "a relay push is always whole" is
  worth more.
- **Sparse covered sets** — the suffix invariant (§3.4) covers every case with bounded duplicate re-sends and
  one number of per-depot state.
- **Sequential relay re-deposit (chasing)** — spreads the file across the network; one jump.
- **Media tracks (RTP/SRTP) for file streaming** — real-time and lossy by design; files need the data
  channel's reliability.
- **Plaintext streaming over DTLS without app-layer AEAD** — kept for determinism across holders, uniformity
  with the relay path, and transport independence.
- **Positioned-I/O platform port in v1** — pure kotlinx.io with a reorder buffer and open+skip instead; swap
  in kotlinx.io positioned I/O when it ships (#241).

## 18. Deferred

Group sender keys (additive by design); OFFER-response carrying held-state (bandwidth optimization for
duplicate relay forwards); per-tag-kind outbox in-flight cap; relays as REQUEST candidates (rejected for v1);
disk-based relay store (only if `smallFileMaxBytes` grows); positioned I/O via kotlinx.io #241; GUI-API
contract for the send/progress/decline surface (to be written with the GUI work).

## 19. Test matrix

- Small file, target offline: deposit → wholeness → forward on surfacing → verify → READY.
- Mid-transfer restarts: sender restart mid-push (resumes from plan/outbox); relay restart mid-forward (free
  recovery); receiver restart mid-assembly (tracker + reorder).
- Direct-link break mid-send: remainder deposit at the policy-selected relays; holes below the prefix are
  inside the remainder; target completes from direct prefix + relay remainder.
- Duplicate relay forwards: target dedups (`fileId`, `chunkIndex`); second relay drains on already-held ACKs.
- Stall → immediate deposit; sender killed right after → relays hold a whole copy.
- Eviction pressure → DECLINED at admission → engine tries the next relay / re-offers later.
- Sender dies mid-deposit: partial never pushed; reservation TTL cleans; target's REQUEST recovers from a
  holder.
- Large file: no session → waits (never Tor); session opens → streams; session drops → checkpoint resume;
  restart mid-transfer → linear-skip resume.
- History sync on a fresh device: attachment renders from the message, REQUEST pulls the bytes with exact
  resume from held-state.
- Declined / cancelled / no-space / ENOSPC paths; "file unavailable" after all candidates decline.
- No-chaining negative test: a forwarding relay must never deposit elsewhere.
- Unverified chunk injection at a relay (bad signature) is dropped and never counts toward wholeness.
- Bare transfer send/receive without any DAG message.

## 20. What exists today (disposition)

| Existing                                                              | Disposition                                        |
|-----------------------------------------------------------------------|----------------------------------------------------|
| `protocol/envelopes/FileEnvelope.kt` (+ codec test)                   | reworked per §3.2; suffix OFFER, REQUEST kind, dropped wire types |
| `protection/envelope/FileProtection.kt`, `PlaintextFileProtection.kt` | interface kept, real implementation replaces the placeholder |
| `routing/inbound/handlers/FileInboundHandler.kt` (stub)               | real logic per §7 (relay) and §8 (target)          |
| `persistence` `file_transfers`, `file_chunk_tracker`, `spaces` (placeholders) | `file_transfers`/`file_chunk_tracker` reworked per §13 (owner: orchestrator); `spaces` untouched by this design |
| `persistence/DatabaseEnums.kt` (`FileTransferStatus`, `FileChunkStatus`) | moved to the owning store, with the terminal-state distinction of §9 |
| `routing/policy/RelaySelectionPolicy.kt`                              | reused unchanged (§5)                              |
| `routing/outbound/RelayDepositor.kt`, `OutboxProcessor` triggers      | the trigger policy is extracted for shared use (§5) |
| `routing/store/PacketOutbox.kt` (`OutboxTag`)                         | new `Kind.FILE`; `OutboxOutcome` gains `packetId`  |
| `JvmWebRtcBackend` channel labels (`envelope`, `av`)                  | add the per-transfer file channel kind (§12)       |
| `wiring/MessageLimits.kt`                                             | joined by `FileLimits` (derived chunk size)        |
