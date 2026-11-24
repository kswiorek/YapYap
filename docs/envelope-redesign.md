# Envelope and file transfer redesign

Agreed 24 November 2026 and implemented on the `files` branch. This document is the design of record
for the envelope layering and the small-file path until [`file/design.md`](file/design.md) is rewritten
on it (§10); where the two disagree, this one is the newer decision. §11 records where the
implementation settled details this document left open.

It replaces two things at once, because the second only makes sense with the first:

- **The envelope layering, for every packet family.** One wire shape: a signed hop layer, a public
  routing header and opaque end-to-end content (§2–§4).
- **The file transfer model of the small path.** A file is *sealed once* per target device; transfers
  are single hops that move chunks of that seal (§5). The large path (WebRTC fetch,
  [`file/design.md`](file/design.md) §6.6) is untouched.

The `files` branch resets every database when it merges, so wire and schema changes are made plainly:
no version negotiation, no decoders for old layouts, and `6.sq` remains the whole migration.

## 1. Why

- **Files.** [`file/design.md`](file/design.md) §3.3 requires one ratchet-sealed OFFER per transfer,
  because the same ratchet frame cannot be opened twice. A relay deposit therefore has to be its own
  sealed OFFER, so a relay copy is not "the same envelope to another peer", and the outbox's generic
  relay backup has to call back into the file engine (`relayCopies` in `DefaultRouter`,
  `FileService.depositMarker`). The relay also cannot open its own transfer to the target: it can only
  replay the author's signed envelopes, which is why `FileEnvelope` carries an inner source/target
  beside the outer one and why the push engine has `via_relay`, DEPOSIT rows and COMPLETE
  special cases.
- **Messages.** Relay transit (`MessageInboundHandler`) re-wraps any `MessageEnvelope` whose inner
  target is not the relay, without checking a signature or that the source is a known device, so
  anyone can fill a relay's message cache. Every hop sets `now + binaryEnvelopeLifetime` (2 days), so
  a relayed message can live about 4 days — past `supersededSessionRetention` (3 days). The direct copy
  and a relay copy are the same ratchet frame under different packet ids, so the second one fails as
  `CryptoSessionException.Replay` and is NACKed PERMANENT, logged as a protection failure.
- **All families.** `MessageEnvelope`, `SystemEnvelope`, `FileEnvelope` and `WebRtcSignalEnvelope`
  repeat the same header (id, source, target, createdAt, nonce, scheme, signature), and the outer
  `BinaryEnvelope` is unsigned. Over Tor the outer source is unauthenticated, so whether a hop is
  authenticated depends on whether the inner signature happens to be the hop's (`sourceAuthenticated = direct`).

## 2. The envelope

One envelope type on the wire, `BinaryEnvelope` version 2, for every family:

```
BinaryEnvelope v2
  MAGIC | VERSION=2
  hop header       packetType u8 | flags u8 (bit 0: dispositionRequested) |
                   createdAt i64 | expiresAt i64 | packetId uuid | source PeerId | target PeerId
  routing header   length-prefixed bytes, codec chosen by packetType   (public)
  content          length-prefixed bytes, may be empty                 (opaque to hops)
  hop signature    Ed25519 by `source`, 64 bytes
```

- **The hop header** is one hop: who handed these bytes to whom, now. `source` and `target` are always
  the two ends of this hop. `dispositionRequested` stays here (ACK/NACK is hop-level).
- **The routing header** is what any device that handles the packet may read: the hop's receiver, and
  a relay that forwards it. Each family defines its own (§4). It is public and covered by the hop
  signature. Plaintext hop-to-hop data with no end-to-end protection lives here too (SYSTEM).
- **The content** is end to end, from the packet's origin to its final target, and hops copy it
  verbatim. How it is protected follows from the family and kind alone (§4) — a ratchet frame from
  origin to final target, a file chunk under its seal's key (§5.3), or bootstrap's own schemes — so it
  carries no scheme byte. Header-only packets have empty content. The old `SIGNED` and
  `PLAINTEXT_TEST_ONLY` schemes are gone: the hop signature authenticates every hop.
- **`contentId` = SHA-256 (content)**, 32 bytes. It is derived, never on the wire. It is what the hop
  signature covers, what relays key their storage by, and for a file OFFER it is the `sealId` (§5.2).
