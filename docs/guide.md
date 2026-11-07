# YapYap Backend Guide

This guide explains what YapYap is, how the core is layered, and the rules new code must follow. It describes
the design, not the current state of any one class: when the code and this guide disagree, treat that as a bug
in one of them and raise it, don't silently follow the code.

Deeper references: [`transport.md`](transport.md) (Tor/WebRTC links), [`router.md`](router.md) (router
internals), [`e2ee.md`](e2ee.md) (sessions and ratchets), [`global events.md`](global%20events.md) (accounts,
devices, admin chain), [`room events.md`](room%20events.md) (rooms and membership), [`gui-api.md`](gui-api.md)
(what the GUI may use).

## 1. What YapYap is

- A messenger for a small trusted network (about 10–20 users). Correctness, resilience and maintainability win
  over scale.
- No servers it depends on. Every device runs the Kotlin core as a library; the same core runs headless on
  optional always-on nodes (e.g. a Raspberry Pi) that act as relays and availability anchors.
- Traffic:
    - text and small images over Tor, with store-and-forward through relays when the recipient is offline;
    - a direct low-latency path over WebRTC when both sides are online;
    - large files and audio/video over WebRTC peer-to-peer, signaled over Tor.
- The GUI is Compose Multiplatform. The frontend is ordinary messenger UI; the backend is where the novel work
  is, and it is what this guide is about.

## 2. The layer model

The core is layered like a small network stack, not like a three-tier app. Three layers carry traffic, top to
bottom, and a few services sit beside them.

```
  GUI ──► Orchestrator   accounts, rooms, messages, history (DAG), folds, onboarding, sync
              │  AccountId + payloads
              ▼
          Router         devices, envelopes, outbox, ACK/NACK, dedup, links, relays, pings
              │  PeerId / TorEndpoint + BinaryEnvelope
              ▼
          Transports     Tor streams, WebRTC data channels: bytes in, bytes out

  Beside them:  protocol (wire types + codecs) · crypto (keys, E2EE) · protection (envelope sign/encrypt)
                storage (SQLDelight, keyring) · config · logging
  Built by:     the composition root (wiring)
```

A three-layer split (presentation / business / data) would merge the router and the transports into "data" and
lose the boundary that matters most here: who knows about devices and packets, and who doesn't.

### Orchestrator — "what is said, and to whom"

- Speaks **accounts, rooms and payloads**: `AccountId`, `RoomId`, `MessagePayload`, `BootstrapPayload`, global
  and room events.
- Owns the message history (the DAG), the global and room folds, onboarding and recovery, typing state, and **sync**
  (working out what is missing, serving sync requests, retrying them).
- Hands a payload to the router addressed to an account and gets back an account-level report. Its own onion
  endpoint (for invites and its own AddDevice) comes from `Router.localTorEndpoint()`; the roster's per-device
  endpoint and "last seen" come from `Router.accountReach()`, reported per account and for display only.
- Models devices only as **members of an account**: an `Account` carries its `AccountDevice`s, which hold
  identity facts (keys, type, status, provisional) and nothing about reaching them. It may name a device as the
  *subject* of an identity fact (add/remove a device, re-verify messages from a device) using `PeerId`, but **never
  addresses** one. The single exception is bootstrap: before an account exists, an
  intro or recovery request is sent to a device id plus an out-of-band `TorEndpoint`.
- Never sees `BinaryEnvelope`, packet ids, link or session state, transport types, or Tor types.

### Router — "get this payload there"

- Speaks **devices and envelopes**: `PeerId`, `BinaryEnvelope`, packet ids.
- Fans a payload out to every device of the target account, protects it (via protection), queues it in the
  outbox, picks a link, retries, deposits relay copies, and handles ACK/NACK and dedup.
- Keeps links alive, sends pings, tracks peer availability, and exposes presence to the orchestrator **per
  account only** (`onlineAccounts`); which device carries an account stays inside the router.
- Reads accounts and devices through `DeviceDirectory` (account → devices, status, the endpoint a device
  declared), a read-only view it declares, and who it is through crypto's `LocalIdentity`. What it learns about
  reaching peers (proven endpoints, reliability, last seen) is its own state, in its own table (§4).
- Treats payloads as opaque beyond their routing header. It never reads a `RoomId`, never looks at the DAG or
  membership, and never decides what content to send. When it needs content it does not own (the frontiers it
  advertises in a ping), it asks through an interface it declares itself, implemented by the orchestrator.
