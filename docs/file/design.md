# File transfer design

This is the design of record for file transfer (v1, 1:1 sends). The first draft was agreed in the design
sessions of November 2026 and revised after a full pre-implementation review and its small-file follow-up (13 November
2026), then completed by the large-file session (14 November 2026): large files are pull-only
streams (§6.6), the `fileId` is block-hashed (§2), receiver durability is checkpointed (§8) and the sender
may reference a file in place instead of copying it (§10). **Both paths are the base for implementation.**
[`../sprints.mmd`](../sprints.mmd) stays authoritative for sprint sequencing at its coarser grain and is not
modified by this design.

Related: [`../guide.md`](../guide.md) (layer model, dependency rules, the delivery model in §6),
[`../transport.md`](../transport.md) (links, channels, frame limits), [`../router.md`](../router.md) (outbox and
relay machinery this design reuses), [`../e2ee.md`](../e2ee.md) (sessions — used for the OFFER's sealed half,
deliberately **not** for chunk content, see §4). Diagrams: [`transfer paths.mmd`](transfer%20paths.mmd),
[`transfer states.mmd`](transfer%20states.mmd).

## 1. Scope and philosophy

Two transfer classes, one engine:

- **Small files** — at or below `smallFileMaxBytes` (default 10 MB, the Discord limit; the exact value lives in
  config, §15). May traverse relays with store-and-forward. Bounded size, so bounded relay storage.
- **Large files** — above the limit, no upper bound (hundreds of GB must work, though the expected use is
  files that slightly exceed the limit, in the ~100 MB range). **Pull-only and WebRTC-only**: nothing is
  pushed; each receiving device decides to fetch (automatically below its auto-download limit, otherwise on a
  user click) and REQUESTs the bytes, which a holder streams over a per-transfer data channel (§6.6). Bytes
  never go over Tor and never go to relays. The receive engine is shared with small files.

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
  an opaque AEAD blob keyed per transfer, so group fan-out (sender keys) is additive later without touching the
  chunk pipeline.

## 2. Identity: `fileId` and `transferId`

- **`fileId` = content identity, block-hashed.** The plaintext is cut into fixed **blocks** and the `fileId`
  is the SHA-256 over the file size, the block size and the list of per-block SHA-256 hashes, hex-encoded like
  the other ids — content addressing. Stable forever, referenced by the DAG message, and the key for the
  receiver's coverage state, `.part` file, final file and the permanent attachment store. Free dedup: two
  senders shipping identical bytes produce the same `fileId`, and a receiver who already holds it renders the
  second message instantly from local storage. The identity *is* the integrity check — the block list is
  checked against it, and each block against the list.
    - `fileId = SHA-256("yapyap-file-v1" ‖ u64 totalBytes ‖ u32 blockSize ‖ h₀ ‖ … ‖ hₙ₋₁)`, `hᵢ` = SHA-256 of
      block `i` (the last block may be short; an empty file has no blocks).
    - `blockSize` derives from `totalBytes` alone: start at 1 MiB and double until the file has at most 1024
      blocks. The list is therefore at most 32 KiB: 10 blocks for 10 MB, 1024 × 1 MiB for 1 GiB, about 800 ×
      128 MiB for 100 GB. The rule and the version tag are **protocol constants**, not config — changing either
      changes every `fileId`.
    - The **block list** travels in the OFFER's sealed half (§3.3); the sender keeps it in its file descriptor
      and the receiver on its receive row (§13).
    - Why blocks: verification runs as the bytes arrive (no final full pass, and a restart re-hashes at most one
      partial block); a hash mismatch costs one block, not the file; a holder checks blocks as it reads them to
      serve instead of re-hashing the whole file first (§8, §9). Small files run the same code with 1–10 blocks.
    - Hashing needs an incremental SHA-256 on `CryptoProvider` (init/update/final — a block can be 128 MiB). The
      sender hashes once at send, in the same pass as the copy into the store when it copies (§10).
- **`transferId` = routing session identity.** One push/serve attempt **between two devices**. Keys the relay
  store, the OFFER, the outbox marker tag, chunk envelope addressing, every control packet but REQUEST (§3.2)
  and the receiver's per-transfer row (§8). Relays never see or need the `fileId`.
- **Transfers are device-to-device.** A send to an account is one orchestrator-level send that routing fans
  out into one transfer per device of that account — the message model's fan-out, pushed one step further (the
  transfer state itself is device-grain and lives routing-side, §12). This strains relays more than an
  account-grained design would; that cost is accepted and will be optimized away with group keys and sends.
  Sibling devices never targeted by a transfer get the file through REQUEST (§9); that pull is normal traffic,
  not a failure.
- **A transfer is a completely separate process.** Its key and its chunk parameters are its own: the key is
  generated fresh for every transfer, and the chunk size is snapshotted at creation. Transfers of the same
  file share nothing but the plaintext they deliver — every chunking of the same `fileId` puts the same bytes
  at the same offsets, so the receiver merges them by plaintext byte coverage (§8), and resume works across
  transfers, chunkings and serving devices without any two transfers agreeing on anything.
- Two grains at the receiver: the **file** state is `fileId`-scoped (coverage, `.part`, valid across
  transfers); each **incoming transfer** is its own `transferId`-scoped row (key, chunking, author). Relay
  state is `transferId`-scoped.

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

