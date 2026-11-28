## Data Types

Typed keys, ids and entities — never raw bytes and strings. This file derives from docs/guide.md §4 (Data types — the
authoritative rules-of-the-code document), corroborated by the core's own type definitions.

### Keys, ids and entities are typed

Public keys (`SigningPublicKey`, `EncryptionPublicKey` under `PublicKey` in protocol/identity) and private keys
(`SigningPrivateKey`, `EncryptionPrivateKey` under `PrivateKey` in crypto/keys) are separate hierarchies so a private
key can never stand where a wire key is expected. Key types wrap the bytes, compare by content, and never print a
private key. Identifiers are never raw strings or UUIDs in signatures: `PeerId`/`AccountId` are dedicated data classes
enforcing invariants with `require()` in init; `RoomId`, `MessageId` and `SyncId` are `@JvmInline` value classes over
`Uuid`; prekey ids
(`SignedPreKeyId`, `OneTimePreKeyId`) and file ids (`FileId`, `TransferId`, `SealKey` — exactly 32 bytes, never printed)
follow the same pattern. Validation is fail-fast at construction/decode points, never deferred. Raw `ByteArray` keys
appear only at the wire (codecs, payload classes) and inside the ratchet — convert at that boundary.

Sources: docs (guide.md §4), code · confidence 91.
Evidence:

- guide.md §4: "Keys are typed. … separate hierarchies, so a private key can never stand where a wire key is expected …
  Prekey ids are `SignedPreKeyId` / `OneTimePreKeyId`. File seal keys are `SealKey` (exactly 32 bytes, never printed) …
  Raw `ByteArray` keys appear only at the wire (codecs, payload classes) and inside the ratchet; convert at that
  boundary."
- guide.md §9: "Use the typed keys, ids and entities (§4, data types), not raw bytes and strings."
- code: all 9 id types in the sampled vocabularies (PeerId, AccountId, RoomId, TorEndpoint, FileId, TransferId, SealKey,
  SignedPreKeyId, OneTimePreKeyId) are dedicated validated types; 39 of 224 sampled core files use `require()` for
  invariant checks.

```kotlin
public data class PeerId(val id: String) {
    init { require(id.isNotBlank()) { "PeerId cannot be blank" } }
}

@JvmInline public value class RoomId(public val value: Uuid) {
    public companion object { public val GLOBAL: RoomId = RoomId(Uuid.NIL) }
}
```

### Keyring entries are KeySlots with stable names

Each key the app holds outside the database has one `KeySlot` in the OS keyring; a slot's keyring name never changes for
an existing slot.

Sources: docs (guide.md §4) · confidence 89.
Evidence:

- guide.md §4 (Data types): "**Keyring entries are `KeySlot`s.** Each key the app holds outside the database has one
  slot; its keyring name never changes for an existing slot."

### One entity per table, named for what it is

Stored entities are named for what they are, with no `Row`/`Record` suffixes. A stored entity contains its identity
rather than duplicating it: `Account` (with its `AccountDevice`s, in orchestrator/identity) is the stored account;
`AccountIdentity`/`DeviceIdentity` are what an account or device presents about itself (carried in bootstrap payloads).
Public and private halves are separate types: `SignedPreKey` is what peers see; `LocalSignedPreKey` adds our private
half.

Sources: docs (guide.md §4) · confidence 90.
Evidence:

- guide.md §4 (Data types): "**One entity per table, named for what it is.** … a stored entity contains its identity
  rather than duplicating it. Public and private halves are separate types … No `Row`/`Record` suffixes."

### Generated SQLDelight classes never leave their store

Generated SQLDelight row classes never leave their store; column adapters map columns straight to the owning layer's
domain types where they can. Column-mapped types live with the layer that owns their tables (`OpkStatus` in
crypto/store, `VerificationState` in orchestrator/dag); the schema may name them, hand-written persistence code does
not.

Sources: docs (guide.md §2, §4) · confidence 90.
Evidence:

- guide.md §4: "**Generated SQLDelight classes never leave their store.** Column adapters map columns straight to these
  types where they can."
- guide.md §2 (persistence service): "So do the types a column maps to (`OpkStatus` in `crypto/store`,
  `VerificationState` in `orchestrator/dag`); the schema may name them, hand-written persistence code does not."