- **The hop signature** covers `hop header ‖ routing header ‖ contentId` (the content's hash, not its
  bytes, so the signing input stays small and the hash is computed once). Every hop signs what it
  sends, including relays forwarding someone else's content. The per-family `nonce` and `signature`
  fields go: `packetId` is in the signed bytes.
- **Expiry is the content's, not the hop's.** The first hop sets `expiresAt`; every later hop copies
  it and never extends it. For a file transfer it is the seal's expiry (§5.2). The config invariant
  becomes: content lifetime (`binaryEnvelopeLifetime`, `fileTransferTtl`) < `supersededSessionRetention`.
- Each family's routing header and content get an observability profile (`EnvelopeObservability`) in
  place of today's per-envelope profiles. SYSTEM payloads are only signed today, though their profile
  says `PROTECTED`; the new profile marks them routing-visible, which is the honest label.

## 3. Rules every family follows

### 3.1 Signing and verifying

- Protection gains the hop layer: one `HopProtection` (sign on send, verify on receive) shared by all
  families, beside the per-family content protection. Routing signs when it builds an envelope for the
  outbox or a direct send, and verifies in `InboundEnvelopeProcessor` before any handler runs.
- A verified hop authenticates its `source`, so `sourceAuthenticated` is simply "the hop signature
  verified"; endpoint healing and arrival-link adoption apply to relayed traffic as well.
- **Unknown sources.** A source with no key in `PeerKeys` cannot be verified. Only two cases are
  accepted unverified, exactly the existing rules: a BOOTSTRAP packet (its own scheme authenticates it,
  §4) and an ACK/NACK answering a bootstrap packet in our outbox addressed to exactly that device ([
  `guide.md`](guide.md) §6, "Unknown devices"). Everything else from an unknown source is dropped.
- Packet dedup stays as it is: hop-level, keyed by (`source`, `packetId`).

### 3.2 Relaying

- A packet whose routing header names a final target other than us is **relay traffic**. It is
  admitted only when the hop `source` is the packet's `origin`, the origin is a known, non-banned
  device, and the final target is a known device. This gives every family the same one-hop rule (relays never accept
  from relays) and closes the message cache-stuffing hole.
- A forward is a new hop envelope from the relay to the final target: new `packetId`, the relay as
  `source`, the routing header and content copied (files rewrite the transfer fields, §5.4), `expiresAt`
  copied, signed by the relay.
- A sender's retries of one relay copy share its packet id, so the relay's packet dedup already
  keeps them from making extra copies; a file deposit is keyed by its transfer id.

### 3.3 Duplicate content

- The same content can legitimately reach its target twice: a message's direct copy and a relay copy,
  a seal carried by a direct OFFER and a relay's OFFER.
- **A ratchet `Replay` is a duplicate, not a violation.** `DoubleRatchetSession.rejectReplayIfStale`
  throws `Replay` only when the frame's number is below the receive counter and no skipped key is held
  for it: in practice, the frame was opened before. Protection maps it to `ProtectionException.Duplicate`
  (reason `DUPLICATE`; the disposition stays `PERMANENT`, since these bytes can never be opened again), and
  the inbound processor answers it like a processed packet: ACK, logged at debug.
    - The frame was processed when it was first opened, so the ACK is honest — except in the crash
      window between opening and handling, which is accepted (below).
    - The other way to get `Replay` is a skipped key that was pruned: that message is lost to every
      retry anyway, and the pull path covers it.
    - `Replay` is decided from the ratchet header before decryption, so a forged duplicate earns an ACK
      and nothing else: nothing is processed twice.
- **Accepted edge: a crash between opening a frame and handling it.** The ratchet has consumed the
  key, so every later copy is a `Replay`. Messages are recovered by the pull path (sync). Files are
  recovered by the REQUEST path, which is why a serve always mints a fresh seal (§5.2). No table records
  opened frames.
- **`OpenedFrameCache` is deleted.** It kept the plaintext of recently opened file frames in memory so
  that a frame deferred *after* opening could be opened again on redelivery. Only file protection used
  it, for two frames:
    - the OFFER's seal: the incoming seal row is now written as soon as the seal opens (§5.5), so a
      redelivered OFFER finds the row and never reaches the ratchet;
    - the REQUEST body: when serving fails after the REQUEST opened (a store failure), the requester's
      retry is now a `Replay`, answered as a duplicate. The requester waits for an OFFER that never
      comes, `fileRequestWaitTimeout` moves it on, and the holder is asked again later. That costs one
      wait timeout on a rare failure.

  Messages never used it: they are handled straight after opening and never deferred afterwards. The
  cache also never covered a restart, so nothing it guarded is lost by deleting it.

