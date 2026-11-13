# File transfer design

This is the design of record for file transfer (v1, 1:1 sends). The first draft was agreed in the design
sessions of November 2026 and revised after a full pre-implementation review (13 November 2026); it is the
base for implementation. [`../sprints.mmd`](../sprints.mmd) stays authoritative for sprint sequencing at its
coarser grain and is not modified by this design.

Related: [`../guide.md`](../guide.md) (layer model, dependency rules, the delivery model in §6),
[`../transport.md`](../transport.md) (links, channels, frame limits), [`../router.md`](../router.md) (outbox and
relay machinery this design reuses), [`../e2ee.md`](../e2ee.md) (sessions — used for the OFFER's sealed half,
deliberately **not** for chunk content, see §4). Diagrams: [`transfer paths.mmd`](transfer%20paths.mmd),
[`transfer states.mmd`](transfer%20states.mmd).

## 1. Scope and philosophy

Two transfer classes, one engine:

- **Small files** — at or below `smallFileMaxBytes` (default 10 MB, the Discord limit; the exact value lives in
  config, §15). May traverse relays with store-and-forward. Bounded size, so bounded relay storage.
- **Large files** — above the limit, no upper bound (hundreds of GB must work). WebRTC-only: no session means
  the transfer waits; bytes never go over Tor and never go to relays.

The core philosophy transplants the guide's delivery model (§6) to files, with one hard separation on top:
**the message and the transfer are decoupled. A message with an attachment is a notification — "you may want
this file; make sure you have it" — and carries no key and no chunk parameters. Transfers are the only way
file bytes move, each one self-contained, and the pull path (DAG message → REQUEST → serve) is the loss-proof
backbone.** A deferred, expired, stalled or partial push is never a loss — the message references the file
forever, and the receiver can always REQUEST the bytes from a holder.

**Transfers are temporary; files stay.** A transfer lives for `fileTransferTtl` (§15) and then it is gone; a
completed file lives in the permanent attachment store (§10).

Grounding facts that shape everything below:

- Chunking is forced everywhere: the WebRTC backend carries at most ~250 KiB per envelope
  (`WebRtcBackendConfig.maxPayloadBytes`), Tor at most 4 MB. The chunk size is **derived** (≈220 KiB after
  envelope framing) by the composition root, never independently configured (§15); each transfer snapshots the
  current derived value at creation (§2).
- **No new ARQ is built.** Both transports are reliable per frame (WebRTC data channels are SCTP, configured
  ordered + fully reliable; Tor links are framed TCP streams), and the router already gives every envelope
  ACK/NACK + dedup + outbox retry. A chunk sent as an ordinary packet is effectively-once per hop. The
  SACK-style ACK payload is the **progress/resume** protocol, not loss detection.
- Group sends are out of scope for v1, but the design is deliberately encryption-independent: chunk content is
  an opaque AEAD blob keyed per file, so group fan-out (sender keys) is additive later without touching the
  chunk pipeline.

## 2. Identity: `fileId` and `transferId`

- **`fileId` = content identity.** Derived from the whole-file hash, i.e. content addressing. Stable forever,
  referenced by the DAG message, and the key for the receiver's coverage state, `.part` file, final file and
  the permanent attachment store. Free dedup: two senders shipping identical bytes produce the same `fileId`,
  and a receiver who already holds it renders the second message instantly from local storage. The identity
  *is* the integrity check — no separate hash field exists anywhere.
- **`transferId` = routing session identity.** One push/serve attempt **between two devices**. Keys the relay
  store, the OFFER, the outbox marker tag and chunk envelope addressing. Relays never see or need the
  `fileId`.
- **Transfers are device-to-device.** A send to an account is one orchestrator-level send that routing fans
  out into one transfer per device of that account — the message model's fan-out, pushed one step further (the
  transfer state itself is device-grain and lives routing-side, §12). This strains relays more than an
  account-grained design would; that cost is accepted and will be optimized away with group keys and sends.
  Sibling devices never targeted by a transfer get the file through REQUEST (§9); that pull is normal traffic,
  not a failure.
- The **per-file key** is bound to the `fileId`. **Chunk parameters are bound to the transfer** (snapshotted at
  creation): the receiver is param-agnostic (§8) — every chunking of the same `fileId` puts the same bytes at
  the same offsets, so transfers with different parameters coexist and contribute. Chunk ciphertext is
  deterministic **per byte range**: holders using the same chunking produce byte-identical chunks, and resume
  works across transfers, chunkings and serving devices.
- Receiver state is `fileId`-scoped (valid across transfers); relay state is `transferId`-scoped.

## 3. Wire protocol

All FILE-family envelopes are signed by their author (the `source` field). A relay forwarding stored envelopes
replays the original signature verbatim — the relay is never a signature author.

### 3.1 Message attachments

`MessagePayload.Text` gains an `attachments` list. Each entry is deliberately minimal — everything a receiver
needs to render the message and decide to REQUEST, nothing more:

| Field         | Notes                                            |
|---------------|--------------------------------------------------|
| `fileId`      | The content hash — also the integrity check      |
| `fileName`    | Display                                          |
| `mimeType`    | Display / preview                                |
| `totalBytes`  | Display, the receiver's completeness oracle, and its space check |

**The message is notification only.** No key, no chunk parameters — those travel exclusively with transfers
(§3.3). This is a wire change: codec + round-trip tests, and the attachment bytes are charged against the
message size budget (the `Text` `ENCODED_HEADER_RESERVE_BYTES` accounting grows; `wiring/MessageLimits`
threads it).

### 3.2 FileEnvelope payloads (rework of the existing family)