- Folds transport health into its own `LocalLinkStatus` (`Router.localLinkStatus`); transport types stop at the
  router.

### Transports — "move these bytes"

- Speak **remotes and bytes**: a session key (`PeerId` for WebRTC, `TorEndpoint` for Tor) and `BinaryEnvelope`
  frames.
- Implement `SessionTransport<K>`: per-remote session states, idempotent open/close, send on an open session.
- Know nothing about the database, accounts, identity, crypto sessions or retry policy. The only key a transport
  holds is its own (the onion key, kept in Tor's state directory).
- Details: [`transport.md`](transport.md).

### Services beside the layers

- **protocol** — wire types and codecs (`BinaryEnvelope`, `MessageEnvelope`, `SystemEnvelope`,
  `BootstrapEnvelope`, `FileEnvelope`, `WebRtcSignalEnvelope`), ids (`AccountId`, `PeerId`, `RoomId`,
  `TorEndpoint`), and the observability profiles (`EnvelopeObservability`) that say which routing metadata is
  visible on the wire. Also the public identity vocabulary that travels on the wire (`protocol/identity`: public
  keys, prekey ids, `SignedPreKey`, `AccountIdentity`, `DeviceIdentity`) and the WebRTC signal types. It is the
  bottom of the graph: it depends on nothing else in the core. Codecs encode and decode; signing, hashing and
  encryption happen elsewhere (e.g. room ids are hashed by the DAG's `RoomIds` from bytes `protocol` defines).
- **crypto** — primitives (`CryptoProvider`), private keys (`crypto/keys`), identity services, signatures, and 1:1
  E2EE (X3DH, Double Ratchet, session manager). The identity services: `LocalIdentity` (who this device is;
  readable by every layer), `LocalKeys` (this device's private keys; crypto, protection and setup only) and
  `PeerKeys` (peers' public keys, strictly resolved). Owns its stores, including the keyring-backed key store.
- **protection** — turns a payload into protected envelope bytes and back (sign, encrypt, verify, decrypt) for
  each envelope family. Used by the router; built on crypto.
- **persistence** — the database only: SQLDelight schema (`.sq`), migrations (`.sqm`), driver and column
  adapters, and the generated queries. It is infrastructure, not a layer. Stores — interface and SQL
  implementation — live with the layer that owns their tables: `crypto/store`, `routing/store`,
  `orchestrator/store`, `orchestrator/identity` (§4).
- **config** — each layer defines its own config type next to its code (`OrchestratorConfig`, `RouterConfig`,
  `TorBackendConfig`, …); shared settings and limit types (`TransportLimits`, `MessageLimits`, the GUI's
  setting types) live in `config`. Loading, watching and distributing config is done by the composition root.
- **logging** — `AppLog`, usable everywhere.

## 3. Dependency rules

A package may import only what its row allows (plus `logging`, `config`, `persistence` and the standard
libraries).

| Package      | May depend on                                                                   |
|--------------|---------------------------------------------------------------------------------|
| protocol     | —                                                                               |
| transport    | protocol                                                                        |
| crypto       | protocol                                                                        |
| protection   | crypto, protocol                                                                |
| routing      | transport, protection, protocol, crypto's identity views (`crypto.identity`)    |
| orchestrator | routing (`Router`, its vocabulary, and the ports it declares), crypto, protocol |
| wiring       | everything                                                                      |

Consequences:

- When a lower layer needs something from a higher one, it **declares an interface in its own package** and the
  higher layer implements it. The composition root connects them. Lower layers never import upper-layer types.
- Exceptions cross a layer boundary only as the receiving layer's own vocabulary (e.g. a crypto failure becomes a
  protection verdict before the router sees it).
- The rules are checked by `ArchitectureTest` (jvmTest); a new dependency that breaks the table fails the build.
  Violations still being worked off are listed there by file as known debt — the list only shrinks.

## 4. Data ownership

Every table has exactly **one owning layer**, and only that layer writes it. Other layers read through narrow
read-only interfaces that the *reading* layer declares (the router's `DeviceDirectory`, crypto's
`PeerKeyDirectory`). A store with write methods is handed only to the component that is allowed to write.

| Tables                                                               | Owner          | Writers                                                                                                                                                                                                         |
|----------------------------------------------------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `accounts`, `devices`, `signed_prekeys`                              | orchestrator   | the global fold (`IdentityWriter`); intro seeding of provisional rows (`ProvisionalIdentitySeeder`, onboarding only); this device's own rows and prekeys at setup (`LocalIdentityStore`, identity provisioning) |
| `one_time_prekeys`, `crypto_sessions`, `crypto_session_skipped_keys` | crypto         | the session manager and its key services                                                                                                                                                                        |
| `messages`, `message_parents`, `causal_hold`                         | orchestrator   | the DAG engine                                                                                                                                                                                                  |
| `message_deliveries`                                                 | orchestrator   | the delivery tracker                                                                                                                                                                                            |
| `rooms`, `room_members`                                              | orchestrator   | the room fold; GLOBAL membership: the global fold (the GLOBAL room itself is seeded by the schema)                                                                                                              |
| `pending_syncs`, `pending_sync_candidate_accounts`                   | orchestrator   | the sync coordinator                                                                                                                                                                                            |
| `sync_attempts`, `sync_declined_devices`                             | routing        | the sync retry processor                                                                                                                                                                                        |
| `device_routes`                                                      | routing        | the router: endpoints it proved, reliability, last seen (a proven endpoint overrides the declared one in `devices`)                                                                                             |
| `outbox`, `dedup`                                                    | routing        | the router                                                                                                                                                                                                      |
| `file_transfers`, `file_chunk_tracker`, `spaces`                     | not in use yet | —                                                                                                                                                                                                               |

Rules:

- The folds are the only writers of state derived from the event chains. Services that act on the user's behalf (add a
  member, remove a device) **publish an event**; they never write the projected tables directly.
- How to reach a device (endpoint, availability, last seen) is never part of the orchestrator's identity
  model: the router owns it and reports it per account (`Router.accountReach()`), for display only.
- The full identity store (`IdentityStore`) is held only by the composition root. Everyone else gets the view
  they need: `IdentityReader`, `IdentityWriter`, `ProvisionalIdentitySeeder`, `LocalIdentityStore`,
  `PeerKeyDirectory`, `DeviceDirectory`.
- Each `.sq` file's generated `*Queries` is used only in its owner's package; `ArchitectureTest` checks it.
  Adding a table means adding a row here and an owner there in the same change.
- Schema changes ship with a migration; the database is the only thing that has to survive a rewrite.
  Migrations run with foreign keys off and are checked with `foreign_key_check` before they come back on, so a
  table can be rebuilt in place (SQLite's procedure for changes `ALTER TABLE` can't make).

### Data types

- **Keys are typed.** Public keys (`SigningPublicKey`, `EncryptionPublicKey`, under `PublicKey` in
  `protocol/identity`) and private keys (`SigningPrivateKey`, `EncryptionPrivateKey`, under `PrivateKey` in
  `crypto/keys`) are separate hierarchies, so a private key can never stand where a wire key is expected. All
  wrap the bytes, compare by content, and never print a private key. Prekey ids are `SignedPreKeyId` /
  `OneTimePreKeyId`. Raw `ByteArray` keys appear only at the wire (codecs, payload classes)
  and inside the ratchet; convert at that boundary.
- **Keyring entries are `KeySlot`s.** Each key the app holds outside the database has one slot; its keyring
  name never changes for an existing slot.
- **One entity per table, named for what it is.** `Account` (with its `AccountDevice`s, in
  `orchestrator/identity`) is the stored account; `AccountIdentity`/`DeviceIdentity` are what an account or
  device presents about itself (carried in bootstrap payloads), and a stored entity contains its identity rather
  than duplicating it. Public and private halves are separate types:
  `SignedPreKey` is what peers see, `LocalSignedPreKey` adds our private half. No `Row`/`Record` suffixes.
- **Generated SQLDelight classes never leave their store.** Column adapters map columns straight to these
  types where they can.

## 5. The composition root

The composition root (`wiring`) builds the core in two phases, each built fresh on every start:

- **Vault** (`VaultGraph`): what exists as soon as the data directory is open, before any local identity —
  config store, key store, database, the identity store with `LocalIdentity`, `LocalKeys` and `PeerKeys`,
  provisioning. Boot diagnosis and the
  first half of setup run against it.
- **Node** (`NodeGraph`): everything that needs the local identity — stores, crypto sessions, protection,
  transports, the router, the DAG, the folds, sync, onboarding and the runtime services. Its constructor only
  wires; `start()` starts the parts in dependency order.

The orchestrator never constructs anything. It declares what its lifecycle needs (`NodeAssembler`, `Vault`,
`Node` in `orchestrator/boot`), and `wiring` implements those. The public entry point
`OrchestratorFactory` lives in `wiring`; each platform (JVM, Android, iOS) gives it a `Platform` (keyring,
database driver, Tor and WebRTC backends, logger, config watcher).

- `DefaultOrchestrator` is the lifecycle only: lock, boot diagnosis, setup, start/stop, reset, and the
  public state flows.
- Setup is split around node assembly: `LocalSetupPreparer` provisions the identity on the vault, and
  `LocalSetup` finishes the chosen path on the running node (`orchestrator/onboarding`).
- **Components subscribe to their own inputs.** A component that reacts to another's flow takes that flow (or
  its owner) in its constructor and collects it in its own `start()`. The composition root contains no
  `launch`/`collect`; the only collectors in the lifecycle mirror the node's flows into the stable public ones.
- Each component takes its dependencies through its constructor as interfaces; no service locators, no
  `lateinit` wiring after construction. Something that needs a value only known at runtime (the local device
  id) is built after that value is resolved, not patched in later.

## 6. Delivery model

Effectively-once delivery rests on the **pull path**, not the push path. Every sent message is appended to the
local DAG first; pings advertise DAG tips (frontiers); peers pull what they miss with sync requests. The outbox
push (dispatch + ACK/NACK retry) is a latency optimization — a deferred or expired push is a latency problem,
never a loss.

- Sync is split along the same line as everything else. The orchestrator owns **what** is missing, which
  accounts may hold it, and what may be served to whom (`pending_syncs`, the serve gate). The router owns **asking**:
  which candidate device, when, and carrying the request and reply. It reads the content through
  `SyncPayloadProvider`, an interface it declares and the orchestrator implements, and the orchestrator hands
  it each sync with `Router.startSync`. Only candidate devices with an open link are asked; with none, their
  links are nudged and the sync is asked the moment one comes up. Each attempt is one sync request sent
  through the outbox with a short lifetime (`syncAttemptTimeout`). A `DECLINED` NACK rules that device out for
  the sync; expiry or any other NACK only moves it behind the other candidates, and the next one is asked at
  once. An ACK leaves the backoff to schedule the next attempt. A sync ends only when its pending sync does.
- The router owns pings as a mechanism (liveness, link adoption, availability). The frontiers inside a ping
  come from the orchestrator (`FrontierSnapshotProvider`, per account), and received frontiers go back to it
  per account; what they mean (new syncs, reopening declined ones, re-pushing a removal) is decided there.
- A frontier can name a room this device has never seen (e.g. a device just added to an account that is already
  in rooms): the room gets a provisional `UNKNOWN` row, as on ingest, so a sync row can be created, and the
  room's own history fills it in.
- Sync rows die when their target arrives in any state (`Inserted` or `BecameOrphan`): an arrived orphan's fetch
  is satisfied and its missing parents get fresh rows. This keeps out-of-order delivery (normal for
  store-and-forward) from leaking sync rows.
- Repair that follows from content (e.g. re-delivering a removal node to a device whose ping shows it never saw
  its removal) is decided by the orchestrator and sent through the router as an ordinary payload.

### Outcomes and delivery state

- Every outbox entry that someone waits on carries a tag saying what it is for (a message push, a bootstrap
  packet, a sync attempt). However the entry leaves the outbox — ACK, terminal NACK, expiry — the outcome is
  reported once, by tag, to whoever waits on it. New outbox traffic that needs its outcome gets a tag; it
  does not add another callback path.
- Upward, the router reports only push-path ACKs, per account (`Router.deliveries`). An ACK proves delivery;
  its absence proves nothing, since relays and the pull path never ACK to the original sender.
- What the user sees is the orchestrator's: the delivery tracker records an account as holding a message on
  a push ACK, or when that account's ping frontier descends from the message (which covers relay and sync
  delivery). There is no "failed" state while the pull path exists — only "not known delivered yet".