## 4. Families

| Family    | Kind                             | Routing header                                                                   | Content                                     |
|-----------|----------------------------------|----------------------------------------------------------------------------------|---------------------------------------------|
| MESSAGE   | DAG node, sync request           | `origin`, `finalTarget`                                                          | `RATCHET` (origin → final target)           |
| SIGNAL    | WebRTC signaling                 | `kind`                                                                           | `RATCHET`                                   |
| SYSTEM    | ACK, NACK, typing, ping, log-off | `kind` and its fields (as today's payloads)                                      | empty                                       |
| BOOTSTRAP | intro, recovery, …               | today's bootstrap header (scheme, id); bound as AAD as today                     | today's bootstrap payload                   |
| FILE      | OFFER                            | `kind`, `transferId`, `origin`, `finalTarget`, `chunkSize`, `byteFrom`, `byteTo` | the seal: `RATCHET` (origin → final target) |
| FILE      | CHUNK                            | `kind`, `transferId`, `chunkIndex`                                               | `CHUNK_AEAD`                                |
| FILE      | ACK                              | `kind`, `transferId`, `highestContiguousIndex`, `missingRanges` (capped)         | empty                                       |
| FILE      | CANCEL                           | `kind`, `transferId`, `reason`                                                   | empty                                       |
| FILE      | COMPLETE                         | `kind`, `sealId`                                                                 | empty                                       |
| FILE      | REQUEST                          | `kind`                                                                           | `RATCHET`: `fileId`, held coverage          |

- MESSAGE: for a direct send `origin == source` and `finalTarget == target`; the fields are still
  written, so direct and relayed packets have one shape. `messageEnvelopeId` goes: the DAG id lives in
  the content, and the packet is identified by `contentId`.
- The DAG's `authorSignature` is unchanged: it is orchestrator data inside the encrypted content and
  authenticates the author of the node, not any hop.
- BOOTSTRAP keeps its scheme because the receiver may not know the sender's key yet (§3.1). It still
  carries a hop signature; it is verified when the source is known.
- `fileId` stays off the wire everywhere except inside sealed content (the seal, REQUEST) and the
  large path's `FETCH` frame.

## 5. Files: seals and hops

### 5.1 Three grains

| Grain    | Id           | What it is                                                             | Owner        |
|----------|--------------|------------------------------------------------------------------------|--------------|
| File     | `fileId`     | content identity, block-hashed ([`file/design.md`](file/design.md) §2) | orchestrator |
| Seal     | `sealId`     | one file sealed for one target device by one origin; end to end        | routing      |
| Transfer | `transferId` | one hop between two devices moving a range of one seal's chunks        | routing      |

The transfer machinery only makes sure every chunk it described arrives. What the receiving end does
with them is its **sink**'s business — the target decrypts and ingests, a relay parks the ciphertext —
and where the sending end gets them is its **source**'s — the origin encrypts plaintext, a relay reads
its store.

### 5.2 The seal

- **Contents** (ratchet-encrypted from the origin to the target device): `fileId`, `totalBytes`,
  `blockSize`, the block list, `fileName`, `mimeType` and the **seal key** (32 bytes, ChaCha20-Poly1305,
  `SealKey`, which replaces `TransferKey`). A seal that can't be made (no session to the device yet)
  is skipped for that device: the pull path covers it. File facts only: no chunk size, no transfer data.
- **`sealId` = the `contentId` of the OFFER content**, i.e. SHA-256 of the seal bytes.
  Content-addressed: nobody can swap a seal under an id, and the target recognises a seal it already
  holds without decrypting it (§5.5).
- **One seal per (file, target device)** at the origin, made when the push starts and kept until it
  expires. It is **never re-made for the same push**: the direct transfer and every relay deposit carry
  the same seal bytes.
- **A serve (REQUEST) always mints a fresh seal.** The requester asks because it is missing something,
  and it may hold a seal it can no longer open (the crash edge, §3.3); a fresh seal costs one ratchet
  frame and never loops.
- **Lifetime.** `expiresAt = createdAt + fileTransferTtl`, fixed at creation. Every transfer of the
  seal, at every hop, uses it as `expiresAt` (§2). `fileTransferTtl < supersededSessionRetention`
  (2 days < 3 days) keeps the seal openable for as long as any copy can arrive.

### 5.3 The transfer

- **A transfer is one hop.** It has its own `transferId`, a sender, a receiver, and a fixed shape:
  `chunkSize`, `byteFrom`, `byteTo`. Its chunks are `[byteFrom + i × chunkSize, …)` up to `byteTo`; the
  last may be short.
- **The shape is public** (OFFER routing header) so a relay can reserve space (`byteTo − byteFrom` plus
  per-chunk overhead), count wholeness, and check each chunk's exact ciphertext length.
- **Chunk AEAD binds the byte range, not the transfer:** key = the seal key, AAD = (`sealId`,
  `offset` u64, `length` u32). The target takes the offset from what decrypts, never from the hop's
  claimed shape, so a lying relay produces a decrypt failure, not a write at the wrong place. Any chunk
  that decrypts is placed by its offset and checked against `totalBytes`.
- **Chunkings may differ between transfers of one seal.** Ciphertext cannot be re-chunked, so a relay
  forwards in the chunking it received; the origin may encrypt a later transfer with a different chunk
  size (the frame limit changed, a serve). The receive engine is byte-coverage based already ([
  `file/design.md`](file/design.md) §8), so they merge.
- **Suffixes stay.** A transfer covers a contiguous byte range ending at `totalBytes`; resume and
  deposits move only `byteFrom`.
- **Chunks follow their OFFER** at every hop, as today: a chunk for an unknown `transferId` is
  DECLINED, never stored.

### 5.4 The lifecycle of a push

1. **Push.** The origin makes the seal and opens a direct transfer to the target (`byteFrom = 0`).
2. **The target is gone → deposit.** The origin opens one transfer to **each relay**
   `RelaySelectionPolicy` returns, carrying the same seal and the remainder: `byteFrom` = the target's
   ACKed contiguous prefix, rounded down to a chunk boundary. One deposit round per seal, claimed
   durably. Triggers, all owned by the file engine:
    - before the OFFER is ACKed (the message triggers): no link at claim time, a retried OFFER, or no
      ACK within `relayBackupAfter` — the target may never have been reachable;
    - after it (gone-only, as today): the target's link closed, no link at claim time, or no chunk ACK
      at all for `chunkAckTimeout` while chunks are in flight.
3. **The relay holds.** It admits the OFFER (§3.2: hop source == origin), stores the seal bytes once and
   each chunk's ciphertext, ACKs chunks with its coverage, and answers DECLINED for an unknown
   `transferId`, an index outside the shape or a wrong ciphertext length. **It forwards only when
   whole**: every chunk of its inbound transfer present.
4. **The relay forwards.** It opens a new transfer to the final target: new `transferId`, the same
   seal bytes, the same shape, its own signature, the seal's `expiresAt`. The forward never deposits
   elsewhere (one hop, ever).
5. **The target receives** (§5.5) from whichever hops reach it, merging by byte coverage.

### 5.5 At the target

- **An OFFER with a known `sealId`**: no decryption. The transfer is bound to the stored seal; the
  OFFER is ACKed; if the file is already READY the OFFER is ACKed and answered with a coverage ACK that
  covers everything, which ends that hop.
- **An OFFER with a new `sealId`**: the seal is opened with the session of `origin`, and the incoming
  seal row is written **as soon as it opens**, before `checkOffer`. A DEFER from the receive engine
  then costs nothing: the sender's redelivery finds the row and never touches the ratchet. The routing
  header's `finalTarget` must be us.
- **An OFFER whose seal is a `Replay` and has no row**: the crash edge (§3.3). The OFFER is NACKed
  PERMANENT, that hop ends, and the file comes back through REQUEST, served with a fresh seal.
- **A chunk**: the transfer row gives the seal; decrypt with its key and AAD; ingest at the offset.
  ACK = decrypted and durably staged, as today.
- **Incoming seal rows** are small (`sealId`, origin, `fileId`, key, expiry) and live until the seal
  expires, so late relay OFFERs are recognised. Incoming transfer rows end with their hop.

### 5.6 Completion

The origin cannot see what a relay does with its copy, and the origin and the target may never be
online together. Completion follows the delivery model ([`guide.md`](guide.md) §6): an
acknowledgement proves delivery, its absence proves nothing.

- **A hop ends when its receiver holds every chunk of it**: every chunk ACKed, or one coverage ACK that
  covers the whole shape. Nothing else is needed at hop level; there is no per-hop COMPLETE.
- **A seal is HANDED_OFF** when the target's ACKed prefix and one relay's whole remainder cover the
  file. Deposits start at the target's prefix, so any one relay that becomes whole suffices. The origin **stops pushing
  directly** then: the relays deliver, and repeating it directly would only duplicate
  traffic on the same reconnect.
- **A seal is DELIVERED** when a **COMPLETE** (`sealId`) reaches the origin, or when its direct transfer
  finishes: the target ACKed every chunk as durably staged, which is delivery as the message model
  defines it. The target sends COMPLETE to the seal's origin when the file is READY, directly; it is not
  relay-backed and may never arrive.
- **Otherwise the seal expires.** Until then its row stays, so a stray coverage ACK or COMPLETE still
  resolves. The pull path is the backstop: a target that ends up short REQUESTs, and the serve is a new
  seal.
- **Account level.** `FileSendStatus` gains `HANDED_OFF`; `COMPLETED` keeps its meaning (a COMPLETE
  arrived). The GUI shows "sent" at hand-off and "delivered" only when it knows; there is no failure
  state while the pull path exists.

### 5.7 Control packets and DECLINED

- ACK and CANCEL are hop packets, addressed by `transferId` to the hop's other end. COMPLETE is the only
  seal-level packet. REQUEST is unchanged (sealed, direct-only, one candidate at a time).
- A DECLINED is still read by what it answers ([`file/design.md`](file/design.md) §3.6): on a direct
  OFFER from the target it refuses the file (terminal for that device); on a relay's OFFER the depot
  is full (next relay); on a chunk the hop is gone at that end (the hop ends quietly). A relay whose
  forward is DECLINED by the target drops its copy.
- CANCEL reasons stay; a user cancel ends every live transfer of the seal and the seal itself.

### 5.8 Routing internals

- **The outbox carries no file logic.** `RelayBackup.PENDING` means "copy this envelope's content to
  relays" and only messages use it. `RelayDepositor` moves into `outbound` and the outbox owns it (it needs only relay
  selection and the outbox itself); `RelayCopies` and the `relayCopies` parameter
  of `OutboxProcessor` go, and with them the forward references in `DefaultRouter`.