| Kind       | Fields                                                                 | Author / direction                |
|------------|------------------------------------------------------------------------|-----------------------------------|
| `OFFER`    | public half: `chunkSizeBytes`, `totalBytes`, `startIndex`; sealed half: `fileId`, per-file key, `fileName`, `mimeType` | the serving device → target device (or relay, for deposits) |
| `CHUNK`    | `chunkIndex`, ciphertext (covers `[chunkIndex × chunkSizeBytes, …)` — **the transfer's own chunking**) | pusher → target (replayed by relay) |
| `ACK`      | `fileId`, `contiguousBytes`, `missingRanges` (coverage, capped)        | target → that transfer's pusher    |
| `COMPLETE` | `fileId`                                                               | target → **every live transfer author**; also the cancel-on-complete (§8) |
| `REQUEST`  | `fileId`, held coverage (`contiguousBytes` + `missingRanges`)          | requester → one candidate device   |
| `CANCEL`   | reason                                                                 | either endpoint: author → relay/target (abort); target → author (decline, no space, abort) |

Notes on the rework:

- The existing `FileChunk`/`FileControlPayload`/`FileTransferClass`/`FileTransportPreference` wire types are
  dropped. The small/large distinction is a sender-engine policy driven by the config threshold, not wire
  state; transport preference is path policy (§5), not a negotiated field. `maxInFlightChunks` is engine
  config (§15), not wire.
- `CHUNK` loses its `chunkCount` — the covered set is defined by the OFFER (§3.4).
- The existing per-chunk hash idea stays dead: AEAD tags detect corruption at decrypt; whole-file integrity is
  the `fileId` itself.
- **ACK and REQUEST speak bytes, not chunk indices.** Every transfer may chunk differently, and only its
  author knows its chunking; coverage is the shared language. Each author maps coverage bytes onto its own
  indices.

### 3.3 The OFFER's two halves

- **Public half** — what relays need for their storage contract: `chunkSizeBytes`, `totalBytes`,
  `startIndex`. From these the relay derives the file-level chunk count (`ceil(totalBytes / chunkSizeBytes)`)
  and the covered suffix (§3.4). The `transferId` rides the envelope header.
- **Sealed half** — `fileId`, the per-file key, display metadata — **encrypted to the target device through the
  standard Double Ratchet** (the message-path machinery; epoch-1 X3DH bootstraps first contact offline, so no
  session needs to pre-exist). Relays can never read it and never learn the `fileId`.
- **Key transport: the OFFER's sealed half is the only carrier of the per-file key.** Messages never carry it
  (§3.1). The chain of custody still closes for every legitimate holder, because any holder re-seals the key
  to a requester inside its serve OFFER — a sibling or a fresh device syncing history receives the key with
  the serve (§9).
- **Late decrypt is a config invariant, not luck.** The relay replays the sealed half verbatim up to the
  transfer TTL, so the target's ratchet state for that generation must outlive the TTL —
  `fileTransferTtl < supersededSessionRetention` (2 days < 3 days, §15; e2ee.md keeps superseded generations
  decryptable for that window).
- Correlation, honestly: relays cannot *read* the `fileId`, but same-file transfers at one relay are
  *correlatable* through public metadata (totalBytes, chunk size, covered suffix, participants), and identical
  ciphertext under the same chunking confirms it. "Not readable", never "not correlatable".
- The receiver's completeness oracle is `totalBytes` — from the message or any OFFER's public half.
  Completeness is coverage of `[0, totalBytes)` (§8); no chunk-count agreement is ever needed, which is what
  lets mixed chunkings coexist.

### 3.4 Covered sets are always suffixes (in the transfer's own chunking)

Every transfer covers a **contiguous suffix** of the file's chunk sequence in its own chunking:
`[startIndex, fileCount-1]`.

- Initial full transfer: `startIndex = 0`.
- Direct-link break remainder: `startIndex = highestContiguousAckedPrefix + 1` (§6.4).
- REQUEST serve: `startIndex` = the chunk boundary at or below the requester's lowest missing byte
  (floor-aligned in the server's chunking; the few duplicate bytes are dropped on arrival — never reject
  useful data over alignment).
- Large-path resume: the checkpoint is a contiguous prefix, so resumption is a suffix by construction.

This invariant is what keeps the OFFER to a single start index (no lists, no bitmaps) and collapses the
sending engine's per-depot bookkeeping to one number per depot: its resume point.

### 3.5 Chunks follow their OFFER

A transfer's chunks flow only after that transfer's OFFER was processed by the target. Both paths guarantee it
by construction: the direct push enqueues chunks only after the marker's ACK, and the relay forwards only when
whole, its own OFFER-ACK gating its chunks. This is load-bearing: a CHUNK envelope carries no `fileId`, so the
target maps `transferId → fileId` (and the key) only through a processed OFFER. A chunk for an unknown or
unprocessed `transferId` is never ACKed and never stored — at a target or at a relay.

## 4. Crypto

- **Per-file symmetric key** (AEAD, ChaCha20-Poly1305), created at send time, bound to the `fileId`. Chunk
  content **never** goes through the Double Ratchet: a long file would explode ratchet state, `MAX_SKIP`
  bounds out-of-order tolerance, and one lost chunk would break the chain for interleaved messages.
- The **OFFER's sealed half rides the standard ratchet** (§3.3) — one small frame per transfer, bootstraps
  offline, late-decrypt-safe by the retention invariant.
- **Deterministic ciphertext per byte range: the nonce is derived from (`fileId`, `byteOffset`,
  `chunkLength`) — never from a bare chunk index.** With per-transfer chunkings, two transfers of the same
  file can carry *different plaintext under the same index*; an index-derived nonce would be nonce reuse under
  one key — a hard AEAD break. The range-derived nonce makes that impossible, while holders using the same
  chunking still produce byte-identical chunks, so resume across holders stays exact.
- WebRTC's DTLS already encrypts the channel; the app-layer AEAD is kept regardless — negligible cost
  (AEAD is GB/s), uniform format across paths, determinism across holders, and independence from
  transport-stack correctness.
- New `FileProtection` implementation (ratchet-sealed OFFER half, signing, per-chunk AEAD via
  `CryptoProvider`) replaces the `PlaintextFileProtection` placeholder.

## 5. Path selection

**Small files** mirror the message model exactly: direct over any open link first; relay deposit only when the
direct path looks doubtful. The deposit triggers are the existing `RelayBackup` triggers (no link at claim
time, retry, no ACK in time) **plus** a chunk-level stall trigger (§6.3). The trigger *policy* is extracted
into a shared component that messages and files both consult — including generalizing the outbox's
MESSAGE-only no-ACK backup check — and payload construction stays per-path. FILE control packets (ACK /
COMPLETE / CANCEL) are **not** message-style relay-backed: they are politeness, bounded by the TTL; the pull
path backstops their loss.

**Relay choice is `RelaySelectionPolicy`'s, unchanged from messages.** It returns whichever relays its
reliability math picks for the target (targeting `relayTargetSuccessProbability`, floor `minRelayScore`), and
the number it returns varies up to the `maxRelays` cap. The deposit does not choose a count — it offers the
covered set to **every relay the policy returns**. An empty selection means no deposit; the pull path covers
it.

**Large files**: OFFER and chunks are both **WebRTC-only**. The large path therefore never touches the outbox:
the engine holds its IN_FLIGHT state, subscribes to session events, and sends directly when a session exists
(no per-entry transport mask is needed in v1). The receiver learns "a big file is pending" from the DAG
message, which does travel over Tor/relays — that is the "waiting for session" UX.

**Composed sends push immediately.** Publishing a message with attachments starts the per-device file
transfers alongside the message push — the file is not waiting for anyone to ask. If an OFFER has not landed
within the grace period (`fileRequestFirstAttemptDelay`) — the sibling never targeted, an offer lost, or the
message arrived via sync rather than push — the REQUEST trigger fires (§9). Push is the latency path; REQUEST
is the backstop; the grace period is the only thing telling them apart.

## 6. The push machinery (routing layer)

One mechanism, two chunk sources. The **pusher** owns: the per-device fan-out (an account-level send from the
orchestrator becomes one transfer per target device — the `sendMessage` pattern), the OFFER-as-marker
lifecycle, chunk outbox rows, the in-flight window, the stall detector, deposit-on-stall, and resume. It
speaks pure router vocabulary (packets, outbox, ACKs, links), so it lives in **routing**, parameterized by a
ciphertext source:

- **Sender's push** — source implemented by the orchestrator (reads the app-internal file, AEAD-encrypts on
  the fly) through a `FileChunkSource` port the router declares.