### Protection verdicts

`ProtectionDisposition` is two-valued:

- `PERMANENT` — these bytes are dead (bad signature, replay, violation, corrupt).
- `DEFER` — prerequisites missing (session/identity not ready). There is no "retryable" protection verdict:
  retry policy is owned by the outbox.

### Inbound and outbound dispositions

- `DEFER` is silent by design: no ACK, no NACK, dedup row cleared, so the sender's resend is processed fresh
  once the prerequisite lands. A NACK here would either replay a stale verdict for later duplicates or falsely
  ACK the packet through the duplicate-replay path.
- `ACK` means processed — never sent before a successful decrypt-and-handle.
- The sender owns a message until it decrypts on the receiver: there is no inbound hold queue; the sender's
  outbox retry is the only recovery path for undecryptable packets.
- A received NACK's meaning is a pure function of its reason, decided in one place in the router. Terminal
  reasons (`PERMANENT_PROTECTION_FAILED`, `DECODE_FAILED`, `UNSUPPORTED_TYPE`, `EXPIRED`, `DECLINED`) delete
  the outbox row; `WRONG_TARGET` keeps it (endpoint reconciliation self-heals). An unrecognized reason never
  deletes — never act destructively on confusion with a newer peer.
- Outbound `DEFER` (session not ready at protect time) surfaces as a deferred device count in the router's
  account report. The message stays in the DAG and delivery falls back to the pull path; the orchestrator shows
  that member as pending, never as failed. There is deliberately no staging table: epoch-1 encryption
  self-bootstraps, so the remaining defer windows are round-trip-scale races the pull path covers.