- **Tag kinds:** `FILE_PUSH` and `FILE_FORWARD` collapse into `FILE_TRANSFER`; `FILE_REQUEST` stays.
- **One transfer actor** over a `ChunkSource` (`SealChunkSource`: plaintext from `FilePlaintextSource`
  encrypted with the seal key; `RelayChunkSource`: stored ciphertext). `ForwardActor` goes.
- **One inbound transfer handler** over a `ChunkSink` (`TargetSink`: decrypt and ingest; `RelaySink`:
  park). The shared checks (sender, index range, exact length) live once; `FileRelayDoor` keeps only
  admission and the budget.
- **A seal registry** owns seals at the origin (creation, deposit round, HANDED_OFF, COMPLETE, expiry,
  account-level events). Hops report to it; they never emit account events themselves.
- `FilePushKind` (DIRECT/DEPOSIT/SERVE), `via_relay`, the inner/outer target split in `FileEnvelope`,
  `FileInboundHandler`'s "inner target ≠ local → relay door" dispatch and the COMPLETE
  sibling-resolution in `FilePushEngine` all go: the receiving sink is chosen by the OFFER's
  `finalTarget`.

### 5.9 Schema (in `6.sq`)

```
-- routing: origin side
file_seals (
  seal_id BLOB PK,                    -- SHA-256 of the seal bytes
  file_id TEXT, target_device TEXT,
  seal_bytes BLOB,                    -- attached to every transfer of this seal (≤ ~38 KiB)
  seal_key BLOB,
  status TEXT,                        -- SENDING | HANDED_OFF | DELIVERED | DECLINED | ENDED | CANCELLED | EXPIRED
  kind TEXT,                          -- PUSH | SERVE
  created_at, expires_at, deposited_at NULL      -- deposit-round claim
)
file_transfers_out (                  -- replaces file_pushes: every hop this device sends
  transfer_id TEXT PK, seal_id BLOB NULL,        -- NULL for relay forwards (they hold no seal row)
  relay_transfer_id TEXT NULL,                   -- the relay's inbound transfer it forwards
  peer TEXT,                                     -- the hop's receiver
  chunk_size INT, byte_from INT, byte_to INT,
  next_chunk_index INT, contiguous_acked INT, marker_packet_id NULL,
  status TEXT, created_at, expires_at
)
file_transfer_chunks                  -- packet id → chunk index, keyed by the hop's transfer_id

-- routing: target side
file_incoming_seals (seal_id PK, origin_device, file_id, seal BLOB, expires_at)   -- the opened seal, key included
file_incoming_transfers (transfer_id PK, seal_id, sender_device, chunk_size, byte_from, byte_to, expires_at)

-- routing: relay side
file_relay_transfers (                -- the relay's inbound transfers
  transfer_id PK, seal_id, origin_device, final_target,
  chunk_size, byte_from, byte_to, seal_bytes BLOB,
  status, reserved_bytes, created_at, last_chunk_at, expires_at
)
file_relay_chunks (transfer_id, chunk_index PK, ciphertext BLOB)   -- ciphertext, not envelopes

-- routing: request attempts (unchanged but for the name: a REQUEST is no transfer)
file_request_attempts.request_attempt_id                           -- was request_transfer_id
```

