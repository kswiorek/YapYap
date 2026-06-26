# YapYap E2EE Architecture & Security Notes

This document describes the current 1-on-1 end-to-end encryption (E2EE) stack, intentional design choices, implemented hardening, and known gaps to address before building further protocol features on top.

Related diagrams:

- [`e2ee diagram.mmd`](e2ee%20diagram.mmd) — high-level X3DH + Double Ratchet flow
- [`x3dh diagram.mmd`](x3dh%20diagram.mmd) — X3DH handshake detail

Primary implementation locations:

| Area | Package / type |
|------|----------------|
| X3DH | `org.yapyap.backend.crypto.e2ee.X3dhHandshake` |
| Double Ratchet | `org.yapyap.backend.crypto.e2ee.DoubleRatchetSession` |
| Session orchestration | `org.yapyap.backend.crypto.e2ee.DefaultCryptoSessionManager` |
| Wire codec | `org.yapyap.backend.crypto.e2ee.SessionWireFrame`, `RatchetCiphertext` |
| Envelope integration | `org.yapyap.backend.protection.SignedAndEncryptedMessageProtection` |
| Persistence | `org.yapyap.backend.db.DefaultCryptoSessionStore` |

---

## End-to-end encryption path

```
Router.sendMessage
  → SignedAndEncryptedMessageProtection.protect
    → DefaultCryptoSessionManager.encryptMessage
      → X3DH bootstrap (first contact, epoch 1)
      → DoubleRatchetSession.encrypt
    → SessionWireFrame.encode → MessageEnvelope.payload
  → Ed25519 signature over MessageEnvelope
  → BinaryEnvelope → Tor / WebRTC
```

On receive, the path is reversed: verify envelope signature, decode `SessionWireFrame`, bootstrap session from `outerHandshake` if needed, Double Ratchet decrypt, decode inner application bytes.

### Layer responsibilities

| Layer | Protects | Observable on the wire |
|-------|----------|------------------------|
| `BinaryEnvelope` | Routing, dedup, delivery | `source`, `target`, `packetId`, `packetType` |
| `MessageEnvelope` | Integrity (Ed25519) | Headers + signed blob (includes crypto payload) |
| `SessionWireFrame` | Session epoch, optional X3DH wire info | Inside signed payload |
| `RatchetCiphertext` | Message confidentiality + header integrity (AEAD + AAD) | DH pubkey, counters in ratchet header; body encrypted |

---

## Session model

### Epochs

YapYap uses **session epochs** per peer device pair:

| Epoch | X3DH mode | Purpose |
|-------|-----------|---------|
| **1** | 3-DH | First contact while responder may be offline. No one-time prekey (OPK) required upfront. |
| **2** | 4-DH | Optional upgrade after responder offers an OPK in-band over the epoch-1 ratchet. |

This is **not** a byte-for-byte Signal Protocol implementation. It is a serverless, offline-first adaptation for a small trusted mesh without a central prekey bundle server.

### Roles

- **Initiator** — sends the first message in an epoch; attaches `outerHandshake` on the first send (`sendMessageNumber == 0`).
- **Responder** — bootstraps from `outerHandshake` on first inbound message for that epoch.

### Epoch-2 upgrade flow

1. Alice (initiator) sends epoch-1 message with 3-DH `outerHandshake`.
2. Bob (responder) decrypts, bootstraps epoch 1.
3. Bob's first reply may include `InnerSessionControl.OpkOffer` (when `SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY` is enabled).
4. Alice decrypts the offer and calls `createEpoch2AsInitiator` with a **fresh ephemeral** key pair.
5. Alice's next outbound messages use `sessionEpoch = 2` and a new 4-DH `outerHandshake`.
6. Bob bootstraps epoch 2 on first inbound epoch-2 frame, consuming the offered OPK.

---

## Conscious design choices

These are intentional tradeoffs for YapYap's architecture, not bugs.

### 3-DH first, 4-DH later

Signal prefers 4-DH on first contact when an OPK is available from a bundle server. YapYap has **no server**, so OPKs are not published in advance. Starting with 3-DH allows:

- encrypted first message to an offline peer via Tor store-and-forward,
- OPK delivery in-band once the responder is online,
- upgrade to 4-DH without redesigning the transport layer.