- **Relay's forward** — source implemented by routing itself (reads the `file_relay_chunks` store). The relay
  is simply another sender whose "file" is a DB table (§7).

### 6.1 The marker

The OFFER is enqueued as a normal outbox entry with `OutboxTag.Kind.FILE`, id = `transferId`, and standard
semantics — the delivery contract's "ACK = processed" is untouched:

- ACK (the offer was accepted) → marker removed; **the engine owns everything after**.
- Terminal NACK **DECLINED, split by source**: relay-declined (storage full) → that depot is out; the engine
  tries the next relay or re-offers later. Target-declined (no space, or the user declined a large file) → the
  send to that device is **terminally DECLINED** — no re-offer, no deposit; a refused file is never
  auto-resurrected (§9, invariant 11).
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
reconstructable from the outbox — no parallel per-chunk table on the send side. Chunk offsets derive per
transfer (`chunkIndex × chunkSizeBytes`); nothing is pre-materialized. The OFFER marker and chunk rows are the
FILE packets that participate in the deposit triggers (§5); control packets do not.

### 6.3 Stall detection

No chunk ACK within `chunkAckTimeout` (30–60 s) → the transfer is flagged stalled → small path: deposit the
remainder to relays at once (§6.4); large path: pause and await session re-establishment.

Deposit-on-stall is **insurance, not restart-survival**: the DB state (transfer rows, outbox rows) resumes on
boot regardless. What the deposit buys is that the already-pushed prefix never again needs the sender and the
target online simultaneously.

### 6.4 Deposit rules: one jump, parallel redundancy

- **The direct link breaks or stalls → deposit the remainder.** The remainder is the suffix after the
  **highest contiguous ACKed prefix** (`startIndex = prefix + 1`). This is safe by construction: any ACK holes
  below the prefix end are inside the remainder by definition, so the simplification never excludes a hole; it
  may re-send a few chunks the target already got, which the receiver drops as duplicates.