The outbox's `tag_kind` values follow §5.8. Guide §4's ownership table changes with these rows (all
routing-owned; writers: the seal registry, the transfer engine, the relay door).

### 5.10 The orchestrator contract

Almost unchanged, which says the seam was in the right place: `FileCarrier`, `FileIngestPort` and
`FilePlaintextSource` keep their shape. Changes:

- `FileSendStatus` gains `HANDED_OFF`, and `FileEvent` gains `OutgoingHandedOff(fileId, target)`.
- `FileManifest.totalBytes` comes from the seal, not from a public header.
- `endIncoming(Ready)` makes routing send a seal-level COMPLETE to each origin and a full coverage ACK
  to each live hop sender; `endIncoming(Cancelled)` CANCELs the live hops.

## 6. Invariants

These replace [`file/design.md`](file/design.md) invariants 5, 6 and 19 and the "relay replays the
original signature" rule (§3, §7.1 there); the rest stand.

1. Every packet is signed by its hop's `source`; content is copied verbatim and never re-signed by its
   origin's signature.
2. Relay traffic is admitted only from its origin; relays never accept from relays; one hop, ever.
3. No hop extends a packet's `expiresAt`; content lifetime < `supersededSessionRetention`.
4. A ratchet frame is decrypted at most once; a `Replay` is a duplicate and is ACKed.
5. A seal is made once per (file, target device) per push and carried unchanged by every transfer of
   that push; a serve always makes a new seal.