- The outbox has no processing round: each due entry is claimed (attempt and next retry recorded first) and sent
  on its own, so a slow send never delays what is queued behind it. In-flight sends are capped globally and per
  target. Sends never open a connection: an entry whose peer has no open link waits for one. Bootstrap packets
  with an explicit endpoint are the exception: Tor dials a stream for them on demand.
- Unknown devices: an ACK/NACK from a device with no row is accepted only when it refers to a bootstrap packet
  in our outbox addressed to exactly that device (a recovering device can't verify its sponsor's signature yet;
  the unguessable packet id authenticates the reply). It is never answered. Accepted bootstrap ACKs are
  reported to the orchestrator so onboarding can show "request delivered".

## 7. Identity and keys

- Identity lives in the global chain: accounts and devices, each with signing and encryption keys. The global
  fold projects it into `accounts`/`devices`.
- The account signing key exists only on the account's first device (genesis, new account, or recovery-key
  import). A device added to an existing account never holds it: it starts on a key-less stand-in local account,
  and when the fold confirms the device under its real account, that account becomes the local one (the
  stand-in and its memberships fold into it).
- Device identity keys are immutable; there is no identity-key rotation.
- Private keys are reached only through `LocalKeys`; peers' public keys through `PeerKeys`, which throws on a
  missing record rather than returning a partial one.
