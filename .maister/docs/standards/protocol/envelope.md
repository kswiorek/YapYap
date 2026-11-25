## Envelope

The wire envelope: one type for every packet family, hop signatures, relay admission. This file derives from
docs/envelope-redesign.md §2, §3 and §6 (the design of record for the envelope layering, agreed 2026-11-24) and
docs/guide.md §2 (protocol service). The old per-family envelope classes and the SIGNED / PLAINTEXT_TEST_ONLY schemes
are gone.

### One BinaryEnvelope for every packet family

One envelope type on the wire, `BinaryEnvelope` v2, for every family (MESSAGE, SIGNAL, SYSTEM, BOOTSTRAP, FILE). Shape:
a signed hop header (one hop: who handed these bytes to whom, now — source and target are always the two ends of this
hop), a public routing header chosen by packetType, opaque end-to-end content, and an Ed25519 hop signature by source.

Sources: docs (envelope-redesign.md §2; guide.md §2) · confidence 91.
Evidence:

- envelope-redesign.md §2: "One envelope type on the wire, `BinaryEnvelope` version 2, for every family" with the layout
  "hop header … routing header … content … hop signature Ed25519 by `source`, 64 bytes".
- envelope-redesign.md §2: "The old `SIGNED` and `PLAINTEXT_TEST_ONLY` schemes are gone: the hop signature authenticates
  every hop."
- guide.md §2 (protocol service): "the one `BinaryEnvelope` every family shares (a signed hop header, a public routing
  header, end-to-end content)".

Layout: MAGIC | VERSION=2; hop header (packetType u8, flags u8 with dispositionRequested, createdAt i64, expiresAt i64,
packetId uuid, source PeerId, target PeerId); routing header (length-prefixed, codec by packetType, public); content
(length-prefixed, opaque to hops); hop signature.

### Every hop signs; content is copied verbatim, never re-signed