### Cleartext `outerHandshake`

X3DH public material (ephemeral key, signed prekey ID, epoch, mode) is sent in `SessionWireFrame.outerHandshake`, outside the ratchet ciphertext. This matches the fundamental constraint of X3DH: the responder must see DH public keys to derive the shared secret. The block is **integrity-protected** by the Ed25519 `MessageEnvelope` signature, but **not confidential**.

### Identity binding via routing layer (planned)

The handshake wire format does not include the initiator identity encryption key (IK). Session establishment currently trusts `MessageEnvelope.source` / roster lookup via `IdentityResolver`. A future **signed encrypted DAG roster** will provide trustworthy device routing metadata.

### Custom KDF labels

X3DH and Double Ratchet use YapYap-specific HKDF `info` strings (`YapYapX3DH`, `YapYapDR_RK`, `YapYapDR_CK`). This is not libsignal-compatible but is acceptable for an internal protocol.

### `SessionUpgradePolicy`

`DefaultCryptoSessionManager` defaults to `SessionUpgradePolicy.NEVER` during early development. Production wiring should enable `OFFER_OPK_ON_FIRST_EPOCH1_REPLY` when the upgrade path is ready.

---

## Implemented hardening

### Ratchet header AEAD associated data (AAD)

`RatchetCiphertext` header fields (`dhPublicKey`, `messageNumber`, `previousChainLength`) are authenticated but not encrypted. They are bound to the ciphertext body via ChaCha20-Poly1305 AAD:

- `RatchetCiphertext.headerAssociatedData()` — canonical header serialization (same layout as `encode()` minus the body).
- `DoubleRatchetSession.encrypt` / `decrypt` pass header AD to `CryptoProvider.encryptAead` / `decryptAead`.
- Skipped-message decrypt path also passes AAD.

Tampering with any header field causes AEAD verification failure rather than silent corruption.

**Tests:** `DoubleRatchetSessionTest` — `decrypt_rejectsTampered*` and `decrypt_outOfOrder_rejectsTamperedHeaderOnSkippedMessage`.

### Fresh ephemeral on epoch-2 upgrade

`createEpoch2AsInitiator` generates a **new** ephemeral key pair for 4-DH. It does not reuse the epoch-1 ephemeral that was already sent in cleartext in the first `outerHandshake`.

**Tests:** `DefaultCryptoSessionManagerTest.epoch2_aliceEncryptsBobDecrypts_afterOpkOffer` asserts epoch-2 ephemeral ≠ epoch-1 ephemeral.

### Other solid foundations

- X3DH DH term ordering (DH1–DH4) per standard construction
- Signed prekey signature verification in `IdentityResolver.resolvePeerX3dhRemoteKeys`
- Double Ratchet with out-of-order support (`skipMessageKeys`, `MAX_SKIP = 256`)
- Session state persistence (`crypto_sessions` table)
- Per-peer mutex in `DefaultCryptoSessionManager`
- OPK consume-once semantics in `DefaultOneTimePreKeyStore`
- Envelope-level Ed25519 signing over the encrypted payload

---

## Known gaps & recommended backlog

Prioritized items to address for a solid base (excluding SPK rotation, tracked separately).

### P0 — Critical (offline / Tor delivery)

#### 1. Simultaneous session initiation

**Problem:** If Alice and Bob each send a first message before either receives the other's, both create **initiator** epoch-1 sessions. When each later receives the other's first message, `bootstrapFromFrame` is skipped (session already exists) but the local session has the **wrong role**. Decrypt fails.

**Common scenario:** Both users message each other while offline; both queues deliver "first messages" with `outerHandshake`.

**Fix directions:**

- Lexicographic device-id tie-break (lower ID yields to higher as responder), or
- On inbound `outerHandshake`, if local session is initiator with `sendMessageNumber == 0` and no confirmed peer traffic, discard and re-bootstrap as responder, or
- Separate send/receive session state (Signal-style).

**Test to add:** both peers `encryptMessage` before either `decryptMessage`; expect deterministic recovery or explicit failure mode.

#### 2. Handshake only attached when `sendMessageNumber == 0`

