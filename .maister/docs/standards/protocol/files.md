## Files

The file-transfer protocol: seals, transfers, chunks. The design of record for this file is docs/envelope-redesign.md
(agreed 2026-11-24, implemented on the `files` branch) §5 — it supersedes docs/file/ wherever the two disagree.
**docs/file/ is provisional and unreviewed per the owner; do not mine it for conventions.** The implementation under
orchestrator/file/ and routing/file/ is likewise freshly redesigned and unreviewed.

### File grains: fileId (orchestrator), sealId (routing), transferId (routing)

File transfer has three grains with distinct owners. **File** (`fileId`) — content identity, block-hashed, owned by the
orchestrator. **Seal** (`sealId`) — one file sealed for one target device by one origin, end-to-end, owned by routing.
**Transfer** (`transferId`) — one hop between two devices moving a range of one seal's chunks, owned by routing. The
transfer machinery only ensures every chunk it described arrives; what the receiving end does with chunks is its sink's
business, and where the sending end gets them is its source's.

Sources: docs (envelope-redesign.md §5.1) · confidence 90.
Evidence:

- envelope-redesign.md §5.1: the grain table — File | `fileId` | content identity, block-hashed | orchestrator; Seal |
  `sealId` | one file sealed for one target device by one origin; end to end | routing; Transfer | `transferId` | one
  hop between two devices moving a range of one seal's chunks | routing.
- envelope-redesign.md §5.1: "The transfer machinery only makes sure every chunk it described arrives. What the
  receiving end does with them is its **sink**'s business … and where the sending end gets them is its **source**'s."

### One seal per (file, target device); a serve always mints a fresh seal

A seal is made once per (file, target device) at the origin when the push starts and kept until it expires; it is never
re-made for the same push — the direct transfer and every relay deposit carry the same seal bytes. `sealId` is the
contentId of the OFFER content (SHA-256 of the seal bytes), so nobody can swap a seal under an id. A serve (REQUEST)
always mints a fresh seal — the requester may hold a seal it can no longer open (the crash edge), and a fresh seal never
loops. Seal expiry is fixed at creation (createdAt + fileTransferTtl) and used by every transfer at every hop.

Sources: docs (envelope-redesign.md §5.2, §6) · confidence 91.
Evidence:

- envelope-redesign.md §5.2: "One seal per (file, target device) at the origin, made when the push starts and kept until
  it expires. It is **never re-made for the same push**."
- envelope-redesign.md §5.2: "**A serve (REQUEST) always mints a fresh seal.** … a fresh seal costs one ratchet frame
  and never loops."
- envelope-redesign.md §6 (Invariant 5): "A seal is made once per (file, target device) per push and carried unchanged
  by every transfer of that push; a serve always makes a new seal."

### The seal key never leaves routing and protection

The seal key (`SealKey`, 32 bytes, ChaCha20-Poly1305) travels only inside the ratchet-encrypted seal; the seal is the
only carrier of the seal key, and the key never leaves routing and protection.

Sources: docs (envelope-redesign.md §6) · confidence 90.
Evidence:

- envelope-redesign.md §6 (Invariant 6): "The seal is the only carrier of the seal key; the key never leaves routing and
  protection."

### Chunk AEAD binds the byte range; placement from what decrypts

Chunk AEAD binds the byte range, not the transfer: key = the seal key, AAD = (`sealId`, `offset` u64, `length` u32). The
target takes the offset from what decrypts, never from the hop's claimed shape, so a lying relay produces a decrypt
failure, not a write at the wrong place; any chunk that decrypts is placed by its offset and checked against totalBytes.
Chunkings may differ between transfers of one seal (ciphertext cannot be re-chunked); a transfer covers a contiguous
byte range ending at totalBytes (suffixes stay, resume moves only byteFrom). Chunks follow their OFFER at every hop — a
chunk for an unknown `transferId` is DECLINED, never stored.

Sources: docs (envelope-redesign.md §5.3, §6) · confidence 90.
Evidence:

- envelope-redesign.md §5.3: "**Chunk AEAD binds the byte range, not the transfer:** key = the seal key, AAD =
  (`sealId`, `offset` u64, `length` u32). The target takes the offset from what decrypts, never from the hop's claimed
  shape."
- envelope-redesign.md §6 (Invariants 7–8): "Chunk AEAD binds (`sealId`, offset, length); placement comes from what
  decrypts, never from a public header." / "A transfer is one hop with a fixed public shape; a relay forwards only a
  whole transfer."
- envelope-redesign.md §5.3: "**Chunks follow their OFFER** at every hop, as today: a chunk for an unknown `transferId`
  is DECLINED, never stored."

### Relay forwards only a whole transfer; DECLINED is read by what it answers

