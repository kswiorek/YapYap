# File transfer design

This document describes file transfer as it is built (v1, 1:1 sends): what moves, who owns it, and why. It
covers both paths end to end:

- the **small path**: seals and hops, through the outbox, with store-and-forward relays;
- the **large path**: a pull over a WebRTC data channel.

[`../envelope-redesign.md`](../envelope-redesign.md) stays the design of record for the envelope every family
shares, and its §5 for the seal/hop model. This document agrees with it and adds what it leaves out: the large
path, the receiver, the pull path, storage, config and the reasons behind each choice. Where they disagree, that
is a bug in one of them.

**History.** The first design was agreed in November 2026 and revised in a pre-implementation review, a
large-file session and a code review. Its small path was rebuilt on the envelope redesign: one seal per (file, target
device), every transfer a single hop, the same envelope as every other family. The large path,
the receiver, REQUEST/serve and storage came through that rebuild unchanged.

**Reviewed (27 November 2026).** A review of the implemented routing side confirmed the mechanisms and ordered
a cleanup:

- naming on two axes (Large/Small, Push/Pull);
- the config taxonomy of §15;
- per-transfer sessions over a mux;
- store-owned candidate views;
- one incoming-transfer model for target and relay hops.

[`cleanup.md`](cleanup.md) records its decisions, all of which have landed. The orchestrator side (`orchestrator/file`)
has not been reviewed yet.

Related: [`../guide.md`](../guide.md) (layer model, dependency rules, the delivery model in §6),
[`../transport.md`](../transport.md) (links, channels, frame limits), [`../router.md`](../router.md) (outbox and
relay machinery), [`../e2ee.md`](../e2ee.md) (sessions: they carry the seal, deliberately **not** chunk
content, §4). Diagrams: [`transfer paths.mmd`](transfer%20paths.mmd), [`transfer states.mmd`](transfer%20states.mmd).

## 1. Scope and philosophy

Two transfer classes:

- **Small files**: at or below `smallFileMaxBytes` (10 MB). They are pushed, and they may traverse relays with
  store-and-forward. Their size is bounded, so relay storage is bounded too.
- **Large files**: above the limit, with no upper bound. Hundreds of GB must work, though the expected use is
  files slightly over the limit, around 100 MB. They are **pull-only and WebRTC-only**:
    - nothing is pushed;
    - each receiving device decides to fetch: automatically below its auto-download limit, otherwise on a user
      click;
    - the device asks a holder for the bytes on a dedicated data channel, and the holder answers with a plain
      byte stream (§6.6);
    - the bytes never go over Tor and never go to relays;
    - the receive engine is shared with small files, but the seal and hop machinery is not used at all.

**`smallFileMaxBytes` is a network value, not a device setting.** The requester picks its path by it (a
REQUEST through the outbox, or a pull over WebRTC), and the holder picks how to serve, so every device must
settle on the same number. It lives in `RouterConfig` and is surfaced through the settings registry as a
network field (`FieldSource.NETWORK`, `wiring/Settings.kt`): written programmatically, never user-editable, and
settled from the network's page once that exists.

**The receiver enforces its own value**, so a peer that lags behind the settled number loses nothing:

- a small-path OFFER above the receiver's limit is DECLINED (before its seal is opened, §8);
- the receiver's own class pin routes that file to its large path (§9), which no sender's limit can block.

The reverse lag is temporary. A device *ahead* of the settled value treats a file between the two limits as
small and asks for it with a small REQUEST, while lagging holders still treat it as large and decline. That
file stays unavailable from them until they settle on the same value. The settled value is expected only to
increase, so the window is one-way.

The core philosophy carries the guide's delivery model (§6) over to files, with one hard separation on top:
**the message and the transfer are decoupled.**

- A message with an attachment is a notification: "you may want this file; make sure you have it". It carries
  no key and no transfer parameters.
- Transfers are the only way file bytes move.
- The pull path (DAG message → REQUEST → serve) is the loss-proof backbone.

A deferred, expired, stalled or partial push is therefore never a loss: the message references the file
forever, and the receiver can always REQUEST the bytes from a holder.

**Transfers are temporary; files stay.** A seal and its transfers live for `fileTransferTtl` (§15) and then
they are gone. A completed file lives in the permanent attachment store (§10).

Grounding facts that shape everything below:

- **Chunking is forced everywhere.** The WebRTC backend carries at most ~250 KiB per envelope, Tor at most
  4 MB. The chunk size is **derived** by the composition root (≈249 KiB after envelope framing) and never
  configured on its own (§15). Each transfer snapshots the current value when it is created.
- **No new ARQ is built.** Both transports are reliable per frame: WebRTC data channels are SCTP, configured
  ordered and fully reliable; Tor links are framed TCP streams. The router already gives every envelope
  ACK/NACK, dedup and outbox retry, so a chunk sent as an ordinary packet is effectively-once per hop. The
  chunk ACK's coverage is the **progress/resume** protocol, not loss detection.
- **Group sends are out of scope for v1**, but the design does not depend on how the key is shared. Chunk
  content is AEAD under a key that only the seal carries, so group fan-out (sender keys) can be added later
  without touching the chunk pipeline.

## 2. Identity: three grains

| Grain    | Id           | What it is                                                      | Owner        |
|----------|--------------|-----------------------------------------------------------------|--------------|
| File     | `fileId`     | content identity, block-hashed                                  | orchestrator |
| Seal     | `sealId`     | one file sealed for one target device by one origin; end to end | routing      |
| Transfer | `transferId` | one hop between two devices moving a range of one seal's chunks | routing      |

**`fileId`: content identity, block-hashed.** The plaintext is cut into fixed **blocks**. The `fileId` is the
SHA-256 over the file size, the block size and the list of per-block SHA-256 hashes, hex-encoded like the other
ids.

- It is stable forever. The DAG message references it, and it keys the receiver's coverage state, the `.part`
  file, the final file and the permanent attachment store.
- Dedup is free: two senders shipping identical bytes produce the same `fileId`, and a receiver that already
  holds it renders the second message straight from local storage.
- The identity *is* the integrity check: the block list is checked against the `fileId`, and each block against
  the list.

How it is built:

- `fileId = SHA-256("yapyap-file-v1" ‖ u64 totalBytes ‖ u32 blockSize ‖ h₀ ‖ … ‖ hₙ₋₁)`, where `hᵢ` is the
  SHA-256 of block `i`. The last block may be short; an empty file has no blocks.
- `blockSize` depends only on `totalBytes`: start at 1 MiB and double until the file has at most 1024 blocks.
  The list is therefore at most 32 KiB: 10 blocks for 10 MB, 1024 × 1 MiB for 1 GiB, about 800 × 128 MiB for
  100 GB.
- The rule and the version tag are **protocol constants**, not config: changing either changes every `fileId`.
- The **block list** travels inside the seal (§3.3) and in the large path's `PULL_OK` (§6.6). The sender keeps
  it in its file descriptor, the receiver on its receive row (§13).

Why blocks:

- verification runs as the bytes arrive, with no final full pass, and a restart re-hashes at most one partial
  block;
- a hash mismatch costs one block, not the file;
- a holder checks blocks as it reads them to serve, instead of re-hashing the whole file first (§8, §9).

Small files run the same code with 1–10 blocks. Hashing uses an incremental SHA-256 on `CryptoProvider`
(init/update/final, since a block can be 128 MiB). The sender hashes once, at send time, in the same pass as the
copy into the store when it copies (§10).

**`sealId`: one file sealed for one target device.**

- The **seal** is the file's facts plus a fresh **seal key**, ratchet-encrypted from the origin to one target
  device (§3.3).
- `sealId` is the content id of the OFFER content: the SHA-256 of the seal bytes. It is content-addressed, so
  nobody can swap a seal under an id, and the target recognises a seal it already holds without decrypting it.
- The origin makes **one seal per (file, target device)** when a push starts, keeps it until it expires, and
  attaches the same seal bytes to every transfer of that push: the direct one and every relay deposit.
- **A serve always mints a fresh seal** (§9).

**`transferId`: one hop.**

- A transfer moves a contiguous byte range of one seal's chunks from one device to another, with its own fixed
  **shape**: `chunkSizeBytes`, `byteFrom`, `byteTo` (§3.4).
- The direct push is a transfer, each relay deposit is a transfer, and each relay forward is another transfer
  of the same seal.
- The `transferId` addresses every FILE packet except COMPLETE and REQUEST (§3.2). It keys the sender's and the
  receiver's per-hop rows.
- A large fetch has no seal and no `transferId`: it is a byte stream on one channel (§6.6).

**Transfers are device-to-device.**

- A send to an account is one orchestrator-level send. Routing fans it out into one seal per device of that
  account: the message model's fan-out, carried one step further. The transfer state is device-grain and lives
  in routing (§12).
- This strains relays more than an account-grained design would. That cost is accepted; group keys and sends
  will optimise it away.
- A sibling device that no push targeted gets the file through REQUEST (§9). That pull is normal traffic, not a
  failure.

**Transfers of one file share nothing but the plaintext they deliver.** Every chunking puts the same bytes at
the same offsets, so the receiver merges hops by plaintext byte coverage (§8). Resume then works across
transfers, chunkings, seals and serving devices without any two of them agreeing on anything.

## 3. Wire protocol

Every FILE envelope is a `BinaryEnvelope` v2 like every other family ([
`../envelope-redesign.md`](../envelope-redesign.md)):

- **every hop signs** its own envelope, and the receiver verifies that signature before dedup;
- the content is copied verbatim and never re-signed;
- the content id is the SHA-256 of the content, derived and never sent.

A relay forwarding a transfer is the signer of its forward; what it carries (the seal bytes and the chunk
ciphertext) is the origin's, unchanged.

### 3.1 Message attachments

`DagPayload.Text` carries an `attachments` list. Each entry is deliberately minimal: everything a receiver needs
to render the message and decide to REQUEST, and nothing more.

| Field        | Notes                                                            |
|--------------|------------------------------------------------------------------|
| `fileId`     | the content hash, which is also the integrity check              |
| `fileName`   | display                                                          |
| `mimeType`   | display and preview                                              |
| `totalBytes` | display, the receiver's completeness oracle, and its space check |

**The message is notification only.** It has no key and no transfer parameters; those travel only with
transfers (§3.3).

- The attachment bytes count against the message size budget (the `Text` `ENCODED_HEADER_RESERVE_BYTES`
  accounting grows; `wiring/MessageLimits` threads it).
- The list is capped (`MAX_ATTACHMENTS`), and so is the `fileName` (a shared 80-character / 255-byte cap), so the
  budget is bounded.
- The list is always encoded (a count, then the entries) between the text and the author signature, and it is
  part of the signed bytes. `PAYLOAD_HEADER_VERSION` went to 3 with it: a breaking change made plainly while every
  install was a test install. From the first real deployment on, a layout change needs a decoder for the
  previous version.

### 3.2 The FILE headers

The FILE family is the **small path's** protocol. A large fetch uses none of it (§6.6).

| Kind       | Routing header                                                                        | Content                                                                                 | Direction                                  |
|------------|---------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|--------------------------------------------|
| `OFFER`    | `transferId`, `origin`, `finalTarget`, shape (`chunkSizeBytes`, `byteFrom`, `byteTo`) | the seal: a ratchet frame from `origin` to `finalTarget` (§3.3)                         | the hop's sender → its receiver            |
| `CHUNK`    | `transferId`, `chunkIndex`                                                            | ciphertext of `[byteFrom + i × chunkSize, …)` under the seal key (§4)                   | the hop's sender → its receiver            |
| `ACK`      | `transferId`, `highestContiguousIndex` (`-1` when none), `missingRanges` (≤ 16)       | —                                                                                       | the hop's receiver → its sender            |
| `CANCEL`   | `transferId`, `reason` (`DECLINED`, `NO_SPACE`, `ABORT`; no free text on the wire)    | —                                                                                       | either end of a hop                        |
| `COMPLETE` | `sealId`                                                                              | —                                                                                       | final target → the seal's origin, directly |
| `REQUEST`  | —                                                                                     | a ratchet frame: `fileId`, held coverage in bytes (`contiguousBytes` + `missingRanges`) | requester → one candidate, direct-only     |

- **A transfer is one hop.** OFFER opens it, CHUNKs fill it, ACK and CANCEL answer it. COMPLETE is the only
  seal-level packet, and REQUEST starts a serve.