Every packet is signed by its hop's source; relays sign what they forward. The hop signature covers hop header ‖ routing
header ‖ contentId (the content's hash, so the signing input stays small and the hash is computed once). Content is
copied verbatim end-to-end and never re-signed by its origin's signature; per-family nonce/signature fields are gone
(packetId is in the signed bytes). `RoutingContext.envelope(...)` builds and signs every envelope we send, so no routing
code assembles an unsigned one.

Sources: docs (envelope-redesign.md §2, §6, §11) · confidence 91.
Evidence:

- envelope-redesign.md §6 (Invariant 1): "Every packet is signed by its hop's `source`; content is copied verbatim and
  never re-signed by its origin's signature."
- envelope-redesign.md §2: the hop signature covers `hop header ‖ routing header ‖ contentId`; "Every hop signs what it
  sends, including relays forwarding someone else's content."
- envelope-redesign.md §11: "`RoutingContext.envelope(...)` builds and signs every envelope we send, so no routing code
  assembles an unsigned one."

### contentId = SHA-256 (content), derived, never on the wire

`contentId` is SHA-256 of the content, 32 bytes. It is derived, never put on the wire. It is what the hop signature
covers, what relays key their storage by, and for a file OFFER it is the `sealId`.

Sources: docs (envelope-redesign.md §2) · confidence 90.
Evidence:

- envelope-redesign.md §2: "**`contentId` = SHA-256 (content)**, 32 bytes. It is derived, never on the wire. It is what
  the hop signature covers, what relays key their storage by, and for a file OFFER it is the `sealId` (§5.2)."

### Expiry is the content's, not the hop's; never extended

The first hop sets `expiresAt`; every later hop copies it and never extends it. For a file transfer it is the seal's
expiry. Content lifetime (`binaryEnvelopeLifetime`, `fileTransferTtl`) must stay below `supersededSessionRetention` so
sealed/ratcheted content stays openable for as long as any copy can arrive.

Sources: docs (envelope-redesign.md §2, §6) · confidence 90.
Evidence:

- envelope-redesign.md §2: "**Expiry is the content's, not the hop's.** The first hop sets `expiresAt`; every later hop
  copies it and never extends it. For a file transfer it is the seal's expiry (§5.2)."
- envelope-redesign.md §6 (Invariant 3): "No hop extends a packet's `expiresAt`; content lifetime <
  `supersededSessionRetention`."

### Relay admission: only from the origin; one hop, ever

A packet whose routing header names a final target other than us is relay traffic. It is admitted only when the hop
source is the packet's origin, the origin is a known, non-banned device, and the final target is a known device. Relays
never accept from relays (the one-hop rule closes the message cache-stuffing hole). A forward is a new hop envelope from
the relay: new packetId, relay as source, routing header and content copied, expiresAt copied, signed by the relay. A
forwarding relay never deposits elsewhere.

Sources: docs (envelope-redesign.md §3.2, §6) · confidence 91.
Evidence:

- envelope-redesign.md §3.2: "It is admitted only when the hop `source` is the packet's `origin`, the origin is a known,
  non-banned device, and the final target is a known device. This gives every family the same one-hop rule (relays never
  accept from relays)."
- envelope-redesign.md §6 (Invariant 2): "Relay traffic is admitted only from its origin; relays never accept from
  relays; one hop, ever."
- envelope-redesign.md §3.2: "A forward is a new hop envelope from the relay to the final target: new `packetId`, the
  relay as `source`, the routing header and content copied … `expiresAt` copied, signed by the relay."

### A ratchet Replay is a duplicate, not a violation — ACK it

The same content can legitimately reach its target twice (direct + relay copy, two relay copies). A ratchet Replay means
the frame was opened before; protection maps it to `ProtectionException.Duplicate` (reason DUPLICATE, disposition stays
PERMANENT since these bytes can never be opened again) and the inbound processor answers it like a processed packet:
ACK, logged at debug. Nothing is processed twice; a forged duplicate earns an ACK and nothing else.

Sources: docs (envelope-redesign.md §3.3, §6; e2ee.md) · confidence 90.
Evidence:

- envelope-redesign.md §3.3: "A ratchet `Replay` is a duplicate, not a violation. … the inbound processor answers it
  like a processed packet: ACK, logged at debug."
- envelope-redesign.md §6 (Invariant 4): "A ratchet frame is decrypted at most once; a `Replay` is a duplicate and is
  ACKed."
- e2ee.md (End-to-end encryption path): "A frame opened before (`CryptoSessionException.Replay`) is a duplicate — the
  same content by two routes — and is ACKed, not refused."

### Observability profiles must be honest labels

Each family's routing header and content get an observability profile (`EnvelopeObservability`) saying which routing
metadata is visible on the wire. Labels must be honest: SYSTEM payloads, only signed, are marked routing-visible, not
PROTECTED.

Sources: docs (envelope-redesign.md §2; guide.md §2) · confidence 85.
Evidence:

- envelope-redesign.md §2: "SYSTEM payloads are only signed today, though their profile says `PROTECTED`; the new
  profile marks them routing-visible, which is the honest label."
- guide.md §2 (protocol service): "the observability profiles (`EnvelopeObservability`) that say which routing metadata
  is visible on the wire."

### Pre-fleet schema/wire changes are made plainly (no version negotiation)

While no fleet exists (the files branch resets every database on merge), wire and schema changes are made plainly: no
version negotiation, no decoders for old layouts; `6.sq` remains the whole migration. Old nodes failing decode on a new
payload type NACK `DECODE_FAILED` without storing — acceptable pre-fleet; bump nothing, document it.

Sources: docs (envelope-redesign.md intro; room events.md §2) · confidence 84.
Evidence:

- envelope-redesign.md (intro): "The `files` branch resets every database when it merges, so wire and schema changes are
  made plainly: no version negotiation, no decoders for old layouts, and `6.sq` remains the whole migration."
- room events.md §2 (Protocol note): "old nodes fail decode on the new payload type and NACK `DECODE_FAILED` without
  storing — acceptable pre-fleet; bump nothing, document it."