- Private keys and the database master key live in the OS keyring, outside the data directory. Deleting the data
  directory therefore leaves keys behind; a freshly created database can't own any of them, so boot deletes
  them and goes to setup. A reset deletes everything in the data directory except the held `app.lock`.
- The database is encrypted with the keyring-held master key.
- E2EE design, conscious trade-offs and the open backlog: [`e2ee.md`](e2ee.md).

## 8. Feature status

Working:

- Tor transport with long-lived streams, self-recovery and reachability checks; WebRTC data channels with
  signaling over Tor.
- Router: outbox with retries, relays/store-and-forward, ACK/NACK, dedup, link keeping, pings, availability.
- 1:1 E2EE (X3DH + Double Ratchet) for messages and WebRTC signaling.
- Global chain (accounts, devices, admin roles), rooms with a membership fold, the message DAG and sync.
- Onboarding by invite, adding devices, recovery.
- Compose desktop GUI.

Not done yet:

- File transfer (envelope family exists; protection, pipeline and storage are not built).
- Audio/video media (session contracts exist; media tracks do not).
- Mobile: push and background behavior, network monitoring on Android/iOS.
- Signed-prekey rotation and the remaining epoch lifecycle work (see [`e2ee.md`](e2ee.md)).
- Metadata minimization beyond the current observability profiles (desirable, not a v1 blocker).

## 9. Writing new code

1. **Pick the layer by vocabulary.** If the code talks about rooms, members, messages or accounts, it is
   orchestrator code. If it talks about devices, envelopes, retries or links, it is router code. If it only
   moves bytes, it is transport code.
2. **Check the dependency table.** If you need something from a higher layer, declare an interface in your own
   package and let the higher layer implement it.
3. **Check data ownership.** Write only tables your layer owns; read others through a read-only view your
   layer declares. If identity or room state must change, publish an event and let the fold write it.
   Use the typed keys, ids and entities (§4, data types), not raw bytes and strings.
4. **Wire it in the composition root**, with constructor-injected interfaces.
5. **Wire types go in `protocol`.** A new envelope or payload gets a codec with round-trip tests and an
   observability profile if it adds routing metadata.
6. **Respect the delivery contract** (§6): never ACK before handling, never NACK a `DEFER`, never delete on an
   unknown reason.
7. **Keep the docs honest.** A change that moves a rule in this guide updates the guide in the same change;
   mechanism details go in the layer's own doc.
