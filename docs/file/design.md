# File transfer design

This is the design of record for file transfer (v1, 1:1 sends). The first draft was agreed in the design
sessions of November 2026 and revised after a full pre-implementation review and its small-file follow-up (13 November
2026), then completed by the large-file session (14 November 2026): large files are pull-only
streams (§6.6), the `fileId` is block-hashed (§2), receiver durability is checkpointed (§8) and the sender
may reference a file in place instead of copying it (§10). A second review against the code (14 November 2026)
simplified the large path to a plain fetch protocol on its own data channel (no envelopes, no per-transfer key,
§6.6), made the small/large limit a network constant (§1), narrowed the deposit triggers for chunks (§6.3),
stopped the gap trigger from cancelling transfers (§8) and listed what the outbox needs before it carries
chunks (§6.2). **Both paths are the base for implementation.**
[`../sprints.mmd`](../sprints.mmd) stays authoritative for sprint sequencing at its coarser grain and is not
modified by this design.

Implementation order: [`plan.md`](plan.md).

Related: [`../guide.md`](../guide.md) (layer model, dependency rules, the delivery model in §6),
[`../transport.md`](../transport.md) (links, channels, frame limits), [`../router.md`](../router.md) (outbox and
relay machinery this design reuses), [`../e2ee.md`](../e2ee.md) (sessions — used for the OFFER's sealed half,
deliberately **not** for chunk content, see §4). Diagrams: [`transfer paths.mmd`](transfer%20paths.mmd),
[`transfer states.mmd`](transfer%20states.mmd).

## 1. Scope and philosophy

Two transfer classes, one engine:

- **Small files** — at or below `smallFileMaxBytes` (10 MB, the Discord limit). May traverse relays with
  store-and-forward. Bounded size, so bounded relay storage.
- **Large files** — above the limit, no upper bound (hundreds of GB must work, though the expected use is
  files that slightly exceed the limit, in the ~100 MB range). **Pull-only and WebRTC-only**: nothing is
  pushed; each receiving device decides to fetch (automatically below its auto-download limit, otherwise on a
  user click) and asks a holder for the bytes on a dedicated data channel, which the holder answers with a
  plain byte stream (§6.6). Bytes never go over Tor and never go to relays. The receive engine is shared with
  small files; the transfer machinery (envelopes, keys, `transferId`s) is not used at all.