- **A deposit happens at most once per transfer** (the message backup's claim pattern, restart-safe) — and
  once suffices, because covered sets are suffixes: a later stall's remainder is a subset of the first
  deposit's covered set. The direct path keeps retrying meanwhile; the deposit is the insurance.
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

A transfer's push phase ends when **every deposit has resolved** (complete at its relay, or expired), or
earlier on the only two legitimate cross-depot teardowns:

- **Target-confirmed receipt**: on readiness the receiver sends COMPLETE to **every live transfer author**
  (§8) — ordinary routable packets, so "the target has everything" reaches each author even when only relays
  delivered. Each author resolves its transfer and politely CANCELs its outstanding deposits so relays free
  quota immediately.
- **User cancel**: CANCEL to every active transfer and deposit.

COMPLETE is per-transfer-addressed (target → that transfer's author), never account-fanned: the transfer
logic happens between the two devices that participate in it (§12).

## 7. The relay

The relay is **dumb to content, smart about its storage contract**. It cannot read chunks (no key), cannot
verify content (the `fileId` hash is over plaintext — count-based wholeness is the ceiling, by design, and
nothing smarter helps any actor), and never learns the `fileId`. It is not a REQUEST candidate (§9). Its
storage is DB tables, not a files directory — transactional admission, eviction and crash recovery for free at
≤10 MB per transfer.

### 7.1 Signature verification at the door

The relay verifies the **original sender's signature** on the OFFER and on every CHUNK before storing (the
inner `FileEnvelope.source` must be a known, non-banned device; its public key comes through protection from
the identity views routing may read). Without this, anyone who learns a `transferId` could stuff junk chunks
into the store: they would fail the target's decrypt later, but they would poison wholeness counting and
waste quota. Verified envelopes only; unverified are dropped.

Two more door rules, both answered **DECLINED** so the sender cleans up at once instead of retrying into a
dead store: a chunk whose `transferId` has no live transfer row (never admitted, expired reservation,
evicted), and a chunk index outside the covered suffix (it can never count toward wholeness and only wastes
quota).

### 7.2 Lifecycle

1. **Admission**: the OFFER's public half is checked against the relay's share of the storage pledge (§15);
   the covered suffix's bytes are reserved, or the OFFER is NACKed DECLINED (the existing vocabulary). The
   relay also stores the OFFER's payload blob verbatim — the forward re-uses it without re-signing (§7.4).
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
   - **Blind full-send in v1**: the target ACKs every chunk it processes; a chunk whose range is already
     covered returns Success → row deleted. Duplicate waste is bounded; an OFFER-response carrying held-state
     is a later optimization, not a correctness need (§18).
   - **Target-declined forward**: a DECLINED on the forwarded OFFER — the only reachable case is no space,
     since small files auto-accept and large files never touch relays (§8) — drops the transfer row and frees
     the reservation. The target said no; re-offering would be spam.
   - **No chaining**: a forwarding relay never deposits its forwarded transfer at other relays. Stall at a
     forwarding relay = park and wait (outbox-native).
5. **Eviction**: whole-transfer LRU over `file_relay_transfers` — never a single row (evicting chunk 37 of 46
   silently kills the transfer). RESERVED transfers are not exempt: evicting a mid-deposit transfer is
   allowed, and the sender learns through the door DECLINED on its next chunk.
6. **Forget after delivery** — same as relayed messages: rows die as the target ACKs, the transfer row dies
   when drained.

**Invariant**: *anything a relay pushes is whole.* A forwarded push that stalls is therefore always a link
problem (the forwarder retries from its complete store), never a content problem — the receiver needs no stall
detection on relay pushes.

**Restart recovery is free**: the forward's state is entirely outbox rows + store rows, both in the DB, both
already recovered by boot; wholeness is recomputed from the store.

## 8. The receiver

One engine for both classes — the small/large distinction is send-side path policy (§5); the receiver never
branches on it.

- **`fileId`-scoped state.** The message or the first OFFER creates one receive row; the key is stored from
  the first OFFER's sealed half; every incoming transfer merges into it. The receiver is transfer-agnostic and
  **chunking-agnostic**.
- **Byte coverage is the whole model.** A chunk covers `[chunkIndex × chunkSizeBytes, …)` in its transfer's
  chunking — and the bytes at any offset are identical across every transfer of the `fileId` (the `fileId` is
  the hash of the whole file). Chunks from any number of transfers, with any chunkings, land their byte
  ranges; fully-covered ranges are duplicates and are dropped — duplicates are normal traffic (multiple relay
  deposits, the direct path, serves all overlap). **Nothing is rejected for disagreeing with another
  transfer** — every transfer is a potential accelerator; two chunkings of the same file can finish it
  together.
- **Durable-before-ACK.** The durable state is one contiguous prefix P — bytes `[0, P)` appended to the
  `.part`, append-only — plus staged ranges beyond it. A chunk that extends coverage is decrypted and durably
  staged, and only then ACKed: the chunk ACK means "decrypted and durably recorded", which is the delivery
  contract's ACK = processed with "processed" defined per payload kind (a sync request is processed when its
  reply is sent; a chunk when it is staged). The handoff from routing to the receive engine is a synchronous
  port call for exactly this reason.
- **Staging is bounded** (`maxStagedBytes`). Beyond the bound, a chunk that cannot extend P is answered DEFER
  — silent, dedup-cleared; the sender's window closes and its retry resumes once P advances. Without this, a
  racing transfer could stage an unbounded suffix behind a stuck gap.
- **Reserve** `<fileId>.part` at `totalBytes` with an **explicit free-space check** (preallocation is sparse
  on most filesystems — stat the volume, not the file). Small files auto-accept — the prompt exists only for
  large ones, where declining makes sense per device (no to 100 GB on the phone, yes on the PC). Insufficient
  space or a decline → a DECLINED/CANCEL to the author and a clean failure; mid-write ENOSPC → CANCEL, clean
  failure.
- **Completeness → READY.** Coverage of `[0, totalBytes)` → **verify the hash (`fileId`) → atomic rename →
  status READY**. Never READY without verification. On READY the receiver sends **COMPLETE to every live
  transfer author**; each ends its transfer and cancels its deposits — COMPLETE is the cancel-on-complete. A
  transfer arriving for an already-READY file is answered COMPLETE at its OFFER (its chunks, if any raced
  ahead, were duplicates).
- **Progress ACKs** (coverage: `contiguousBytes` + missing ranges, capped) flow back to each pusher; each
  maps the bytes onto its own chunking.