- **The shape is public** (it sits in the OFFER's routing header), so a relay can:
    - reserve space for the hop (`byteTo − byteFrom` plus per-chunk overhead);
    - count wholeness;
    - check each chunk's exact ciphertext length.
- **The `fileId` stays off the wire**, except inside sealed content (the seal and REQUEST) and the large path's
  `PULL` frame. ACK, CANCEL and COMPLETE travel carrying nothing a relay could tie to the file's content.
- **ACK speaks the transfer's own chunk indices; REQUEST speaks bytes.** The receiver knows every incoming hop's
  shape, so it maps its byte coverage (which may come from any source) onto that hop's indices: a chunk counts
  as covered once its whole range is. A REQUEST comes before any transfer, so it has no chunking to speak and
  stays in bytes; the server maps them onto the shape it picks (§3.4).
- **REQUEST is direct-only.** It goes to a candidate with an open link (§9), never through a relay. It exists
  only for small files: a holder answers a REQUEST for a file above `smallFileMaxBytes` with DECLINED.
- **COMPLETE is not relay-backed.** It is sent to the seal's origin, directly, when the file is READY, and may
  never arrive (§6.5).
- **No hash per chunk.** AEAD tags catch corruption at decrypt, and plaintext integrity is the block list,
  checked against the `fileId` (§2). Blocks are a property of the file and chunks of a transfer; the two never
  need to align.

### 3.3 The seal

- **Contents**: `fileId`, `totalBytes`, `blockSize`, the block list, `fileName`, `mimeType`, and the **seal key**
  (`SealKey`, 32 bytes, ChaCha20-Poly1305). It holds file facts only: no chunk size and no transfer data.
- **Ratchet-encrypted from the origin to the target device**, through the standard Double Ratchet. A relay can
  never read it and never learns the `fileId`.
- **A seal that can't be made is skipped for that device.** This happens when there is no session to the device
  yet; the pull path covers it.
- **The seal is the only carrier of its key.** Messages never carry one (§3.1), and no key outlives its seal.
  Every legitimate holder can serve, because serving needs the plaintext, not anyone's key: a serve makes a fresh
  seal for the requester (§9). A sibling device, or a fresh device syncing history, receives the key that way.
- **Size.** A seal is the block list plus a few fields, so the block rule keeps it at most ~38 KiB. It must fit
  an OFFER through both transports: the budget is derived (`FileLimits.maxSealBytes`, chained from the transport
  limits, §15) and checked when the seal is made and again at admission.
- **Opened once.** The seal is a ratchet message, and the same seal can reach the target over several hops: the
  direct push and every relay's forward. So the target writes its incoming seal row **the moment the seal
  opens** (§8):
    - every later OFFER with that `sealId` binds to the stored row without touching the ratchet;
    - a DEFER from the receive engine after the seal opened costs nothing, because the sender's redelivery finds
      the row.
- **The crash edge.** A seal that opened but whose row was lost to a crash fails as a `Replay` with no row.
  That hop is NACKed PERMANENT, and the file comes back through REQUEST with a fresh seal.
- **Late decrypt is a config invariant, not luck.** A relay can forward the seal bytes up to the seal's expiry,
  so the target's ratchet state for that generation must outlive it: `fileTransferTtl <
  supersededSessionRetention` (2 days < 3 days, §15). Known edge, accepted for v1: a seal held that long is also
  an out-of-order message within its chain, so a busy chat can push it past the skipped-key limits. The open then
  fails, that hop ends, and the pull path recovers.
- **Correlation, honestly.** Relays cannot *read* the `fileId`, but hops of one seal are *correlatable* by their
  `sealId`, and hops of one file by public metadata (sizes, participants). They are "not readable", never "not
  correlatable".
- **The completeness oracle is `totalBytes`**, from the message or the seal. Completeness is coverage of
  `[0, totalBytes)` (§8). No chunk-count agreement is ever needed, which is what lets mixed chunkings coexist.

### 3.4 Transfers cover suffixes

Every transfer covers a contiguous byte range **ending at `totalBytes`**. Resume and deposits move only
`byteFrom`:

- the direct push: `byteFrom = 0`;
- a deposit: `byteFrom` is the target's ACKed contiguous prefix, rounded down to a chunk boundary (§6.4);
- a serve: `byteFrom` is the chunk boundary at or below the requester's held contiguous prefix. The few duplicate
  bytes are dropped on arrival; useful data is never rejected over alignment.

A target accepts only shapes that end at the file's `totalBytes`; it can place no other range. A large fetch is
not a transfer and has no shape: it names a byte offset (§6.6).

This invariant keeps the shape to one start offset (no lists, no bitmaps). It also lets a deposit's remainder be
one number.

### 3.5 Chunks follow their OFFER

A transfer's chunks flow only after its OFFER was ACKed by the hop's receiver. The transfer session guarantees
this by construction: chunks are enqueued only after the OFFER's ACK (§6.1). The relay's forward is the same
kind of transfer, so its chunks wait on its own OFFER's ACK.

This is load-bearing. A CHUNK carries no seal and no `fileId`: the receiver maps `transferId → seal` (and so the
key) only through an admitted OFFER, which is its incoming-transfer row (§8).

- A chunk for an unknown `transferId` is never ACKed and never stored, at a target or at a relay.
- Because chunks cannot overtake their OFFER, an unknown `transferId` is never a race: the hop is gone (ended,
  cancelled, expired) or never existed. The chunk is answered **DECLINED** (§3.6), and the sender stops instead of
  retrying into the void for the rest of the TTL.

### 3.6 What a DECLINED means

A DECLINED is read by **what it answers**, never on its own:

- **On an OFFER from the target.** The target refused the file (no space, or a shape it cannot place). This is
  the only refusal: the seal goes DECLINED, and there is no re-offer and no deposit.
    - A large file is never offered unasked, so declining one is local and never on the wire (§8).
    - A target that already holds the file does **not** decline. It ACKs the OFFER with full coverage and sends
      COMPLETE (§8), so "already have it" never reads as a refusal.
- **On an OFFER from a relay.** That relay's storage is full: that relay is out of the round, the round's other
  relays carry on, and nothing re-deposits (§6.4).
- **On a CHUNK**, at a target or a relay: the hop is gone at that end. The sender ends that transfer quietly as
  `ENDED` (outcome unknown), never as a refusal.
    - The receiver drops its hop rows once a hop ends (§8), so a sender whose final ACK was lost learns it this
      way.
    - This is deliberate. Holding rows open to answer late chunks better would be the two generals problem, and
      the pull path makes "probably done" safe.
- **On a forward's OFFER, from the target to a relay.** The relay drops its copy.
- **On a REQUEST.** "I don't hold it, or I won't serve it" (§9): the next candidate is asked.

## 4. Crypto

Everything in this section is the **small path**. A large fetch has no app-layer encryption (see the last
bullet).

- **One seal key per seal** (`SealKey`, ChaCha20-Poly1305), fresh for every push seal and every serve.
- **Chunk content never goes through the Double Ratchet.** A long file would explode ratchet state, `MAX_SKIP`
  bounds out-of-order tolerance, and one lost chunk would break the chain for interleaved messages.
- **The seal rides the standard ratchet** (§3.3): one small frame per seal, however many hops carry it. It is
  safe to decrypt late by the retention invariant.
- **Chunk AEAD binds the byte range, not the transfer.**
    - The key is the seal key, and AAD = (`sealId`, `offset` u64, `length` u32).
    - The target takes the offset from what decrypts, never from the hop's claimed shape. A lying relay therefore
      produces a decrypt failure, not a write at the wrong place.
    - Any chunk that decrypts is placed by its offset and checked against `totalBytes`.
    - This is what lets a relay forward ciphertext in the chunking it received, and lets transfers of one seal use
      different chunkings: ciphertext cannot be re-chunked, and it never needs to be.
- **The IV is random and library-managed.** No nonce derivation is needed: a random 96-bit IV is safe at any
  chunk count a seal reaches, and nothing relies on ciphertext being reproducible. Re-encrypting a chunk after a
  restart yields different bytes for the same range, which nobody compares: the relay keeps the first by its key,
  and the receiver tracks plaintext coverage.
- **Keys are threaded explicitly per call.** The transfer session passes its seal's key to encrypt, and the
  target side passes its incoming seal's key to decrypt; protection never stores a key.
- **A chunk keeps its AEAD whichever link it takes.** A small-path chunk on a WebRTC link is still encrypted: the
  same outbox row may go over Tor on a retry, or be stored at a relay, so its format cannot depend on the link.
- **Encryption and decryption happen in routing**, through protection. The orchestrator only ever supplies and
  sees plaintext and coverage (§6, §8).
- **Only file routing holds `FileProtection`**, one member of the `EnvelopeProtections` bundle that the
  composition root builds and hands the router. It covers sealing and opening seals, sealing and opening
  REQUESTs, and chunk AEAD. No crypto exception leaves protection: failures are verdicts (`PERMANENT` or
  `DEFER`).
- **A large fetch relies on the channel.** Its bytes cross exactly one hop, a WebRTC data channel between the
  two devices.
    - DTLS encrypts the channel, and the DTLS fingerprints are exchanged in signaling that is signed and encrypted
      to the peer device, so the channel is authenticated to that device.
    - No relay ever holds the bytes.
    - An AEAD layer, a key and per-chunk signatures on top would protect nothing more (about 475k signatures for
      100 GB), and end-to-end integrity is the block list either way (§2).
    - This holds only while WebRTC signaling stays signed and sealed (invariant 23).

## 5. Path selection

**Small files mirror the message model.** They go direct over any open link first, and to relays only when the
direct path looks gone. The aim is that a sender whose target went offline can hand the remainder to relays
quickly and close the app, without a slow but working link setting it off. The **gone signals** therefore differ
by phase (§6.3):

- **before the OFFER is ACKed**, the message triggers apply as they are (no link, no ACK within
  `relayBackupAfter`);
- **after it**, only signals that the target is gone: its link closed, no link, or no chunk ACK at all for
  `chunkAckTimeout`.

The file engine owns these triggers; the outbox carries no file logic. FILE control packets (ACK, CANCEL,
COMPLETE) are **not** relay-backed: they are politeness, bounded by the TTL, and the pull path backstops their
loss.

**Relay choice belongs to `RelaySelectionPolicy`, unchanged from messages.**

- It returns whichever relays its reliability math picks for the target (aiming at
  `relayTargetSuccessProbability`, floor `minRelayScore`), up to the `maxRelays` cap.
- The deposit does not choose a count: it opens one transfer to **every relay the policy returns** (§6.4).
- An empty selection means no deposit, and the pull path covers the file.

**Large files are pull-only.** Nothing is pushed.

- The DAG message, which does travel over Tor and relays, tells each receiving device that the file exists, and
  the device fetches it when it decides to (§9).
- The whole exchange happens on one data channel of a WebRTC session that the requester opens (§6.6): the ask,
  the holder's answer and the bytes. The router hands those frames straight to the transport. The large path
  never touches the outbox, the envelope pipeline or the router's ACK/NACK.
- Pull-only removes what a pushed large OFFER would have needed: no OFFER sits processed while a user decides,
  there is no separate accept signal, every device decides for itself, and the side that wants the bytes is the
  side that opens the session.
- The cost is that a holder must be online when the receiver fetches, which WebRTC-only already required.
- With the auto-download limit (§15) above a file's size, the fetch starts as soon as the message lands, so a
  typical ~100 MB file still arrives like a push.

**Composed sends push immediately (small files).** Publishing a message with attachments starts the per-device
pushes alongside the message push; the file does not wait for anyone to ask.

- If an OFFER has not landed within the push window, the REQUEST trigger fires (§9). The push window is
  `filePullDelay`, routing's value, since routing owns both the push and the ask.
- Typical cases: a sibling device that was never targeted, a lost offer, or a message that arrived via sync
  rather than push.
- Push is the latency path and REQUEST is the backstop. The wait is the only thing telling them apart.

## 6. The push machinery (routing layer)

The small path pushes through the outbox (§6.1–§6.5). The large path is a fetch on a dedicated data channel (§6.6). The
two share the plaintext source and the receive engine, and nothing else.

Two components and one engine:

- **The seal registry** (`file/seal/SealRegistry`) owns the origin side:
    - the per-device fan-out of an account-level push;
    - seal creation and the direct transfer;
    - the deposit round;
    - the seal's delivery state (§6.5) and the account-level events and send state.

  It speaks pure router vocabulary, so it lives in **routing**.
- **The transfer engine** (`file/transfer/TransferEngine`) runs every hop this device sends: our direct
  transfers, our deposits, our serves, and relay forwards alike. Each transfer is an `OutgoingTransferSession`
  with its own mailbox and coroutine, so a transfer that hangs (a plaintext read stuck on disk) blocks only
  itself.
    - The engine itself is only three things:
        - the registry;
        - the start-time recovery, which rebuilds sessions from the transfer rows plus the outbox and never re-sends
          what is still queued;
        - a mux that posts ACKs, CANCELs, outbox outcomes and link-downs into session mailboxes.
    - What a transfer is *for* is its owner's business. The engine reports `TransferEvent`s (progress, gone,
      ended) and never decides about seals, deposits or relays.

**What a transfer sends comes from its `ChunkSource`:**

- `SealChunkSource` serves our own seals. The orchestrator implements `FilePlaintextSource`, a port routing
  declares: plaintext from any byte offset (the stored copy or the referenced original, §10), each block checked
  against the block list as it is read (§9). Routing cuts the plaintext into the transfer's chunks and encrypts
  each under the seal key. The orchestrator never sees a key.
- `RelayChunkSource` serves a relay forward: the seal bytes and the chunk ciphertext the relay stored, exactly as
  they arrived (§7). Nothing is encrypted again.

The relay is simply another sender whose "file" is a DB table. The same `FilePlaintextSource` feeds the large
fetch (§6.6).

### 6.1 The transfer: OFFER, then chunks

- **The OFFER is an ordinary outbox entry**, tagged `OutboxTag.FileTransfer(transferId)`, and
  with standard semantics: the delivery contract's "ACK = processed" is untouched. Its `expiresAt` is the seal's,
  at every hop. The outbox honours it natively through its expiry prune.
- **On the OFFER's ACK the transfer streams** (`OFFERED → STREAMING`). The receiver's ACK may already carry
  coverage (a target that holds a prefix answers with it), so the sender skips what is held.
- **Chunks are individual outbox rows** with the same tag. Per-chunk retry, dedup and restart survival come for
  free.
    - A window (`maxInFlightChunks`) bounds pending rows. Each row holds a ~249 KiB blob, so a window of 16 is about
      3.5 MB of outbox per transfer.
    - `file_transfer_chunks` maps each queued packet to its chunk index, so a restart rebuilds the window from the
      rows still queued instead of re-encrypting.
- **A hop is DONE when its receiver holds every chunk of it**: every chunk ACKed, or one coverage ACK that covers
  the whole shape. There is no per-hop COMPLETE.
- **How a transfer ends:**
    - a DECLINED on the OFFER → `DECLINED` (§3.6);
    - a DECLINED on a chunk, or any other terminal NACK (the seal could not be opened, an older build) → `ENDED`.
      Nothing is re-offered by the engine; the receiver's REQUEST (§9) is the recovery, with a fresh seal;
    - the seal's expiry → `EXPIRED`;
    - a cancel by its owner → `CANCELLED`.
- **Every end passes one place** (`TransferEngine.finish`): the row takes its status, its queued packets and
  chunk rows go, and an `Ended` event is reported.
- **A cancel is a post to the session**, so a hung transfer cannot stall its canceller. The session sends the
  CANCEL and ends itself shortly after. Rows are deleted by the transfer's owner only once no transfer of them is
  live.
- **The session tick is a fixed cadence**, three ticks per `fileSweepInterval`: a ticker feeds a conflated channel
  that the loop selects ahead of the mailbox. A steady stream of ACKs and outcomes therefore cannot starve expiry,
  the lost-OFFER re-arm or the gone checks.

The OFFER is establishment-only: it is never held open until completion. Holding it open would need a "processed
but don't ACK yet" inbound result, would fight every message-scale outbox assumption, and would duplicate state
the transfer already owns (§17).

### 6.2 The outbox under chunk traffic

The outbox was built for message-sized rows. Three things changed so it can carry chunks:

- **The due query loads no blobs.** It returns ids, targets, tags and retry classes, ordered and limited, and a
  row's envelope is loaded only when the row is claimed. Targets already at their per-target in-flight cap are
  hidden from the due query, so one capped peer never starves the rest.
- **Messages go first.** Due FILE rows are claimed after everything else, so two chunks never hold both of a
  peer's slots while a text message waits. On a Tor link a chunk takes seconds to write.
- **Only chunk rows retry flat** (`retry_class`). The message policy drops to `standbyRetryDelay` (1 h) after
  `fastRetryBudget` (3) attempts. A chunk answered with a silent DEFER (the receiver's staging bound, §8) would
  burn through that in about three minutes and then sleep for an hour. Chunk rows instead retry at a flat
  `fileChunkRetryDelay` for as long as their transfer lives; the transfer's TTL and the gone signals bound them,
  not an attempt count. OFFERs and REQUESTs keep the message schedule.

`outboxMaxInFlightPerTarget` stays at 2. The ordering guarantee (messages before chunks) is covered by tests, but
no real-network chunk stress run has set a higher number yet, so no FILE-only cap exists. Revisit once multi-MB
transfers run over real Tor and WebRTC links.

### 6.3 Gone signals

A transfer to the seal's target reports **gone**, and its seal deposits the remainder (§6.4), on signals that
mean "the target is gone" and never "the link is slow":

- **Before the OFFER is ACKed** (the message triggers): no link to the target, or no ACK within
  `relayBackupAfter`. The target may never have been reachable.
- **After it:**
    - the link to the target closes, which is the router's own link event;
    - no link at all while chunks are in flight;
    - no chunk ACK at all for `chunkAckTimeout` while chunks are in flight. This is the one that matters on Tor,
      where a dead link often gives no close event; the worst case is one timeout.

A single chunk that is slow to be ACKed, or a single chunk retry, is **not** a signal. On a slow circuit it would
send the whole remainder to every selected relay, for every device, exactly when traffic should not multiply. As
long as any chunk was ACKed within the timeout, the direct path is working.

The sender still has to stay up while the deposit uploads. What the signals buy is that the deposit starts within
one timeout of the target disappearing. Deposit-on-gone is **insurance, not restart-survival**: the DB state (seal
and transfer rows, outbox rows) resumes on boot regardless. What the deposit buys is that the already-pushed
prefix never again needs the sender and the target online at the same time.

A large fetch has no gone signal on the holder's side: it is bounded by its channel and by the receiver's gap
trigger (§6.6).

### 6.4 The deposit round: one jump, parallel redundancy

- **The remainder.** When the direct transfer reports gone, the seal deposits what remains after the target's
  ACKed contiguous prefix, rounded down to a chunk boundary (§3.4). This is safe by construction: any ACK holes
  are inside the remainder by definition. It may re-send a few chunks the target already got, which the receiver
  drops as duplicates.
- **One deposit round per seal**, claimed durably on the seal row (`deposited_at`). Once is enough because
  transfers cover suffixes: a later stall's remainder is a subset of the first round's.
    - Relays are picked *before* the claim, so an empty selection never burns the round.
    - The direct path keeps going meanwhile; the deposit is the insurance.
- **Parallel redundancy, never sequential.** The round opens one transfer to **every relay the policy returns**
  (§5), the target itself excluded. Each carries the same seal and the same remainder, as its own transfer with
  its own `transferId`.
    - The seal is the same bytes everywhere. The target opens it once and binds every later hop to the stored row
      (§3.3), so redundant copies never trip the ratchet.
    - If one relay's deposit stalls, that is the outbox's business: chunk rows retry at their own cadence, and the
      relay's idle TTL (§7.2) cleans up what never lands.
    - **The sender never chases a failing relay** by depositing its missing parts elsewhere.
- **One jump, ever.** Deposits happen only at the seal's origin (a serve is an origin too), and a forwarding relay
  never deposits (§7). File ciphertext therefore makes at most one relay hop: origin → relay → target.
- If every deposit and the direct path die, the pull path (§9) is the backstop. That is its job.

### 6.5 Completion: the seal's state

The origin cannot see what a relay does with its copy, and the origin and the target may never be online
together. Completion follows the delivery model ([`../guide.md`](../guide.md) §6): an acknowledgement proves
delivery, and its absence proves nothing.

The seal registry derives each seal's state from its transfers:

| Seal state   | When                                                                                                                                                   | What happens                                                                                                                                             |
|--------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SENDING`    | the direct transfer is live                                                                                                                            | —                                                                                                                                                        |
| `DELIVERED`  | the direct transfer is DONE (the target ACKed every chunk as durably staged, which is delivery as the message model defines it), or a COMPLETE arrived | every remaining live transfer (deposits included) is cancelled, so relays free their quota                                                               |
| `DECLINED`   | the target refused the OFFER                                                                                                                           | final for that device                                                                                                                                    |
| `HANDED_OFF` | one deposit is DONE at its relay. Deposits start at the target's prefix, so any one whole relay copy covers the file                                   | the origin **stops pushing directly**: the relays deliver, and repeating it directly would only duplicate traffic on the same reconnect                  |
| `ENDED`      | every transfer ended otherwise                                                                                                                         | the pull path recovers                                                                                                                                   |
| `EXPIRED`    | the seal's TTL passed                                                                                                                                  | its live transfers are cancelled; the seal and its rows go on the first sweep that finds none of them live, so no row is removed under a running session |

The seal row stays until it expires, so a stray coverage ACK or COMPLETE still resolves.

**User cancel.** Every live push seal of the file to the account's devices ends, with its transfers, as
CANCELLED. Cancel is a plan-time decision: a push that has planned but not yet landed its seal row still lands and
may deliver. A user cancel can simply be repeated.

**Account level.** The orchestrator sees one `FileSendState` per (file, account), aggregated over that account's
device seals:

- delivered to any device → `COMPLETED`;
- otherwise `SENDING` while any seal is sending;
- `HANDED_OFF` when the rest are handed off;
- a failure status only when every seal ended without either.

Progress is the least any device has covered. The GUI shows "sent" at hand-off and "delivered" only when it knows.
There is no failure state while the pull path exists.

**Concurrency.** The registry's mutex only plans: what needs a seal, which transfers to cancel, which relays a
deposit goes to. The expensive part runs outside it, in guarded launches, one per device or relay, joined before
the entry point returns: crypto, store inserts and `TransferEngine.start`. A multi-device push therefore never
blocks serves, COMPLETEs and settles behind it.

An in-memory in-flight lease per (file, device) stops two overlapping operations from both planning "no live
seal" before either lands. A placeholder row would drag the store back into the critical section the split exists
to empty.

### 6.6 The large path (pull over WebRTC)

A large file moves the way files normally move over WebRTC. The libraries have no file-transfer function: a data
channel is a reliable, ordered message stream, and every application sends a small header, then binary frames,
pausing on the channel's buffered amount. The large path is exactly that and no more.

- There is no envelope, no seal, no `transferId`, no key, no OFFER and no router ACK.
- The router passes the frames straight between the transport's channel and the file code, the same kind of
  direct path AV will use.
- **One pull is one data channel** between the requesting device and one holder.

1. **The requester opens the session.** Its pull processor (§9) asks for a WebRTC session with the candidate
   through the router's budgeted link await (`LinkKeeper.await`, signaled over Tor as usual).
    - Sends never open connections; a large pull does, because the requesting device is the one with the user's
      intent.
    - No session within `largeFileSessionOpenTimeout` means that candidate is unreachable, and the next one is
      asked.
2. **The requester opens a file channel** on that peer connection and sends `PULL { fileId, fromOffset }`, where
   `fromOffset` is its checkpointed P (§8).
    - Either side may add a data channel, so no renegotiation is needed.
    - A dedicated channel keeps a 100 GB stream from starving messages and ACKs on the envelope channel.
3. **The holder answers once.**
    - Before the gate runs, the PULL claims its channel: a repeated PULL on the same channel is ignored, and one peer
      holds at most `maxLargeFileServesPerPeer` serves; beyond that it is told to come back later, never declined.
    - The holder then runs the serve gate for the session's peer device (`LargeFileAdmissionGate`, §9, which also
      rejects removed or revoked devices).
    - It replies `PULL_OK { totalBytes, block list, fileName, mimeType }`, or `DECLINED` (not held, or not allowed)
      and closes the channel.
    - The requester checks the block list against the `fileId` (§2) before it accepts a byte. A list that does not
      match closes the channel and counts that holder as declined; this is the only holder-side decline. A local
      refusal (no space, an ended receive) never counts against the holder.