**`smallFileMaxBytes` is a network value, not a device setting.** The requester picks its path by it (REQUEST
through the outbox, or a fetch over WebRTC) and the holder picks how to serve, so every device must hold the
same number. It belongs in the network config (fetched from the network's page, not editable by the user);
until that exists it is a protocol constant next to the block-size rule (§2). It is never a field a user or a
config file can change per device.

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
- **`transferId` = routing session identity (small path only).** A large fetch has no `transferId`, no key and
  no chunking: it is a byte stream on one channel (§6.6). One push/serve attempt **between two devices**. Keys the relay
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

| Field        | Notes                                                            |
|--------------|------------------------------------------------------------------|
| `fileId`     | The content hash — also the integrity check                      |
| `fileName`   | Display                                                          |
| `mimeType`   | Display / preview                                                |
| `totalBytes` | Display, the receiver's completeness oracle, and its space check |

**The message is notification only.** No key, no chunk parameters — those travel exclusively with transfers (§3.3). The
attachment bytes are charged against the message size budget (the `Text`
`ENCODED_HEADER_RESERVE_BYTES` accounting grows; `wiring/MessageLimits` threads it); the list is capped
(`MAX_ATTACHMENTS`, and a `fileName` length cap) so the budget is bounded.

**This is a breaking wire change, done plainly.** The list is always encoded (a count, then the entries)
between the text and the author signature, and it is part of the signed bytes. No second payload type and no
optional trailing block. `PAYLOAD_HEADER_VERSION` goes to 3, so an old build rejects every new payload cleanly
and a new build rejects every old one: **new apps do not decode old messages**, including what is already
stored (payloads are stored as their encoded bytes, and the version byte is shared by text and event
payloads). Existing test data directories are reset with this change. That is acceptable while every install
is a test install; it stops being acceptable at the first real deployment, and from then on a layout change
needs a decoder for the previous version.

### 3.2 FileEnvelope payloads (rework of the existing family)

The FILE envelope family is the **small path's** protocol. A large fetch uses none of it (§6.6).

| Kind       | Fields                                                                                                                                      | Author / direction                                                                         |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `OFFER`    | public half: `chunkSizeBytes`, `totalBytes`, `startIndex`; sealed half: `fileId`, block list (§2), per-transfer key, `fileName`, `mimeType` | the serving device → target device (or relay, for deposits)                                |
| `CHUNK`    | `chunkIndex`, ciphertext (covers `[chunkIndex × chunkSizeBytes, …)` — **the transfer's own chunking**)                                      | pusher → target (replayed by relay)                                                        |
| `ACK`      | coverage **in the transfer's own chunk indices**: `contiguousChunks` + `missingRanges` (capped)                                             | target → that transfer's pusher                                                            |
| `COMPLETE` | — (the transfer is the header's `transferId`)                                                                                               | target → **every live transfer author**; also the cancel-on-complete (§8)                  |
| `REQUEST`  | sealed: `fileId`, held coverage in bytes (`contiguousBytes` + `missingRanges`)                                                              | requester → one candidate device                                                           |
| `CANCEL`   | reason                                                                                                                                      | either endpoint: author → relay/target (abort); target → author (decline, no space, abort) |

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
- **REQUEST is direct-only**: it goes to a candidate with an open link (§9), never through a relay. It exists
  only for small files: a holder answers a REQUEST for a file above `smallFileMaxBytes` with DECLINED, and
  both ends agree on the class because the limit is a network value (§1).

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

(A large fetch is not a transfer and has no chunk sequence: it names a byte offset, §6.6.)

This invariant is what keeps the OFFER to a single start index (no lists, no bitmaps) and collapses the
sending engine's per-depot bookkeeping to one number per depot: its resume point.

### 3.5 Chunks follow their OFFER

A transfer's chunks flow only after that transfer's OFFER was processed by the target. Both pushers guarantee it
by construction: the direct push enqueues chunks only after the marker's ACK, and the relay forwards only when
whole, its own OFFER-ACK gating its chunks. This is
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
  terminally DECLINED, no re-offer, no deposit (invariant 15). A target that already holds the file does **not**
  decline: it ACKs the OFFER and sends COMPLETE (§8), so "already have it" never reads as a refusal.
- **On an OFFER from a relay** — that depot is full; the engine tries the next relay or re-offers later (§6.1).
- **On a CHUNK** (target or relay) — the transfer is gone at that end. The pusher ends that transfer
  quietly (target: `ENDED`, outcome unknown — not `COMPLETED`, which only a COMPLETE gives, §14; relay: that
  depot is out), never as a refusal. The target
  drops its transfer rows once a transfer resolves (§8), so a sender whose COMPLETE was lost learns it this
  way — deliberately: holding rows open to answer late chunks better would be the two generals problem, and
  the pull path makes "probably done" safe.
- **On a REQUEST** — "I don't hold it, or I won't serve it" (§9): the next candidate is asked.

## 4. Crypto

- Everything in this section is the **small path**. A large fetch has no app-layer encryption: see the last
  bullet.
- **Per-transfer symmetric key** (AEAD, ChaCha20-Poly1305), generated fresh for every transfer — direct push,
  each relay deposit, each serve. Chunk content **never** goes through the Double Ratchet: a long file would
  explode ratchet state, `MAX_SKIP` bounds out-of-order tolerance, and one lost chunk would break the chain for
  interleaved messages.
- The **OFFER's sealed half rides the standard ratchet** (§3.3) — one small frame per transfer, bootstraps
  offline, late-decrypt-safe by the retention invariant.
- **Chunk AEAD is `CryptoProvider.encryptAead` as it is**: library-managed random IV, AAD = (`transferId`,
  `chunkIndex`) laid out as the 16 transfer-id bytes followed by the chunk index u32 BE. The AAD pins every
  chunk to its transfer and position, so a relay can neither
  reorder chunks nor splice them across transfers. No nonce derivation is needed: one key never outlives one
  transfer, a random 96-bit IV is safe at any chunk count a transfer reaches, and nothing relies on
  ciphertext being reproducible — re-encrypting a chunk after a restart yields different bytes for the same
  index, which nobody compares (the relay keeps the first by its PK; the receiver tracks plaintext coverage).
  Keys are threaded explicitly per call (the bootstrap pattern): the pusher passes its push-row key to seal
  and encrypt, the inbound handler passes its incoming-row key to open and decrypt — protection holds no key.
- A small-path chunk that happens to travel over a WebRTC link keeps its AEAD: the same outbox row may go over
  Tor on a retry or to a relay, so its format cannot depend on the link.
- Encryption and decryption happen in routing (through protection), which holds the transfer's key in its
  push row or incoming-transfer row; the orchestrator only ever supplies and sees plaintext and coverage (§6,
  §8).
- New `FileProtection` implementation (ratchet-sealed OFFER half and REQUEST, signing, per-chunk AEAD via
  `CryptoProvider`) replaces the `PlaintextFileProtection` placeholder.
- **A large fetch relies on the channel.** Its bytes cross exactly one hop, a WebRTC data channel between the
  two devices: DTLS encrypts it, and the DTLS fingerprints are exchanged in signaling that is signed and
  encrypted to the peer device, so the channel is authenticated to that device. No relay ever holds the bytes.
  An AEAD layer, a per-transfer key and per-chunk signatures on top would protect nothing more (about 475k
  signatures for 100 GB), and end-to-end integrity is the block list either way (§2). This holds only while
  WebRTC signaling stays signed and sealed — invariant 23.

## 5. Path selection

**Small files** mirror the message model exactly: direct over any open link first; relay deposit only when the
direct path looks doubtful. The goal is that a sender whose target went offline can hand the remainder to
relays quickly and close the app, without a slow but working link setting it off. So the triggers differ by
packet (§6.3): the **OFFER marker** uses the message triggers as they are (`RelayBackup`: no link at claim
time, retry, no ACK in time); **chunk rows** use only signals that the target is gone — its link closed, no
link at claim time, or no chunk ACK at all for `chunkAckTimeout`. The trigger *policy* is extracted
into a shared component that messages and files both consult, and payload construction stays per-path. FILE control
packets (ACK /
COMPLETE / CANCEL) are **not** message-style relay-backed: they are politeness, bounded by the TTL; the pull
path backstops their loss.

**Relay choice is `RelaySelectionPolicy`'s, unchanged from messages.** It returns whichever relays its
reliability math picks for the target (targeting `relayTargetSuccessProbability`, floor `minRelayScore`), and
the number it returns varies up to the `maxRelays` cap. The deposit does not choose a count — it offers the
covered set to **every relay the policy returns**. An empty selection means no deposit; the pull path covers
it.

**Large files are pull-only.** Nothing is pushed: the DAG message (which does travel over Tor and relays) tells
each receiving device the file exists, and the device fetches it when it decides to (§9).
The whole exchange — the ask, the holder's answer and the bytes — happens on one data channel of a WebRTC
session the requester opens (§6.6). The router hands those frames straight to the transport: the large path
never touches the outbox, the envelope pipeline or the router's ACK/NACK, and never needs a per-entry
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

The small path pushes through the outbox (§6.1–§6.5); the large path is a fetch on a dedicated data channel (§6.6). They
share the plaintext source and the receive engine, and nothing else.

One mechanism, two chunk sources. The **pusher** owns: the per-device fan-out (an account-level send from the
orchestrator becomes one transfer per target device — the `sendMessage` pattern), the OFFER-as-marker
lifecycle, chunk outbox rows, the in-flight window, the stall detector, deposit-on-stall, and resume. It
speaks pure router vocabulary (packets, outbox, ACKs, links), so it lives in **routing**. What it enqueues is
always a finished CHUNK envelope; the two sources differ in how one is made:

- **Sender's push** — the orchestrator implements `FilePlaintextSource`, a port the router declares: plaintext
  from any byte offset (the stored copy or the referenced original, §10), each block checked against the block
  list as it is read (§9). The orchestrator never sees a key. Routing cuts the plaintext into the transfer's
  chunks, encrypts each with the transfer's key and signs it, through protection (§4).
- **Relay's forward** — routing's own relay store hands back the stored CHUNK envelopes as they arrived, with
  the original sender's signature (§7). Nothing is encrypted or signed again. The relay is simply another
  sender whose "file" is a DB table.

The same `FilePlaintextSource` feeds the large fetch (§6.6).

### 6.1 The marker

The OFFER is enqueued as a normal outbox entry with `OutboxTag.Kind.FILE`, id = `transferId`, and standard
semantics — the delivery contract's "ACK = processed" is untouched:

- ACK (the offer was accepted) → marker removed; **the engine owns everything after**.
- Terminal NACK **DECLINED, split by source** (§3.6): relay-declined (storage full) → that depot is out; the
  engine tries the next relay or re-offers later. Target-declined (no space) → the send to that device is **terminally
  DECLINED** — no re-offer, no deposit; a refused file is never
  auto-resurrected (§9, invariant 11).
- Any other terminal NACK (`PERMANENT_PROTECTION_FAILED`, `DECODE_FAILED`, `UNSUPPORTED_TYPE` — e.g. the
  sealed half could not be opened, or the peer runs an older build) → that transfer is `ENDED`, not declined.
  Nothing is re-offered by the engine; the receiver's REQUEST (§9) is the recovery, with a fresh transfer.
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
window of 16 ≈ 3.5 MB of outbox per transfer.

Outbox extensions required: the new tag kind, and `packetId` on `OutboxOutcome` so the engine maps a chunk
outcome to its index. Unacked chunks are exactly the tagged rows still queued, so the engine's pending set is
reconstructable from the outbox — no parallel per-chunk table on the send side. Chunk offsets derive per
transfer (`chunkIndex × chunkSizeBytes`); nothing is pre-materialized. The OFFER marker and chunk rows are the
FILE packets that participate in the deposit triggers, each with its own set (§5, §6.3); control packets do
not. A DECLINED on a chunk ends that transfer quietly and drops its remaining rows (§3.6).

**The outbox was built for message-sized rows; three things change before it carries chunks.** They land with
the pusher, not later:

- **The due query stops loading blobs.** Today `getDue` is `SELECT *` with no order and no limit, and
  `processDue` runs again after every finished send. With chunk rows queued, each pass would reload megabytes
  of blobs. The due query returns ids, targets and tags, ordered and limited; a row's envelope is loaded when
  the row is claimed.
- **Messages go first.** Due rows are claimed non-FILE first, so two chunks never hold both of a peer's slots
  while a text message waits. On a Tor link a chunk takes seconds to write.
- **FILE rows have their own retry policy.** The message policy drops to `standbyRetryDelay` (1 h) after
  `fastRetryBudget` (3) attempts. A chunk answered with a silent DEFER (the receiver's staging bound, §8)
  would burn that in about three minutes and then sleep for an hour. Chunk rows retry at a flat
  `fileChunkRetryDelay` for as long as their transfer lives; the transfer's TTL and the stall detector bound
  them, not an attempt count.

`outboxMaxInFlightPerTarget` (2) was set conservatively for messages. Once the three changes are in, it is
stress-tested with chunk traffic over Tor and WebRTC and raised to what the links take; a separate cap for
FILE rows is added only if that test shows messages and chunks need different numbers.

**v1 status (implemented in step 13):** the three outbox changes are in (blob-less due query with
non-FILE first, flat `fileChunkRetryDelay` for FILE rows). The caps stay at 2 per target: the
ordering guarantee (messages before chunks) is covered by tests, but no real-network chunk
stress run has set a higher number yet, so no FILE-only cap was added. Revisit once
multi-MB transfers run over real Tor/WebRTC links.

### 6.3 Stall detection

A transfer in its chunk phase deposits its remainder (§6.4) on the first of three signals, all of which mean
"the target is gone", none of which means "the link is slow":

- **The link to the target closes** — the router's own link event; the deposit starts at once.
- **No link at claim time** — a chunk row is due and the target has no open link.
- **No progress** — no chunk ACK at all for `chunkAckTimeout` (30–60 s) while rows are in flight. This is the
  one that matters on Tor, where a dead link often gives no close event: the worst case is one timeout.

A single chunk that is slow to be ACKed, or a single chunk retry, is **not** a signal: on a slow circuit it
would send the whole remainder to every selected relay, for every device, exactly when traffic should not
multiply. As long as any chunk of the transfer was ACKed within the timeout, the direct path is working. (The
OFFER marker has no chunk ACKs to watch, so it keeps the message triggers, §5.)

The sender still has to stay up while the deposit itself uploads; what the triggers buy is that this starts
within one timeout of the target disappearing. (A large fetch has no stall detector on the holder's side: it
is bounded by its session and the receiver's gap trigger, §6.6.)

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

### 6.6 The fetch (large path)

A large file moves the way files normally move over WebRTC. The libraries have no file-transfer function: a
data channel is a reliable, ordered message stream, and every application sends a small header, then binary
frames, pausing on the channel's buffered amount. The large path is exactly that and no more. There is no
envelope, no `transferId`, no key, no OFFER and no router ACK; the router passes the frames straight between
the transport's channel and the file code, the same kind of direct path AV will use.

One fetch = one data channel between the requesting device and one holder.

1. **The requester opens the session.** Its request processor (§9) asks for a WebRTC session with the
   candidate through the router's existing on-demand opener (`ProactiveSessionOpener.awaitSession`, signaled
   over Tor as today). Sends never opened connections; a large fetch does, because the requesting device is
   the one with the user's intent. No session within `fileSessionOpenTimeout` → that candidate is unreachable
   and the next is asked.
2. **The requester opens a fetch channel** on that peer connection (no renegotiation; either side may add a
   data channel) and sends `FETCH { fileId, fromOffset }`, with `fromOffset` = its checkpointed P (§8). A
   dedicated channel keeps a 100 GB stream from starving messages and ACKs on the envelope channel.
3. **The holder answers once.** It runs the serve gate for the session's peer device (§9) and replies
   `FETCH_OK { totalBytes, block list }`, or `FETCH_DECLINED` (not held, or not allowed) and closes the
   channel. The requester checks the block list against the `fileId` (§2) before it accepts a byte; a list
   that does not match closes the channel and counts that holder as declined.
4. **The holder sends `DATA` frames**: raw plaintext, in order, from `fromOffset` to the end, each frame up to
   the transport's frame limit. It reads through `FilePlaintextSource`, so every block is checked against the
   block list before its bytes go out (a fetch that starts mid-block reads from that block's start). A frame's
   offset is implied by its order; nothing is numbered.
5. **The requester appends at P**, verifies blocks as P passes them, and sends `PROGRESS { P }` after each
   checkpoint (§8), once the fsync and the P commit are done.
6. **The end.** The last block verifies → READY → the requester closes the channel. Either side may send
   `ABORT { reason }` (a bad block at the source, no space, the gap trigger, a user cancel) and close.

**Frames.** Each channel message is one frame: a type byte, then the body. `FETCH`, `FETCH_OK`,
`FETCH_DECLINED`, `PROGRESS` and `ABORT` have a small codec in `protocol`; `DATA` is the type byte followed
by raw bytes. The `fileId` appears in `FETCH` in the clear inside the channel — DTLS covers it, and only the
peer device ever sees the channel (§4).

**A credit window.** The holder may run at most `fileStreamWindowBytes` (e.g. 32 MB) past the last `PROGRESS`,
and also pauses on the channel's `bufferedAmount` low-threshold. Both are needed: the channel's buffer only
protects the sender, and `JvmWebRtcBackend` receives through an `onMessage` callback with no receive-side flow
control. Without the window a receiver with a slow disk would pile frames up in memory, and blocking the
callback instead would stall the envelope channel on the same connection.

**No staging, no routing rows.** One ordered source lands every byte at P, so a fetch never stages. Neither
end has a row for it in routing: the holder's state (offset, window) and the requester's (the channel) live in
memory and die with the channel. What is durable is what already was — the requester's receive row and
request row (orchestrator), and its request attempts (§9). A device runs one fetch per file at a time (one
candidate at a time, §9).

**A fetch lives exactly as long as its channel.** When the session or the channel drops, both ends forget the
fetch and the request goes back to PENDING (§9). The next fetch names the checkpointed P as its `fromOffset`,
and the holder starts there with a positioned read (§11); a restart on either side is the same case. There is
no waiting-for-session state and nothing to resume but P. A fetch that stalls while its session lives is ended
by the receiver's gap trigger (`fileStallTimeout`, §8) with an `ABORT`, and re-requested the same way.

The small path's timers do not apply: there is no grace period (nothing is pushed), no `chunkAckTimeout` and
no deposit.

**When no session can be made.** WebRTC here has STUN only and no relay server, so two devices behind
unfriendly NATs may never connect. A candidate that is online (its Tor link is up) but whose session failed
`fileSessionOpenTimeout` is recorded as **no direct connection**, distinct from offline, and the GUI can show
"can't connect directly to any device that has this file" instead of waiting silently. Bytes still never fall
back to Tor (§17); a relay server for such pairs is deferred (§18).

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

- **Two grains** (a large fetch has only the second: no OFFER, no incoming-transfer row, no key; routing
  hands the channel's plaintext to the same ingest port). Each processed OFFER creates an **incoming-transfer row**
  (routing, `transferId`-grain:
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
  - **Fetch (large path):** frames arrive in order and append at P without staging; nothing is reported
    between checkpoints, and `PROGRESS` names the new P (§6.6). A crash loses at most one checkpoint of
    bytes, which the next fetch re-sends.
- **Staging is bounded** (`maxStagedBytes`, at least one checkpoint and at least one transfer's chunk window).
  Beyond the bound, a chunk that cannot
  extend P is answered DEFER — silent, dedup-cleared; the sender's window closes and its retry resumes once P
  advances (chunk rows retry at a flat delay for exactly this, §6.2). Without this, a racing transfer could
  stage an unbounded suffix behind a stuck gap.
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
  file means never fetching it: the receive row goes DECLINED locally and nothing goes on the wire. Each
  device decides on its own (no to 100 GB on the phone, yes on the PC); a user re-open fetches it later.
- **Completeness → READY.** Coverage of `[0, totalBytes)` with the last block verified → **atomic rename →
  status READY**. No final pass: every block was verified on the way. Never READY with an unverified block. On
  READY the receiver sends **COMPLETE to every live transfer author** (from its incoming-transfer rows); each
  ends its transfer and cancels its deposits — COMPLETE is the cancel-on-complete. A transfer arriving for an
  already-READY file has its OFFER **ACKed** (never DECLINED — that would read as a refusal, §3.6), no
  incoming-transfer row is created, and COMPLETE is sent to its author. A forwarding relay, which is not the
  author, learns it from the DECLINED on its first chunk and drops the transfer.
- **A file nobody references is not kept.** A small OFFER is accepted before, or without, its message, so a
  device can be handed files no message ever mentions. A receive row whose `origin_message_id` is still empty
  `fileTransferTtl` after it was created is deleted with its `.part` or finished file. v1 has no bare
  transfers (§10), so a legitimate file always gets its message within that time.
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
  sender's `chunkAckTimeout` so the push machinery acts first), the
  file goes STALLED — re-requestable, not refused — the durable coverage is kept, and the REQUEST path re-opens
  with held coverage (§9). **It cancels nothing.** The live incoming transfers stay: byte coverage merges
  whatever arrives from whichever source, a transfer that is really dead ends by its TTL, and a serve from a
  device that was already pushing supersedes that push at the serving device (§9). Cancelling would throw away
  the one copy that needs no holder online: a receiver that was offline for a few minutes while a relay was
  forwarding would come back, cancel, answer the relay's next chunk DECLINED and make it drop a whole copy —
  the opposite of §7's "the receiver needs no stall detection on relay pushes". The timer counts only while
  this device is running, so waking from sleep does not fire it at once. On a large fetch, where a dead source
  cannot be superseded, it ends the fetch with an `ABORT` (§6.6). TTL
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
- **A large request is a fetch, not a REQUEST envelope** (§6.6). The same loop picks the candidate; the
  attempt then opens a session and a fetch channel instead of enqueueing a packet, `FETCH_OK` plays the ACK
  and `FETCH_DECLINED` the DECLINED, and there is no WAITING state because the answer and the bytes come on
  the same channel. A candidate that cannot be
  reached is not a decline: when every remaining candidate is merely unreachable, the request stays PENDING and
  retries when one of them comes online — for WebRTC-only bytes, offline holders are the normal case. A
  candidate that is online but cannot be connected to is recorded as "no direct connection" so the GUI can say
  so (§6.6). A fetch that ends with its session sends the request back to PENDING, asking the same candidate
  first.
- The small-file REQUEST is **ratchet-sealed** (it carries the `fileId`) and **direct-only** (§3.2). It carries **held
  coverage** in bytes (`contiguousBytes` + missing ranges, capped; heavy fragmentation degrades to
  contiguous-only — a coarser serve with more duplicates, still correct).
- **The serve gate — the same logic as sync's.** A holder serves only when the requesting account may see a
  message that references the `fileId`: the orchestrator's `message_attachments` index (filled at DAG ingest)
  maps the `fileId` to its messages, and the sync serve gate decides visibility of those messages. No visible
  referencing message → DECLINED. Without the gate, anyone who has or guesses a file's content could pull it
  from any holder by its hash. A large fetch passes the same gate before `FETCH_OK`: the requesting account
  is the account of the session's peer device.
- **Holders check blocks as they read.** AEAD only protects bytes in transit: a copy that rotted on disk, or a
  referenced original the user edited (§10), would be encrypted faithfully and fail only at the requester. So
  the plaintext source hashes each block it reads and compares it with the block list before the block's bytes go
  out (a serve that starts mid-block reads from that block's start) — no full re-hash before a serve, which at
  100 GB would take minutes every time. A mismatch or a missing
  file ends the transfer with a CANCEL (a fetch with an `ABORT`); the holder treats the file as not held (a reference
  becomes
  SOURCE_LOST, §10) and DECLINEs later REQUESTs for it.
- For a small file, the serving device (any holder: original sender, sibling that completed) re-runs the push engine
  with a **fresh
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
      pass. The source is immutable across restarts, and a picture survives the user clearing their
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
  Knob: per-account quota with LRU above it. A file the quota evicts goes `EVICTED`: its descriptor is
  removed, this device stops serving it, the message still shows the attachment, and it is fetched again only
  when the user opens it — never automatically, or the quota would evict and re-fetch in a loop.
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
- **The sender reads at an offset — the one exception to pure kotlinx.io.** Every large fetch starts at the
  requester's P (§6.6), and every session drop starts a new fetch, so skipping by reading through the prefix
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
  outbox), the incoming-transfer rows, chunk encryption and decryption, sealed-half protection and every transfer key,
  **all
  transfer signaling** — OFFER, CHUNK, ACK, COMPLETE and CANCEL travel between the two devices that
  participate in the transfer — the relay store, and the request attempts. For large files it owns the fetch
  channel (§6.6): opening the session and the channel, the frames, the credit window — device and channel
  vocabulary, with no table.
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

| Layer                  | Owns                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `protocol`             | `FileEnvelope` rework (§3.2: `transferId`-addressed control packets, chunk-index ACK, sealed byte-coverage REQUEST, the block list in the sealed OFFER half), `Text.attachments` (§3.1, breaking, payload header version 3), the `fileId` construction, the block-size rule and `smallFileMaxBytes` until the network config exists (§1, §2), the fetch frames (§6.6), codecs + round-trip tests, observability profile update                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `crypto`               | incremental SHA-256 on `CryptoProvider` (init/update/final, §2)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `protection`           | real `FileProtection` (§4): ratchet-sealed OFFER half and REQUEST, per-transfer chunk AEAD and signing (small path only)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `routing`              | the pusher (`file/push`): per-device fan-out + per-transfer state, cutting and protecting chunks from `FilePlaintextSource`; the fetch (`file/fetch`, §6.6): both ends of the fetch channel, credit window, frames passed straight to the transport; the relay store and forwarder (`file/relay`); the incoming-transfer rows and chunk decryption; real `FileInboundHandler` (foreign target → admission/park; local → decrypt, synchronous ingest port + emit); `FileCarrier` port on `Router` + account-level file events; the `FilePlaintextSource` port (orchestrator implements it); the file request processor on the shared candidate core (a large request opens a session and a fetch channel); `OutboxTag.Kind.FILE`; `packetId` on `OutboxOutcome`; the outbox changes of §6.2 (blob-less due query, messages first, FILE retry policy); the deposit-trigger policy shared with messages (§6.3) |
| `orchestrator/file`    | the file machinery: `FileIds` block hashing, classification, descriptor creation, the block-checking plaintext source, the file store (copies and references, quota and retention, §10), the receive engine (coverage, staging, checkpoints, block verification, ready), the auto-download decision, the REQUEST trigger + coordinator, serve-on-REQUEST and serve-on-fetch with the serve gate (account-level); stores: `file_descriptors`, `file_transfers`, `file_receives`, `file_receive_chunks`, `pending_file_requests`; the DAG gains `message_attachments`; the transfer enums move out of `persistence/DatabaseEnums.kt` to the owning stores                                                                                                                                                                                                                                                     || `orchestrator/runtime/file` | the GUI-facing receive-state facade: `FileTransferService` (`receiveState`, `localFilePath`) over `orchestrator/file` — sending goes through `MessagingService.sendMessage` with picked files (no separate file-send entry point, §10); progress, accept/decline and open land with the GUI surface — the runtime stays the GUI's whole API ([`../gui-api.md`](../gui-api.md))                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `transport/webrtc`     | a file channel kind opened per fetch by either side (the backend already multiplexes by label: envelope and AV channels), carrying raw frames instead of `BinaryEnvelope`s; `bufferedAmount` low-threshold exposure; channel close events                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `orchestrator/file/io` | chunk-shaped read/write boundary over kotlinx.io, plus the positioned read (`expect`/`actual`, §11)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `wiring`               | `FileLimits` (derived chunk size, chained like `MessageLimits`), config wiring incl. the storage-pledge split                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |

Within the orchestrator the split follows the layer's own shape: the machinery is `orchestrator/file`, a
domain package beside `dag` and `sync`, and the GUI reaches it only through `FileTransferService` in
`orchestrator/runtime/file` — a facade over the machinery, the way the runtime's message service sits on the
DAG. The runtime stays the GUI's whole API ([`../gui-api.md`](../gui-api.md) is that contract; the boundary
test enforces it), and what the facade does not expose never crosses into the GUI.

The fetch (§6.6) needs from the session machinery only "open a session to this device" and "this session
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
  status TEXT,                                           -- CREATED | IN_FLIGHT | COMPLETED | ENDED | EXPIRED |
                                                          -- DECLINED | CANCELLED | FAILED
                                                          -- (COMPLETED only on a COMPLETE; ENDED = outcome unknown)
  progress …, created_at, updated_at, expires_at
)
file_receives (               -- receive state, fileId-scoped; incoming transfers merge here
  file_id TEXT PK,
  status TEXT,                -- PENDING | IN_FLIGHT | STALLED | READY | FAILED | DECLINED | EVICTED
  total_bytes INT, contiguous_bytes INT,                 -- P: the coverage checkpoint, truth over the .part;
                                                          -- every block below P is verified
  block_size INT NULL, block_hashes BLOB NULL,           -- from the first OFFER, checked against file_id
  hash_mismatches INT,                                   -- bounded by fileHashRetryLimit (§8)
  part_path TEXT, final_path TEXT,
  origin_message_id TEXT NULL,                           -- discovered when the message shows up; still NULL
                                                          -- after fileTransferTtl → the row and file go (§8)
  …
)
file_receive_chunks (         -- staging is the chunk tracker: staged, not-yet-drained ranges
  file_id TEXT, start_offset INT, length INT, plaintext BLOB,
  PRIMARY KEY (file_id, start_offset)
)
pending_file_requests (file_id TEXT PK, status TEXT, next_attempt_at, …)

-- routing (transferId grain: pushing, forwarding, asking)
file_pushes (                 -- the pusher's per-device transfer state (small path; a large fetch has no row)
  transfer_id TEXT PK, file_id TEXT, target_device TEXT,
  kind TEXT,                  -- DIRECT | DEPOSIT | SERVE
  key BLOB,                   -- this transfer's own key
  chunk_size_bytes INT, start_index INT, …               -- the covered suffix; resume derives
  status …,                   -- CREATED | IN_FLIGHT | COMPLETED | ENDED | EXPIRED | DECLINED | CANCELLED (§14)
  created_at, expires_at
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

The outbox table gains only the new tag-kind value; its queries change as §6.2 says. The receive staging table and the
relay store share a
`((scope), index-or-range) → blob` **shape** and never a **table**: routing stores ciphertext for foreign
traffic; the orchestrator stores plaintext for its own — one owner per table (guide §4), and the domains
never mix. Guide §4's ownership table already records the rows (owners per this section); the schema and its
migration land with implementation.

## 14. State machines

See [`transfer states.mmd`](transfer%20states.mmd). Compact listing:

- **Sender transfer (per device, routing)**: CREATED → IN_FLIGHT (offer accepted) → COMPLETED (COMPLETE from
  the target; deposits cancelled) / **ENDED** (outcome unknown: a target's DECLINED on a chunk — the transfer
  is gone there, §3.6 — or another terminal NACK on the marker, §6.1; never shown as delivered) /
  EXPIRED (`fileTransferTtl`) / **DECLINED (the target declined the OFFER — terminal for this device)** /
  CANCELLED (user cancel, or superseded by a serve to the same device, §9).
- **Fetch (large, routing, in memory only)**: ASKED (channel open, `FETCH` sent) → STREAMING (`FETCH_OK`,
  block list checked) → done (READY, channel closed) / ended (`FETCH_DECLINED`, session or channel dropped,
  `ABORT` from the receiver's gap trigger, or a block mismatch at the source). No row on either end: an ended
  fetch is forgotten, and the next one starts from the checkpointed P.
- **Incoming transfer (`transferId`-scoped, routing)**: ACTIVE (OFFER processed) → dropped on COMPLETE sent,
  CANCEL either way, or expiry. The gap trigger does not end it (§8).
- **Receiver (`fileId`-scoped)**: PENDING → IN_FLIGHT (`.part` reserved) → READY (coverage complete, last
  block verified, renamed, COMPLETE to live authors); IN_FLIGHT → STALLED on a block mismatch below
  `fileHashRetryLimit` (`.part` truncated to the block's start), FAILED at the limit; IN_FLIGHT → FAILED (ENOSPC);
  PENDING → DECLINED (no space, or a large file this device did not fetch — local, re-openable by
  the user); IN_FLIGHT → **STALLED** (no prefix progress within `fileStallTimeout` → REQUEST re-opens; live
  transfers are left alone); STALLED → IN_FLIGHT when any source resumes delivery; READY → **EVICTED** (quota
  LRU; fetched again only on a user re-open); IN_FLIGHT that never progresses expires → the REQUEST
  trigger re-opens.
- **Relay transfer**: RESERVED (admitted) → WHOLE (covered suffix complete) → FORWARDING (offer ACKed) →
  DRAINED; EXPIRED (reservation TTL or transfer TTL) from RESERVED/WHOLE; **dropped when the target DECLINED
  the forwarded OFFER**.
- **Request**: PENDING → ASKING (one candidate; a large request first opens a session) → **WAITING (ACK;
  offer pending, timeout → next candidate)** → SATISFIED (OFFER received; the serve transfer takes over) /
  UNAVAILABLE (every candidate declined or expired). A large request whose remaining candidates are only
  unreachable stays PENDING until one comes online. A large request has no WAITING: ASKING → SATISFIED on
  `FETCH_OK`, and SATISFIED → PENDING when the fetch ends with its session.
- **Marker** (outbox row): unchanged from the message contract — QUEUED → dispatched → gone on ACK / terminal
  NACK / expiry; retries resume, never restart. Only a target's DECLINED is a refusal; other terminal NACKs
  end the transfer as ENDED (§6.1).

## 15. Config

Per guide §2, each layer's config type lives next to its code:

- **Network value, not config**: `smallFileMaxBytes = 10 MB` (§1) — the network config when it exists, a
  protocol constant until then.
- `FileServiceConfig` (orchestrator, `orchestrator/file`): receive directory, retention quota, `maxStagedBytes`,
  `fileHashRetryLimit` (e.g. 3, §8);
  `autoDownloadMaxBytes` (per device — large files at or below it are fetched without asking; e.g. 250 MB on
  desktop, lower on mobile, §8); `copyIntoStoreMaxBytes` (the "keep a copy" checkbox default, default
  `smallFileMaxBytes`, §10); `fileCheckpointBytes` (e.g. 8 MB) and `fileCheckpointInterval` (e.g. 2 s, §8).
- `RouterConfig` additions: `fileTransferTtl = 2 days` (marker, relay reservation and transfer lifetimes),
  `maxInFlightChunks` (the pusher's window, §6.2), `fileChunkRetryDelay` (the flat retry of chunk rows, §6.2),
  `chunkAckTimeout` (no chunk ACK at all for this long → deposit, §6.3), `fileStallTimeout` (the receiver's gap
  trigger), `fileRequestAttemptTimeout`,
  `fileRequestFirstAttemptDelay` (the grace period, small files), `fileRequestWaitTimeout` (WAITING for the
  OFFER), `fileSessionOpenTimeout` (a large request opening its session) and `fileStreamWindowBytes` (e.g.
  32 MB — how far a fetch's holder may run past the last `PROGRESS`, §6.6).
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
- Startup checks: `maxStagedBytes ≥ fileCheckpointBytes` (staging must hold a checkpoint's worth) and
  `maxStagedBytes ≥ maxInFlightChunks × chunkSizeBytes` (one transfer's out-of-order window must fit; the two values
  live in different layers, so wiring checks it);
  `copyIntoStoreMaxBytes` and `autoDownloadMaxBytes` are free per device, `smallFileMaxBytes` is not.
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
   clear — and lives no longer than its transfer. Only routing and protection ever hold one.
6. Key and chunk parameters are per transfer; chunk AEAD binds (`transferId`, `chunkIndex`) as AAD. Nothing
   relies on ciphertext being reproducible.
7. Covered sets are contiguous suffixes in the transfer's own chunking; per-depot engine state is one resume
   point.
8. The marker is establishment-only; ACK = processed: an outbox chunk is processed when decrypted and durably
   staged; a fetch's `PROGRESS` is sent only after the fsync and the P commit (§8).
9. Receiver file state is `fileId`-scoped byte coverage — transfer- and chunking-agnostic; each incoming
   transfer is its own `transferId`-scoped row; relay state is `transferId`-scoped.
10. One REQUEST candidate at a time.
11. STALLED / EXPIRED are re-requestable; DECLINED / CANCELLED / FAILED / EVICTED are refused — never
    auto-resurrected. The gap trigger opens a REQUEST and cancels no transfer.
12. The relay verifies the original sender's signature on OFFER and CHUNK before storing anything; chunks
    without a live transfer row are DECLINED at the door, never stored.
13. Transfers are temporary (`fileTransferTtl`); files stay.
14. A transfer's chunks flow only after its OFFER was processed by the target (§3.5).
15. Any DECLINED touching a transfer cleans up that party's state for it; only the target's DECLINED **on an
    OFFER** is terminal for the send to that device — a DECLINED on a chunk ends the transfer quietly as
    ENDED (§3.6). A sender transfer is COMPLETED only by a COMPLETE.
16. Transfers are device-to-device; the orchestrator speaks files and accounts, routing fans out and signals
    per device.
17. In envelopes the `fileId` travels only in the OFFER's sealed half and the sealed, direct-only REQUEST;
    every other FILE envelope is addressed by `transferId`. Outside envelopes it appears only in a fetch's
    `FETCH` frame, inside the DTLS channel to the one peer device.
18. Every serve passes the serve gate: the requester's account must see a message referencing the `fileId`.
19. One sealed OFFER per transfer, and one transfer per relay deposit; at a serving device, one live transfer
    per (`fileId`, target device).
20. Large files are pull-only: nothing large is sent unasked; the receiving device decides to fetch, and
    declining is local. `smallFileMaxBytes` is one value for the whole network.
21. A fetch lives within one data channel of one WebRTC session; it never waits for a session and never goes
    over Tor, a relay, the outbox or the envelope pipeline.
22. The `fileId` is computed by one protocol-fixed rule (§2); a block list is used only after it hashes to the
    `fileId`, and a holder sends no block it has not just checked against the list.
23. A fetch's bytes are protected by the channel alone (DTLS, authenticated through signed and sealed
    signaling, §4). If WebRTC signaling ever stops being signed and sealed to the peer device, the fetch needs
    its own encryption before that ships.
24. Chunk rows deposit only on signals that the target is gone (link closed, no link, no chunk ACK at all
    within `chunkAckTimeout`) — never on one slow or retried chunk (§6.3).
25. A received file that no message references within `fileTransferTtl` is deleted (§8).

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
- **App-layer AEAD, envelopes and per-chunk signatures on the large path** — the earlier design, kept "for
  uniformity with the relay path". Once large files became pull-only and WebRTC-only there was no relay path
  to be uniform with: the bytes cross one authenticated, encrypted channel, and the block list is the
  integrity check. A plain fetch protocol on the channel removes the key, the OFFER, the `transferId`, two
  routing rows and the question of how its control packets get ACKed (§6.6).
- **A large-file fallback to Tor when WebRTC cannot connect** — 100 MB and up over onion circuits is not a
  path worth having; the failure is shown to the user instead (§6.6).
- **A per-device `smallFileMaxBytes`** — requester and holder must agree on the class; it is a network value (§1).
- **A second payload type, or an optional trailing block, for attachments** — a patch to keep old bytes
  decoding while nothing deployed needs them; the layout is changed plainly and the version bumped (§3.1).
- **Message-style deposit triggers on every chunk row** — one slow ACK or one retry would send the remainder
  to every relay on a slow but working link (§6.3).
- **The gap trigger cancelling live transfers** — it would make a returning receiver drop a relay's whole
  copy; coverage merges, so nothing needs cancelling (§8).
- **Showing a transfer as COMPLETED when a chunk is DECLINED** — the transfer is gone at the target, which
  may equally mean it stalled or expired there; that outcome is ENDED (§3.6).
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
  positioned (§11), because a large fetch starts at an offset after every session drop; swap in kotlinx.io
  positioned I/O when it ships (#241).
- **Pushed large OFFERs with an accept prompt** — the original large-path outline: an OFFER held processed
  while the user decides, a separate accept signal, a rule for which devices prompt, a sender waiting for a
  session, and timers that must not fire meanwhile. Pull-only removes all of it (§5, §6.6).
- **Per-chunk ACKs and outbox rows on the large path** — about 475k rows for 100 GB on a channel that already
  delivers in order; `PROGRESS` at checkpoints with a credit window instead (§6.6).
- **Relying on the channel's flow control alone** — `bufferedAmount` protects only the sender; the receive
  callback has no backpressure, so a slow disk would buffer in memory. The credit window bounds it (§6.6).
- **A fetch that waits for its session** — it would need state at both ends and a waiting state; a new fetch
  from the checkpointed P is the same result with no state at all.
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
- Raising `smallFileMaxBytes` (a network-config change, for every device at once) once the dedicated relays (~10 GB)
  exist, so ~100 MB files also get
  store-and-forward.
- An auto-download limit per network type (metered vs. not).

Also deferred: group sender keys (additive by design — also the fan-out and deposit-cost optimization for multi-device
accounts); OFFER-response carrying held-state (bandwidth optimization for duplicate relay forwards);
a separate outbox in-flight cap for FILE rows (only if the stress test of §6.2 asks for one); a relay
server (TURN) for device pairs that cannot connect directly (§6.6); relays as REQUEST candidates (rejected for v1);
disk-based relay store (only if
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
  byte; the stalled transfers are not cancelled.
- Staging bound: a racing transfer hits `maxStagedBytes` → DEFER backpressure → gap filled → drain → resume.
- Eviction pressure → DECLINED at admission → engine tries the next relay / re-offers later.
- Chunks for a dead, evicted or expired `transferId` at a relay → DECLINED at the door → sender cleans that
  depot.
- Target declines the relay-forwarded OFFER (no space) → relay drops the transfer, frees quota.
- Sender dies mid-deposit: partial never pushed; reservation TTL cleans; target's REQUEST recovers from a
  holder.
- Large file, auto-download: the message lands → the requester opens a session and a fetch channel → `FETCH`
  → `FETCH_OK` → `DATA` → READY; nothing goes over Tor, a relay or the outbox.
- Large file above `autoDownloadMaxBytes`: no fetch until the user clicks; declining sends nothing; a
  re-open later fetches it.
- Fetch session drops mid-file: both ends forget it → request PENDING → a new fetch starts at the checkpointed
  P with a positioned read; same for a restart on either side.
- `FETCH_OK` with a block list that does not hash to the `fileId` → channel closed, nothing written, next
  candidate.
- Fetch serve gate: a `FETCH` from a device whose account sees no referencing message gets `FETCH_DECLINED`.
- A REQUEST envelope for a file above `smallFileMaxBytes` is DECLINED.
- No direct connection: the holder is online over Tor but no session forms → recorded as such, not as
  offline, and no byte goes over Tor.
- Deposit triggers: a slow link that still ACKs a chunk within `chunkAckTimeout` never deposits; a closed link
  deposits at once; a silent dead link deposits after one timeout.
- Gap trigger keeps transfers: receiver offline mid-forward beyond `fileStallTimeout`, back online → the
  relay's forward continues and completes; the REQUEST that opened is satisfied or superseded.
- Messages first: a text message enqueued behind a full chunk window to the same peer is sent before the
  remaining chunks.
- Chunk DEFER: a chunk DEFERred at the staging bound keeps retrying at `fileChunkRetryDelay` and lands once P
  advances — no hour-long standby.
- Marker terminal NACK other than DECLINED → the transfer is ENDED, never shown as declined or delivered.
- Unreferenced receive: a file that reaches READY with no message is deleted after `fileTransferTtl`.
- Quota eviction → EVICTED; no automatic re-fetch; a user re-open fetches it.
- Attachments codec: round trip with 0, 1 and the maximum number of attachments; a version-2 payload is
  rejected.
- All large candidates offline: the request stays PENDING (not UNAVAILABLE) and fetches when one comes online.
- Credit window: a receiver with a slow disk holds the holder at `fileStreamWindowBytes` past the last
  `PROGRESS`; memory stays bounded and the envelope channel keeps flowing.
- Checkpoint crash: kill the receiver between checkpoints → restart truncates to P, re-hashes the partial
  block, and the next fetch re-sends at most one checkpoint.
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
- Late chunk after READY: the incoming-transfer row is gone → DECLINED → the sender ends the transfer as
  ENDED, never as DECLINED or COMPLETED.
- REQUEST to a device already pushing to the requester: the old transfer is cancelled, one fresh serve runs.
- Torn drain: crash between the `.part` append and the P commit → truncated to P at open, staged rows drain
  again.
- No `fileId` on the wire outside the sealed OFFER half and the sealed REQUEST (codec-level check).

## 20. What exists today (disposition)

| Existing                                                                      | Disposition                                                                                                                                                                   |
|-------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `protocol/envelopes/FileEnvelope.kt` (+ codec test)                           | reworked per §3.2 (coverage-based ACK/REQUEST, suffix OFFER, dropped wire types); observability profile revisited                                                             |
| `protection/envelope/FileProtection.kt`, `PlaintextFileProtection.kt`         | interface kept, real implementation replaces the placeholder (ratchet-sealed OFFER half and REQUEST, per-transfer chunk AEAD)                                                 |
| `crypto/primitives/CryptoProvider` (`sha256`, `encryptAead`)                  | `encryptAead` reused unchanged (chunk AEAD with library IV + AAD, §4); gains an incremental SHA-256 for block hashing (§2)                                                    |
| `routing/inbound/handlers/FileInboundHandler.kt` (stub)                       | real logic per §7 (relay) and §8 (target: synchronous ingest port)                                                                                                            |
| `persistence` `file_transfers`, `file_chunk_tracker`, `spaces` (placeholders) | reworked and regrained per §13 (orchestrator: file/account grain; routing: transferId grain); `spaces` untouched by this design                                               |
| `persistence/DatabaseEnums.kt` (`FileTransferStatus`, `FileChunkStatus`)      | moved to the owning stores, with the terminal-state distinctions of §9/§14                                                                                                    |
| `routing/policy/RelaySelectionPolicy.kt`                                      | reused unchanged (§5)                                                                                                                                                         |
| `routing/outbound/RelayDepositor.kt`, `OutboxProcessor` triggers              | the trigger policy extracted for shared use; the marker uses the message triggers, chunk rows their own set (§5, §6.3)                                                        |
| `routing/store/PacketOutbox.kt` (`OutboxTag`), `Outbox.sq`, `OutboxProcessor` | new `Kind.FILE`; `OutboxOutcome` gains `packetId`; the due query stops loading blobs and orders messages first; FILE rows get a flat retry (§6.2)                             |
| `routing/policy/LinkPreferencePolicy.kt` (retry delays)                       | unchanged for messages; chunk rows do not use its `fastRetryBudget` / `standbyRetryDelay` (§6.2)                                                                              |
| `routing/outbound/ProactiveSessionOpener.kt` (`awaitSession`)                 | used as it is to open a session for a large fetch; its doc's "fallback to Tor" for files is removed (§6.6)                                                                    |
| `protocol/envelopes/MessageEnvelope.kt` (`Text`, `PAYLOAD_HEADER_VERSION`)    | `Text` gains `attachments` in the signed bytes; the header version goes 2 → 3; test data is reset (§3.1)                                                                      |
| `JvmWebRtcBackend` channel labels (`envelope`, `av`)                          | add a per-fetch file channel kind carrying raw frames, `bufferedAmount` low-threshold (the `onBufferedAmountChange` hook exists, unused) and channel close events (§6.6, §12) |
| `wiring/MessageLimits.kt`                                                     | joined by `FileLimits` (derived chunk size) and the storage-pledge split (§15)                                                                                                |
