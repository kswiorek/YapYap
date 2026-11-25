## E2EE

The end-to-end encryption layer: X3DH epochs, Double Ratchet, session lifecycle. The authoritative source for this file
is docs/e2ee.md, which documents the implemented design (session model, hardening, test coverage) — this is a deliberate
serverless, offline-first adaptation of the Signal design for a small trusted mesh, not a byte-for-byte Signal Protocol
implementation.

### Epoch model: 3-DH first contact, optional in-band 4-DH upgrade

Sessions use per-peer-device-pair epochs. Epoch 1 is 3-DH — first contact while the responder may be offline; no
one-time prekey (OPK) required upfront. Epoch 2 is an optional 4-DH upgrade after the responder offers an OPK in-band
over the epoch-1 ratchet. Both sides can stay on 3-DH indefinitely (fail-soft).

Sources: docs (e2ee.md — Session model / Epochs) · confidence 88.
Evidence:

- e2ee.md: the epoch table — epoch 1 = 3-DH "First contact while responder may be offline. No one-time prekey (OPK)
  required upfront."; epoch 2 = 4-DH "Optional upgrade after responder offers an OPK in-band over the epoch-1 ratchet."
- e2ee.md: "This is **not** a byte-for-byte Signal Protocol implementation. It is a serverless, offline-first adaptation
  for a small trusted mesh without a central prekey bundle server."
- e2ee.md (Fail-soft OPK policy): "Both sides can stay on 3-DH indefinitely."

### YapYap-specific HKDF labels

X3DH and Double Ratchet use YapYap-specific HKDF `info` strings (`YapYapX3DH`, `YapYapDR_RK`, `YapYapDR_CK`) — not
libsignal-compatible, accepted for an internal protocol.

Sources: docs (e2ee.md — Custom KDF labels) · confidence 87.
Evidence:

- e2ee.md: "X3DH and Double Ratchet use YapYap-specific HKDF `info` strings (`YapYapX3DH`, `YapYapDR_RK`,
  `YapYapDR_CK`). This is not libsignal-compatible but is acceptable for an internal protocol."

### Signal-aligned ratchet bootstrap

After X3DH, the initiator's first ratchet sending key is the X3DH ephemeral (`EK_A`), not a separately generated key;
the responder seeds the ratchet with its signed prekey (`SPK_B`) as local DH material. The first outbound
`RatchetCiphertext.dhPublicKey` therefore matches `outerHandshake.ephemeralPublicKey` (Signal/libsignal convention).

Sources: docs (e2ee.md — Signal-aligned ratchet bootstrap) · confidence 89.
Evidence:

- e2ee.md: "the initiator's **first ratchet sending key** is the X3DH ephemeral (`EK_A`), not a separately generated
  key. The responder seeds the ratchet with its **signed prekey** (`SPK_B`) as local DH material."

### Ratchet header authenticated via AEAD associated data

`RatchetCiphertext` header fields (`dhPublicKey`, `messageNumber`, `previousChainLength`) are authenticated but not
encrypted, bound to the ciphertext body via ChaCha20-Poly1305 AAD using canonical header serialization. Tampering with
any header field causes AEAD verification failure rather than silent corruption; the skipped-message decrypt path also
passes AAD.

Sources: docs (e2ee.md — Ratchet header AAD) · confidence 90.
Evidence:

- e2ee.md: "header fields … are authenticated but not encrypted. They are bound to the ciphertext body via
  ChaCha20-Poly1305 AAD" and "Tampering with any header field causes AEAD verification failure rather than silent
  corruption."

### Fresh ephemeral on epoch-2 upgrade

`createEpoch2AsInitiator` generates a new ephemeral key pair for 4-DH; it must not reuse the epoch-1 ephemeral that was
already sent in cleartext in the first `outerHandshake`.

Sources: docs (e2ee.md — Fresh ephemeral on epoch-2 upgrade) · confidence 89.
Evidence:

- e2ee.md: "`createEpoch2AsInitiator` generates a **new** ephemeral key pair for 4-DH. It does not reuse the epoch-1
  ephemeral that was already sent in cleartext in the first `outerHandshake`."

### Canonical session invariant and sessionGeneration

At most one ACTIVE canonical row per `(peer, sessionEpoch)`, enforced by a partial unique index in `Crypto.sq`. A
`sessionGeneration` counter distinguishes successive cryptographic sessions with the same peer (on wire via
`SessionWireFrame` + `X3dhWireInfo`); each generation is a separate `crypto_sessions` row — new bootstrap bumps
generation instead of overwriting superseded rows. Epoch-2 initiator rows start PENDING until promoted; abandoned
PENDING rows are deleted after `pendingEpoch2RetentionSeconds`; superseded rows are retained for
`supersededRetentionSeconds` from supersede time and stay decryptable until pruned.