4. **The holder sends `DATA` frames**: raw plaintext, in order, from `fromOffset` to the end, each frame up to the
   transport's frame limit (`largeFileFrameBytes`).
    - It reads through a `FilePlaintextSource` stream (§11), so every block is checked against the block list
      before its bytes go out. A pull that starts mid-block reads from that block's start and verifies the whole
      block first.
    - A frame's offset is implied by its order; nothing is numbered.
5. **The requester appends at P through its own port** (`FileIngestPort.append`, not the small path's staging
   `ingest`).
    - Frames are written without an fsync. A checkpoint (§8) fsyncs, verifies the blocks P passes and commits P.
      The requester sends `PROGRESS { P }` only when P moves.
    - A failed write or verify leaves P where it was: the requester ABORTs, and the next attempt resumes from P.
    - `Defer` (the engine is still recovering) retries the same bytes until the channel goes; no frame is dropped.
    - `NoSpace` (the disk filled after the reservation) declines the receive locally and ends the request without
      blacklisting, as does a receive that ended meanwhile.
    - `Mismatch` ABORTs and re-asks in the usual candidate order.
6. **The end.** The last block verifies → READY → the requester closes the channel.
    - Either side may send `ABORT { reason }` and close: a bad block at the source, no space, the gap trigger, a
      user cancel.
    - A pull lives exactly as long as its channel. `abortLargeFile` ends only the requester for that file, never the
      node's serves of it.

**Frames.** Each channel message is one frame: a type byte, then the body.

- `PULL`, `PULL_OK`, `DECLINED`, `PROGRESS` and `ABORT` have a small codec in `protocol` (`LargeFileFrame`),
  separate from the `FileHeader` kinds. `DATA` is the type byte followed by raw bytes.
- The `fileId` appears in `PULL` in the clear inside the channel: DTLS covers it, and only the peer device ever
  sees the channel (§4).
- One pull runs on one data channel per file, so concurrent pulls to one peer demultiplex.
- The transport mints the channel handle; routing never builds label strings.
- File channels are always reliable (ordered, no retransmit or lifetime limit), whatever the shared data-channel
  config says.

**A credit window.** Two limits hold the holder back, and both are needed:

- the holder may start a frame at most `largeFileStreamWindowBytes` (32 MB) past the last `PROGRESS`. The requester
  closes a channel whose holder starts one further out, since its frames would queue without bound;
- the holder also waits on `awaitBufferedBelow` (resume at or below `largeFileBufferedResumeBytes`) instead of
  polling a threshold or events.

The channel's buffer only protects the sender, and the backend receives through an `onMessage` callback with no
receive-side flow control. Without the window, a receiver with a slow disk would pile frames up in memory, and
blocking the callback instead would stall the envelope channel on the same connection.

The backend delivers each data channel's messages in order: one per-channel queue drained by one coroutine, no
per-message launch. A close lets the queue drain before the channel is reported gone. File frames have their own
flow and buffer, so a stalled file consumer never delays envelopes. A remotely opened file channel that is not
ordered and reliable is refused.

**Who holds what.** `LargeFileChannels` keeps the channel maps and serve claims, shared by:

- `LargeFileRequester`, which opens the channel, checks `PULL_OK`, appends at P and reports PROGRESS;
- `LargeFileHolder`, which gates, answers and streams;
- `LargeFileMux`, which dispatches frames and channel events.

Requester state and holder state never mix. A pull runs its session loop as a child of the pull call, and a serve
runs on the coroutine the mux launched for its PULL, so nothing needs a stored scope.

**No staging, no routing rows.** One ordered source lands every byte at P, so a pull never stages. Neither end has
a row for it in routing: the holder's state (offset, window) and the requester's (the channel) live in memory and
die with the channel. The durable state is what already existed:

- the requester's receive row and request row (orchestrator);
- its pull attempts (§9).

A device runs one pull per file at a time (one candidate at a time, §9).

**A pull lives exactly as long as its channel.**

- When the session or the channel drops, both ends forget the pull, and the request goes back to PENDING (§9).
- The next pull names the checkpointed P as its `fromOffset`, and the holder starts there with a positioned read (§11).
  A restart on either side is the same case.
- There is no waiting-for-session state and nothing to resume but P.
- The holder reaps a requester that stops making progress: no real PROGRESS for one pull attempt's budget
  (`filePullAttemptTimeout`). A requester repeating its P cannot pin the holder's stream and channel forever.
- A pull that stalls while its session lives is ended by the receiver's gap trigger (`fileStallTimeout`, §8) with
  an `ABORT`, and re-requested the same way.

The small path's timers do not apply: there is no push window (nothing is pushed), no `chunkAckTimeout` and no
deposit.

**When no session can be made.** WebRTC here has STUN only and no relay server, so two devices behind unfriendly
NATs may never connect.

- A candidate that is online (its Tor link is up) but whose session failed within `largeFileSessionOpenTimeout`
  is recorded as **no direct connection**, distinct from offline.
- The GUI can then show "can't connect directly to any device that has this file" instead of waiting silently.
- Bytes still never fall back to Tor (§17). A relay server for such pairs is deferred (§18).

## 7. The relay

The relay is **dumb to content and strict about its storage contract**.

- It cannot read chunks: it has no key.
- It cannot verify content: the `fileId` hash is over plaintext, so count-based wholeness is the ceiling, by
  design, and nothing smarter helps any actor.
- It never learns the `fileId`, and it is not a REQUEST candidate (§9).

**A relay hop is an incoming transfer like any other.** Every hop delivering to a device is one
`file_incoming_transfers` row, received the same way: the same re-sent-OFFER rule, the same shared admission, the
same chunk checks (§8). Only what happens to it differs, and that is its `TransferSink`'s business:

- `TargetSide` handles a hop whose seal's `finalTarget` is us;
- `RelayDoor` handles any other hop.

The relay's own rows hold only what it cannot open: `file_relay_seals` (the seal bytes) and `file_relay_chunks`
(each chunk's ciphertext). Both cascade with the hop. Wholeness, the reservation and the idle clock are derived from
those rows, not stored. Its storage is DB tables, not a files directory: transactional admission, eviction and
crash recovery come for free at ≤10 MB per hop.

### 7.1 Admission at the door

Every envelope's hop signature is verified on the inbound path before dedup, for every family. A forged or
re-signed packet never reaches the door.

On top of the shared admission (an acceptable shape, at or below our `smallFileMaxBytes`, a seal of the allowed
size), the relay admits a hop only when:

- **it comes from the seal's origin**, meaning the hop's sender is the OFFER's `origin`. This makes it one hop,
  ever: a relay never accepts from a relay;
- the origin is a known, non-banned device;
- the final target is a known device;
- **it fits the budget**: the hop's reservation (seal bytes, the shape's bytes and per-chunk overhead) fits the
  relay's share of the storage pledge (§15), evicting relay hops least recently fed first.

Anything else is DECLINED, so the depositor learns at once.

`MIN_FILE_CHUNK_SIZE_BYTES` is what makes the per-chunk rows safe. A hop's chunk size is chosen by its sender, and
every accepted chunk costs a stored row, so the floor bounds storage amplification: a 10 MiB deposit is 160 rows,
never ten million.

### 7.2 Lifecycle

1. **Admit.** The hop row and its seal bytes are written in one transaction with any eviction.
2. **Hold.** Each chunk lands in `file_relay_chunks`, keyed by (`transferId`, `chunkIndex`), so a re-sent chunk is a
   no-op. The door answers each chunk with a coverage ACK built from the chunks stored so far, so the depositor's
   window advances.
3. **Whole.** Every chunk of the hop is stored. **A partial hop is never forwarded**: the relay stays silent, and
   the **idle TTL** (`fileRelayIdleTtl`, counted from the last stored chunk) drops what the origin never finished.
   The TTL is load-bearing for budget correctness, not hygiene.
4. **Forward.** Once whole, the relay starts one forward: an ordinary outgoing transfer (§6.1) to the final target.
    - It has a new `transferId`, the same seal bytes, the same shape, the relay's own hop signature and the seal's
      `expiresAt`.
    - Its chunks come from `RelayChunkSource`, and the forward names its source hop
      (`file_transfers_out.source_transfer_id`).
    - Its OFFER waits for the target's ACK like any OFFER, and gates its chunks.
    - **No chaining**: a forward never deposits elsewhere.
    - A target that declines the forwarded OFFER (no space) ends the forward, and the relay drops its copy.
5. **Forget.** However the forward ends, the relay forgets the hop: the forward rows go, then the hop row, and the
   stored ciphertext with it.
    - A finished forward is delivered. Anything else is the pull path's to recover.
    - The ciphertext is never deleted under a running forward, which reads its chunks from it: a live forward is
      cancelled first, and its end drops the hop.
6. **Evict.** Whole-hop LRU over the relay's hops, least recently fed first, never a single chunk (evicting chunk 37
   of 46 would silently kill the transfer).
    - Hops that are still filling are not exempt: evicting a mid-deposit hop is allowed, and the depositor learns
      through a DECLINED on its next chunk.
    - An evicted hop with a live forward cancels the forward first.
7. **Expire.** At the seal's expiry the relay drops the hop. The target's expiry sweep leaves relay hops alone, since
   they end with their forward.

**Invariant: anything a relay forwards is whole.** A forward that stalls is therefore always a link problem (the
forwarder retries from its complete store), never a content problem. The receiver needs no stall detection on relay
forwards.

**Restart recovery is free.** The forward's state is entirely outbox rows plus store rows, all in the DB and all
recovered at boot. The relay's sweep starts a forward for a whole hop that has none, and forgets a hop whose forward
ended while nobody listened.

## 8. The receiver

One engine serves both classes. The small/large distinction is send-side path policy (§5); the receiver never
branches on it.

**Three grains at the receiver** (a large fetch has only the last):

- **Incoming seals** (routing, `sealId`-grain): every seal opened here, written **the moment it opens**: the origin,
  the `fileId`, the opened seal (its key included) and its expiry. The row lives until the seal expires, so a late
  relay forward is recognised by its `sealId` without decrypting it again (§3.3).
- **Incoming transfers** (routing, `transferId`-grain): every hop delivering to us, with its sender, its shape, its
  seal and its `finalTarget`. A target's rows end with their hop: on READY, on a CANCEL either way, or at expiry. A
  later chunk for that `transferId` is unknown and gets DECLINED (§3.5); the row is not kept around to answer it
  better.
- **The receive row** (orchestrator, `fileId`-grain): coverage, the `.part`, status.

Routing decrypts each chunk with its seal's key and hands `(fileId, byteOffset, plaintext)` to the receive engine
through the synchronous ingest port. The receive row holds no key, and the receive engine is transfer-agnostic and
**chunking-agnostic**.

**The inbound face** (`FileInboundHandler`) does everything every hop shares, once, before the hop's sink sees it:

- **OFFER**: a re-sent OFFER for a known `transferId` is answered for its sender alone. A new one passes the shared
  admission: an acceptable shape, `byteTo` at or below *our* `smallFileMaxBytes` (a large file is pulled, never
  pushed), a seal of the allowed size. This all happens before the seal is opened, so a refused OFFER never spends
  a ratchet message.
- **CHUNK**: it must come from the hop's sender, inside its shape, exactly the length its index allows. A chunk for no
  live hop is DECLINED.
- **CANCEL** from a hop's sender ends that hop at its sink.

**At the target** (`TargetSide`):

- **An OFFER with a known `sealId`** binds to the stored seal without decrypting it. A stored seal of that id that came
  from a different origin is refused.
- **An OFFER with a new `sealId`** is opened with the session of its `origin`, and the seal row is written at once,
  before the receive engine is asked. A `Replay` with no row is the crash edge: the hop is NACKed PERMANENT, and
  REQUEST brings the file back (§3.3).
- **The shape must end at the seal's `totalBytes`**; the target can place no other range.
- **The receive engine's verdict:**
    - **Accept** records the hop and answers with the coverage already held, so the sender skips it;
    - **AlreadyReady** ACKs the whole shape (which ends that hop) and sends COMPLETE to the seal's origin;
    - **Refuse** is DECLINED;
    - **Defer** is a silent DEFER: the sender redelivers, and the stored seal row makes that redelivery free.

**Byte coverage is the whole model.**

- A chunk covers its byte range, and the bytes at any offset are identical across every hop of the `fileId` (the
  `fileId` is the hash of the whole file).
- Chunks from any number of hops, seals and chunkings land their byte ranges. Fully covered ranges are duplicates and
  are dropped; duplicates are normal traffic (the direct path, several relay forwards and serves all overlap).
- **Nothing is rejected for disagreeing with another transfer.** Every hop is a potential accelerator, and two
  chunkings of the same file can finish it together.

**Durable-before-ACK, checkpointed.**

- The durable state is one contiguous prefix P (bytes `[0, P)` in the `.part`, append-only), plus staged ranges
  beyond it.
- **The persisted `contiguous_bytes` is the source of truth for P, never the file length.**
- P advances only at **checkpoints**: every `fileCheckpointBytes` (8 MB), every `fileCheckpointInterval` (2 s), and at
  completion. A checkpoint appends what is drainable, fsyncs the `.part` once, then advances `contiguous_bytes` and
  deletes the drained staged rows in one DB transaction.
- That is one fsync per checkpoint instead of one per chunk: at 50 MB/s, the difference between ~230 fsyncs a second
  and one.

The same rule runs for both classes:

- **Small path (outbox chunks).** A chunk that extends coverage is decrypted and written to staging in one DB
  transaction, and only then ACKed. The chunk ACK means "decrypted and durably recorded", which is the delivery
  contract's ACK = processed, with "processed" defined per payload kind. The handoff from routing to the receive engine
  is a synchronous port call for exactly this reason. The drain into the `.part` waits for the checkpoint.
- **Pull (large path).** Frames arrive in order and append at P without staging. Nothing is reported between
  checkpoints, and `PROGRESS` names the new P (§6.6). A crash loses at most one checkpoint of bytes, which the next pull
  re-sends.

**Staging is bounded** (`maxStagedBytes`, at least one checkpoint and at least one transfer's chunk window).

- Beyond the bound, a chunk that cannot extend P is answered DEFER: silent and dedup-cleared.
- The sender's window closes, and its retry resumes once P advances (chunk rows retry at a flat delay for exactly this,
  §6.2).
- Without the bound, a racing hop could stage an unbounded suffix behind a stuck gap.

**Blocks are verified as P passes them.**

- The first seal's block list is checked against the `fileId` (§2) and stored on the receive row. An OFFER whose list
  does not hash to the `fileId` is refused.
- The engine keeps a running SHA-256 of the block containing P, and **P is never committed past the end of an
  unverified block**: every block below P has matched its hash.
- After a restart only the partial block at P is re-hashed from the `.part`.

**Reserve before accepting.** The engine reserves `<fileId>.part` at `totalBytes` with an **explicit free-space
check**. Preallocation is sparse on most filesystems, so it stats the volume, not the file.

- Insufficient space → the OFFER is refused and the receive goes DECLINED.
- A mid-write ENOSPC → a clean failure, and the live hops are CANCELled.

**Accepting is local.**

- Small files auto-accept.
- A large file is pulled only when the device decides to: automatically at or below its `autoDownloadMaxBytes` (§15),
  otherwise on a user click. Declining a large file means never pulling it: the receive row goes DECLINED locally, and
  nothing goes on the wire.