6. The seal is the only carrier of the seal key; the key never leaves routing and protection.
7. Chunk AEAD binds (`sealId`, offset, length); placement comes from what decrypts, never from a
   public header.
8. A transfer is one hop with a fixed public shape; a relay forwards only a whole transfer.
9. Delivery is proven only by a COMPLETE or the direct transfer finishing; HANDED_OFF stops the
   origin's direct push.

## 7. Rejected alternatives

- **A seal per relay deposit** (the current design) — it forces the file engine into the outbox's
  relay backup and doubles the ratchet frames per deposit round, to avoid a duplicate that §3.3 now
  handles.
- **Reusing a live seal for a serve** — after the crash edge the target cannot open it again, and every
  serve would loop on `Replay`.
- **An `opened_content` table** (plaintext of opened frames until handled, to close the crash window) —
  a durable table for a window of milliseconds that the pull path already recovers.
- **Reusing the `dedup` table for seals** — it is packet-level, keyed by (source, packetId), pruned on
  a short window, and has no place for a key; the incoming seal row is needed for the key anyway and
  is the dedup.
- **Retrying the direct push until the TTL after hand-off** — it duplicates what the relays will
  deliver, usually on the same reconnect.
- **The chunk size inside the seal** — it would pin every transfer of a seal to one chunking; the
  offset in the AAD makes the public shape safe instead.
- **A depositor field** — the hop `source` is the depositor, and the relay rule compares it with
  `origin`.

## 8. Implementation order

1. **protocol:** `BinaryEnvelope` v2 (hop header, routing header, content, signature); per-family
   routing-header codecs; content schemes; `contentId`; FILE kinds per §4; drop the family envelope
   classes; observability profiles; codec round-trip tests.