**Problem:** `shouldAttachOutboundWire` returns true only when the initiator's `sendMessageNumber == 0`. After the first `encryptMessage` call:

- A **new** outbound message (new packet) does not include `outerHandshake`.
- If the first message was lost and the app sends again, the peer cannot bootstrap.
- If message 2 arrives before message 1, the peer cannot bootstrap.

**Note:** Outbox **retry of the same envelope** is safe — the payload is frozen at enqueue time and still contains the handshake.

**Fix direction:** Track `handshakeAcknowledged` / `peerSessionEstablished` in `CryptoSessionMeta`. Keep attaching `outerHandshake` until the peer has successfully bootstrapped, or until an explicit session-confirm control message is received.

**Tests to add:**

- First message lost, second `encryptMessage` call — handshake still deliverable.
- Message 2 arrives before message 1 — graceful buffer or NACK.

#### 3. Wire metadata validation

**Problem:** `bootstrapFromFrame` does not cross-validate:

- `frame.sessionEpoch == wire.sessionEpoch`
- `wire.mode` consistency with `frame.sessionEpoch`
- Optional consistency checks on `signedPreKeyId` against session meta

**Fix direction:** Validate in `bootstrapFromFrame` before X3DH computation.

**Test to add:** `wire.sessionEpoch != frame.sessionEpoch` → reject at bootstrap.

---

### P1 — High (protocol lifecycle)

#### 4. Dual-epoch overlap not managed

When epoch 2 is created on the initiator, `latestEncryptEpoch` immediately returns `2`. The initiator stops sending epoch-1 frames; the responder may still receive late epoch-1 messages (decrypt still works via `frame.sessionEpoch`).

**Missing:**

- `markSuperseded` exists but is never called.
- `meta.status` (`ACTIVE` / `SUPERSEDED`) is not checked on encrypt or decrypt.
- No policy for retiring epoch 1 after epoch 2 is confirmed.

**Fix direction:** Define epoch lifecycle explicitly: when to mark superseded, when to stop decrypting epoch 1, timeout for in-flight epoch-1 messages.

#### 5. OPK pool lifecycle

When Bob sends `OpkOffer`, `oneTimePreKeyStore.allocate()` creates an OPK. If Alice never upgrades (or the offer is lost), the OPK is never consumed and remains in the DB.

**Fix direction:**

- Mark OPKs as `offered` vs `available`.
- TTL / garbage-collect unused offers.
- Replenish pool when low.

#### 6. OpkOffer binding

Alice trusts `offer.opkPublicKey` from the decrypted control block without additional binding to the epoch-1 session. Cryptographically, 4-DH fails if wrong, but cross-session confusion is possible.

**Fix direction (optional):** Bind offer to epoch-1 session (e.g. hash of root key or handshake transcript) in the control message.

#### 7. Sensitive key material in persistence

`crypto_sessions` stores:

- `initiator_ephemeral_private_key`
- `skipped_message_keys` (derived message keys)
- Full ratchet chain state

SQLCipher protects at rest, but for release hardening consider:

- Zeroing ephemeral private keys after epoch-2 bootstrap completes.
- Bounding persisted `skipped_message_keys` size.
- RAM wipe behavior (Sprint 7 goal).

---

### P2 — Medium (robustness)

#### 8. Ratchet replay at session layer

Router dedup is per `packetId`. An old ratchet frame replayed inside a **new** envelope (new packet ID) is usually rejected by ratchet state / AEAD, but error semantics are generic exceptions.

**Fix direction:** Treat decrypt failures on duplicate `messageNumber` as terminal for that message; consider explicit session-level seen counters.

#### 9. Decrypt partial state mutation

`DoubleRatchetSession.decrypt` may advance DH ratchet state before AEAD verification. A failure mid-decrypt could leave in-memory state inconsistent if not persisted carefully.

**Fix direction:** Snapshot-before-decrypt with rollback, or ensure no partial persist on failure (audit all paths).

#### 10. Protocol versioning

`SESSION_WIRE_VERSION` exists on `SessionWireFrame`, but there is no version for:

- Ratchet AAD format
- Inner control message types
- Algorithm identifiers

**Fix direction:** Add `cryptoProtocolVersion` (or bump `SESSION_WIRE_VERSION`) when changing KDF labels, AAD binding, or inner plaintext format.

