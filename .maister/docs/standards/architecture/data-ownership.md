## Data Ownership

Who writes which table. This file derives from docs/guide.md §4 (the ownership table and rules — the authoritative
rules-of-the-code document), corroborated by docs/global events.md and docs/room events.md; per-table ownership is
enforced by `ArchitectureTest`'s `queryOwners` map.

### Every table has exactly one owning layer

Every database table has exactly one owning layer and only that layer writes it. Other layers read through narrow
read-only interfaces that the *reading* layer declares (the router's `DeviceDirectory`, crypto's `PeerKeyDirectory`). A
store with write methods is handed only to the component allowed to write. The full ownership table in guide.md §4 is
normative; adding a table means adding a row there and an owner in the same change.

Sources: docs (guide.md §4, §9) · confidence 92.
Evidence:

- guide.md §4: "Every table has exactly **one owning layer**, and only that layer writes it … A store with write methods
  is handed only to the component that is allowed to write."
- guide.md §4 rules: "Adding a table means adding a row here and an owner there in the same change."
- guide.md §9 step 3: "Write only tables your layer owns; read others through a read-only view your layer declares."
- Ownership table (excerpt): accounts/devices/signed_prekeys → orchestrator (global fold `IdentityWriter`);
  one_time_prekeys/crypto_sessions → crypto; messages/message_parents/causal_hold → orchestrator (DAG engine);
  rooms/room_members → orchestrator (room fold); pending_syncs → orchestrator (sync coordinator);
  sync_attempts/sync_declined_devices → routing (message pull processor); device_routes/outbox/dedup → routing (router);
  file seal/transfer tables split per envelope-redesign.md §5.

### Folds are the only writers of chain-derived state; services publish events

State derived from the event chains is written only by the folds. Services acting on the user's behalf (add a member,
remove a device) must publish an event and let the fold write the projected tables; they never write the projected
tables directly. If identity or room state must change, publish an event.

Sources: docs (guide.md §4; global events.md §1; room events.md §2) · confidence 92.
Evidence:

- guide.md §4 rules: "The folds are the only writers of state derived from the event chains. Services that act on the
  user's behalf … **publish an event**; they never write the projected tables directly."
- global events.md §1: the `accounts`/`devices` tables are a **materialized view** of the global event log; the
  projector (`RbacProjector`) is the **sole writer** of their chain-derived columns.
- room events.md §2: `room_members` becomes a materialized projection of the room event log; a room fold is the sole
  writer of chain-derived membership.

### Router reachability data never enters the identity model

What the router learns about reaching a device (proven endpoint, availability, last seen) is router state in routing's
own tables (`device_routes`), never part of the orchestrator's identity model. The router reports it only per account
(`onlineAccounts`, `Router.accountReach()`), never per device. The endpoint a device declared on the chain is identity
data and stays with the orchestrator; a proven endpoint overrides the declared one in routing's `device_routes`.

Sources: docs (guide.md §4) · confidence 90.
Evidence:

- guide.md §4 rules: "What the router learns about reaching a device (proven endpoint, availability, last seen) is never
  part of the orchestrator's identity model. The router reports it only per account …, never per device."
- guide.md §4 table: `device_routes` | routing | "endpoints it proved, reliability, last seen (a proven endpoint
  overrides the declared one in `devices`)."

### Full IdentityStore held only by the composition root

The full identity store (`IdentityStore`) is held only by the composition root. Every other component receives only the
narrow view it needs: `IdentityReader`, `IdentityWriter`, `ProvisionalIdentitySeeder`, `LocalIdentityStore`,
`PeerKeyDirectory`, `DeviceDirectory`.

Sources: docs (guide.md §4) · confidence 90.
Evidence:

- guide.md §4 rules: "The full identity store (`IdentityStore`) is held only by the composition root. Everyone else gets
  the view they need: `IdentityReader`, `IdentityWriter`, `ProvisionalIdentitySeeder`, `LocalIdentityStore`,
  `PeerKeyDirectory`, `DeviceDirectory`."

### Generated SQLDelight queries stay in their owner's package

Each `.sq` file's generated `*Queries` class is used only in the package that owns those tables; `ArchitectureTest`
checks this via its `queryOwners` map (e.g. identityQueries → orchestrator.identity, roomQueries → orchestrator,
messageQueries/syncQueries → orchestrator.store, cryptoQueries → crypto.store, outbox/dedup/routesQueries →
routing.store, fileQueries → orchestrator.file).

Sources: docs (guide.md §4), CI config · confidence 91.
Evidence:

- guide.md §4 rules: "Each `.sq` file's generated `*Queries` is used only in its owner's package; `ArchitectureTest`
  checks it."
- ArchitectureTest.kt `queryOwners` map names every generated queries class and its owning package; a use outside the
  owner fails the build.
