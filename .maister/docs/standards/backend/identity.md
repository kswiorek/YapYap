## Identity

How identity keys and stores are handled. This file derives from docs/guide.md §7 (identity rules — the authoritative
rules-of-the-code document) with GUI-facing statements from docs/gui-api.md.

### Account signing key exists only on the first device

The account signing key exists only on the account's first device (genesis, new account, or recovery-key import). A
device added to an existing account never holds it: it starts on a key-less stand-in local account, and when the fold
confirms the device under its real account, that account becomes the local one (the stand-in and its memberships fold
into it).

Sources: docs (guide.md §7) · confidence 90.
Evidence:

- guide.md §7: "The account signing key exists only on the account's first device (genesis, new account, or recovery-key
  import). A device added to an existing account never holds it: it starts on a key-less stand-in local account, and
  when the fold confirms the device under its real account, that account becomes the local one."

### Private keys via LocalKeys; peers' public keys via PeerKeys (throw on missing)

Private keys are reached only through `LocalKeys` (this device's private keys; usable by crypto, protection and setup
only). Peers' public keys are resolved strictly through `PeerKeys`, which throws on a missing record rather than
returning a partial one. `LocalIdentity` (who this device is) is readable by every layer.

Sources: docs (guide.md §2, §7) · confidence 90.
Evidence:

- guide.md §7: "Private keys are reached only through `LocalKeys`; peers' public keys through `PeerKeys`, which throws
  on a missing record rather than returning a partial one."
- guide.md §2 (crypto service): "The identity services: `LocalIdentity` (who this device is; readable by every layer),
  `LocalKeys` (this device's private keys; crypto, protection and setup only) and `PeerKeys` (peers' public keys,
  strictly resolved)."

### Keys live in the OS keyring, never in the data directory

Private keys and the database master key live in the OS keyring, outside the data directory; the database is encrypted
with the keyring-held master key. Deleting the data directory therefore leaves keys behind; a freshly created database
can't own any of them, so boot deletes them and goes to setup. A reset deletes everything in the data directory except
the held `app.lock`. GUI-facing statement: key material lives in the OS keyring under service `org.yapyap` (suffixed per
`keyringNamespace`) — never in the data directory.

Sources: docs (guide.md §7; gui-api.md) · confidence 91.
Evidence:

- guide.md §7: "Private keys and the database master key live in the OS keyring, outside the data directory. Deleting
  the data directory therefore leaves keys behind; a freshly created database can't own any of them, so boot deletes
  them and goes to setup."
- gui-api.md (Bring-up sequence): "Key material lives in the OS keyring under service `org.yapyap` (suffixed per
  `keyringNamespace` when set) — never in the data directory."