#### 11. Decode bounds

`RatchetCiphertext.decode` and `SessionWireFrame.decode` have no practical upper bounds on `dhPublicKey` size, body size, or string lengths. Hostile frames could cause large allocations.

**Fix direction:** Enforce sane maxima at decode time.

#### 12. Identity / session reset

Re-provisioning a peer (new identity keys, new device record) does not invalidate existing `crypto_sessions` rows.

**Fix direction:** Wipe or bump session generation on identity change (separate from SPK rotation).

---

## Deferred: SPK rotation

Signed prekey (SPK) rotation and handshake edge cases when `wire.signedPreKeyId` refers to a retired SPK are **known and deferred**. Epoch-2 upgrade pins the SPK ID from epoch 1 (`handshakeSpkId`); rotation mid-session requires explicit policy. Track in a future sprint.

---

## Wire format summary

### `SessionWireFrame` (magic `YSW1`, version `1`)

| Field | Notes |
|-------|-------|
| `sessionEpoch` | `1` or `2` |
| `outerHandshake` | Optional; present on initiator's first send per epoch |
| `ratchet` | `RatchetCiphertext` |

### `X3dhWireInfo` (inside `outerHandshake`)

| Field | Encrypted? |
|-------|------------|
| `ephemeralPublicKey` | No (required for X3DH) |
| `signedPreKeyId` | No |
| `sessionEpoch` | No |
| `mode` | No (`THREE_DH` / `FOUR_DH`) |
| `oneTimePreKeyId` | No (epoch 2 only) |

### `RatchetCiphertext`

| Field | Encrypted? | AAD? |
|-------|------------|------|
| `dhPublicKey` | No | Yes (authenticated via AAD) |
| `messageNumber` | No | Yes |
| `previousChainLength` | No | Yes |
| `body` | Yes (ChaCha20-Poly1305) | — |

### Inner plaintext (`RatchetInnerPlaintext`)

| Kind | Purpose |
|------|---------|
| `Payload` | Application bytes |
| `WithControl` | Application bytes + optional `InnerSessionControl` |
| `OpkOffer` | `opkId` + `opkPublicKey` for epoch-2 upgrade |

---

## Test coverage

### Unit tests (implemented)

| Test class | Coverage |
|------------|----------|
| `X3dhHandshakeTest` | 3-DH / 4-DH shared secret agreement, SPK/OPK ID mismatch rejection |
| `DoubleRatchetSessionTest` | Round-trip, bidirectional, out-of-order, snapshot restore, skip gap limit, header/body tamper rejection |
| `DefaultCryptoSessionManagerTest` | Epoch-1 bootstrap, bidirectional, out-of-order, epoch-2 OPK upgrade, fresh epoch-2 ephemeral |
| `KmpCryptoProviderTest` | AEAD round-trip, AAD round-trip, tamper rejection |

### Integration tests (recommended backlog)

| Scenario | Priority |
|----------|----------|
| Both peers send first message before any decrypt | P0 |
| First message lost, second `encryptMessage` | P0 |
| Message 2 arrives before message 1 | P0 |
| `wire.sessionEpoch != frame.sessionEpoch` | P0 |
| Epoch-2 send before peer processed OpkOffer | P1 |
| Replay old ratchet frame in new envelope | P2 |
| Unused OpkOffer after timeout | P1 |

---

## Security assumptions

1. **Roster trust** — Peer identity encryption keys and SPKs are obtained from a trusted local roster (future: signed encrypted DAG). Compromised roster ⇒ MITM until detected.
2. **Envelope signatures** — Ed25519 on `MessageEnvelope` prevents tampering with signed fields including the crypto payload.
3. **SQLCipher** — Session secrets at rest depend on DB encryption and OS keystore for the master key.
4. **Tor / relay observers** — Can see routing metadata and cleartext X3DH wire fields inside the signed payload. Message content remains encrypted.
5. **Small network** — Protocol optimized for ~10–20 peers, not hyperscale prekey distribution.

---

## Change log (doc)

| Date | Notes |
|------|-------|
| 2026-06-26 | Initial E2EE architecture, design choices, gaps, and test matrix documented after Sprint 1 implementation review. |