- Each device decides on its own (no to 100 GB on the phone, yes on the PC), and a user re-open pulls it later.

**Completeness → READY.**

- Coverage of `[0, totalBytes)` with the last block verified → **atomic rename → status READY**.
- There is no final pass, since every block was verified on the way. A file is never READY with an unverified block.
- On READY the orchestrator ends the incoming side through `FileCarrier.endIncoming`:
    - every live incoming hop's sender gets an ACK covering everything, which ends that hop;
    - **every seal's origin gets a COMPLETE**, which makes its seal DELIVERED (§6.5) and cancels its deposits.

  Both are politeness, bounded by the TTL.

**A file nobody references is not kept.** A small OFFER is accepted before, or without, its message, so a device can be
handed files no message mentions. A receive row whose `origin_message_id` is still empty after
`unreferencedFileRetention` is deleted, with its `.part` or finished file. v1 has no bare transfers (§10), so a
legitimate file always gets its message within that time.

**A hash mismatch costs one block, not the file.**

- The bad block is always the one ending at P's next commit, so the append-only writer can handle it: the `.part` is
  truncated to the block's start, and the block's staged rows are dropped.
- The file goes back to re-requestable (STALLED) with a mismatch count.
- The next REQUEST, with coverage ending at the bad block, follows the usual candidate order. Every holder checks blocks
  as it reads (§9), so a rotten copy stops at its source whichever device is asked.
- After `fileHashRetryLimit` mismatches the file is FAILED, and every live incoming hop is CANCELled.