Sources: docs (e2ee.md — Session lifecycle) · confidence 90.
Evidence:

- e2ee.md: "**Canonical invariant** — at most one `ACTIVE` canonical row per `(peer, sessionEpoch)`; partial unique
  index in `Crypto.sq`."
- e2ee.md: "**`sessionGeneration`** — on wire (`SessionWireFrame` + `X3dhWireInfo`) and in `CryptoSessionMeta`; new
  bootstrap bumps generation instead of overwriting superseded rows."
- e2ee.md: "Abandoned `PENDING` epoch-2 rows are **deleted** by `DefaultCryptoHousekeeping` after
  `pendingEpoch2RetentionSeconds` (default 2 days, aligned with offer TTL)."

### Handshake attaches while the peer has not replied (recvMessageNumber == 0)

The initiator attaches `outerHandshake` on each send until the peer has replied (`recvMessageNumber == 0` on the
initiator session), for epoch 1 and 2 — so a lost first message or out-of-order delivery still lets the peer bootstrap.

Sources: docs (e2ee.md — P0.2, Fixed) · confidence 90.
Evidence:

- e2ee.md: "`shouldAttachOutboundWire` attaches `outerHandshake` while the initiator has **`recvMessageNumber == 0`**
  (peer has not yet replied on that session), for epoch 1 and 2."

### Wire metadata cross-validation at bootstrap

`bootstrapFromFrame` must cross-validate: `frame.sessionEpoch == wire.sessionEpoch`;
`frame.sessionGeneration == wire.sessionGeneration`; wire mode consistency with epoch (THREE_DH for epoch 1, FOUR_DH for
epoch 2); and the epoch-2 SPK pin — `bootstrapEpoch2Responder` requires
`wire.signedPreKeyId == epoch1.meta.handshakeSpkId` before OPK consume.

Sources: docs (e2ee.md — P0.3, Fixed) · confidence 89.
Evidence:

- e2ee.md: "`frame.sessionEpoch == wire.sessionEpoch` — validated in `bootstrapFromFrame`." plus the generation and mode
  consistency checks.
- e2ee.md: "**Epoch-2 SPK pin** — `bootstrapEpoch2Responder` requires
  `wire.signedPreKeyId == epoch1.meta.handshakeSpkId` before OPK consume."

### OPK lifecycle and offer binding

OPK state machine on `one_time_prekeys`: ALLOCATED → OFFERED → CONSUMED; consume requires OFFERED; offered OPKs are
pruned after a TTL. The same OPK is re-offered on every epoch-1 outbound message until epoch 2 is confirmed. Upgrade
offers are accepted only when decrypting on a canonical ACTIVE epoch-1 initiator session with matching generation and a
valid 32-byte `sessionBinding`; invalid offers are ignored (plaintext still delivered). Fail-soft: skip the offer when
OPK allocation/mark fails; both sides can stay on 3-DH indefinitely.

Sources: docs (e2ee.md — P1.5, P1.6) · confidence 89.
Evidence:

- e2ee.md: "`OpkStatus` on `one_time_prekeys`: `ALLOCATED` → `OFFERED` → `CONSUMED`. … `consume(opkId)` requires
  `OFFERED`."
- e2ee.md: "`maybeUpgradeToEpoch2` accepts offers only when decrypting on **canonical `ACTIVE` epoch-1 initiator**
  session with matching generation and valid `sessionBinding`; invalid offers are ignored (plaintext still delivered)."
- e2ee.md: "Bob **re-offers the same OPK** on every epoch-1 outbound message until epoch-2 is confirmed"; "**Fail-soft
  OPK policy:** Bob skips `OpkOffer` attachment when OPK allocation/mark fails."

### Simultaneous initiation resolved by device-id tie-break

When both peers send a first message before either receives the other's, a lexicographic device-id tie-break decides
roles: the lower ID becomes the canonical responder, the higher ID keeps the canonical initiator; rogue duplicate rows
are marked SUPERSEDED.

Sources: docs (e2ee.md — P0.1, Fixed) · confidence 89.
Evidence:

- e2ee.md: "Lexicographic device-id tie-break — lower ID becomes canonical **responder**; higher ID keeps canonical
  **initiator**. Rogue duplicate rows marked `SUPERSEDED` when `supersedeRogueSessionsAfterSimultaneousInit` is
  enabled."

### Transactional ratchet decrypt (no state mutation on failure)