| Kind       | Fields                                                                                                                                                           | Author / direction                                                                         |
|------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `OFFER`    | public half: `chunkSizeBytes`, `totalBytes`, `startIndex`; sealed half: `fileId`, block list (§2), per-transfer key, `fileName`, `mimeType`                      | the serving device → target device (or relay, for deposits)                                |
| `CHUNK`    | `chunkIndex`, ciphertext (covers `[chunkIndex × chunkSizeBytes, …)` — **the transfer's own chunking**)                                                           | pusher → target (replayed by relay)                                                        |
| `ACK`      | coverage **in the transfer's own chunk indices**: `contiguousChunks` + `missingRanges` (capped); on a stream only `contiguousChunks`, sent per checkpoint (§6.6) | target → that transfer's pusher                                                            |
| `COMPLETE` | — (the transfer is the header's `transferId`)                                                                                                                    | target → **every live transfer author**; also the cancel-on-complete (§8)                  |
| `REQUEST`  | sealed: `fileId`, held coverage in bytes (`contiguousBytes` + `missingRanges`)                                                                                   | requester → one candidate device                                                           |
| `CANCEL`   | reason                                                                                                                                                           | either endpoint: author → relay/target (abort); target → author (decline, no space, abort) |

Notes on the rework:

- The existing `FileChunk`/`FileControlPayload`/`FileTransferClass`/`FileTransportPreference` wire types are
  dropped. The small/large distinction is a sender-engine policy driven by the config threshold, not wire
  state; transport preference is path policy (§5), not a negotiated field. `maxInFlightChunks` is engine
  config (§15), not wire.
- `CHUNK` loses its `chunkCount` — the covered set is defined by the OFFER (§3.4).
- The existing per-chunk hash idea stays dead: AEAD tags detect corruption at decrypt; plaintext integrity is
  the block list, checked against the `fileId` (§2). Blocks are a property of the file, chunks of a transfer —
  the two never need to align.
- **Every FILE envelope but REQUEST is addressed by `transferId`; the `fileId` is payload, needed only for
  storage.** It appears in exactly two places — the OFFER's sealed half and the REQUEST — and both are
  ratchet-sealed. ACK, COMPLETE and CANCEL travel to and through relays carrying nothing a relay could tie to
  the file's content.
- **ACK speaks the transfer's own chunk indices; REQUEST speaks bytes.** The receiver knows every incoming
  transfer's chunking from its OFFER, so it maps its byte coverage (which may come from any source) onto each
  transfer's indices: a chunk counts as covered once its whole range is. The pusher reads its own vocabulary
  back. A REQUEST precedes any transfer, so it has no chunking to speak and stays in bytes; the server maps
  them onto the chunking it picks (§3.4).
- **REQUEST is direct-only**: it goes to a candidate with an open link (§9), never through a relay. A large
  REQUEST goes only over a WebRTC session, which the requester opens (§6.6).

### 3.3 The OFFER's two halves

- **Public half** — what relays need for their storage contract: `chunkSizeBytes`, `totalBytes`,
  `startIndex`. From these the relay derives the file-level chunk count (`ceil(totalBytes / chunkSizeBytes)`)
  and the covered suffix (§3.4). The `transferId` rides the envelope header.
- **Sealed half** — `fileId`, the block list, the per-transfer key, display metadata (at most ~33 KiB, within
  both transports' frame limits) — **encrypted to the target device through
  the standard Double Ratchet** (the message-path machinery; epoch-1 X3DH bootstraps first contact offline, so
  no session needs to pre-exist). Relays can never read it and never learn the `fileId`.
- **Key transport: the OFFER's sealed half is the only carrier of a transfer's key.** Messages never carry one (§3.1),
  and no key outlives its transfer. Every legitimate holder can serve, because serving needs the
  plaintext, not anyone's key: the holder's serve is a new transfer with a fresh key, sealed to the requester
  in its own OFFER — a sibling or a fresh device syncing history receives that key with the serve (§9).
- **One sealed OFFER per transfer, delivered once.** The sealed half is a ratchet message, so the same sealed
  bytes must never reach the target twice through different routes: the second decrypt would fail as a
  replay. This is why every relay deposit is its own transfer with its own sealed OFFER (§6.4).
- **Late decrypt is a config invariant, not luck.** The relay replays the sealed half verbatim up to the
  transfer TTL, so the target's ratchet state for that generation must outlive the TTL —
  `fileTransferTtl < supersededSessionRetention` (2 days < 3 days, §15; e2ee.md keeps superseded generations
  decryptable for that window). Known edge, accepted for v1: a sealed half held that long is also an
  out-of-order message within its chain, so a busy chat can push it past the skipped-key limits. The decrypt
  then fails `PERMANENT_PROTECTION_FAILED`, the relay drops that transfer, and the pull path (§9) recovers.
- Correlation, honestly: relays cannot *read* the `fileId`, but same-file transfers at one relay are *correlatable*
  through public metadata (totalBytes, chunk size, covered suffix, participants). "Not
  readable", never "not correlatable". (Ciphertext no longer confirms it: every transfer has its own key.)
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
- Large-path resume: a new serve from the requester's held coverage (a stream ends with its session, §6.6) —
  the REQUEST-serve case above.

This invariant is what keeps the OFFER to a single start index (no lists, no bitmaps) and collapses the
sending engine's per-depot bookkeeping to one number per depot: its resume point.

### 3.5 Chunks follow their OFFER

A transfer's chunks flow only after that transfer's OFFER was processed by the target. Both paths guarantee it
by construction: the direct push enqueues chunks only after the marker's ACK, the relay forwards only when
whole, its own OFFER-ACK gating its chunks, and a stream's channel opens only after its OFFER's ACK (§6.6). This is
load-bearing: a CHUNK envelope carries no `fileId`, so the
target maps `transferId → fileId` (and the key) only through a processed OFFER — its incoming-transfer row (§8). A chunk
for an unknown or unprocessed `transferId` is never ACKed and never stored — at a target or at a
relay. Because chunks cannot overtake their OFFER, an unknown `transferId` at the target is never a race: the
transfer is gone (resolved, cancelled, expired) or never existed, so the chunk is answered **DECLINED** (§3.6),
and the sender stops instead of retrying into the void for the rest of the TTL.

### 3.6 What a DECLINED means

A DECLINED is read by **what it answers**, never on its own:

- **On an OFFER from the target** — the target refused the file (no space; a large file is never offered
  unasked, so declining one is local and never on the wire, §8). This is the only refusal: the send to that device is
  terminally DECLINED, no re-offer, no deposit (invariant 15).
- **On an OFFER from a relay** — that depot is full; the engine tries the next relay or re-offers later (§6.1).
- **On a CHUNK** (target or relay) — the transfer is gone at that end. The pusher ends that transfer
  quietly (target: resolved as finished-unknown; relay: that depot is out), never as a refusal. The target
  drops its transfer rows once a transfer resolves (§8), so a sender whose COMPLETE was lost learns it this
  way — deliberately: holding rows open to answer late chunks better would be the two generals problem, and
  the pull path makes "probably done" safe.
- **On a REQUEST** — "I don't hold it, or I won't serve it" (§9): the next candidate is asked.

## 4. Crypto

- **Per-transfer symmetric key** (AEAD, ChaCha20-Poly1305), generated fresh for every transfer — direct push,
  each relay deposit, each serve. Chunk content **never** goes through the Double Ratchet: a long file would
  explode ratchet state, `MAX_SKIP` bounds out-of-order tolerance, and one lost chunk would break the chain for
  interleaved messages.
- The **OFFER's sealed half rides the standard ratchet** (§3.3) — one small frame per transfer, bootstraps
  offline, late-decrypt-safe by the retention invariant.
- **Chunk AEAD is `CryptoProvider.encryptAead` as it is**: library-managed random IV, AAD = (`transferId`,
  `chunkIndex`). The AAD pins every chunk to its transfer and position, so a relay can neither
  reorder chunks nor splice them across transfers. No nonce derivation is needed: one key never outlives one
  transfer, a random 96-bit IV is safe at any chunk count a transfer reaches, and nothing relies on
  ciphertext being reproducible — re-encrypting a chunk after a restart yields different bytes for the same
  index, which nobody compares (the relay keeps the first by its PK; the receiver tracks plaintext coverage).
- WebRTC's DTLS already encrypts the channel; the app-layer AEAD is kept regardless — negligible cost (AEAD is GB/s),
  uniform format across paths, and independence from transport-stack correctness.
- Decryption happens in routing (through protection), which holds the transfer's key in its incoming-transfer
  row; the orchestrator only ever sees plaintext and coverage (§8).
- New `FileProtection` implementation (ratchet-sealed OFFER half and REQUEST, signing, per-chunk AEAD via
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

**Large files are pull-only.** Nothing is pushed: the DAG message (which does travel over Tor and relays) tells
each receiving device the file exists, and the device fetches it through a REQUEST when it decides to (§9).
The REQUEST, the serve OFFER and the chunks all travel over a WebRTC session the requester opens; the chunks
on a per-transfer data channel (§6.6). The large path never touches the outbox and never needs a per-entry
transport mask. Pull-only settles what a pushed large OFFER would have needed: no OFFER sits processed while
a user decides, no separate accept signal exists, every device decides for itself, and the side that wants
the bytes is the side that opens the session. The cost is that a holder must be online when the receiver
fetches — which WebRTC-only already required. With the auto-download limit (§15) above a file's size, the
fetch starts as soon as the message lands, so a typical ~100 MB file still arrives like a push.

**Composed sends push immediately** (small files). Publishing a message with attachments starts the per-device file
transfers alongside the message push — the file is not waiting for anyone to ask. If an OFFER has not landed
within the grace period (`fileRequestFirstAttemptDelay`) — the sibling never targeted, an offer lost, or the
message arrived via sync rather than push — the REQUEST trigger fires (§9). Push is the latency path; REQUEST
is the backstop; the grace period is the only thing telling them apart.

## 6. The push machinery (routing layer)

The small path pushes through the outbox (§6.1–§6.5); the large path streams a serve over a dedicated data
channel (§6.6). Both read the same chunk sources and use the same OFFER and AEAD.

One mechanism, two chunk sources. The **pusher** owns: the per-device fan-out (an account-level send from the
orchestrator becomes one transfer per target device — the `sendMessage` pattern), the OFFER-as-marker
lifecycle, chunk outbox rows, the in-flight window, the stall detector, deposit-on-stall, and resume. It
speaks pure router vocabulary (packets, outbox, ACKs, links), so it lives in **routing**, parameterized by a
ciphertext source:

- **Sender's push** — source implemented by the orchestrator (reads the stored copy or the referenced
  original, §10, from any offset; checks each block against the block list as it reads, §9; AEAD-encrypts on
  the fly) through a `FileChunkSource` port the router declares.
- **Relay's forward** — source implemented by routing itself (reads the `file_relay_chunks` store). The relay
  is simply another sender whose "file" is a DB table (§7).

### 6.1 The marker

The OFFER is enqueued as a normal outbox entry with `OutboxTag.Kind.FILE`, id = `transferId`, and standard
semantics — the delivery contract's "ACK = processed" is untouched:

- ACK (the offer was accepted) → marker removed; **the engine owns everything after**.
- Terminal NACK **DECLINED, split by source** (§3.6): relay-declined (storage full) → that depot is out; the
  engine tries the next relay or re-offers later. Target-declined (no space) → the send to that device is **terminally
  DECLINED** — no re-offer, no deposit; a refused file is never
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
FILE packets that participate in the deposit triggers (§5); control packets do not. A DECLINED on a chunk ends
that transfer quietly and drops its remaining rows (§3.6).

### 6.3 Stall detection

No chunk ACK within `chunkAckTimeout` (30–60 s) → the transfer is flagged stalled → small path: deposit the
remainder to relays at once (§6.4). (A stream has no stall detector of its own: it is bounded by its session and
the receiver's gap trigger, §6.6.)

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
- **Parallel redundancy, never sequential.** The same covered set goes to every relay `RelaySelectionPolicy`
  returns (§5) — **each deposit its own transfer**: its own `transferId`, its own key, its own separately
  sealed OFFER. Two relays forwarding one shared sealed OFFER would deliver the same ratchet ciphertext twice,
  and the second would fail as a replay (§3.3). The "at most once" above counts the deposit *round*, not the
  relays in it. If one relay's deposit stalls, that is the outbox's business — per-chunk
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

### 6.6 The stream (large path)

A large transfer is always a serve (§5): one holder streams to one requesting device over one WebRTC session.

1. **The requester opens the session.** Its request processor (§9) asks the WebRTC transport for a session
   with the candidate (signaled over Tor, as today). Sends never opened connections; a large fetch does,
   because the requesting device is the one with the user's intent. No session within
   `fileSessionOpenTimeout` → that candidate is unreachable and the next is asked.
2. **REQUEST → ACK → OFFER over the session's envelope channel.** The REQUEST is sealed and carries the held
   coverage (§9); the holder runs the serve gate, ACKs, and replies with the serve OFFER (fresh `transferId`,
   fresh key, the block list). The requester processes it — creating its incoming-transfer row — and ACKs.
3. **The holder opens a data channel labeled for the transfer** on the same peer connection (no
   renegotiation, `BinaryEnvelope` framing) and streams CHUNK envelopes in order from the floor-aligned start (§3.4). A
   dedicated channel keeps a 100 GB stream from starving messages and ACKs on the envelope channel.

**Not the outbox, not per-chunk ACKs.** On an ordered, fully reliable channel, SCTP already delivers every
chunk once and in order for as long as the session lives; per-chunk router ACKs and outbox rows (about 475k for
100 GB) would be pure overhead. The stream's chunks bypass the router's per-envelope ACK, dedup and retry, and
its control packets (ACK, COMPLETE, CANCEL) travel on the same channel.

**Checkpoint ACKs and a credit window.** The receiver ACKs at each checkpoint (§8), after the fsync and the P
commit, with `contiguousChunks` only. The holder may run at most `fileStreamWindowBytes` (e.g. 32 MB) past the
last ACK, and also honors the channel's `bufferedAmount` low-threshold. Both are needed: the channel's buffer
only protects the sender, and `JvmWebRtcBackend` receives through an `onMessage` callback with no
receive-side flow control — without the window, a receiver with a slow disk would pile chunks up in memory.
The window is the stream's backpressure, the counterpart of the small path's `maxInFlightChunks`.

**No staging.** One ordered source lands every chunk at P (the few bytes below P at the start are dropped), so
a stream never stages. A device runs one fetch per file at a time (one candidate at a time, §9).

**A stream lives exactly as long as its session.** When the session drops, both ends end the transfer — the
holder's serve row and the requester's incoming-transfer row are dropped — and the request goes back to
PENDING with the held coverage (§9). The next serve is a new transfer with a new key, starting where the
checkpointed P left off; a restart on either side is the same case. There is no waiting-for-session state and
no resume inside a transfer. A stream that stalls while its session lives is ended by the receiver's gap trigger
(`fileStallTimeout`, §8) with a CANCEL, and re-requested the same way.

**Completion**: the last block verifies → READY → COMPLETE on the channel → the holder closes it. The small
path's timers do not apply: there is no grace period (nothing is pushed), no `chunkAckTimeout` and no deposit.

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

- **Two grains.** Each processed OFFER creates an **incoming-transfer row** (routing, `transferId`-grain:
  `fileId`, author device, chunking, covered suffix, key, expiry) — the transfer is a separate process that
  happens to write into the same file. The message or the first OFFER creates the one **receive row**
  (orchestrator, `fileId`-grain: coverage, `.part`, status). Routing decrypts each chunk with its own
  transfer's key and hands `(fileId, byteOffset, plaintext)` to the receive engine through the synchronous
  ingest port; the receive row holds no key, and the receive engine is transfer-agnostic and
  **chunking-agnostic**.
- **Incoming-transfer rows end with their transfer** — COMPLETE sent, CANCEL either way, or expiry — and are
  dropped then. A later chunk for that `transferId` is unknown and gets DECLINED (§3.5, §3.6); the row is not
  kept around to answer it better.
- **Byte coverage is the whole model.** A chunk covers `[chunkIndex × chunkSizeBytes, …)` in its transfer's
  chunking — and the bytes at any offset are identical across every transfer of the `fileId` (the `fileId` is
  the hash of the whole file). Chunks from any number of transfers, with any chunkings, land their byte
  ranges; fully-covered ranges are duplicates and are dropped — duplicates are normal traffic (multiple relay
  deposits, the direct path, serves all overlap). **Nothing is rejected for disagreeing with another
  transfer** — every transfer is a potential accelerator; two chunkings of the same file can finish it
  together.
- **Durable-before-ACK, checkpointed.** The durable state is one contiguous prefix P — bytes `[0, P)` in the
  `.part`, append-only — plus staged ranges beyond it. **The persisted `contiguous_bytes` is the source of
  truth for P, never the file length.** P advances only at **checkpoints** (every `fileCheckpointBytes`, e.g.
  8 MB, every `fileCheckpointInterval`, e.g. 2 s, and at completion): append what is drainable, fsync the
  `.part` once, then advance `contiguous_bytes` and delete the drained staged rows in one DB transaction. One
  fsync per checkpoint instead of one per chunk — at 50 MB/s that is the difference between ~230 fsyncs a
  second and one. The same rule runs for both classes.
    - **Small path (outbox chunks):** a chunk that extends coverage is decrypted and written to staging in one
      DB transaction, and only then ACKed: the chunk ACK means "decrypted and durably recorded", which is the
      delivery contract's ACK = processed with "processed" defined per payload kind (a sync request is processed
      when its reply is sent; a chunk when it is staged). The handoff from routing to the receive engine is a
      synchronous port call for exactly this reason. The drain into the `.part` waits for the checkpoint.
    - **Stream (large path):** chunks arrive in order and append at P without staging; nothing is ACKed between
      checkpoints, and the checkpoint ACK covers everything up to the new P (§6.6). A crash loses at most one
      checkpoint of bytes, which the next serve re-sends.
- **Staging is bounded** (`maxStagedBytes`, at least one checkpoint). Beyond the bound, a chunk that cannot
  extend P is answered DEFER — silent, dedup-cleared; the sender's window closes and its retry resumes once P
  advances. Without this, a racing transfer could stage an unbounded suffix behind a stuck gap.
- **Blocks are verified as P passes them.** The first OFFER's block list is checked against the `fileId` (§2)
  and stored on the receive row; an OFFER whose list does not hash to the `fileId` is CANCELled and its
  author's copy treated as bad. The engine keeps a running SHA-256 of the block containing P, and **P is never
  committed past the end of an unverified block**: every block below P has matched its hash. After a restart
  only the partial block at P is re-hashed from the `.part`.
- **Reserve** `<fileId>.part` at `totalBytes` with an **explicit free-space check** (preallocation is sparse
  on most filesystems — stat the volume, not the file). Insufficient space → a DECLINED/CANCEL to the author
  and a clean failure; mid-write ENOSPC → CANCEL, clean failure.
- **Accepting is local.** Small files auto-accept. A large file is fetched only when the device decides to —
  automatically at or below its `autoDownloadMaxBytes` (§15), otherwise on a user click — so declining a large
  file means never REQUESTing it: the receive row goes DECLINED locally and nothing goes on the wire. Each
  device decides on its own (no to 100 GB on the phone, yes on the PC); a user re-open fetches it later.
- **Completeness → READY.** Coverage of `[0, totalBytes)` with the last block verified → **atomic rename →
  status READY**. No final pass: every block was verified on the way. Never READY with an unverified block. On
  READY the receiver sends **COMPLETE to every live transfer author** (from its incoming-transfer rows); each
  ends its transfer and cancels its deposits — COMPLETE is the cancel-on-complete. A transfer arriving for an
  already-READY file is answered COMPLETE at its OFFER.
- **A hash mismatch costs one block, not the file.** The bad block is always the one ending at P's next
  commit, so the append-only writer handles it: the `.part` is truncated to the block's start, the block's
  staged rows are dropped, the live transfers are CANCELled (merged coverage cannot say which one sent the bad
  bytes), and the file goes back to re-requestable (STALLED) with a mismatch count. The next REQUEST, with
  coverage ending at the bad block, asks the same device first (a holder checks blocks as it reads, §9, so a
  rotten copy stops at the source), then the candidate order continues. After `fileHashRetryLimit` mismatches
  the file is FAILED.
- **Progress ACKs** (coverage in the transfer's own chunk indices, capped, §3.2) flow back to each transfer's
  pusher.
- **The gap trigger.** If P does not advance for `fileStallTimeout` (minutes — deliberately beyond the
  sender's `chunkAckTimeout` so the push machinery acts first), the incoming transfers are CANCELled and the
  file goes STALLED — re-requestable, not refused — the durable coverage is kept, and the REQUEST path re-opens
  with held coverage (§9). On a stream it ends a transfer that stalls while its session lives (§6.6). TTL
  expiry is the passive backstop; the trigger is the active path.
- **Restart.** P is the persisted `contiguous_bytes`; at open the `.part` is truncated to it (a torn or
  uncommitted append is cut away, and the staged rows it came from are still there to drain again), and the
  partial block at P is re-hashed. Staged ranges rehydrate from their table; missing is the complement of
  durable coverage. What was ACKed was durable, so nothing ACKed is ever lost.

## 9. The pull path: REQUEST

The recovery mechanism for everything the push path failed to deliver, and the only way history-acquired
files get their bytes.

- **Trigger** (orchestrator inbound pipeline, on message ingest — the message may have arrived by push or by
  sync): a message references a `fileId` with no completed local file and no progressing transfer. Guards:
    - **large files: the device's decision** — at or below `autoDownloadMaxBytes` the trigger fires at once (nothing is
      pushed, so there is nothing to wait for); above it, only a user click fires it (§8);
    - small files: a grace period before the first attempt (`fileRequestFirstAttemptDelay`) — the OFFER may be in
      flight, or
    may never come (the sibling case, §2); the grace period is the only thing telling "late" and "never"
    apart, and it errs on the quiet side;
    - **STALLED / EXPIRED are re-requestable** (something broke; try again — a hash mismatch below the retry
      limit lands here too, §8); **DECLINED / CANCELLED / FAILED are refused** — never auto-resurrect a file the
      user declined or that exhausted its retries; those need a user re-open;
  - dedup by `fileId` (message duplicates create one row).
- **One candidate at a time, never parallel** — parallel asks would make N devices each serve the entire
  file; that is the spam/DDoS shape. Candidates: the sender's devices, then own-account siblings (room
  members later), link-availability filtered like sync.
- **The attempt is sync-shaped: the REQUEST is ACKed ("I hold it, serving") or NACKed DECLINED ("I don't, or
  I won't") — and the OFFER is the reply**, exactly like a sync request's ACK-then-reply. On ACK the request
  waits (WAITING) with a timeout for the OFFER; on timeout or DECLINED the next candidate is asked at once.
  All candidates declined → "file unavailable" (terminal; a new sync or a user re-open retries).
- **Large requests open their own session** (§6.6) and are sent only over it. A candidate that cannot be
  reached is not a decline: when every remaining candidate is merely unreachable, the request stays PENDING and
  retries when one of them comes online — for WebRTC-only bytes, offline holders are the normal case. A
  stream that ends with its session sends the request back to PENDING, asking the same candidate first.
- The REQUEST is **ratchet-sealed** (it carries the `fileId`) and **direct-only** (§3.2). It carries **held
  coverage** in bytes (`contiguousBytes` + missing ranges, capped; heavy fragmentation degrades to
  contiguous-only — a coarser serve with more duplicates, still correct).
- **The serve gate — the same logic as sync's.** A holder serves only when the requesting account may see a
  message that references the `fileId`: the orchestrator's `message_attachments` index (filled at DAG ingest)
  maps the `fileId` to its messages, and the sync serve gate decides visibility of those messages. No visible
  referencing message → DECLINED. Without the gate, anyone who has or guesses a file's content could pull it
  from any holder by its hash.
- **Holders check blocks as they read.** AEAD only protects bytes in transit: a copy that rotted on disk, or a
  referenced original the user edited (§10), would be encrypted faithfully and fail only at the requester. So
  the chunk source hashes each block it reads and compares it with the block list before the block's chunks go
  out (a serve that starts mid-block reads from that block's start) — no full re-hash before a serve, which at
  100 GB would take minutes every time. A mismatch or a missing
  file ends the transfer with a CANCEL; the holder treats the file as not held (a reference becomes
  SOURCE_LOST, §10) and DECLINEs later REQUESTs for it.
- The serving device (any holder: original sender, sibling that completed) re-runs the push engine with a **fresh
  `transferId` and a fresh key**, covering the suffix from the floor-aligned boundary at or below the
  requester's lowest missing byte (§3.4). Mixed chunkings are correct — the receiver is param-agnostic.
- **One live transfer per (`fileId`, target device) at a serving device.** A REQUEST that reaches a device
  already pushing that file to the requester (a late OFFER after the grace period, a stalled push) supersedes
  it: the old transfer is CANCELled and the serve starts fresh from the held coverage. Never two parallel
  transfers of one file from one device to one device.
- The candidate-asking loop is **extracted as a shared core with `SyncRetryProcessor`** (the needs now
  coincide; on success the file request hands off to the transfer engine instead of consuming a reply payload
  — a callback seam, not a structural fork).
- Relays are **not** candidates: they are transient push caches with forget-after-delivery semantics.

## 10. Storage and retention

- **Sender/holder: the GUI picks, the core does the rest.** The GUI owns only the platform file chooser and a **"keep a
  copy" checkbox**, checked by default at or below `copyIntoStoreMaxBytes` (default
  `smallFileMaxBytes`) and unchecked above it. The orchestrator's send API takes the choice:
    - **Copy** — the file is copied into **app-internal storage** before anything is sent, hashed in the same
      pass. The chunk source is immutable across restarts, and a picture survives the user clearing their
      downloads folder. A few MB of extra space is worth that for memes and photos.
    - **Reference** — the descriptor records the original's path (on Android a persistable URI permission) and
      the file is hashed in place. Nothing is duplicated, which matters at 100 GB. The store does not own it: it
      is outside the quota and never deleted by retention. If the original is moved, deleted or edited, the
      block check while serving catches it (§9) and the descriptor goes SOURCE_LOST: this device stops serving
      it, the message still shows the attachment, and other holders may still serve it.

  Either way the orchestrator owns the store's directory layout, retention and quota, **vending paths and
  metadata to the GUI** for rendering; the GUI never writes into the store. Hashing 100 GB takes minutes; the
  message is published once the `fileId` is known, and the GUI shows the attachment as preparing until then. **Keep
  forever** is the default policy — serverlessness
  forces it (the bytes are on a device or nowhere; there is no lazy-loading when a user scrolls back a year).
  Knob: per-account quota with LRU above it.
- **Receiver**: the same permanent store once READY; `.part` until verified.
- **Relay**: transient ciphertext only — pledged-storage-capped (§15), TTL'd, forget-after-delivery.
- **Transfers are temporary; files stay** (§1). v1 has no bare transfers — every transfer belongs to a
  message-composed send; a GUI-only send without a message, if it ever exists, gets its own table (§18).

## 11. File I/O: kotlinx.io plus one positioned read

kotlinx.io is the house standard already (`ConfigStore`, `JvmAppLogger`, `KmpTorBackend`, `DataDirLock`,
`Platform`), and v1 uses it everywhere but one place — positioned I/O is on kotlinx.io's roadmap (files-API
redesign, kotlinx-io issue #241) but not shipped, so the design needs it only for reads:

- **The receiver is a single append-only writer position** (P, §8): staged ranges absorb arbitrary arrival
  order and mixed chunkings, so the writer never seeks — small and large alike. A staged range's tail beyond P is
  sliced in memory at drain; block verification hashes the bytes as they are appended.
- **The sender reads at an offset — the one exception to pure kotlinx.io.** Every large serve starts at the
  requester's P (§6.6), and every session drop starts a new serve, so skipping by reading through the prefix
  would re-read up to the whole file each time. The I/O module therefore has a **positioned read**
  (`expect`/`actual` over `FileChannel` on JVM and Android) behind its chunk-shaped boundary; reads stay linear
  from the start offset.
- A torn append (crash mid-drain) is truncated at open to the persisted P (§8) — the DB is the checkpoint,
  the file length never is.
- The chunk-shaped I/O boundary (`readChunk`/`writeChunk`-style module) still exists as a module, with a
  kotlinx.io implementation inside apart from the positioned read — so when #241 ships positioned I/O, the
  swap is internal, not a design change.

## 12. Layering and data ownership

**Two grains, one seam:**

- **Routing owns the `transferId` grain** — device vocabulary: the per-device fan-out, per-transfer chunk
  parameters, per-depot resume points (the pusher's state, restart-recoverable from its rows plus the
  outbox), the incoming-transfer rows and chunk decryption, sealed-half protection and every transfer key, **all
  transfer signaling** — OFFER, CHUNK, ACK, COMPLETE and CANCEL travel between the two devices that
  participate in the transfer — the stream (§6.6), the relay store, and the request attempts including
  opening a WebRTC session for a large request.
- **The orchestrator owns the `fileId`/account grain** — file vocabulary: send rows per (file, peer account),
  receive state per `fileId` (coverage, checkpoints, block verification), serve rows per (file, requester
  account), the serve gate, the accept/decline verdicts (including the large-file auto-download decision),
  the store's copies and references, and the REQUEST trigger. It never holds a key. It sees account-level events through
  the `FileCarrier` port and issues
  account-level ops; source devices may appear in events as *facts* (like "re-verify messages from a
  device"), never as *addresses* — replies are routing-owned signaling.

The precedent is the message path: the router fans out per device and the orchestrator never addresses one;
files push the same line one step further (transfer state itself is device-grain, so it lives routing-side).
A headless relay node's orchestrator (file service, REQUEST logic, GUI progress) is completely uninvolved in
forwarding. Serve-on-REQUEST is the one relay-node case that *is* orchestrator business (we hold the file →
own traffic → our row, account-grain, pushed through the same machinery).

### Module layout

| Layer              | Owns                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `protocol`         | `FileEnvelope` rework (§3.2: `transferId`-addressed control packets, chunk-index ACK, sealed byte-coverage REQUEST, the block list in the sealed OFFER half), `Text.attachments` (§3.1), the `fileId` construction and block-size rule (§2), codecs + round-trip tests, observability profile update                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `crypto`           | incremental SHA-256 on `CryptoProvider` (init/update/final, §2)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `protection`       | real `FileProtection` (§4): ratchet-sealed OFFER half and REQUEST, per-transfer chunk AEAD                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `routing`          | the pusher (`file/push`): per-device fan-out + per-transfer state; the stream (`file/stream`, §6.6): serve over a per-transfer channel, credit window, checkpoint ACKs; the relay store and forwarder (`file/relay`); the incoming-transfer rows and chunk decryption; real `FileInboundHandler` (foreign target → admission/park; local → decrypt, synchronous ingest port + emit); `FileCarrier` port on `Router` + account-level file events; the `FileChunkSource` port (orchestrator implements the disk source, routing implements the store source); the file request processor on the shared candidate core (opens a WebRTC session for a large request); `OutboxTag.Kind.FILE`; `packetId` on `OutboxOutcome`; the extracted deposit-trigger policy shared with messages |
| `orchestrator`     | `runtime/file` — `FileTransferService`: send API (account-level; copy into the store or reference in place, §10), block hashing, classification, descriptor creation, the receive engine (coverage, staging, checkpoints, block verification, ready), the auto-download decision, the REQUEST trigger + coordinator, serve-on-REQUEST with the serve gate (account-level); stores: `file_descriptors`, `file_transfers`, `file_receives`, `file_receive_chunks`, `pending_file_requests`; the DAG gains `message_attachments`; the transfer enums move out of `persistence/DatabaseEnums.kt` to the owning stores                                                                                                                                                                 |
| `transport/webrtc` | a file channel kind + per-transfer channel labels (the backend already multiplexes by label: envelope and AV channels); `bufferedAmount` low-threshold exposure; opening a session on request (sends never did)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| file I/O module    | chunk-shaped read/write boundary over kotlinx.io, plus the positioned read (`expect`/`actual`, §11)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `wiring`           | `FileLimits` (derived chunk size, chained like `MessageLimits`), config wiring incl. the storage-pledge split                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |

The stream (§6.6) needs from the session machinery only "open a session to this device" and "this session
ended"; the planned shared session machinery ("offer retry queues for voice and file", sprints s6a) can serve
both AV and file requests without a file-specific session contract — a large file has no accept/reject
session state, because accepting is the requester's local decision (§8).

## 13. Database sketch

Final DDL lands with implementation; every schema change ships with a migration (guide §4).

```
-- orchestrator (own traffic — file/account grain; transfers never model message linkage, files do)
message_attachments (         -- DAG-owned, filled at ingest: the serve gate's fileId → message index
  message_id TEXT, file_id TEXT, PRIMARY KEY (message_id, file_id)   -- + index on file_id
)
file_descriptors (            -- the permanent store index: what we hold (no key: keys are per transfer)
  file_id TEXT PK, file_name TEXT, mime_type TEXT,
  total_bytes INT, block_size INT, block_hashes BLOB,   -- the block list (§2), sent in every OFFER
  storage TEXT,               -- COPY (in the store) | REFERENCE (the original in place, §10)
  path TEXT,                  -- store path, or the original's path / URI
  status TEXT,                -- HELD | SOURCE_LOST (a reference moved, deleted or edited)
  created_at …
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
  status TEXT,                -- PENDING | IN_FLIGHT | STALLED | READY | FAILED | DECLINED
  total_bytes INT, contiguous_bytes INT,                 -- P: the coverage checkpoint, truth over the .part;
                                                          -- every block below P is verified
  block_size INT NULL, block_hashes BLOB NULL,           -- from the first OFFER, checked against file_id
  hash_mismatches INT,                                   -- bounded by fileHashRetryLimit (§8)
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
  kind TEXT,                  -- DIRECT | DEPOSIT | SERVE | STREAM (a large serve, session-bound, §6.6)
  key BLOB,                   -- this transfer's own key
  chunk_size_bytes INT, start_index INT, …               -- the covered suffix; resume derives
  status …, created_at, expires_at
)
file_incoming_transfers (     -- the receiver's per-transfer rows: chunk mapping, decryption, COMPLETE targets
  transfer_id TEXT PK, file_id TEXT, author_device TEXT,
  key BLOB, chunk_size_bytes INT, total_bytes INT, start_index INT,
  status TEXT,                -- ACTIVE | ENDED (dropped on end: COMPLETE sent, CANCEL, expiry)
  created_at, expires_at
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
  the target; deposits cancelled; also a target's DECLINED on a chunk — the transfer is gone there, §3.6) /
  EXPIRED (`fileTransferTtl`) / **DECLINED (the target declined the OFFER — terminal for this device)** /
  CANCELLED (user cancel, or superseded by a serve to the same device, §9).
- **Stream (large serve, routing)**: OFFERED (REQUEST ACKed, serve OFFER sent) → STREAMING (OFFER ACKed,
  channel open) → COMPLETED (COMPLETE) / ENDED (session dropped, CANCEL from the receiver's gap trigger, or a
  block mismatch at the source). Never waits for a session: an ended stream's row is dropped, and the next
  REQUEST starts a new one.
- **Incoming transfer (`transferId`-scoped, routing)**: ACTIVE (OFFER processed) → dropped on COMPLETE sent,
  CANCEL either way, expiry, or (a stream) the end of its session.
- **Receiver (`fileId`-scoped)**: PENDING → IN_FLIGHT (`.part` reserved) → READY (coverage complete, last
  block verified, renamed, COMPLETE to live authors); IN_FLIGHT → STALLED on a block mismatch below
  `fileHashRetryLimit` (`.part` truncated to the block's start), FAILED at the limit; IN_FLIGHT → FAILED (ENOSPC);
  PENDING → DECLINED (no space, or a large file this device did not fetch — local, re-openable by
  the user); IN_FLIGHT → **STALLED** (no prefix progress within `fileStallTimeout` → REQUEST re-opens);
  STALLED → IN_FLIGHT when a serve resumes delivery; IN_FLIGHT that never progresses expires → the REQUEST
  trigger re-opens.
- **Relay transfer**: RESERVED (admitted) → WHOLE (covered suffix complete) → FORWARDING (offer ACKed) →
  DRAINED; EXPIRED (reservation TTL or transfer TTL) from RESERVED/WHOLE; **dropped when the target DECLINED
  the forwarded OFFER**.
- **Request**: PENDING → ASKING (one candidate; a large request first opens a session) → **WAITING (ACK;
  offer pending, timeout → next candidate)** → SATISFIED (OFFER received; the serve transfer takes over) /
  UNAVAILABLE (every candidate declined or expired). A large request whose remaining candidates are only
  unreachable stays PENDING until one comes online; SATISFIED → PENDING when a stream ends with its session.
- **Marker** (outbox row): unchanged from the message contract — QUEUED → dispatched → gone on ACK / terminal
  NACK / expiry; retries resume, never restart.

## 15. Config

Per guide §2, each layer's config type lives next to its code:

- `FileServiceConfig` (orchestrator, `runtime/file`): `smallFileMaxBytes = 10 MB`, `maxInFlightChunks`,
  receive directory, retention quota, `maxStagedBytes`, `fileHashRetryLimit` (e.g. 3, §8);
  `autoDownloadMaxBytes` (per device — large files at or below it are fetched without asking; e.g. 250 MB on
  desktop, lower on mobile, §8); `copyIntoStoreMaxBytes` (the "keep a copy" checkbox default, default
  `smallFileMaxBytes`, §10); `fileCheckpointBytes` (e.g. 8 MB) and `fileCheckpointInterval` (e.g. 2 s, §8).
- `RouterConfig` additions: `fileTransferTtl = 2 days` (marker, relay reservation and transfer lifetimes),
  `chunkAckTimeout`, `fileStallTimeout` (the receiver's gap trigger), `fileRequestAttemptTimeout`,
  `fileRequestFirstAttemptDelay` (the grace period, small files), `fileRequestWaitTimeout` (WAITING for the
  OFFER), `fileSessionOpenTimeout` (a large request opening its session) and `fileStreamWindowBytes` (e.g.
  32 MB — how far a stream may run past the last checkpoint ACK, §6.6).
- **Storage pledge**: one user-facing `relayStoragePledgeBytes` (default 100 MB) — how much of this device's
  storage the network may use — split by the composition root into the message relay-cache budget and the
  file relay-store budget, each keeping its own eviction semantics (per-row LRU vs whole-transfer LRU). Our
  own outbox rows are **never** part of the pledge (a user's undelivered traffic is not network charity).
  Relay-profile presets (tens of GB for always-on nodes) are deferred (§18).
- **Config invariant: `fileTransferTtl < supersededSessionRetention`** (2 days < 3 days) — the ratchet-sealed
  OFFER half must stay decryptable for the whole transfer TTL. `supersededSessionRetention` is e2ee.md's
  superseded-generation retention, renamed to Duration form and bumped from 2 days (see the planned note
  there). Skipped-key limits within a chain are not covered by it — an accepted edge the pull path recovers (§3.3).
- Derived in wiring (`FileLimits`, chained from the transport backend configs like `MessageLimits`):
  `chunkSizeBytes ≈ 220 KiB`; each transfer snapshots the current derived value at creation (§2).
- **Not config**: the block-size rule and the `fileId` version tag (§2) are protocol constants — every device
  must compute the same `fileId` for the same bytes.
- Startup check: `maxStagedBytes ≥ fileCheckpointBytes` (staging must hold a checkpoint's worth).
- Reused unchanged: `RelaySelectionPolicy` with `relayTargetSuccessProbability`, `minRelayScore` and the
  `maxRelays` cap (§5); the outbox's own retry/park/expiry behavior.

## 16. Invariants

1. Anything a relay pushes is whole — count-based check over the covered suffix; this is the ceiling by
   design (the relay cannot and must not verify content).
2. One relay hop, ever: deposits happen only at a transfer's authoring device (a serve is an authoring
   device too), redundancy is parallel (same covered set at every policy-selected relay), forwarding relays
   never re-deposit, the sender never chases a failing relay.
3. Relays are not REQUEST candidates; relays forget after delivery.
4. Never READY without full byte coverage and every block verified; P is never committed past an unverified
   block. A mismatch is a failed attempt costing one block, retried a bounded number of times.
5. A transfer's key travels only inside its OFFER's ratchet-sealed half — never in messages, never in the
   clear — and lives no longer than its transfer.
6. Key and chunk parameters are per transfer; chunk AEAD binds (`transferId`, `chunkIndex`) as AAD. Nothing
   relies on ciphertext being reproducible.
7. Covered sets are contiguous suffixes in the transfer's own chunking; per-depot engine state is one resume
   point.
8. The marker is establishment-only; ACK = processed: an outbox chunk is processed when decrypted and durably
   staged, a stream's checkpoint ACK only after the fsync and the P commit (§8).
9. Receiver file state is `fileId`-scoped byte coverage — transfer- and chunking-agnostic; each incoming
   transfer is its own `transferId`-scoped row; relay state is `transferId`-scoped.
10. One REQUEST candidate at a time.
11. STALLED / EXPIRED are re-requestable; DECLINED / CANCELLED / FAILED are refused — never auto-resurrected.
12. The relay verifies the original sender's signature on OFFER and CHUNK before storing anything; chunks
    without a live transfer row are DECLINED at the door, never stored.
13. Transfers are temporary (`fileTransferTtl`); files stay.
14. A transfer's chunks flow only after its OFFER was processed by the target (§3.5).
15. Any DECLINED touching a transfer cleans up that party's state for it; only the target's DECLINED **on an
    OFFER** is terminal for the send to that device — a DECLINED on a chunk ends the transfer quietly (§3.6).
16. Transfers are device-to-device; the orchestrator speaks files and accounts, routing fans out and signals
    per device.
17. The `fileId` travels only in the OFFER's sealed half and the sealed, direct-only REQUEST; every other FILE
    envelope is addressed by `transferId`.
18. Every serve passes the serve gate: the requester's account must see a message referencing the `fileId`.
19. One sealed OFFER per transfer, and one transfer per relay deposit; at a serving device, one live transfer
    per (`fileId`, target device).
20. Large files are pull-only: no large OFFER is sent unasked; the receiving device decides to fetch, and
    declining is local.
21. A stream lives within one WebRTC session; it never waits for a session and never goes over Tor or a relay.
22. The `fileId` is computed by one protocol-fixed rule (§2); a block list is used only after it hashes to the
    `fileId`, and a holder sends no block it has not just checked against the list.

## 17. Rejected alternatives

Recorded so they are not relitigated:

- **Whole file as one packet** — transport frame caps (250 KiB WebRTC, 4 MB Tor) make it impossible; chunking
  everywhere.
- **Chunk content through the Double Ratchet** — ratchet state explosion, `MAX_SKIP` bounds, chain breakage
  for interleaved messages; a per-transfer symmetric key instead.
- **A key in the message plaintext** — transfers must be self-contained; the OFFER's sealed half is the
  single key carrier (§3.3).
- **One key per file with deterministic per-range ciphertext** — the original design: it needed range-derived
  nonces to avoid nonce reuse across chunkings, keys stored with the file and re-sealed on every serve, and
  broke outright when two senders of the same file generated two keys. Cross-holder ciphertext identity
  bought nothing the receiver's plaintext coverage doesn't already give; per-transfer keys instead (§4).
- **`fileId`-keyed control packets** — ACKs to a forwarding relay would hand it the plaintext hash; control
  packets are addressed by `transferId` (§3.2).
- **Keeping incoming-transfer rows to answer late chunks** — any retention window can be outlived by one more
  late packet (the two generals); rows die with their transfer and a late chunk ends the transfer quietly (§3.6).
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
- **Plaintext streaming over DTLS without app-layer AEAD** — kept for uniformity with the relay path and
  transport independence.
- **Receiver-side chunk-parameter enforcement** — byte coverage (§8) makes mixed chunkings contribute instead
  of conflict; rejecting useful data on principle buys nothing (the block hashes are the gate). Chunk parameters
  are snapshotted per transfer and never compared across transfers.
- **One staging table for relay and receive** — one owner per table (guide §4), and ciphertext for foreign
  traffic never mixes with own plaintext; the two stores share a shape, never a table (§13).
- **The OFFER as the REQUEST's ACK** — outbox rows must leave through their own disposition, not an
  out-of-band correlation; the request is ACKed and the OFFER follows, exactly like sync (§9).
- **Unbounded receive staging** — a racing transfer can stage a whole suffix behind a stuck gap; the
  `maxStagedBytes` bound plus DEFER backpressure closes it (§8).
- **Positioned writes in v1** — the receiver keeps a single append position and staged ranges. Only reads are
  positioned (§11), because a large serve starts at an offset after every session drop; swap in kotlinx.io
  positioned I/O when it ships (#241).
- **Pushed large OFFERs with an accept prompt** — the original large-path outline: an OFFER held processed
  while the user decides, a separate accept signal, a rule for which devices prompt, a sender waiting for a
  session, and timers that must not fire meanwhile. Pull-only removes all of it (§5, §6.6).
- **Per-chunk ACKs and outbox rows on the stream** — about 475k rows for 100 GB on a channel that already
  delivers in order; checkpoint ACKs with a credit window instead (§6.6).
- **Relying on the channel's flow control alone** — `bufferedAmount` protects only the sender; the receive
  callback has no backpressure, so a slow disk would buffer in memory. The credit window bounds it (§6.6).
- **A stream that waits for its session** — resume inside a transfer would need a held key, a checkpoint at
  both ends and a waiting state; a new serve from the held coverage is the same result with no new state.
- **A plain whole-file SHA-256 `fileId`** — a mismatch throws away the whole file, verification is one more
  full pass at the end and cannot resume after a restart, and every serve needs a full re-hash first. The
  block-hashed `fileId` fixes all three (§2).
- **A Merkle tree with per-chunk proofs** — finer than needed: blocks capped at 1024 per file keep the list at
  32 KiB, small enough to send whole in every OFFER.
- **One fsync per chunk** — ~230 a second at 50 MB/s; checkpoints batch them (§8).
- **Always copying into the store** — doubles 100 GB on disk; the user picks copy or reference (§10).

## 18. Deferred

**After v1** (the large-file path itself is no longer deferred — §6.6):

- Fetching one file from several holders in parallel (swarming) — the receiver already merges any coverage,
  but one-candidate-at-a-time (§9) would need a split-by-range rule first.
- A receiver-side "save to" location — receiving a 100 GB file into the store and then exporting it doubles
  it; the receiver could write the final file straight to a user-chosen location, a reference like the
  sender's (§10).
- Raising `smallFileMaxBytes` once the dedicated relays (~10 GB) exist, so ~100 MB files also get
  store-and-forward.
- An auto-download limit per network type (metered vs. not).

Also deferred: group sender keys (additive by design — also the fan-out and deposit-cost optimization for multi-device
accounts); OFFER-response carrying held-state (bandwidth optimization for duplicate relay forwards);
per-tag-kind outbox in-flight cap; relays as REQUEST candidates (rejected for v1); disk-based relay store (only if
`smallFileMaxBytes` grows); replacing the positioned read with kotlinx.io #241; bare transfers (a separate table, if
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
  byte.
- Staging bound: a racing transfer hits `maxStagedBytes` → DEFER backpressure → gap filled → drain → resume.
- Eviction pressure → DECLINED at admission → engine tries the next relay / re-offers later.
- Chunks for a dead, evicted or expired `transferId` at a relay → DECLINED at the door → sender cleans that
  depot.
- Target declines the relay-forwarded OFFER (no space) → relay drops the transfer, frees quota.
- Sender dies mid-deposit: partial never pushed; reservation TTL cleans; target's REQUEST recovers from a
  holder.
- Large file, auto-download: the message lands → the requester opens a session → REQUEST → serve OFFER →
  channel opens → stream → READY; nothing goes over Tor or a relay.
- Large file above `autoDownloadMaxBytes`: no REQUEST until the user clicks; declining sends nothing; a
  re-open later fetches it.
- Stream session drops mid-file: both rows dropped → request PENDING → new serve (new `transferId`, new key)
  starts at the checkpointed P with a positioned read; same for a restart on either side.
- All large candidates offline: the request stays PENDING (not UNAVAILABLE) and fetches when one comes online.
- Credit window: a receiver with a slow disk holds the holder at `fileStreamWindowBytes` past the last
  checkpoint ACK; memory stays bounded.
- Checkpoint crash: kill the receiver between checkpoints → restart truncates to P, re-hashes the partial
  block, and the next serve re-sends at most one checkpoint.
- `fileId` vectors: fixed test vectors for empty, one-block, exact-multiple and short-last-block files, and
  the block-size doubling at 1024 blocks.
- Block list that does not hash to the `fileId` → the OFFER is CANCELled, nothing written.
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
- Same file at one relay under two transferIds: different keys and ciphertext, independent rows, both
  forward.
- Relay deposit round to two relays: two transferIds, two sealed OFFERs, both forwards decrypt at the target.
- Block mismatch: `.part` truncated to the bad block's start, earlier blocks kept → STALLED → re-REQUEST (same device
  first) → FAILED after `fileHashRetryLimit`; a holder with a rotten block ends the serve at that
  block and declines later REQUESTs.
- Reference mode: the original is edited, moved or deleted → the next serve stops at the first bad block →
  descriptor SOURCE_LOST; the message still renders; another holder serves.
- Serve gate: a REQUEST from an account that sees no referencing message is DECLINED.
- Late chunk after READY: the incoming-transfer row is gone → DECLINED → the sender ends the transfer quietly,
  never as DECLINED.
- REQUEST to a device already pushing to the requester: the old transfer is cancelled, one fresh serve runs.
- Torn drain: crash between the `.part` append and the P commit → truncated to P at open, staged rows drain
  again.
- No `fileId` on the wire outside the sealed OFFER half and the sealed REQUEST (codec-level check).

## 20. What exists today (disposition)

| Existing                                                                      | Disposition                                                                                                                     |
|-------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `protocol/envelopes/FileEnvelope.kt` (+ codec test)                           | reworked per §3.2 (coverage-based ACK/REQUEST, suffix OFFER, dropped wire types); observability profile revisited               |
| `protection/envelope/FileProtection.kt`, `PlaintextFileProtection.kt`         | interface kept, real implementation replaces the placeholder (ratchet-sealed OFFER half and REQUEST, per-transfer chunk AEAD)   |
| `crypto/primitives/CryptoProvider` (`sha256`, `encryptAead`)                  | `encryptAead` reused unchanged (chunk AEAD with library IV + AAD, §4); gains an incremental SHA-256 for block hashing (§2)      |
| `routing/inbound/handlers/FileInboundHandler.kt` (stub)                       | real logic per §7 (relay) and §8 (target: synchronous ingest port)                                                              |
| `persistence` `file_transfers`, `file_chunk_tracker`, `spaces` (placeholders) | reworked and regrained per §13 (orchestrator: file/account grain; routing: transferId grain); `spaces` untouched by this design |
| `persistence/DatabaseEnums.kt` (`FileTransferStatus`, `FileChunkStatus`)      | moved to the owning stores, with the terminal-state distinctions of §9/§14                                                      |
| `routing/policy/RelaySelectionPolicy.kt`                                      | reused unchanged (§5)                                                                                                           |
| `routing/outbound/RelayDepositor.kt`, `OutboxProcessor` triggers              | the trigger policy extracted for shared use; MESSAGE-only checks generalized (§5)                                               |
| `routing/store/PacketOutbox.kt` (`OutboxTag`)                                 | new `Kind.FILE`; `OutboxOutcome` gains `packetId`                                                                               |
| `JvmWebRtcBackend` channel labels (`envelope`, `av`)                          | add the per-transfer file channel kind, `bufferedAmount` low-threshold, and opening a session on request (§6.6, §12)            |
| `wiring/MessageLimits.kt`                                                     | joined by `FileLimits` (derived chunk size) and the storage-pledge split (§15)                                                  |
