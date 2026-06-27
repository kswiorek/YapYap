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
| OPK store | `org.yapyap.backend.db.DefaultOneTimePreKeyStore` |
| OPK offer binding | `org.yapyap.backend.crypto.e2ee.OpkOfferBinding` |
| Crypto housekeeping | `org.yapyap.backend.crypto.e2ee.DefaultCryptoHousekeeping` |
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

- **Initiator** — sends the first message in an epoch; attaches `outerHandshake` on each send until the peer has replied (`recvMessageNumber == 0` on the initiator session).
- **Responder** — bootstraps from `outerHandshake` on first inbound message for that epoch.

### Session generation

Within each `sessionEpoch`, a **`sessionGeneration`** counter distinguishes successive cryptographic sessions with the same peer (e.g. after idle supersede). Each generation is stored as a separate `crypto_sessions` row so superseded ratchet state is retained for late decrypt until pruned.

### Epoch-2 upgrade flow

1. Alice (initiator) sends epoch-1 message with 3-DH `outerHandshake`.
2. Bob (responder) decrypts, bootstraps epoch 1.
3. Bob's outbound epoch-1 messages include `InnerSessionControl.OpkOffer` (when `SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY` is enabled) — same OPK re-offered until epoch 2 is confirmed, not only on the first reply.
4. Alice decrypts the offer on the **canonical active** epoch-1 initiator session, verifies `sessionBinding`, and creates a **pending** epoch-2 initiator session (`SessionStatus.PENDING`) with a fresh ephemeral key pair.
5. Alice keeps encrypting epoch-1 until she decrypts a **subsequent** epoch-1 message from Bob (promotes pending → `ACTIVE`); then outbound uses `sessionEpoch = 2` with a new 4-DH `outerHandshake`.
6. Bob bootstraps epoch 2 on first inbound epoch-2 frame, consuming the offered OPK. If OPK bootstrap fails (missing offer, consume failure, SPK mismatch), decrypt fails for that frame only and epoch-1 continues; Bob keeps re-offering until upgrade succeeds.

**Fail-soft OPK policy:** Bob skips `OpkOffer` attachment when OPK allocation/mark fails; Alice ignores invalid offers. Both sides can stay on 3-DH indefinitely.

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

### Session lifecycle (supersede & maintenance)

- **`ACTIVE` / `PENDING` / `SUPERSEDED`** — epoch-2 initiator rows start `PENDING` until promoted after the next inbound epoch-1 message; `latestEncryptEpoch` counts only `ACTIVE` rows. Abandoned `PENDING` epoch-2 rows are **deleted** by `DefaultCryptoHousekeeping` after `pendingEpoch2RetentionSeconds` (default 2 days, aligned with offer TTL). `markSuperseded` is used after simultaneous-init tie-break, peer reset (`sessionGeneration` bump), and idle canonical supersede.
- **Canonical invariant** — at most one `ACTIVE` canonical row per `(peer, sessionEpoch)`; partial unique index in `Crypto.sq`.
- **`sessionGeneration`** — on wire (`SessionWireFrame` + `X3dhWireInfo`) and in `CryptoSessionMeta`; new bootstrap bumps generation instead of overwriting superseded rows.
- **Retention** — superseded rows kept for `supersededRetentionSeconds` from supersede time (`updatedAtEpochSeconds` refreshed on `markSuperseded` / `markEpochSuperseded`), then deleted; idle canonical rows superseded after `canonicalIdleSupersedeSeconds`.
- **Epoch-1 reset** — starting a new epoch-1 generation calls `markEpochSuperseded(peer, epoch = 2)`.
- **Epoch-2 confirmation** — `onEpoch2Confirmed` marks all epoch-1 rows `SUPERSEDED` when epoch 2 is successfully decrypted: on responder bootstrap (first inbound epoch-2 frame) and on initiator decrypt of the peer's first epoch-2 reply. Superseded epoch-1 rows remain decryptable until retention prune.

**Tests:** `DefaultCryptoSessionManagerTest` — simultaneous init, handshake re-attach, idle supersede, `sessionGeneration` round-trip, superseded late decrypt, per-generation prune, epoch-2 confirmation supersede.

### Other solid foundations