**Progress ACKs** (coverage in the hop's own chunk indices, capped, §3.2) flow back to each hop's sender.

- Chunk `k` is covered iff its whole range is below P, or P is the total.
- A duplicate chunk is answered with the same coverage ACK, since the sender's window only advances on ACKs.

**The gap trigger.** If P does not advance for `fileStallTimeout` (minutes, deliberately beyond the sender's
`chunkAckTimeout`, so the push machinery acts first):

- the file goes STALLED: re-requestable, not refused;
- the durable coverage is kept;
- the REQUEST path re-opens with the held coverage (§9).

**It cancels nothing.** The live incoming hops stay: byte coverage merges whatever arrives from whichever source, a hop
that is really dead ends by its TTL, and a serve from a device that was already pushing supersedes that push at the
serving device (§9). Cancelling would throw away the one copy that needs no holder online. A receiver that was offline
for a few minutes while a relay was forwarding would come back, cancel, answer the relay's next chunk DECLINED and make
it drop a whole copy: the opposite of §7's "the receiver needs no stall detection on relay forwards".

The timer counts only while this device is running, so waking from sleep does not fire it at once. On a large pull,
where a dead source cannot be superseded, the trigger ends the pull with an `ABORT` (§6.6). TTL expiry is the passive
backstop; the trigger is the active path.

**Restart.**

- P is the persisted `contiguous_bytes`. At open the `.part` is truncated to it: a torn or uncommitted append is cut
  away, and the staged rows it came from are still there to drain again.
- The partial block at P is re-hashed.
- Staged ranges rehydrate from their table, and missing is the complement of durable coverage.
- What was ACKed was durable, so nothing ACKed is ever lost.

## 9. The pull path: REQUEST

The recovery mechanism for everything the push path failed to deliver, and the only way history-acquired files get
their bytes.

**The trigger** (the orchestrator's inbound pipeline, on message ingest; the message may have arrived by push or by
sync): a message references a `fileId` with no completed local file and no receive in progress. Guards:

- **Want-now vs wait-for-permission is the orchestrator's only size decision.** At or below `autoDownloadMaxBytes` the
  trigger fires for every file, small ones included; above it, only a user click does (§8). Beyond that one gate the
  orchestrator is size-blind: it never branches on the network's small-file boundary (§1).
- **When the first attempt runs is routing's call**, and the seam carries only the trigger's origin
  (`startFilePull(fileId, immediate = false)`).
    - Automatic triggers omit the flag. Routing pins the class by the file's total bytes and schedules:
        - small files wait `filePullDelay`. The direct OFFER may be in flight, or may never come (the sibling case, §2);
          the wait is the only thing telling "late" and "never" apart, and it errs on the quiet side;
        - large files start at once: nothing is ever pushed, so there is nothing to wait for.
    - Explicit triggers (a user's accept, a fresh stall) set the flag for now, at any size. The flag expresses the
      trigger's origin, never a size.
    - The schedule lives in routing's attempt row. The orchestrator's pending rows carry only the accept/decline state,
      just as the sync coordinator's rows never schedule (routing's `sync_attempts` owns that).
- **STALLED is re-requestable**: something broke, so try again (a hash mismatch below the retry limit lands here too,
  §8). A stalled file re-asks once, not on every sweep, so routing's backoff holds.
- **DECLINED, CANCELLED and FAILED are refused.** A file the user declined, or one that exhausted its retries, is never
  auto-resurrected; it needs a user re-open.
- **Dedup by `fileId`**: message duplicates create one row.

**One candidate at a time, never parallel.** Parallel asks would make N devices each serve the entire file, which is the
spam/DDoS shape. Candidates are:

- the sender's devices;
- then own-account siblings (room members later);
- filtered by link availability, like sync.

**The small attempt is sync-shaped.** The REQUEST is ACKed ("I hold it, serving") or NACKed DECLINED ("I don't, or I
won't"), and **the OFFER is the reply**, exactly like a sync request's ACK-then-reply.

- On ACK the request waits (WAITING) for the OFFER, with `fileRequestWaitTimeout`.
- On timeout or DECLINED, the next candidate is asked at once.
- When every candidate has declined, the file is "unavailable": terminal until a new sync or a user re-open retries.
- The small REQUEST is **ratchet-sealed** (it carries the `fileId`) and **direct-only** (§3.2). It carries **held
  coverage** in bytes (`contiguousBytes` plus missing ranges, capped). Heavy fragmentation degrades to contiguous-only:
  a coarser serve with more duplicates, still correct.

**A large attempt is a pull, not a REQUEST envelope** (§6.6).

- The same loop picks the candidate. The attempt then opens a session and a file channel instead of enqueueing a
  packet: `PULL_OK` plays the ACK and `DECLINED` the decline.
- There is no WAITING state, because the answer and the bytes come on the same channel.
- **A candidate that cannot be reached is not a decline.** When every remaining candidate is merely unreachable, the
  request stays PENDING and retries when one comes online; for WebRTC-only bytes, offline holders are the normal case.
- A candidate that is online but cannot be connected to is recorded as "no direct connection", so the GUI can say so
  (§6.6).
- A pull that ends with its session sends the request back to PENDING, asking the same candidate first.

**The serve gate, the same logic as sync's.** A holder serves only when the requesting account may see a message that
references the `fileId`.

- The orchestrator's `message_attachments` index (filled at DAG ingest) maps the `fileId` to its messages, and the sync
  serve gate decides visibility of those messages.
- No visible referencing message → DECLINED. Without the gate, anyone who has or guesses a file's content could pull it
  from any holder by its hash.
- A large pull passes the same gate before `PULL_OK`, with the requesting account being the account of the session's
  peer device.
- A message that references a file shares it with everyone who can see that message, its author included. This is
  intended.

**Holders check blocks as they read.** AEAD only protects bytes in transit. A copy that rotted on disk, or a referenced
original the user edited (§10), would be encrypted faithfully and fail only at the requester.

- So the plaintext source hashes each block it reads and compares it with the block list before the block's bytes go
  out. A serve that starts mid-block reads from that block's start.
- There is no full re-hash before a serve, which at 100 GB would take minutes every time.
- A mismatch or a missing file ends the transfer with a CANCEL (a pull with an `ABORT`). The holder then treats the file
  as not held (a reference becomes SOURCE_LOST, §10) and DECLINEs later REQUESTs for it.

**A small serve is a fresh seal.**

- The serving device can be any holder: the original sender, or a sibling that completed the file.
- It makes a **new seal** for the requesting device: a new seal key, a new ratchet frame, never re-offered seal bytes.
  The requester may hold a seal it can no longer open (the crash edge, §3.3), and a fresh seal costs one ratchet frame
  and never loops.
- It opens a transfer from the chunk boundary at or below the requester's held contiguous prefix (§3.4). Mixed
  chunkings are correct, because the receiver is chunking-agnostic.
- The serve's ACK means the seal exists and its transfer is queued. That is why the serve is made on the inbound path:
  the work is local crypto and two inserts, and a seal that cannot be made must answer DECLINED at once.

**One live transfer per (`fileId`, target device) at a serving device.**

- A REQUEST that reaches a device already pushing that file to the requester (a late OFFER after the push window, a
  stalled push) supersedes it: the old transfer is cancelled, and the serve starts fresh from the held coverage.
- A REQUEST repeated within one attempt is answered by the serve already under way.
- A REQUEST that arrives while a seal for the same pair is still being made is declined; that seal covers the device.
- There are never two parallel transfers of one file from one device to one device.

**A shared core with two executors** (`routing/candidate`) runs the candidate-asking loop:

- sync and small file requests queue outbox attempts;
- large-file pulls run as bounded concurrent calls (`maxConcurrentLargeFiles` at a time).

Each request class has its own core instance over its own due query (`SMALL` vs `LARGE` rows). Each is fed by a
**store-owned candidate view**: the attempt store hands each core a `CandidateLedger`, the parent row plus its
declined children read in one statement, composed by the store that owns the tables. No consumer assembles the join.

- A satisfied id is dropped before any candidate is picked.
- A small REQUEST's ACK parks the row in WAITING for its OFFER; expiry re-asks the next candidate, with the silent
  holder
  tried last.
- An unreachable candidate is never a decline.

**Relays are not candidates.** They are transient push caches with forget-after-delivery semantics.

## 10. Storage and retention

**Sender and holder: the GUI picks, the core does the rest.** The GUI owns only the platform file chooser and a **"keep
a copy" checkbox**. The checkbox default is the GUI's own preference, not core config, and the orchestrator's send API
requires the choice:

- **Copy.** The file is copied into **app-internal storage** before anything is sent, and hashed in the same pass. The
  source is then immutable across restarts, and a picture survives the user clearing their downloads folder. A few MB
  of extra space is worth that for memes and photos.
- **Reference.** The descriptor records the original's path (on Android, a persistable URI permission), and the file is
  hashed in place. Nothing is duplicated, which matters at 100 GB.
    - The store does not own it: it is outside the quota and never deleted by retention.
    - If the original is moved, deleted or edited, the block check while serving catches it (§9), and the descriptor
      goes
      SOURCE_LOST. This device stops serving it, the message still shows the attachment, and other holders may still
      serve it.

Either way the orchestrator owns the store's directory layout, retention and quota, **vending paths and metadata to the
GUI** for rendering; the GUI never writes into the store. Hashing 100 GB takes minutes, so the message is published once
the `fileId` is known, and the GUI shows the attachment as preparing until then.

**Keep forever** is the default policy. Serverlessness forces it: the bytes are on a device or nowhere, and there is no
lazy-loading when a user scrolls back a year.

- The knob is a per-account quota with LRU above it.
- A file the quota evicts goes `EVICTED`: its descriptor is removed, and this device stops serving it. The message still
  shows the attachment.
- An evicted file is fetched again only when the user opens it, never automatically; otherwise the quota would evict
  and re-fetch in a loop.

Other storage:

- **Receiver**: the same permanent store once READY; a `.part` until verified.
- **Relay**: transient ciphertext only, capped by the storage pledge (§15), TTL'd, forget-after-delivery (§7.2).

**Transfers are temporary; files stay** (§1). v1 has no bare transfers: every push belongs to a message-composed send. A
GUI-only send without a message, if it ever exists, gets its own table (§18).

## 11. File I/O: kotlinx.io plus one positioned read

kotlinx.io is the house standard (`ConfigStore`, `JvmAppLogger`, `KmpTorBackend`, `DataDirLock`, `Platform`), and v1
uses it everywhere but one place. Positioned I/O is on kotlinx.io's roadmap (the files-API redesign, kotlinx-io issue
#241) but not shipped, so the design needs it only for reads:

- **The receiver has a single append-only writer position** (P, §8). Staged ranges absorb arbitrary arrival order and
  mixed chunkings, so the writer never seeks, for small and large files alike. A staged range's tail beyond P is sliced
  in memory at drain, and block verification hashes the bytes as they are appended.
- **The sender reads at an offset**, the one exception to pure kotlinx.io. Every large pull starts at the requester's P
  (§6.6), and every session drop starts a new pull, so skipping by reading through the prefix would re-read up to the
  whole file each time. The I/O module therefore has a **positioned read** (`expect`/`actual` over `FileChannel` on JVM
  and Android); reads stay linear from the start offset.
- **The serve is a two-pass stream** (`FilePlaintextSource.open` → `PlaintextStream.read`), with bounded 256 KiB
  buffers and never a whole block, which can reach 128 MiB:
    - pass 1 hashes each block and compares it with the block list, handing out nothing yet;
    - pass 2 re-reads the block with positioned reads and streams it from the requested offset (when the serve starts
      mid-block), hashing again and comparing at the block's end.

  A referenced original edited between the two passes ends the serve at that block: the receiver's own block check
  would also catch it, but the holder must stop serving it. Push chunks and pull frames both read through this stream,
  so each block is verified once per serve, not once per chunk. Disk reads double, which is fine since the disk
  outpaces the network.
- **No blocking fsync on a router or node coroutine.** `FileAccess` is suspend and switches to the IO dispatcher
  internally.
- **A torn append** (a crash mid-drain) is truncated at open to the persisted P (§8). The DB is the checkpoint; the file
  length never is.
- **The chunk-shaped I/O boundary stays a module**, with a kotlinx.io implementation inside apart from the positioned
  read. When #241 ships positioned I/O, the swap is internal, not a design change.

## 12. Layering and data ownership

**Two grains, one seam:**

- **Routing owns the device grain** (device vocabulary):
    - the per-device fan-out;
    - seals and their delivery state;
    - every hop, outgoing and incoming: shapes, windows, resume points, all restart-recoverable from its rows plus the
      outbox;
    - every seal key, and chunk encryption and decryption through protection;
    - **all transfer signaling**: OFFER, CHUNK, ACK and CANCEL travel between the two devices of a hop, and COMPLETE
      from the target to the seal's origin;
    - the relay's ciphertext;
    - the pull attempts.

  For large files it owns the pull channel (§6.6): opening the session and the channel, the frames, the credit window.
  That is device and channel vocabulary, with no table.
- **The orchestrator owns the `fileId`/account grain** (file vocabulary):
    - receive state per `fileId` (coverage, checkpoints, block verification);
    - the serve gate and the accept/decline verdicts, including the large-file auto-download decision;
    - the store's copies and references;
    - the REQUEST trigger.

  It never holds a key. It sees account-level events and send state through the `FileCarrier` port and issues
  account-level operations. Source devices may appear in events as *facts* (like "re-verify messages from a device"),
  never as *addresses*: replies are routing-owned signaling.

The precedent is the message path: the router fans out per device, and the orchestrator never addresses one. Files push
the same line one step further, because the transfer state itself is device-grain and so lives routing-side.

A headless relay node's orchestrator (file service, REQUEST logic, GUI progress) is completely uninvolved in
forwarding. Serving a REQUEST is the one relay-node case that *is* orchestrator business: we hold the file, so it is our
own traffic.

### Module layout

| Layer                       | Owns                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `protocol`                  | `FileHeader` (§3.2: the hop headers, `TransferShape`, the `FileSeal` and `FileRequest` contents, `MIN_FILE_CHUNK_SIZE_BYTES`); `Text.attachments` (§3.1); the `fileId` construction and the block-size rule (`FileIds`, §2); the large-path frames `LargeFileFrame` (§6.6); codecs and round-trip tests                                                                                                                                                                        |
| `crypto`                    | incremental SHA-256 on `CryptoProvider` (§2)                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `protection`                | `FileProtection` (§4): sealing and opening seals, sealing and opening REQUESTs, chunk AEAD under the seal key                                                                                                                                                                                                                                                                                                                                                                  |
| `routing/file`              | `FileService` (the `FileCarrier` itself), with its sub-packages:                                                                                                                                                                                                                                                                                                                                                                                                               |
|                             | `seal/` — `SealRegistry`: seals, the deposit round, hand-off, delivery, account-level events                                                                                                                                                                                                                                                                                                                                                                                   |
|                             | `transfer/` — `TransferEngine` + `OutgoingTransferSession` over a `ChunkSource` (`SealChunkSource`, `RelayChunkSource`)                                                                                                                                                                                                                                                                                                                                                        |
|                             | `inbound/` — `FileInboundHandler`: every incoming hop, the shared checks, dispatch to its `TransferSink` (`TargetSide` or the relay's `RelayDoor`); `FileServeHandler`                                                                                                                                                                                                                                                                                                         |
|                             | `relay/` — `RelayDoor` (admission, budget, parking) and `RelayForwards` (forward when whole, forget when done)                                                                                                                                                                                                                                                                                                                                                                 |
|                             | `large/` — `LargeFileService` (the face), `LargeFileChannels`, `LargeFileMux`, `LargeFileRequester`, `LargeFileHolder`, `LargeFileAdmissionGate`, `LargeFileChannelIo`                                                                                                                                                                                                                                                                                                         |
|                             | the package root — `FilePackets` (every FILE envelope built in one place), `FilePullProcessor` with `FileRequestStrategy` and `LargeFileStrategy` on the shared candidate core, `FileGuard` (one catch-and-log shape)                                                                                                                                                                                                                                                          |
|                             | `port/` — the orchestrator seam: `FileCarrier`, `FilePlaintextSource`, `FileIngestPort`, `FileRequestSource` and their types                                                                                                                                                                                                                                                                                                                                                   |
| `routing/store`             | the file stores, each named for its type: `FileSealStore`, `FileTransferStore`, `FileIncomingStore` (incoming seals, every incoming hop, the relay's ciphertext), `FilePullAttemptStore` (with its candidate views); data types in `FileStoreTypes.kt`                                                                                                                                                                                                                         |
| `orchestrator/file`         | the file machinery: `FileIds` block hashing, descriptors, the block-checking plaintext source, the file store (copies and references, quota and retention, §10), the receive engine (`receive/`: coverage, staging, checkpoints, block verification, READY), the auto-download decision, the REQUEST trigger and coordinator, the serve gate; stores: `file_descriptors`, `file_receives`, `file_receive_chunks`, `pending_file_requests`; the DAG fills `message_attachments` |
| `orchestrator/runtime/file` | the GUI-facing facade `FileTransferService` over `orchestrator/file`. Sending goes through `MessagingService.sendMessage` with picked files; progress display and open land with the GUI surface ([`../gui-api.md`](../gui-api.md))                                                                                                                                                                                                                                            |
| `orchestrator/file/io`      | the chunk-shaped read/write boundary over kotlinx.io, plus the positioned read (§11)                                                                                                                                                                                                                                                                                                                                                                                           |
| `transport/webrtc`          | a file channel kind opened per pull by either side, carrying raw frames; `awaitBufferedBelow`; channel close events                                                                                                                                                                                                                                                                                                                                                            |
| `wiring`                    | `FileLimits` (chunk size, large frame size, seal budget, chained from the transport limits like `MessageLimits`); the storage-pledge split; the config invariants                                                                                                                                                                                                                                                                                                              |

Within the orchestrator, the split follows the layer's own shape. The machinery is `orchestrator/file`, a domain package
beside `dag` and `sync`, and the GUI reaches it only through `FileTransferService` in `orchestrator/runtime/file`: a
facade over the machinery, the way the runtime's message service sits on the DAG. The runtime stays the GUI's whole API
([`../gui-api.md`](../gui-api.md) is that contract, and the boundary test enforces it).

The large path (§6.6) needs only two things from the session machinery: "open a session to this device" and "this
session ended". A large file has no accept/reject session state, because accepting is the requester's local decision
(§8).

## 13. Schema

The DDL lives in the `.sq` files (`File.sq`, `PendingFileRequest.sq`, `FileSeal.sq`, `FileTransfer.sq`,
`FileIncoming.sq`, `FilePullAttempt.sq`) and arrives on existing stores through `6.sqm`. Every schema change ships with
a migration (guide §4); while the files branch is unmerged, its own migration is amended in place.

```
-- orchestrator (own traffic, file/account grain; transfers never model message linkage, files do)
message_attachments (message_id, file_id)       -- DAG-owned, filled at ingest: the serve gate's fileId → message index
file_descriptors (                              -- the permanent store index: what we hold (no key)
  file_id PK, file_name, mime_type, total_bytes, block_size, block_hashes BLOB,
  storage,                                      -- COPY | REFERENCE (§10)
  path, storage_kind,                           -- store path, or the original's path / content URI (typed, not sniffed)
  status,                                       -- HELD | SOURCE_LOST
  created_at, last_access_at                    -- LRU over copies only
)
file_receives (                                 -- receive state, fileId-scoped; every hop merges here
  file_id PK, status,                           -- IN_FLIGHT | STALLED | READY | FAILED | DECLINED | EVICTED
  total_bytes, contiguous_bytes,                -- P: the checkpoint, truth over the .part; every block below P verified
  block_size NULL, block_hashes NULL,           -- from the first seal, checked against file_id
  file_name, mime_type, hash_mismatches,
  origin_message_id NULL,                       -- found when the message shows up; still NULL after the retention → deleted
  created_at, updated_at                        -- paths derive from fileId and the store layout, never stored
)
file_receive_chunks (file_id, start_offset PK, length, plaintext BLOB)   -- staged, not-yet-drained ranges
pending_file_requests (file_id PK, status, updated_at)                   -- accept/decline only; routing schedules

-- routing: origin side
file_seals (
  seal_id PK,                                   -- SHA-256 of the seal bytes
  file_id, target_device, kind,                 -- PUSH | SERVE
  seal_bytes BLOB, seal_key,                    -- the bytes every transfer of the seal carries; its key
  total_bytes,
  status,                                       -- SENDING | HANDED_OFF | DELIVERED | DECLINED | ENDED | CANCELLED | EXPIRED
  created_at, expires_at, deposited_at NULL     -- the deposit-round claim
)
file_transfers_out (                            -- every hop this device sends: ours and relay forwards
  transfer_id PK, seal_id,
  source_transfer_id NULL,                      -- a forward: the incoming relay hop whose ciphertext it sends
  peer, chunk_size, byte_from, byte_to,         -- the hop's receiver and shape
  next_chunk_index, contiguous_acked, offer_packet_id NULL,
  status,                                       -- OFFERED | STREAMING | DONE | DECLINED | ENDED | EXPIRED | CANCELLED
  created_at, expires_at
)
file_transfer_chunks (transfer_id, chunk_index PK, packet_id)   -- queued packet → chunk index, for restart recovery

-- routing: receiving side (target and relay alike)
file_incoming_seals (seal_id PK, origin_device, file_id, seal BLOB, expires_at)   -- the opened seal, key included
file_incoming_transfers (                       -- every hop delivering to us; final_target picks its sink
  transfer_id PK, seal_id, sender_device, final_target, chunk_size, byte_from, byte_to, expires_at
)
file_relay_seals (transfer_id PK, seal_bytes BLOB, stored_at)                      -- what a relay holds of a hop it
file_relay_chunks (transfer_id, chunk_index PK, stored_at, ciphertext BLOB)        -- can't open; cascades with the hop

-- routing: asking
file_pull_attempts (file_id PK, attempts, next_attempt_at, waiting_device NULL, waiting_until NULL,
                    request_packet_id NULL, pull_class)                           -- SMALL | LARGE
file_pull_declined_devices (file_id, device_id PK)                                 -- read with its parent in one join
```

The outbox gains the file tag kinds (`FILE_TRANSFER`, `FILE_REQUEST`) and `retry_class` (0 standard, 1 flat for chunks);
its queries change as §6.2 says.

The receive staging table and the relay's chunk table share a `((scope), index-or-range) → blob` **shape** and never a
**table**: routing stores ciphertext for foreign traffic, and the orchestrator stores plaintext for its own. One owner
per
table (guide §4), and the domains never mix. Guide §4's ownership table records the owners.

## 14. State machines

See [`transfer states.mmd`](transfer%20states.mmd). Compact listing:

- **Seal (routing, origin side).** SENDING → DELIVERED (the direct transfer DONE, or COMPLETE) / HANDED_OFF (a deposit
  DONE; the direct push stops) / DECLINED (the target refused the OFFER) / ENDED (every transfer ended otherwise) /
  CANCELLED (user cancel) / EXPIRED. HANDED_OFF → DELIVERED on a COMPLETE. The account aggregate follows (§6.5).
- **Outgoing transfer (routing, any hop we send).** OFFERED → STREAMING (OFFER ACKed) → DONE (the receiver holds every
  chunk). Final otherwise:
    - DECLINED (the OFFER was refused);
    - ENDED (a DECLINED chunk or another terminal NACK; the outcome there is unknown, never shown as delivered);
    - EXPIRED;
    - CANCELLED (by its owner: the seal's end, a superseding serve, a relay dropping its hop).
- **Incoming transfer (routing, any hop delivering to us).** Admitted at its OFFER.
    - A target's hop ends on READY, a CANCEL either way, or expiry; the gap trigger does not end it (§8).
    - A relay's hop: filling → whole → forwarding → dropped when its forward ends, when idle while filling
      (`fileRelayIdleTtl`), when evicted, when its origin cancels, or at expiry (§7.2).
- **Receiver (orchestrator, `fileId`-scoped).** An accepted OFFER starts IN_FLIGHT (`.part` reserved).
    - IN_FLIGHT → READY: coverage complete, last block verified, P committed at the total, renamed; COMPLETE to the
      seals'
      origins.
    - IN_FLIGHT → STALLED on a block mismatch below `fileHashRetryLimit` (`.part` truncated to the block's start);
      → FAILED at the limit.
    - DECLINED when there is no space (at the OFFER, or when a drain or pull write no longer fits), or for a large file
      this device did not pull. This is local and re-openable by the user.
    - IN_FLIGHT → STALLED when there is no prefix progress within `fileStallTimeout`: REQUEST re-opens, even one that
      had
      run out of holders, and live hops are left alone.
    - STALLED → IN_FLIGHT when any source resumes delivery (a staged chunk or a pull write).
    - READY → EVICTED by quota LRU; fetched again only on a user re-open.
    - A READY file whose stored copy was lost (its descriptor no longer HELD) is received again from scratch.
- **Pull (large, routing, in memory only).** ASKED (channel open, `PULL` sent) → STREAMING (`PULL_OK`, block list
  checked) → done (READY, channel closed) / ended (`DECLINED`, the session or channel dropped, `ABORT` from the
  receiver's gap trigger, a block mismatch at the source). There is no row on either end: an ended pull is forgotten,
  and the next starts from the checkpointed P.
- **Request (routing, attempt row).** PENDING → ASKING (one candidate; a large request first opens a session) →
  WAITING (small only: REQUEST ACKed, OFFER pending, timeout → next candidate) → SATISFIED (OFFER received, or
  `PULL_OK`) / UNAVAILABLE (every candidate declined). A large request whose remaining candidates are only unreachable
  stays PENDING until one comes online, and SATISFIED → PENDING when a pull ends with its session.

## 15. Config

Per guide §2, each layer's config type lives next to its code. There are four kinds of number, agreed at the
file-branch review ([`cleanup.md`](cleanup.md) §2.8):

- **Protocol constants**: the contract for this version, changing only with the format.
    - The block-size rule and the `fileId` version tag (§2): every device must compute the same `fileId` for the same
      bytes.
    - Key lengths and decode-time allocation guards.
    - `MIN_FILE_CHUNK_SIZE_BYTES`, the shape floor. A hop's chunk granularity is chosen by its sender, and every
      accepted chunk costs a stored row at a relay, so the floor bounds storage amplification (§7.1). It is not config:
      there is no fallback granularity, so two devices disagreeing on a floor would kill the small path between them.
- **Derived sizes**: chained by the composition root from the transport limits (`wiring/FileLimits`, guide §2), never
  configured on their own and never a constant checked after the fact.
    - `chunkSizeBytes` ≈ 249.5 KiB at the default 256,000-byte frames, at least the protocol's minimum chunk; each
      transfer snapshots the current value at creation.
    - `largeFileFrameBytes`: a WebRTC frame's file bytes.
    - `maxSealBytes`: a seal must fit an OFFER through both transports
      (`BinaryEnvelope.maxContentBytes(maxRoutableBytes, FileHeader.OFFER_ENCODED_BYTES)`). It is checked when the seal
      is made and again at admission, and `fileLimitsFor` requires it to be positive. The block rule keeps real seals
      far
      below it.
- **Network values**: config every device must settle on together, never user-editable. `smallFileMaxBytes = 10 MB`
  (§1) is a `RouterConfig` field surfaced as `FieldSource.NETWORK`, settled from the network's page once that exists and
  receiver-enforced meanwhile.
- **Local mechanics**: plain config fields, deliberately not settings. The file fields are listed below.

**`RouterConfig` (routing):**

- **Lifetimes and the push.** `fileTransferTtl = 2 days` is the seal's lifetime and so every hop's. Then
  `maxInFlightChunks` (16, the transfer window, §6.1), `fileChunkRetryDelay` (the flat retry of chunk rows, §6.2),
  `chunkAckTimeout` (no chunk ACK at all for this long → gone, §6.3), and `relayBackupAfter` (the OFFER's gone signal,
  shared with messages).
- **The relay.** `fileRelayIdleTtl` (a filling relay hop idle this long is dropped, §7.2), and `relayStoragePledgeBytes`
  with `fileRelayShare` (below).
- **Asking.**
    - `filePullDelay`: the push window before a small file's first REQUEST (§9).
    - `filePullAttemptTimeout`: one pull attempt's answer budget, the small REQUEST and the large PULL alike. It is also
      the large holder's idle bound.
    - `fileRequestWaitTimeout`: WAITING for the OFFER.
    - `maxConcurrentLargeFiles`: large pulls running at once.
- **The large path.**
    - `largeFileSessionOpenTimeout`, and `largeFileStreamWindowBytes` (32 MB, the credit window, §6.6).
    - `maxLargeFileServesPerPeer`: large serves one peer may hold open.
    - The pacing values: `largeFileAnswerGrace` (the close race), `largeFilePacingDelay` (the holder's window and
      empty-read poll), `largeFileDeferRetry`, `largeFileParkedPoll` and `largeFileBufferedResumeBytes`.
- **Cadence.** `fileSweepInterval` drives the seal registry's, the relay forwards' and the target side's sweeps. The
  transfer session tick is a third of it: one stated value, not ad-hoc floors.

**`OrchestratorConfig` (orchestrator).** The receive and store directories are the node layout's, not config.

- `storeQuotaBytes`: at least `autoDownloadMaxBytes`, so a file fetched without asking is never evicted on arrival.
- `maxStagedBytes` and `fileHashRetryLimit` (3, §8).
- `autoDownloadMaxBytes`: per device. **Every** file at or below it is fetched without asking, small ones included (250
  MB on desktop, lower on mobile).
- `fileCheckpointBytes` (8 MB) and `fileCheckpointInterval` (2 s, §8).
- `fileStallTimeout`: the receiver's gap trigger, §8.
- `unreferencedFileRetention`: a received file no message references.
- Its own `fileSweepInterval`.

The orchestrator schedules no file attempts: the push window before a small file's first pull is routing's
`filePullDelay`, and beyond `autoDownloadMaxBytes` the orchestrator is size-blind (§9).

**Storage pledge.** One user-facing `relayStoragePledgeBytes` (default 100 MB) says how much of this device's storage
the network may use. The composition root splits it by `fileRelayShare` into:

- the file relay budget (whole-hop LRU, §7.2);
- the message relay cache (per-row LRU).

Our own outbox rows are **never** part of the pledge: a user's undelivered traffic is not network charity. Relay-profile
presets (tens of GB for always-on nodes) are deferred (§18).

**Config invariants.**

- **`fileTransferTtl < supersededSessionRetention`** (2 days < 3 days): the seal must stay openable for as long as any
  copy can arrive (§3.3). Skipped-key limits within a chain are not covered by it; that is an accepted edge the pull
  path recovers.
- `fileRelayIdleTtl < fileTransferTtl`.
- `maxStagedBytes ≥ fileCheckpointBytes`: staging must hold a checkpoint's worth.
- `maxStagedBytes ≥ maxInFlightChunks × chunkSizeBytes`: one transfer's out-of-order window must fit.
- `largeFileStreamWindowBytes > fileCheckpointBytes`: otherwise the holder would wait for the timed checkpoint on every
  window.
- `fileStallTimeout > chunkAckTimeout`: the sender's deposit gets its chance before the receiver re-requests.

The last three span two layers, so `wiring/ConfigInvariants` checks them; the rest are each config's own `require`s.

`RelaySelectionPolicy` (`relayTargetSuccessProbability`, `minRelayScore`, the `maxRelays` cap) and the outbox's own
retry, park and expiry behaviour are reused unchanged.

## 16. Invariants

1. **Anything a relay forwards is whole**, by a count over the hop's shape. This is the ceiling by design: the relay
   cannot and must not verify content.
2. **One relay hop, ever.**
    - Deposits happen only at a seal's origin (a serving device is an origin too), and a relay admits a hop only from
      the seal's origin.
    - Redundancy is parallel: the same seal and remainder go to every policy-selected relay.
    - Forwards never deposit, and the origin never chases a failing relay.
3. **Relays are not REQUEST candidates, and relays forget after delivery.**
4. **Never READY without full byte coverage and every block verified.** P is never committed past an unverified block. A
   mismatch is a failed attempt costing one block, retried a bounded number of times.
5. **A seal key travels only inside its seal's ratchet frame**: never in messages, never in the clear. It lives no
   longer
   than its seal, and only routing and protection ever hold one.
6. **One seal per (file, target device) per push**, carried unchanged by every hop of that push. A serve always makes a
   fresh seal. Chunk AEAD binds (`sealId`, `offset`, `length`) as AAD, and nothing relies on ciphertext being
   reproducible.
7. **Transfers cover contiguous suffixes ending at `totalBytes`**; resume and deposits move only `byteFrom`.
8. **ACK = processed.**
    - The OFFER is establishment-only.
    - An outbox chunk is processed when it is decrypted and durably staged (at a target) or stored (at a relay).
    - A pull's `PROGRESS` is sent only after the fsync and the P commit (§8).
9. **Receiver file state is `fileId`-scoped byte coverage**, agnostic of transfers and chunkings. Each incoming hop is
   its
   own `transferId`-scoped row, whatever its sink.
10. **One REQUEST candidate at a time.**
11. **STALLED is re-requestable; DECLINED, CANCELLED, FAILED and EVICTED are refused**, never auto-resurrected. The gap
    trigger opens a REQUEST and cancels no hop.
12. **Every hop's signature is verified before anything is stored.** A chunk without a live incoming hop is DECLINED at
    the inbound face and never stored.
13. **Transfers are temporary (`fileTransferTtl`); files stay.**
14. **A transfer's chunks flow only after its OFFER was ACKed by the hop's receiver** (§3.5).
15. **Any DECLINED touching a hop cleans up that party's state for it.** Only the target's DECLINED **on an OFFER** is a
    refusal of the file; a DECLINED on a chunk ends the hop quietly as ENDED (§3.6). A seal is DELIVERED only by its
    direct transfer finishing or a COMPLETE.
16. **Transfers are device-to-device.** The orchestrator speaks files and accounts; routing fans out and signals per
    device.
17. **The `fileId` travels only inside sealed content** (the seal and REQUEST) and in a large pull's `PULL` frame inside
    the DTLS channel to the one peer device. Every hop packet is addressed by `transferId`, and COMPLETE by `sealId`.
18. **Every serve passes the serve gate**: the requester's account must see a message referencing the `fileId`.
19. **A seal is opened once at the target**, and its row is written the moment it opens. At a serving device there is
    one
    live transfer per (`fileId`, target device).
20. **Large files are pull-only.** Nothing large is sent unasked; the receiving device decides to pull, and declining is
    local. `smallFileMaxBytes` is a network value, settled network-wide. A device that lags enforces its own: a
    small-path OFFER above it is DECLINED, and the receiver's class pin routes the file to its large path. No
    disagreement is a loss.
21. **A pull lives within one data channel of one WebRTC session.** It never waits for a session and never goes over
    Tor, a relay, the outbox or the envelope pipeline.
22. **The `fileId` is computed by one protocol-fixed rule** (§2). A block list is used only after it hashes to the
    `fileId`, and a holder sends no block it has not just checked against the list.
23. **A pull's bytes are protected by the channel alone** (DTLS, authenticated through signed and sealed signaling, §4).
    If WebRTC signaling ever stops being signed and sealed to the peer device, the pull needs its own encryption before
    that ships.
24. **A transfer to the target reports gone only on signals that the target is gone** (link closed, no link, no chunk
    ACK at all within `chunkAckTimeout`), never on one slow or retried chunk (§6.3).
25. **A received file that no message references within `unreferencedFileRetention` is deleted** (§8).
26. **A seal must fit an OFFER through both transports.** The budget is derived (`FileLimits.maxSealBytes`) and checked
    at seal creation and at admission, never a constant checked after the fact.

## 17. Rejected alternatives

Recorded so they are not relitigated.

### Small path: keys, seals and hops

- **Whole file as one packet.** The transport frame caps (250 KiB WebRTC, 4 MB Tor) make it impossible, so files are
  chunked everywhere.
- **Chunk content through the Double Ratchet.** Ratchet state explodes, `MAX_SKIP` bounds out-of-order tolerance, and
  one
  lost chunk breaks the chain for interleaved messages. A symmetric seal key instead.
- **A key in the message plaintext.** Transfers must be self-contained; the seal is the single key carrier (§3.3).
- **One key per file with deterministic per-range ciphertext**, the very first design. It needed range-derived nonces,
  keys stored with the file and re-sealed on every serve, and it broke outright when two senders of one file generated
  two keys.
- **A key per transfer, with a sealed OFFER per hop** (the pre-redesign small path).
    - Every relay deposit needed its own sealed OFFER, because two relays forwarding one sealed OFFER would deliver the
      same ratchet frame twice.
    - The deposit rows needed inner/outer addressing.
    - Relays replayed the author's signed envelopes.

  One seal per (file, device), opened once and bound by `sealId`, removes all of it (§2, §3.3).
- **Chunk AEAD bound to the transfer** (`transferId`, `chunkIndex` as AAD). It would forbid a relay from forwarding in a
  new hop under its own id. Binding the byte range instead lets the ciphertext travel unchanged across hops and
  chunkings (§4).
- **`fileId`-keyed control packets.** ACKs to a forwarding relay would hand it the plaintext hash. Hop packets are
  addressed by `transferId`, and COMPLETE by `sealId` (§3.2).
- **Keeping incoming-transfer rows to answer late chunks.** Any retention window can be outlived by one more late packet
  (the two generals problem). Rows die with their hop, and a late chunk ends the transfer quietly (§3.6).
- **The OFFER held open until completion.** It needs a "processed but don't ACK" result, fights every message-scale
  outbox assumption, and duplicates state the transfer owns.
- **Sparse covered sets.** The suffix invariant (§3.4) covers every case with bounded duplicate re-sends and one number
  per deposit.
- **Message-style triggers on every chunk row.** One slow ACK or one retry would send the remainder to every relay on a
  slow but working link (§6.3).
- **Showing a transfer as delivered when a chunk is DECLINED.** The hop is gone at the target, which may equally mean
  it stalled or expired there; that outcome is ENDED (§3.6).

### Relays

- **Relay-held chunks as outbox `is_relay` rows.**
    - The message relay cache budget would be shared with files: one 10 MB file would consume it entirely.
    - Per-row LRU eviction would strand hops.
    - Chunks must wait for their forward's OFFER to be ACKed, which is file logic the outbox must not carry.

  Dedicated tables isolate it.
- **A separate relay transfer type and table.** A relay hop is received exactly like a target's: the same OFFER rule,
  admission and chunk checks. Only its sink differs, so it is one incoming-transfer row, and the relay's own tables hold
  only the ciphertext it cannot open (§7).
- **Relay chunks on disk.** At ≤10 MB per hop, the DB gives transactional admission, eviction and crash recovery for
  free.
- **Eager per-chunk relay forwarding.** Gate on wholeness instead. The latency cost is theoretical, because the relay
  path
  is only used when the direct path already failed, and the invariant "a relay forward is always whole" is worth more.
  It also keeps the forward's chunk source simple: a stored chunk is there or the source is done.
- **Sequential relay re-deposit (chasing).** It spreads the file across the network; one jump instead.
- **Relays as REQUEST candidates.** Simplified away: relays are pure push caches.
- **One staging table for relay and receive.** One owner per table (guide §4), and ciphertext for foreign traffic never
  mixes with own plaintext. The two share a shape, never a table (§13).

### Asking and receiving

- **Parallel REQUEST fan-out.** N devices each serving the entire file is the spam shape; one candidate at a time.
- **The OFFER as the REQUEST's ACK.** Outbox rows must leave through their own disposition, not an out-of-band
  correlation. The request is ACKed and the OFFER follows, exactly like sync (§9).
- **Re-offering an existing seal on a serve.** The requester may hold a seal it can no longer open; a fresh seal costs
  one ratchet frame and never loops (§9).
- **Receiver-side chunk-parameter enforcement.** Byte coverage (§8) makes mixed chunkings contribute instead of
  conflict,
  and rejecting useful data on principle buys nothing: the block hashes are the gate.
- **Unbounded receive staging.** A racing hop could stage a whole suffix behind a stuck gap. The `maxStagedBytes` bound
  plus DEFER backpressure closes that (§8).
- **The gap trigger cancelling live hops.** It would make a returning receiver drop a relay's whole copy. Coverage
  merges,
  so nothing needs cancelling (§8).
- **Positioned writes in v1.** The receiver keeps a single append position and staged ranges. Only reads are positioned
  (§11), because a large pull starts at an offset after every session drop.
- **One fsync per chunk.** That is ~230 a second at 50 MB/s; checkpoints batch them (§8).
- **A plain whole-file SHA-256 `fileId`.**
    - A mismatch throws away the whole file.
    - Verification is one more full pass at the end and cannot resume after a restart.
    - Every serve needs a full re-hash first.

  The block-hashed `fileId` fixes all three (§2).
- **A Merkle tree with per-chunk proofs.** Finer than needed: capping files at 1024 blocks keeps the list at 32 KiB,
  small
  enough to carry whole in every seal.
- **Always copying into the store.** It doubles 100 GB on disk; the user picks copy or reference (§10).
- **A second payload type, or an optional trailing block, for attachments.** It would patch old bytes into decoding
  while
  nothing deployed needed them. The layout changed plainly, and the version was bumped (§3.1).

### The large path

- **Media tracks (RTP/SRTP) for file streaming.** Real-time and lossy by design; files need the data channel's
  reliability.
- **App-layer AEAD, envelopes and per-chunk signatures.** The earlier design kept them "for uniformity with the relay
  path". Once large files became pull-only and WebRTC-only, there was no relay path to be uniform with: the bytes cross
  one authenticated, encrypted channel, and the block list is the integrity check (§4, §6.6).
- **A Tor fallback when WebRTC cannot connect.** 100 MB and up over onion circuits is not a path worth having; the
  failure
  is shown to the user instead (§6.6).
- **A per-device `smallFileMaxBytes`.** Requester and holder must agree on the class, so it is a network value (§1).
- **Pushed large OFFERs with an accept prompt**, the original large-path outline. It needed:
    - an OFFER held processed while the user decides;
    - a separate accept signal;
    - a rule for which devices prompt;
    - a sender waiting for a session;
    - timers that must not fire meanwhile.

  Pull-only removes all of it (§5, §6.6).
- **Per-chunk ACKs and outbox rows on the large path.** About 475k rows for 100 GB, on a channel that already delivers
  in
  order. `PROGRESS` at checkpoints, with a credit window, instead (§6.6).
- **Relying on the channel's flow control alone.** `bufferedAmount` protects only the sender, and the receive callback
  has
  no backpressure, so a slow disk would buffer in memory. The credit window bounds it (§6.6).
- **A pull that waits for its session.** It would need state at both ends and a waiting state; a new pull from the
  checkpointed P gives the same result with no state at all.

## 18. Deferred

**After v1:**

- **Swarming**: fetching one file from several holders in parallel. The receiver already merges any coverage, but one
  candidate at a time (§9) would first need a split-by-range rule.
- **A receiver-side "save to" location.** Receiving a 100 GB file into the store and then exporting it doubles it; the
  receiver could write the final file straight to a user-chosen location, a reference like the sender's (§10).
- **Raising `smallFileMaxBytes`** (a network-config change, for every device at once) once dedicated relays (~10 GB)
  exist, so ~100 MB files also get store-and-forward.
- **An auto-download limit per network type** (metered vs. not).

Also deferred:

- group sender keys: additive by design, and also the fan-out and deposit-cost optimisation for multi-device accounts;
- a coverage answer on the forwarded OFFER beyond today's ACK, to save duplicate relay forwards;
- a separate outbox in-flight cap for FILE rows, only if a stress test asks for one (§6.2);
- a relay server (TURN) for device pairs that cannot connect directly (§6.6);
- a disk-based relay store, only if `smallFileMaxBytes` grows;
- replacing the positioned read with kotlinx.io #241;
- bare transfers: a separate table, if a GUI-only send without a message ever needs one;
- relay storage presets (tens of GB for always-on nodes);
- the GUI file surface and its API contract (send, progress, decline), to be written with the GUI work;
- the orchestrator-side review and umbrella naming ([`cleanup.md`](cleanup.md) §6).

## 19. Test matrix

**Small path:**

- **Target offline.** Deposit → the relay hop is whole → forward when the target surfaces → verify → READY → COMPLETE to
  the origin.
- **Mid-transfer restarts.**
    - The sender restarts mid-push and resumes from its rows and the outbox.
    - A relay restarts mid-forward; recovery is free, and the sweep starts a missing forward.
    - The receiver restarts mid-assembly: staging plus prefix re-derivation, and nothing ACKed is ever lost.
- **Mixed chunkings.** Two hops with different shapes interleave on one file; byte coverage completes and the hashes
  verify.
- **Direct link breaks mid-send.** The remainder is deposited at the policy-selected relays, and the target completes
  from the direct prefix plus a relay's remainder. The seal is HANDED_OFF, then DELIVERED.
- **Duplicate relay forwards.** The target binds every forward to the stored seal row without decrypting again, and
  drops fully covered ranges.
- **Gone signals.**
    - A slow link that still ACKs a chunk within `chunkAckTimeout` never deposits.
    - A closed link deposits at once.
    - A silent dead link deposits after one timeout.
- **Gone → deposit, then the sender is killed.** The relays hold a whole copy and deliver.
- **Gap trigger.** The prefix stalls beyond `fileStallTimeout` → STALLED → REQUEST → serve from the held prefix with a
  fresh seal; the stalled hops are not cancelled.
- **Gap trigger keeps hops.** The receiver goes offline mid-forward beyond `fileStallTimeout` and comes back; the
  relay's
  forward continues and completes.
- **Staging bound.** A racing hop hits `maxStagedBytes` → DEFER → the gap fills → drain → resume. The DEFERred chunk
  keeps
  retrying at `fileChunkRetryDelay` and never drops to the hour-long standby.
- **Messages first.** A text message enqueued behind a full chunk window to the same peer is sent before the remaining
  chunks.
- **OFFER terminal NACK other than DECLINED.** The transfer is ENDED, never shown as declined or delivered.

**Relays:**

- **Eviction pressure.** Admission evicts the least recently fed hops; a hop that doesn't fit is DECLINED and the rest
  of
  the round carries on.
- **Chunks for a dropped, evicted or expired hop** → DECLINED at the inbound face → the depositor ends that transfer.
- **The target declines the forwarded OFFER** (no space) → the relay drops the hop and frees its quota.
- **The origin dies mid-deposit.** The partial hop is never forwarded; the idle TTL drops it; the target's REQUEST
  recovers.
- **No chaining.** A forwarding relay never deposits elsewhere.
- **Not from the origin.** A deposit whose hop sender is not the seal's origin, or whose origin is unknown or banned, is
  DECLINED.
- **Out of shape.** A chunk outside the hop's shape, or of the wrong length, is DECLINED and never counts toward
  wholeness.
- **The target's expiry sweep leaves relay hops alone**; they end with their forward.

**Seals and serving:**

- **The seal late-decrypt invariant.** The session is superseded mid-hold;
  `fileTransferTtl < supersededSessionRetention`
  keeps the seal openable at the TTL edge.
- **The crash edge.** A seal opened without its row → the hop is NACKed PERMANENT → REQUEST → a fresh seal.
- **A REQUEST to a device already pushing to the requester.** The old transfer is cancelled, and one fresh serve runs.
- **A serve while a seal for the same pair is still being made** is declined.
- **A REQUEST envelope for a file above `smallFileMaxBytes`** is DECLINED.
- **Serve gate.** A REQUEST, or a `PULL`, from an account that sees no referencing message is DECLINED.
- **History sync on a fresh device.** The attachment renders from the message; REQUEST pulls the bytes; the serve's seal
  carries the key; exact resume from held coverage.
- **A multi-device account.** The non-targeted sibling REQUESTs after the push window: normal traffic, one candidate at
  a
  time.
- **Every path ends somewhere.** Declined, cancelled, no-space and ENOSPC paths; "file unavailable" after all candidates
  decline; ACK-but-no-OFFER → WAITING timeout → next candidate.

**Large path:**

- **Auto-download.** The message lands → session → file channel → `PULL` → `PULL_OK` → `DATA` → READY. Nothing goes over
  Tor, a relay or the outbox.
- **Above `autoDownloadMaxBytes`.** No pull until the user clicks; declining sends nothing; a re-open later pulls it.
- **The session drops mid-file.** Both ends forget it → request PENDING → a new pull starts at the checkpointed P with a
  positioned read. A restart on either side is the same.
- **`PULL_OK` with a block list that does not hash to the `fileId`** → channel closed, nothing written, next candidate.
- **No direct connection.** The holder is online over Tor but no session forms → recorded as such, not as offline, and
  no
  byte goes over Tor.
- **All large candidates offline.** The request stays PENDING (not UNAVAILABLE) and pulls when one comes online.
- **Credit window.** A receiver with a slow disk holds the holder at `largeFileStreamWindowBytes` past the last
  `PROGRESS`; memory stays bounded, and the envelope channel keeps flowing.
- **A requester repeating its P** is reaped after `filePullAttemptTimeout`.

**Receiver and storage:**

- **Checkpoint crash.** Kill the receiver between checkpoints → restart truncates to P, re-hashes the partial block, and
  the next source re-sends at most one checkpoint.
- **Torn drain.** A crash between the `.part` append and the P commit → truncated to P at open, and the staged rows
  drain
  again.
- **`fileId` vectors.** Fixed test vectors for empty, one-block, exact-multiple and short-last-block files, and the
  block-size doubling at 1024 blocks.
- **Block mismatch.**
    - The `.part` is truncated to the bad block's start, and earlier blocks are kept → STALLED → re-REQUEST (usual
      candidate order) → FAILED after `fileHashRetryLimit`.
    - A holder with a rotten block ends the serve at that block and declines later REQUESTs.
- **Reference mode.** The original is edited, moved or deleted → the next serve stops at the first bad block → the
  descriptor goes SOURCE_LOST. The message still renders, and another holder serves.
- **A late chunk after READY.** The hop row is gone → DECLINED → the sender ends the transfer as ENDED.
- **Unreferenced receive.** A file that reaches READY with no message is deleted after `unreferencedFileRetention`.
- **Quota eviction** → EVICTED; no automatic re-fetch; a user re-open fetches it.
- **Attachments codec.** Round trip with 0, 1 and the maximum number of attachments; a version-2 payload is rejected.
- **No `fileId` on the wire** outside sealed content and the `PULL` frame (a codec-level check).