A relay admits the OFFER (hop source == origin), stores the seal bytes once and each chunk's ciphertext, ACKs chunks
with its coverage, answers DECLINED for an unknown `transferId`, an index outside the shape or a wrong ciphertext
length, and forwards only when whole (every chunk of its inbound transfer present). A DECLINED is read by what it
answers: on a direct OFFER from the target it refuses the file (terminal for that device); on a relay's OFFER the depot
is full (next relay); on a chunk the hop is gone at that end (the hop ends quietly). A user cancel ends every live
transfer of the seal and the seal itself.

Sources: docs (envelope-redesign.md §5.4, §5.7) · confidence 90.
Evidence:

- envelope-redesign.md §5.4: "It admits the OFFER (§3.2: hop source == origin) … **It forwards only when whole**: every
  chunk of its inbound transfer present."
- envelope-redesign.md §5.7: "A DECLINED is still read by what it answers …: on a direct OFFER from the target it
  refuses the file (terminal for that device); on a relay's OFFER the depot is full (next relay); on a chunk the hop is
  gone at that end (the hop ends quietly)."

### Completion: hop-level ACKs, seal-level HANDED_OFF and DELIVERED

A hop ends when its receiver holds every chunk of it (every chunk ACKed, or one coverage ACK covering the whole shape) —
there is no per-hop COMPLETE. A seal is HANDED_OFF when the target's ACKed prefix and one relay's whole remainder cover
the file; the origin then stops pushing directly. A seal is DELIVERED when a COMPLETE (sealId) reaches the origin or its
direct transfer finishes. Otherwise the seal expires; the pull path (REQUEST) is the backstop. Delivery is proven only
by a COMPLETE or the direct transfer finishing.

Sources: docs (envelope-redesign.md §5.6, §6) · confidence 90.
Evidence:

- envelope-redesign.md §5.6: "A hop ends when its receiver holds every chunk of it: every chunk ACKed, or one coverage
  ACK that covers the whole shape. … there is no per-hop COMPLETE."
- envelope-redesign.md §5.6: "A seal is HANDED_OFF when the target's ACKed prefix and one relay's whole remainder cover
  the file … The origin **stops pushing directly** then."
- envelope-redesign.md §6 (Invariant 9): "Delivery is proven only by a COMPLETE or the direct transfer finishing;
  HANDED_OFF stops the origin's direct push."

### Incoming seal row is written the moment the seal opens

At the target, an OFFER with a known `sealId` is never decrypted (the transfer is bound to the stored seal, the OFFER is
ACKed; if the file is already READY, a coverage ACK covering everything ends the hop). An OFFER with a new `sealId` is
opened with the origin's session and the incoming seal row is written as soon as it opens, before `checkOffer` — so a
DEFER from the receive engine costs nothing and a redelivered OFFER never touches the ratchet. The routing header's
finalTarget must be us.

Sources: docs (envelope-redesign.md §5.5) · confidence 89.
Evidence:

- envelope-redesign.md §5.5: "An OFFER with a new `sealId`: the seal is opened with the session of `origin`, and the
  incoming seal row is written **as soon as it opens**, before `checkOffer`. A DEFER from the receive engine then costs
  nothing: the sender's redelivery finds the row and never touches the ratchet."
- envelope-redesign.md §5.5: "An OFFER with a known `sealId`: no decryption. The transfer is bound to the stored seal;
  the OFFER is ACKed."

### Seal registry owns seals; hops never emit account events

A seal registry (routing) owns seals at the origin — creation, deposit round, HANDED_OFF, COMPLETE, expiry,
account-level events. Hops report to it; they never emit account events themselves. ACK and CANCEL are hop packets
addressed by `transferId`; COMPLETE is the only seal-level packet; REQUEST is sealed, direct-only, one candidate at a
time.

Sources: docs (envelope-redesign.md §5.7, §5.8) · confidence 89.
Evidence:

- envelope-redesign.md §5.8: "**A seal registry** owns seals at the origin (creation, deposit round, HANDED_OFF,
  COMPLETE, expiry, account-level events). Hops report to it; they never emit account events themselves."
- envelope-redesign.md §5.7: "ACK and CANCEL are hop packets, addressed by `transferId` to the hop's other end. COMPLETE
  is the only seal-level packet. REQUEST is unchanged (sealed, direct-only, one candidate at a time)."

### fileId stays off the wire

`fileId` stays off the wire everywhere except inside sealed content (the seal, REQUEST) and the large path's FETCH
frame. File facts only inside the seal: no chunk size, no transfer data.

Sources: docs (envelope-redesign.md §4, §5.2) · confidence 88.
Evidence:

- envelope-redesign.md §4: "`fileId` stays off the wire everywhere except inside sealed content (the seal, REQUEST) and
  the large path's `FETCH` frame."
- envelope-redesign.md §5.2: "File facts only: no chunk size, no transfer data."