- X3DH DH term ordering (DH1–DH4) per standard construction
- Signed prekey signature verification in `IdentityResolver.resolvePeerX3dhRemoteKeys`
- Double Ratchet with out-of-order support (`skipMessageKeys`, `MAX_SKIP = 256`); skipped keys keyed by `(remoteDh, messageNumber)`; superseded DH chains tombstoned via `RatchetSkippedKeyId.SUPERSEDED_DH_CHAIN` in the same `skipped_message_keys` blob (no extra DB columns)
- Session state persistence (`crypto_sessions` table)
- Per-peer mutex in `DefaultCryptoSessionManager`
- OPK lifecycle (`OpkStatus`, offer TTL prune) in `DefaultOneTimePreKeyStore` + `DefaultCryptoHousekeeping`
- `OpkOffer` session binding (`OpkOfferBinding`) + canonical-only epoch-2 upgrade
- Envelope-level Ed25519 signing over the encrypted payload

---

## Known gaps & recommended backlog

Prioritized items to address for a solid base (excluding SPK rotation, tracked separately).

Status legend: **✅ Fixed** · **🟡 Partially fixed** · **⬜ Open**

### P0 — Critical (offline / Tor delivery)

#### 1. Simultaneous session initiation — ✅ Fixed

**Problem:** If Alice and Bob each send a first message before either receives the other's, both create **initiator** epoch-1 sessions. When each later receives the other's first message, `bootstrapFromFrame` is skipped (session already exists) but the local session has the **wrong role**. Decrypt fails.

**Common scenario:** Both users message each other while offline; both queues deliver "first messages" with `outerHandshake`.

**Implemented:** Lexicographic device-id tie-break — lower ID becomes canonical **responder**; higher ID keeps canonical **initiator**. Rogue duplicate rows marked `SUPERSEDED` when `supersedeRogueSessionsAfterSimultaneousInit` is enabled (`CryptoSessionConfig`).

**Test:** `DefaultCryptoSessionManagerTest.epoch1_simultaneousInit_bothDecryptAndContinue`.

#### 2. Handshake only attached when `sendMessageNumber == 0` — ✅ Fixed

**Problem:** `shouldAttachOutboundWire` returned true only when the initiator's `sendMessageNumber == 0`. After the first `encryptMessage` call:

- A **new** outbound message (new packet) does not include `outerHandshake`.
- If the first message was lost and the app sends again, the peer cannot bootstrap.
- If message 2 arrives before message 1, the peer cannot bootstrap.

**Note:** Outbox **retry of the same envelope** is safe — the payload is frozen at enqueue time and still contains the handshake.

**Implemented:** `shouldAttachOutboundWire` attaches `outerHandshake` while the initiator has **`recvMessageNumber == 0`** (peer has not yet replied on that session), for epoch 1 and 2.

**Tests:** `epoch1_firstMessageLost_secondMessageStillCarriesHandshake`, `epoch1_message2ArrivesBeforeMessage1_bobBootstrapFromSecond`, `epoch1_stopsAttachingHandshake_afterPeerReply`.

#### 3. Wire metadata validation — ✅ Fixed

**Problem:** `bootstrapFromFrame` does not cross-validate:

- `frame.sessionEpoch == wire.sessionEpoch`
- `wire.mode` consistency with `frame.sessionEpoch`
- `signedPreKeyId` consistency against existing session meta on epoch-2 upgrade

**Implemented:**

- `frame.sessionEpoch == wire.sessionEpoch` — validated in `bootstrapFromFrame`.
- `frame.sessionGeneration == wire.sessionGeneration` — validated in `bootstrapFromFrame`.
- `wire.mode` vs epoch — enforced in `bootstrapEpoch1Responder` (`THREE_DH`) and `bootstrapEpoch2Responder` (`FOUR_DH`).
- **Epoch-2 SPK pin** — `bootstrapEpoch2Responder` requires `wire.signedPreKeyId == epoch1.meta.handshakeSpkId` before OPK consume (initiator already pins the same id via `createEpoch2AsInitiator`).

**Tests:** `epoch1_bootstrap_rejectsMismatchedWireSessionEpoch`, `epoch2_bootstrap_rejectsMismatchedSignedPreKeyId`.