Double Ratchet decrypt must be transactional: snapshot/rollback of ratchet state on any failure; skipped message keys
removed only after successful `decryptAead`; an early replay guard throws `CryptoSessionException.Replay` on stale
`messageNumber` with no matching skipped key before mutating receive state; inbound generation reset runs only after the
first decrypt succeeds (a failed bootstrap+decrypt must not supersede the prior canonical row).

Sources: docs (e2ee.md — P2.9, Fixed) · confidence 90.
Evidence:

- e2ee.md: "**Snapshot rollback** — `decrypt` checkpoints `RatchetSessionState`, runs `decryptPlaintext`, and restores
  on any failure. **Deferred skipped-key removal** — skipped message keys are removed only after successful
  `decryptAead`. **Early replay guard** …"
- e2ee.md: "**Manager transaction ordering** — `handleInboundGenerationReset` runs after successful `decryptRatchet` …
  so a failed bootstrap+decrypt does not supersede the prior canonical row."

### Decode bounds are protocol constants at the codec layer

All wire decoders enforce size bounds via `CryptoWireLimits` compile-time constants (`ByteReader.readByteArray(maxSize)`
validates length before allocation), applied by `SessionWireFrame`, `RatchetCiphertext`, `RatchetInnerPlaintext`,
`InnerSessionControl` and the skipped-keys codec. Outbound checks reject oversized inner plaintext and oversized signed
payload before decode. No runtime config threading — limits are protocol constants at the codec layer.

Sources: docs (e2ee.md — P2.11, Fixed) · confidence 90.
Evidence:

- e2ee.md: "**`CryptoWireLimits`** — compile-time constants in `CryptoSessionTypes.kt` … **Decode enforcement** —
  `ByteReader.readByteArray(maxSize)` validates length before allocation" and "No runtime config threading — limits are
  protocol constants at the codec layer."

### Immutable device identity — no in-place key rotation

`device_id` is derived from the device signing public key (`peerIdFromPublicKey`). Signing and encryption identity keys
are fixed at provision time and never rotated in place on an existing device record. New keys for a user mean a new
device (new `device_id`, new roster entry, fresh X3DH sessions). Compromise is handled by banning/retiring the old
device (sessions for that peer_device_id are pruned or wiped); in-place IK/encryption-key replacement on the same
`device_id` is unsupported — indistinguishable from roster MITM. SPK rotation is a separate deferred concern;
`sessionGeneration` bumps handle crypto-session lifecycle only.

Sources: docs (e2ee.md — Immutable device identity; guide.md §7; global events.md §3) · confidence 92.
Evidence:

- e2ee.md: "`device_id` is derived from the device **signing** public key (`peerIdFromPublicKey`). Signing and
  encryption identity keys are fixed at provision time and are **not rotated in place** on an existing device record."
- e2ee.md (policy table): "New keys for a user → **New device** … In-place IK/encryption key replacement on same
  `device_id` → **Unsupported** — indistinguishable from roster MITM without a heavy re-trust flow."
- guide.md §7: "Device identity keys are immutable; there is no identity-key rotation."
- global events.md §3: "Re-adding after removal requires a fresh key set → new device_id → effectively a new device.
  This is what makes removal a ban."

### Cleartext outerHandshake is a conscious design choice

X3DH public material (ephemeral key, signed prekey ID, epoch, mode) is sent in `SessionWireFrame.outerHandshake` outside
the ratchet ciphertext and is not confidential — required by X3DH itself. On a direct send the origin's hop signature
covers it; on a relayed one the origin's authorship rests on the handshake and ratchet: a frame decrypts only under keys
derived from the origin's identity key, which a relay never holds, so tampering yields a decrypt failure, never a
forgery.

Sources: docs (e2ee.md — Cleartext `outerHandshake`) · confidence 87.
Evidence:

- e2ee.md: "This matches the fundamental constraint of X3DH: the responder must see DH public keys to derive the shared
  secret. The block is **not confidential** … a frame decrypts only under keys derived from the origin's identity key,
  which a relay never holds."

### SessionUpgradePolicy gates epoch-2 in production wiring

`DefaultCryptoSessionManager` defaults to `SessionUpgradePolicy.NEVER` during early development; production wiring
should enable `OFFER_OPK_ON_FIRST_EPOCH1_REPLY` when the upgrade path is ready.

Sources: docs (e2ee.md — `SessionUpgradePolicy`) · confidence 84.
Evidence:

- e2ee.md: "`DefaultCryptoSessionManager` defaults to `SessionUpgradePolicy.NEVER` during early development. Production
  wiring should enable `OFFER_OPK_ON_FIRST_EPOCH1_REPLY` when the upgrade path is ready."