- **The gap trigger.** If P does not advance for `fileStallTimeout` (minutes — deliberately beyond the
  sender's `chunkAckTimeout` so the push machinery acts first), the incoming transfers are marked STALLED —
  re-requestable, not refused — the durable coverage is kept, and the REQUEST path re-opens with held coverage
  (§9). This is also the large-path resume story: a dropped session with a gone sender funnels into the same
  trigger. TTL expiry is the passive backstop; the trigger is the active path.
- **Restart.** P re-derives from the `.part` length (a torn append truncates to the last whole chunk boundary
  at open); staged ranges rehydrate from their table; missing is the complement of durable coverage. What was
  ACKed was durable, so nothing ACKed is ever lost.

## 9. The pull path: REQUEST

The recovery mechanism for everything the push path failed to deliver, and the only way history-acquired
files get their bytes.

- **Trigger** (orchestrator inbound pipeline, on message ingest — the message may have arrived by push or by
  sync): a message references a `fileId` with no completed local file and no progressing transfer. Guards:
  - a grace period before the first attempt (`fileRequestFirstAttemptDelay`) — the OFFER may be in flight, or
    may never come (the sibling case, §2); the grace period is the only thing telling "late" and "never"
    apart, and it errs on the quiet side;
  - **STALLED / EXPIRED are re-requestable** (something broke; try again); **DECLINED / CANCELLED / FAILED are
    refused** — never auto-resurrect a file the user declined or that failed; those need a user re-open;
  - dedup by `fileId` (message duplicates create one row).
- **One candidate at a time, never parallel** — parallel asks would make N devices each serve the entire
  file; that is the spam/DDoS shape. Candidates: the sender's devices, then own-account siblings (room
  members later), link-availability filtered like sync.