**Deferred:** SPK rotation policy when `wire.signedPreKeyId` refers to a retired SPK on epoch-1 bootstrap or generation reset (see [Deferred: SPK rotation](#deferred-spk-rotation)).

---

### P1 — High (protocol lifecycle)

#### 4. Dual-epoch overlap not managed — ✅ Fixed

Epoch-2 upgrade is deferred: the initiator keeps sending epoch-1 until pending epoch-2 is promoted (after the next inbound epoch-1 message post-offer). `latestEncryptEpoch` returns `2` only when epoch-2 is `ACTIVE`.

**Implemented:**

- `markSuperseded` / `markEpochSuperseded` wired in manager; peer session prune via `DefaultCryptoHousekeeping`.
- `loadActiveCanonical` returns only `ACTIVE` canonical rows; encrypt bootstraps a new session when canonical is superseded.
- `sessionGeneration` prevents overwriting superseded rows; late decrypt by generation.
- Peer maintenance: idle canonical supersede + superseded retention prune (via `DefaultCryptoHousekeeping`).
- Epoch-1 generation reset supersedes all epoch-2 rows for that peer.
- **Epoch-2 confirmation supersede** — `onEpoch2Confirmed` calls `markEpochSuperseded(peer, epoch = 1)` once epoch 2 decrypt succeeds on that device (responder bootstrap or initiator first epoch-2 reply). Epoch 1 stays `ACTIVE` until then so late epoch-1 delivery still works during the upgrade window; after supersede, rows are retained for `supersededRetentionSeconds` (measured from supersede time) and remain decryptable until pruned.

**Tests:** `epoch2_aliceEncryptsBobDecrypts_afterOpkOffer` (responder), `epoch2_confirmed_marksEpoch1SupersededOnInitiatorAfterPeerReply` (initiator), `epoch2_supersedeEpoch1_retentionMeasuredFromSupersedeTime`, `epoch2_encryptDeferredUntilNextInboundAfterOffer`, `epoch2_earlyEpoch2SendBeforePromote_recoversOnEpoch1`, `epoch2_bootstrapFailsSoft_missingOfferedOpk_staysOnEpoch1`, `epoch2_skipsOpkOfferWhenOpkUnavailable`.

#### 5. OPK pool lifecycle — ✅ Fixed (offer TTL); pool provisioning deferred

When Bob sends `OpkOffer`, `oneTimePreKeyStore.allocate()` creates an OPK. If Alice never upgrades (or the offer is lost), the OPK was previously never consumed and remained in the DB indefinitely.

**Implemented:**

- `OpkStatus` on `one_time_prekeys`: `ALLOCATED` → `OFFERED` → `CONSUMED`.
- `markOffered(opkId)` when attaching `OpkOffer`; `consume(opkId)` requires `OFFERED`.
- `offered_at_epoch_seconds` + `pruneExpiredOffers` (default retention `offeredOpkRetentionSeconds` = 2 days, aligned with message lifetime).
- `DefaultCryptoHousekeeping` prunes expired offers, clears dangling `offeredOpkId` on sessions, and runs peer session maintenance.

**Deferred:** bulk pool provisioning and replenish-when-low (future sprint).

**Tests:** `DefaultOneTimePreKeyStoreJvmTest`, `DefaultCryptoSessionManagerTest.housekeeping_prunesExpiredOfferedOpkAndClearsSessionMeta`.

#### 6. OpkOffer binding — ✅ Fixed

Alice previously trusted `offer.opkPublicKey` from the decrypted control block without binding to the epoch-1 session. Cryptographically, 4-DH fails if wrong, but cross-session confusion was possible (e.g. late offer from a superseded generation).

**Implemented:**

- Extended `OpkOffer` with `sessionEpoch`, `sessionGeneration`, and `sessionBinding` (32-byte HKDF over handshake transcript: `handshakeSpkId` + initiator ephemeral public key + ordered peer ids + epoch/generation). Uses handshake material rather than ratchet root key, which diverges between initiator and responder after the first reply.
- Responder bootstrap stores `initiatorEphemeralPublicKey` from wire for binding on the Bob side.
- `maybeUpgradeToEpoch2` accepts offers only when decrypting on **canonical `ACTIVE` epoch-1 initiator** session with matching generation and valid `sessionBinding`; invalid offers are ignored (plaintext still delivered).
- Bob **re-offers the same OPK** on every epoch-1 outbound message until epoch-2 is confirmed (`loadOffered` + `offeredOpkId` reuse), supporting out-of-order delivery and delayed upgrade.

**Tests:** `DefaultCryptoSessionManagerTest` — `opkOfferBinding_matchesAcrossPeers`, `epoch2_reoffersSameOpkOnSubsequentMessages`, `epoch2_ignoresOfferDecryptedOnSupersededGeneration`, `epoch2_rejectsOfferWithInvalidBinding`; `innerPlaintext_withOpkOffer_roundTrip`.

#### 7. Sensitive key material in persistence — 🟡 Partially fixed (persistence); RAM wipe deferred

`crypto_sessions` stores:

- `initiator_ephemeral_private_key` — **no longer written** for initiator sessions after X3DH bootstrap (epoch 1 and pending epoch 2); in-memory copies are zeroed. Public ephemeral is retained for handshake re-attach and `OpkOffer` binding.
- `skipped_message_keys` — bounded in practice: per-step skip capped by `MAX_SKIP = 256`; superseded DH chains tombstoned and drained; idle session supersede + retention prune drops whole rows when unused.
- Full ratchet chain state (still at rest under SQLCipher; RAM wipe tracked separately below).

SQLCipher protects at rest. Persistence hardening:

- ~~Zeroing ephemeral private keys after epoch-2 bootstrap completes.~~ ✅ Initiator ephemeral private keys zeroed in RAM and omitted from persistence after 3-DH / 4-DH bootstrap.
- ~~Bounding persisted `skipped_message_keys` size.~~ ✅ Superseded receive DH chains tombstoned in the existing skipped-keys map; late decrypt on old chains; orphan superseded headers fail closed; markers pruned when chain drained; session rows pruned by housekeeping when idle/superseded.
- RAM wipe behavior (Sprint 7 goal) — ⬜ deferred.

**Tests:** `initiatorEphemeralPrivateKey_notPersistedAfterBootstrap`; `DoubleRatchetSessionTest` — `decrypt_lateMessageOnSupersededDhChain_usesSkippedKey`, `decrypt_orphanOnSupersededDhChain_failsWithoutSkippedKey`, `snapshot_restore_preservesSupersededDhSkippedKeys`.

---

### P2 — Medium (robustness)

#### 8. Ratchet replay at session layer — ⬜ Open

Router dedup is per `packetId`. An old ratchet frame replayed inside a **new** envelope (new packet ID) is usually rejected by ratchet state / AEAD, but error semantics are generic exceptions.

**Fix direction:** Treat decrypt failures on duplicate `messageNumber` as terminal for that message; consider explicit session-level seen counters.

#### 9. Decrypt partial state mutation — ⬜ Open

`DoubleRatchetSession.decrypt` may advance DH ratchet state before AEAD verification. A failure mid-decrypt could leave in-memory state inconsistent if not persisted carefully.

**Fix direction:** Snapshot-before-decrypt with rollback, or ensure no partial persist on failure (audit all paths).

#### 10. Protocol versioning — ⬜ Open

`SESSION_WIRE_VERSION` exists on `SessionWireFrame`, but there is no version for:

- Ratchet AAD format
- Inner control message types
- Algorithm identifiers

**Fix direction:** Add `cryptoProtocolVersion` (or bump `SESSION_WIRE_VERSION`) when changing KDF labels, AAD binding, or inner plaintext format.

#### 11. Decode bounds — ⬜ Open

`RatchetCiphertext.decode` and `SessionWireFrame.decode` have no practical upper bounds on `dhPublicKey` size, body size, or string lengths. Hostile frames could cause large allocations.

**Fix direction:** Enforce sane maxima at decode time.

#### 12. Identity / session reset — 🟡 Partially fixed

Re-provisioning a peer (new identity keys, new device record) does not invalidate existing `crypto_sessions` rows.

**Implemented:** `sessionGeneration` bump on idle supersede / inbound peer reset (crypto session lifecycle, not identity rotation).

**Still open:** Wipe or bump sessions when peer **identity keys** or device record change (separate from SPK rotation).

---

## Deferred: SPK rotation

Signed prekey (SPK) rotation and handshake edge cases when `wire.signedPreKeyId` refers to a retired SPK are **known and deferred**. Epoch-2 upgrade pins the SPK ID from epoch 1 (`handshakeSpkId`); rotation mid-session requires explicit policy. Track in a future sprint.

---

## Wire format summary

### `SessionWireFrame` (magic `YSW1`, version `1`)

| Field | Notes |
|-------|-------|
| `sessionEpoch` | `1` or `2` |
| `sessionGeneration` | Monotonic per epoch; distinguishes successive bootstraps |
| `outerHandshake` | Optional; present on initiator sends until peer replies |
| `ratchet` | `RatchetCiphertext` |

### `X3dhWireInfo` (inside `outerHandshake`)

| Field | Encrypted? |
|-------|------------|
| `ephemeralPublicKey` | No (required for X3DH) |
| `signedPreKeyId` | No |
| `sessionEpoch` | No |
| `sessionGeneration` | No |
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
| `OpkOffer` | `sessionEpoch`, `sessionGeneration`, `opkId`, `opkPublicKey`, `sessionBinding` for epoch-2 upgrade |

---

## Test coverage

### Unit tests (implemented)

| Test class | Coverage |
|------------|----------|
| `X3dhHandshakeTest` | 3-DH / 4-DH shared secret agreement, SPK/OPK ID mismatch rejection |
| `DoubleRatchetSessionTest` | Round-trip, bidirectional, out-of-order, snapshot restore, skip gap limit, header/body tamper rejection |
| `DefaultCryptoSessionManagerTest` | Epoch-1 bootstrap, wire epoch/SPK mismatch rejection, bidirectional, out-of-order, simultaneous init, handshake re-attach, epoch-2 OPK upgrade + binding + re-offer, confirmation supersede, crypto housekeeping, `sessionGeneration` |
| `DefaultOneTimePreKeyStoreJvmTest` | OPK allocate/offer/consume lifecycle, expired-offer prune + key deletion |
| `KmpCryptoProviderTest` | AEAD round-trip, AAD round-trip, tamper rejection |

### Integration tests (recommended backlog)

| Scenario | Priority | Status |
|----------|----------|--------|
| Both peers send first message before any decrypt | P0 | ✅ `epoch1_simultaneousInit_bothDecryptAndContinue` |
| First message lost, second `encryptMessage` | P0 | ✅ `epoch1_firstMessageLost_secondMessageStillCarriesHandshake` |
| Message 2 arrives before message 1 | P0 | ✅ `epoch1_message2ArrivesBeforeMessage1_bobBootstrapFromSecond` |
| `wire.sessionEpoch != frame.sessionEpoch` | P0 | ✅ `epoch1_bootstrap_rejectsMismatchedWireSessionEpoch` |
| Epoch-2 `signedPreKeyId != epoch-1 handshakeSpkId` | P0 | ✅ `epoch2_bootstrap_rejectsMismatchedSignedPreKeyId` |
| Idle supersede → new generation handshake round-trip | P1 | ✅ `idleSupersede_newGenerationHandshakeRoundTrip` |
| Superseded session still decrypts late message | P1 | ✅ `supersededSession_stillDecryptsLateGen1Message` |
| Prune respects retention per generation | P1 | ✅ `peerMaintenance_pruneRespectsRetention_perGeneration` |
| Stale `OpkOffer` from superseded generation ignored | P1 | ✅ `epoch2_ignoresOfferDecryptedOnSupersededGeneration` |
| Invalid `OpkOffer` session binding rejected | P1 | ✅ `epoch2_rejectsOfferWithInvalidBinding` |
| Same OPK re-offered on subsequent epoch-1 messages | P1 | ✅ `epoch2_reoffersSameOpkOnSubsequentMessages` |
| Epoch-2 send before peer processed OpkOffer | P1 | ✅ `epoch2_encryptDeferredUntilNextInboundAfterOffer`, `epoch2_earlyEpoch2SendBeforePromote_recoversOnEpoch1`, `epoch2_bootstrapFailsSoft_missingOfferedOpk_staysOnEpoch1` |
| Replay old ratchet frame in new envelope | P2 | ⬜ |
| Unused OpkOffer after timeout | P1 | ✅ `housekeeping_prunesExpiredOfferedOpkAndClearsSessionMeta`, `DefaultOneTimePreKeyStoreJvmTest` |

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
| 2026-06-26 | Marked backlog status: P0.1–P0.2 fixed; P0.3, P1.4, P1.12 partial; session lifecycle + `sessionGeneration` documented. |
| 2026-06-26 | P0.3 wire epoch mismatch reject test; P1.4 epoch-2 confirmation supersede policy + tests. |
| 2026-06-26 | P1.4 completed: `markEpochSuperseded` refreshes `updatedAt`; retention measured from supersede time. |
| 2026-06-26 | P0.3 completed: epoch-2 responder rejects `signedPreKeyId` not pinned to epoch-1 session. |
| 2026-06-27 | P1.5 completed: `OpkStatus` lifecycle, offered-OPK TTL prune via `DefaultCryptoHousekeeping`; bulk pool provisioning deferred. |
| 2026-06-27 | P1.6 completed: `OpkOffer` session binding, canonical-only upgrade, same-OPK re-offer until epoch-2 confirmed. |
| 2026-06-27 | Fail-soft optional OPK upgrade: deferred epoch-2 encrypt (`PENDING` → promote), skip offer on OPK failure, soft epoch-2 bootstrap failure. |
| 2026-06-27 | Housekeeping deletes stale `PENDING` epoch-2 sessions after `pendingEpoch2RetentionSeconds`. |
| 2026-06-27 | P1.7 persistence hardening: initiator ephemeral private keys not persisted; skipped-keys superseded-DH tombstones + session housekeeping bounds exposure. RAM wipe still deferred. |