2. **protection:** `HopProtection`; content protections per family on the new shapes; `Replay` →
   `DUPLICATE`; seal protection (open with the origin's session) and chunk AEAD with the new AAD; seal
   key type in place of `TransferKey`; delete `OpenedFrameCache`.
3. **routing kernel:** sign in the dispatcher/outbox path, verify in `InboundEnvelopeProcessor` with
   the unknown-source exceptions; `DUPLICATE` answered as ACK; message relay transit per §3.2 with
   expiry copied; `RelayDepositor` into `outbound`, `relayCopies` removed; `FILE_TRANSFER` tag.
4. **schema:** the tables of §5.9 in `6.sq` and their stores.
5. **file engine:** seal registry, the transfer actor over chunk sources, the inbound handler over
   chunk sinks, relay door admission, deposit triggers for both phases, HANDED_OFF/COMPLETE, serve with
   a fresh seal.
6. **orchestrator seam:** `HANDED_OFF`, the new event, manifest from the seal.
7. **docs:** §10.

## 9. Out of scope

The large path (WebRTC fetch), the receive engine's coverage and checkpoint model, the REQUEST loop and
serve gate, block hashing and the file store are unchanged.

## 10. Docs to update when this lands

- [`guide.md`](guide.md): §2 protocol and protection bullets (one envelope, hop signing), §4 ownership
  rows for the new tables, §6 outcomes (tag kinds, relay backup is message-only) and dispositions (`DUPLICATE` is
  ACKed), and its links to the deleted `file/plan.md` and `file/refactor.md`.
- [`router.md`](router.md): inbound verification, relay transit, `RelayDepositor` in `outbound`.
- [`file/design.md`](file/design.md): §2–§7, §12–§14 and §16 for seals and hops; remove what §6 and §7
  here replace, including §3.3's "protection keeps the plaintext of frames it opened".
- [`e2ee.md`](e2ee.md): `Replay` as a duplicate; the content lifetime invariant.
- [`transport.md`](transport.md): only if it describes the envelope layout.

## 11. Implementation notes

Where the code settled what this document left open (the code is the reference for the rest):

- **Packages.** `protocol/envelopes`: `BinaryEnvelope` (+ `UnsignedEnvelope`), `MessageHeader`,
  `SignalHeader`, `SystemPayload` (the SYSTEM header), `BootstrapHeader`, `FileHeader` with
  `TransferShape`, `FileSeal` and `FileRequest`; `protocol/SealKey.kt` holds `SealKey` and `ContentId`.
  `protection/envelope`: `HopProtection` (`SignedHopProtection`), `RatchetSealer` shared by the
  ratchet families, `MessageProtection`, `WebRtcSignalProtection`, `BootstrapProtection`,
  `FileProtection`; `EnvelopeProtections` bundles them.
- **Signing.** `RoutingContext.envelope(...)` builds and signs every envelope we send, so no routing
  code assembles an unsigned one. `InboundEnvelopeProcessor` verifies a known source's signature
  before dedup, so a forged packet never claims a real packet id; a failed check is dropped
  unanswered. `InboundHandleResult.Success` no longer carries `sourceAuthenticated`.
- **Outbox.** `OutboxProcessor` owns `RelayDepositor` (messages only) and takes the relay policy;
  `backUpToRelays(envelope, reason)` has no tag. One file tag kind, `FILE_TRANSFER`.
- **File routing** (`routing/file`): `transfer/TransferEngine` runs every outgoing transfer (ours and
  relay forwards) over a `ChunkSource` and reports `TransferEvent`s (progress, gone, ended) on an
  unbounded flow; `seal/SealRegistry` owns seals, the deposit round, hand-off, delivery and the
  account-level events; `relay/RelayDoor` admits deposits and parks chunks, `relay/RelayForwards`
  forwards whole deposits and forgets them when the forward ends; `inbound/TargetSide` holds seals
  and incoming transfers and feeds the receive engine; `inbound/FileInboundHandler` dispatches by
  header and runs the shared chunk checks before a `ChunkSink` sees a chunk. The registry and the
  forwards subscribe to the engine's events (guide §5), and each sweeps its rows so a restart that
  cut an event short still settles.
- **Gone signals.** Before the OFFER is ACKed: no link to the peer, or no ACK within
  `relayBackupAfter`. After: chunks in flight and no link, or no ACK at all for `chunkAckTimeout`, or
  the peer's link closing. Only a seal's direct transfer acts on them (one deposit round).
- **Config.** `wiring/ConfigInvariants` also checks `binaryEnvelopeLifetime <
  supersededSessionRetention` (relayed messages keep their first hop's expiry).