- **The attempt is sync-shaped: the REQUEST is ACKed ("I hold it, serving") or NACKed DECLINED ("I don't, or
  I won't") — and the OFFER is the reply**, exactly like a sync request's ACK-then-reply. On ACK the request
  waits (WAITING) with a timeout for the OFFER — a large serve legitimately sits in AWAIT_SESSION for a
  while; on timeout or DECLINED the next candidate is asked at once. All candidates dead → "file unavailable"
  (terminal; a new sync or a user re-open retries).
- The REQUEST carries **held coverage** (`contiguousBytes` + missing ranges, capped; heavy fragmentation
  degrades to contiguous-only — a coarser serve with more duplicates, still correct).
- The serving device (any holder: original sender, sibling that completed) re-runs the push engine with a
  **fresh `transferId`**, covering the suffix from the floor-aligned boundary at or below the requester's
  lowest missing byte (§3.4). Same-chunking serves are byte-identical (§4); mixed chunkings are equally
  correct — the receiver is param-agnostic.
- The candidate-asking loop is **extracted as a shared core with `SyncRetryProcessor`** (the needs now
  coincide; on success the file request hands off to the transfer engine instead of consuming a reply payload
  — a callback seam, not a structural fork).
- Relays are **not** candidates: they are transient push caches with forget-after-delivery semantics.

## 10. Storage and retention

- **Sender/holder: the GUI picks, the core does the rest.** The GUI owns only the platform file chooser; the
  orchestrator's send API copies the picked file into **app-internal storage** before anything is sent — the
  chunk source is immutable across restarts, so resume cannot fail because the user moved the original — and
  owns the store's directory layout, retention and quota, **vending paths and metadata to the GUI** for
  rendering; the GUI never writes into the store. **Keep forever** is the default policy — serverlessness
  forces it (the bytes are on a device or nowhere; there is no lazy-loading when a user scrolls back a year).
  Knob: per-account quota with LRU above it.
- **Receiver**: the same permanent store once READY; `.part` until verified.
- **Relay**: transient ciphertext only — pledged-storage-capped (§15), TTL'd, forget-after-delivery.
- **Transfers are temporary; files stay** (§1). v1 has no bare transfers — every transfer belongs to a
  message-composed send; a GUI-only send without a message, if it ever exists, gets its own table (§18).

## 11. File I/O: pure kotlinx.io

kotlinx.io is the house standard already (`ConfigStore`, `JvmAppLogger`, `KmpTorBackend`, `DataDirLock`,
`Platform`), and v1 uses **only** it — positioned I/O is on kotlinx.io's roadmap (files-API redesign,
kotlinx-io issue #241) but not shipped, so the design avoids needing it:

- **The receiver is a single append-only writer position** (P, §8): staged ranges absorb arbitrary arrival
  order and mixed chunkings, so no path ever seeks — small and large alike. A staged range's tail beyond P is
  sliced in memory at drain; verification reads linearly.
- **The sender** reads linearly via `source()` and keeps the source open across session drops (a drop needs
  no reopen); re-reads after a full app restart are open + linear skip to the resume point (a one-time bounded
  read of the already-sent prefix).
- A torn append (crash mid-chunk) truncates to the last whole chunk boundary at open — the checkpoint
  re-derives from the file.
- The chunk-shaped I/O boundary (`readChunk`/`writeChunk`-style module) still exists as a module, with a
  kotlinx.io-only implementation inside — so when #241 ships positioned read/write, the swap is internal, not
  a design change.

## 12. Layering and data ownership

**Two grains, one seam:**

- **Routing owns the `transferId` grain** — device vocabulary: the per-device fan-out, per-transfer chunk
  parameters, per-depot resume points (the pusher's state, restart-recoverable from its rows plus the
  outbox), sealed-half protection, **all transfer signaling** — OFFER, CHUNK, ACK, COMPLETE and CANCEL travel
  between the two devices that participate in the transfer — and the relay store and the request attempts.
- **The orchestrator owns the `fileId`/account grain** — file vocabulary: send rows per (file, peer account),
  receive state per `fileId`, serve rows per (file, requester account), the per-file keys, the accept/decline
  verdicts, and the REQUEST trigger. It sees account-level events through the `FileCarrier` port and issues
  account-level ops; source devices may appear in events as *facts* (like "re-verify messages from a
  device"), never as *addresses* — replies are routing-owned signaling.

The precedent is the message path: the router fans out per device and the orchestrator never addresses one;
files push the same line one step further (transfer state itself is device-grain, so it lives routing-side).
A headless relay node's orchestrator (file service, REQUEST logic, GUI progress) is completely uninvolved in
forwarding. Serve-on-REQUEST is the one relay-node case that *is* orchestrator business (we hold the file →
own traffic → our row, account-grain, pushed through the same machinery).

### Module layout

| Layer      | Owns                                                                                        |
|------------|---------------------------------------------------------------------------------------------|
| `protocol` | `FileEnvelope` rework (§3.2: coverage-based ACK/REQUEST), `Text.attachments` (§3.1), codecs + round-trip tests, observability profile update |
| `protection` | real `FileProtection` (§4): ratchet-sealed OFFER half, per-chunk AEAD                      |
| `routing`  | the pusher (`file/push`): per-device fan-out + per-transfer state; the relay store and forwarder (`file/relay`); real `FileInboundHandler` (foreign target → admission/park; local → synchronous ingest port + emit); `FileCarrier` port on `Router` + account-level file events; the `FileChunkSource` port (orchestrator implements the disk source, routing implements the store source); the file request processor on the shared candidate core; `OutboxTag.Kind.FILE`; `packetId` on `OutboxOutcome`; the extracted deposit-trigger policy shared with messages |
| `orchestrator` | `runtime/file` — `FileTransferService`: send API (account-level; core-owned copy into the store), classification, descriptor/key creation, the receive engine (coverage, staging, verify, ready), the REQUEST trigger + coordinator, serve-on-REQUEST (account-level); stores: `file_descriptors`, `file_transfers`, `file_receives`, `file_receive_chunks`, `pending_file_requests`; the transfer enums move out of `persistence/DatabaseEnums.kt` to the owning stores |
| `transport/webrtc` | a file channel kind + per-transfer channel labels (the backend already multiplexes by label: envelope and AV channels); flow-control exposure |
| `wiring`   | `FileLimits` (derived chunk size, chained like `MessageLimits`), config wiring incl. the storage-pledge split |

The large-path transfer session (offer → accept/reject → active → ended/failed) is designed as a sibling of
the AV session contract, so the planned shared session machinery ("offer retry queues for voice and file",
sprints s6a) plugs in rather than forks.

### Large-path transport detail

A per-**transfer dedicated data channel** (labeled, opened on the existing peer connection — no renegotiation,
BinaryEnvelope framing always) carries the stream. This also solves head-of-line blocking: multiplexing a
100 GB stream onto the single ordered envelope channel would starve messages and ACKs behind file data.
Pacing is the channel's own flow control (`bufferedAmount` / low-threshold backpressure), which **replaces
the app-level window for this path**.

## 13. Database sketch

Final DDL lands with implementation; every schema change ships with a migration (guide §4).

```
-- orchestrator (own traffic — file/account grain; transfers never model message linkage)
file_descriptors (            -- the permanent store index: what we hold
  file_id TEXT PK, key BLOB, file_name TEXT, mime_type TEXT,
  total_bytes INT, path TEXT, created_at …
)
file_transfers (              -- our sends and serves: one row per (file, peer account)
  file_id TEXT, peer_account_id TEXT, direction TEXT,   -- SEND | SERVE
  class TEXT,                                            -- SMALL | LARGE
  status TEXT,                                           -- CREATED | IN_FLIGHT | COMPLETED | EXPIRED |
                                                          -- DECLINED | CANCELLED | FAILED
  progress …, created_at, updated_at, expires_at
)
file_receives (               -- receive state, fileId-scoped; incoming transfers merge here
  file_id TEXT PK,
  status TEXT,                -- PENDING | IN_FLIGHT | STALLED | VERIFYING | READY | FAILED | DECLINED
  total_bytes INT, contiguous_bytes INT,                 -- the coverage checkpoint
  key BLOB,                   -- until READY; the descriptor holds it afterwards
  part_path TEXT, final_path TEXT,
  origin_message_id TEXT NULL,                           -- discovered when the message shows up
  …
)
file_receive_chunks (         -- staging is the chunk tracker: staged, not-yet-drained ranges
  file_id TEXT, start_offset INT, length INT, plaintext BLOB,
  PRIMARY KEY (file_id, start_offset)
)
pending_file_requests (file_id TEXT PK, status TEXT, next_attempt_at, …)

-- routing (transferId grain: pushing, forwarding, asking)
file_pushes (                 -- the pusher's per-device transfer state
  transfer_id TEXT PK, file_id TEXT, target_device TEXT,
  kind TEXT,                  -- DIRECT | DEPOSIT | SERVE
  chunk_size_bytes INT, start_index INT, …               -- the covered suffix; resume derives
  status …, created_at, expires_at
)
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

The outbox gains only the new tag-kind value. The receive staging table and the relay store share a
`((scope), index-or-range) → blob` **shape** and never a **table**: routing stores ciphertext for foreign
traffic; the orchestrator stores plaintext for its own — one owner per table (guide §4), and the domains
never mix. Guide §4's ownership table already records the rows (owners per this section); the schema and its
migration land with implementation.

## 14. State machines

See [`transfer states.mmd`](transfer%20states.mmd). Compact listing:

- **Sender transfer (per device, routing)**: CREATED → IN_FLIGHT (offer accepted) → COMPLETED (COMPLETE from
  the target; deposits cancelled) / EXPIRED (`fileTransferTtl`) / **DECLINED (the target declined — terminal
  for this device)** / CANCELLED. A large transfer's IN_FLIGHT includes waiting for a session (engine loop
  detail, not a distinct status).
- **Receiver (`fileId`-scoped)**: PENDING → IN_FLIGHT (`.part` reserved, key stored) → VERIFYING (byte
  coverage complete) → READY (hash verified, renamed, COMPLETE to live authors) / FAILED (hash mismatch,
  ENOSPC) / DECLINED; IN_FLIGHT → **STALLED** (no prefix progress within `fileStallTimeout` → REQUEST
  re-opens); STALLED → IN_FLIGHT when a serve resumes delivery; IN_FLIGHT that never progresses expires →
  the REQUEST trigger re-opens.
- **Relay transfer**: RESERVED (admitted) → WHOLE (covered suffix complete) → FORWARDING (offer ACKed) →
  DRAINED; EXPIRED (reservation TTL or transfer TTL) from RESERVED/WHOLE; **dropped when the target DECLINED
  the forwarded OFFER**.
- **Request**: PENDING → ASKING (one candidate) → **WAITING (ACK; offer pending, timeout → next candidate)**
  → SATISFIED (OFFER received; the serve transfer takes over) / UNAVAILABLE (every candidate declined or
  expired).
- **Marker** (outbox row): unchanged from the message contract — QUEUED → dispatched → gone on ACK / terminal
  NACK / expiry; retries resume, never restart.

## 15. Config

Per guide §2, each layer's config type lives next to its code:

- `FileServiceConfig` (orchestrator, `runtime/file`): `smallFileMaxBytes = 10 MB`, `maxInFlightChunks`,
  receive directory, retention quota, `maxStagedBytes`.
- `RouterConfig` additions: `fileTransferTtl = 2 days` (marker, relay reservation and transfer lifetimes),
  `chunkAckTimeout`, `fileStallTimeout` (the receiver's gap trigger), `fileRequestAttemptTimeout`,
  `fileRequestFirstAttemptDelay` (the grace period), `fileRequestWaitTimeout` (WAITING for the OFFER).
- **Storage pledge**: one user-facing `relayStoragePledgeBytes` (default 100 MB) — how much of this device's
  storage the network may use — split by the composition root into the message relay-cache budget and the
  file relay-store budget, each keeping its own eviction semantics (per-row LRU vs whole-transfer LRU). Our
  own outbox rows are **never** part of the pledge (a user's undelivered traffic is not network charity).
  Relay-profile presets (tens of GB for always-on nodes) are deferred (§18).
- **Config invariant: `fileTransferTtl < supersededSessionRetention`** (2 days < 3 days) — the ratchet-sealed
  OFFER half must stay decryptable for the whole transfer TTL. `supersededSessionRetention` is e2ee.md's
  superseded-generation retention, renamed to Duration form and bumped from 2 days (see the planned note
  there).
- Derived in wiring (`FileLimits`, chained from the transport backend configs like `MessageLimits`):
  `chunkSizeBytes ≈ 220 KiB`; each transfer snapshots the current derived value at creation (§2).
- Reused unchanged: `RelaySelectionPolicy` with `relayTargetSuccessProbability`, `minRelayScore` and the
  `maxRelays` cap (§5); the outbox's own retry/park/expiry behavior.

## 16. Invariants

1. Anything a relay pushes is whole — count-based check over the covered suffix; this is the ceiling by
   design (the relay cannot and must not verify content).
2. One relay hop, ever: deposits happen only at a transfer's authoring device (a serve is an authoring
   device too), redundancy is parallel (same covered set at every policy-selected relay), forwarding relays
   never re-deposit, the sender never chases a failing relay.
3. Relays are not REQUEST candidates; relays forget after delivery.
4. Never READY without full byte coverage and hash verification.
5. The per-file key travels only inside the OFFER's ratchet-sealed half — never in messages, never in the
   clear.
6. Deterministic ciphertext per byte range: key per `fileId`, nonce per (`fileId`, `byteOffset`,
   `chunkLength`); chunk parameters per transfer. A bare chunk index never derives a nonce.
7. Covered sets are contiguous suffixes in the transfer's own chunking; per-depot engine state is one resume
   point.
8. The marker is establishment-only; ACK = processed, and a chunk is processed only when decrypted and
   durably staged (§8).
9. Receiver state is `fileId`-scoped byte coverage — transfer- and chunking-agnostic; relay state is
   `transferId`-scoped.
10. One REQUEST candidate at a time.
11. STALLED / EXPIRED are re-requestable; DECLINED / CANCELLED / FAILED are refused — never auto-resurrected.
12. The relay verifies the original sender's signature on OFFER and CHUNK before storing anything; chunks
    without a live transfer row are DECLINED at the door, never stored.
13. Transfers are temporary (`fileTransferTtl`); files stay.
14. A transfer's chunks flow only after its OFFER was processed by the target (§3.5).
15. Any DECLINED touching a transfer cleans up that party's state for it; only the target's DECLINED is
    terminal for the send to that device.
16. Transfers are device-to-device; the orchestrator speaks files and accounts, routing fans out and signals
    per device.

## 17. Rejected alternatives

Recorded so they are not relitigated:

- **Whole file as one packet** — transport frame caps (250 KiB WebRTC, 4 MB Tor) make it impossible; chunking
  everywhere.
- **Chunk content through the Double Ratchet** — ratchet state explosion, `MAX_SKIP` bounds, chain breakage
  for interleaved messages; per-file symmetric key instead.
- **The per-file key in the message plaintext** — transfers must be self-contained; the OFFER's sealed half
  is the single key carrier, and the serve chain distributes it to any legitimate holder (§3.3).
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
- **Receiver-side chunk-parameter enforcement** — byte coverage (§8) makes mixed chunkings contribute instead
  of conflict; rejecting useful data on principle buys nothing (the final hash is the gate). Chunk parameters
  are snapshotted per transfer and never compared across transfers.
- **Index-derived chunk nonces** — with per-transfer chunkings the same index can carry different plaintext
  under one key: nonce reuse, a hard AEAD break. Range-derived nonces instead (§4).
- **One staging table for relay and receive** — one owner per table (guide §4), and ciphertext for foreign
  traffic never mixes with own plaintext; the two stores share a shape, never a table (§13).
- **The OFFER as the REQUEST's ACK** — outbox rows must leave through their own disposition, not an
  out-of-band correlation; the request is ACKed and the OFFER follows, exactly like sync (§9).
- **Unbounded receive staging** — a racing transfer can stage a whole suffix behind a stuck gap; the
  `maxStagedBytes` bound plus DEFER backpressure closes it (§8).
- **Positioned-I/O platform port in v1** — pure kotlinx.io with a single append position and staged ranges
  instead; swap in kotlinx.io positioned I/O when it ships (#241).

## 18. Deferred

Group sender keys (additive by design — also the fan-out and deposit-cost optimization for multi-device
accounts); OFFER-response carrying held-state (bandwidth optimization for duplicate relay forwards);
per-tag-kind outbox in-flight cap; relays as REQUEST candidates (rejected for v1); disk-based relay store
(only if `smallFileMaxBytes` grows); positioned I/O via kotlinx.io #241; bare transfers (a separate table, if
a GUI-only send without a message ever needs one); relay storage presets (tens of GB for always-on nodes);
GUI-API contract for the send/progress/decline surface (to be written with the GUI work).

## 19. Test matrix

- Small file, target offline: deposit → wholeness → forward on surfacing → verify → READY.
- Mid-transfer restarts: sender restart mid-push (resumes from outbox rows); relay restart mid-forward (free
  recovery); receiver restart mid-assembly (staging + prefix re-derivation — nothing ACKed is ever lost).
- Concurrent mixed-chunking transfers: two transfers with different chunk parameters interleave on one file;
  byte coverage completes; hash verifies; COMPLETE reaches both authors.
- Direct-link break mid-send: remainder deposit at the policy-selected relays; holes below the prefix are
  inside the remainder; target completes from direct prefix + relay remainder.
- Duplicate relay forwards: target drops fully-covered ranges; second relay drains on duplicate ACKs.
- Stall → immediate deposit; sender killed right after → relays hold a whole copy.
- Gap trigger: prefix stalls beyond `fileStallTimeout` → STALLED → REQUEST → serve from the lowest missing
  byte; same flow for the large-path dropped-session case.
- Staging bound: a racing transfer hits `maxStagedBytes` → DEFER backpressure → gap filled → drain → resume.
- Eviction pressure → DECLINED at admission → engine tries the next relay / re-offers later.
- Chunks for a dead, evicted or expired `transferId` at a relay → DECLINED at the door → sender cleans that
  depot.
- Target declines the relay-forwarded OFFER (no space) → relay drops the transfer, frees quota.
- Sender dies mid-deposit: partial never pushed; reservation TTL cleans; target's REQUEST recovers from a
  holder.
- Large file: no session → waits (never Tor); session opens → streams; session drops → checkpoint resume;
  restart mid-transfer → linear-skip resume.
- History sync on a fresh device: attachment renders from the message, REQUEST pulls the bytes — the serve
  OFFER carries the key; exact resume from held coverage.
- Declined / cancelled / no-space / ENOSPC paths; "file unavailable" after all candidates decline;
  ACK-but-no-OFFER → WAITING timeout → next candidate.
- Multi-device account: the non-targeted sibling REQUESTs after the grace period (normal traffic, one
  candidate at a time).
- No-chaining negative test: a forwarding relay must never deposit elsewhere.
- Unverified chunk injection at a relay (bad signature) is dropped and never counts toward wholeness; a chunk
  for an unknown or unprocessed `transferId` is never ACKed or stored (target and relay — the §3.5 negative).
- OFFER sealed-half late decrypt: the session superseded mid-hold; the retention invariant
  (`fileTransferTtl < supersededSessionRetention`) keeps it decryptable at the TTL edge.
- Same file at one relay under two transferIds: identical ciphertext, independent rows, both forward.

## 20. What exists today (disposition)

| Existing                                                              | Disposition                                        |
|-----------------------------------------------------------------------|----------------------------------------------------|
| `protocol/envelopes/FileEnvelope.kt` (+ codec test)                   | reworked per §3.2 (coverage-based ACK/REQUEST, suffix OFFER, dropped wire types); observability profile revisited |
| `protection/envelope/FileProtection.kt`, `PlaintextFileProtection.kt` | interface kept, real implementation replaces the placeholder (ratchet-sealed OFFER half, per-chunk AEAD) |
| `routing/inbound/handlers/FileInboundHandler.kt` (stub)               | real logic per §7 (relay) and §8 (target: synchronous ingest port) |
| `persistence` `file_transfers`, `file_chunk_tracker`, `spaces` (placeholders) | reworked and regrained per §13 (orchestrator: file/account grain; routing: transferId grain); `spaces` untouched by this design |
| `persistence/DatabaseEnums.kt` (`FileTransferStatus`, `FileChunkStatus`) | moved to the owning stores, with the terminal-state distinctions of §9/§14 |
| `routing/policy/RelaySelectionPolicy.kt`                              | reused unchanged (§5)                              |
| `routing/outbound/RelayDepositor.kt`, `OutboxProcessor` triggers      | the trigger policy extracted for shared use; MESSAGE-only checks generalized (§5) |
| `routing/store/PacketOutbox.kt` (`OutboxTag`)                         | new `Kind.FILE`; `OutboxOutcome` gains `packetId`  |
| `JvmWebRtcBackend` channel labels (`envelope`, `av`)                  | add the per-transfer file channel kind (§12)       |
| `wiring/MessageLimits.kt`                                             | joined by `FileLimits` (derived chunk size) and the storage-pledge split (§15) |
