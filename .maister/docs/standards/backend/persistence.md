## Persistence

SQLDelight/SQLCipher conventions for the encrypted database. This file derives from docs/guide.md §2 (persistence
service) and §4 (schema rules — the authoritative rules-of-the-code document), with event-log FK policy from docs/global
events.md.

### Schema changes ship with a migration; FKs off + foreign_key_check

Every schema change ships with a migration — the database is the only thing that has to survive a rewrite. Migrations
run with foreign keys off and are checked with `foreign_key_check` before they come back on, so a table can be rebuilt
in place (SQLite's procedure for changes `ALTER TABLE` can't make).

Sources: docs (guide.md §4) · confidence 91.
Evidence:

- guide.md §4 rules: "Schema changes ship with a migration; the database is the only thing that has to survive a
  rewrite. Migrations run with foreign keys off and are checked with `foreign_key_check` before they come back on, so a
  table can be rebuilt in place."

### persistence is infrastructure, not a layer; stores live with their owner

The persistence package holds only the database itself: SQLDelight schema (`.sq`), migrations (`.sqm`), driver and
column adapters, and the generated queries. It is infrastructure, not a layer. Stores — interface and SQL
implementation — live with the layer that owns their tables: `crypto/store`, `routing/store`, `orchestrator/store`,
`orchestrator/identity`.

Sources: docs (guide.md §2) · confidence 90.
Evidence:

- guide.md §2 (persistence service): "**persistence** — the database only: SQLDelight schema (`.sq`), migrations
  (`.sqm`), driver and column adapters, and the generated queries. It is infrastructure, not a layer. Stores — interface
  and SQL implementation — live with the layer that owns their tables."

### The log is the source of truth; no FKs from messages to identity tables

The `messages` FKs to `accounts`/`devices` were removed: the event log is the source of truth, the tables are derived,
and authorship validity is position-dependent — a static row-existence FK was semantically wrong. `room_id` and
`causal_hold` FKs stay. Bootstrap-targeting tables deliberately carry no FK to devices (the user-supplied endpoint
override is preferred over the DB lookup at dispatch).

Sources: docs (global events.md §6, §8.2) · confidence 87.
Evidence:

- global events.md §6: "The `messages` FKs to `accounts`/`devices` were removed: the log is the source of truth, the
  tables are derived, and authorship validity is position-dependent — a static row-existence FK was semantically wrong
  anyway. `room_id` and `causal_hold` FKs stay."
- global events.md §8.2: "no devices row needed for the target (`outbox`/`dedup` deliberately carry no FK to `devices`;
  the override is preferred over the DB lookup at dispatch)."

### Stores hand out composed views of their child tables; consumers never assemble them

When a consumer needs a parent row composed with its child table (the candidate-asking loop needs a pull-attempt row
plus its declined devices), the store that owns those tables exposes it as a named view, written once in the store's
file: `FilePullAttemptStore.candidateStore(pullClass): CandidateAttemptStore<FileId>`, `SyncAttemptStore`'s
`candidateStore(): CandidateAttemptStore<Uuid>`. No consumer-side adapter wrappers ("docks") — we control all the
interfaces, so there is no need for wrappers. The read is one statement (a LEFT JOIN, the page limit applied to parents
in a subquery), never one query per row. This is intra-layer; "the reading layer declares the view" applies only to
cross-layer reads (`DeviceDirectory`, `PeerKeyDirectory`).

Sources: docs (docs/file/cleanup.md §2.7, §7) · confidence 90.
Evidence:

- cleanup.md §2.7: the view's composition "belongs to **the store that owns the tables**, handed out as a named view,
  never assembled by consumers"; `SyncAttemptDock` and the anonymous file docks are deleted.
- cleanup.md §2.7 (layering note): the reading-layer view rule "is for cross-layer reads"; these views are intra-layer
  and "a store accessor is the right shape for that".
- cleanup.md §7: "The pull and sync stores read a row with its declined devices in one statement (a LEFT JOIN, the page
  limit applied to parents in a subquery) instead of one query per row."
- routing/store/FilePullAttemptStore.kt and routing/store/SyncAttemptStore.kt declare `candidateStore(…)`; sqldelight
  FilePullAttempt.sq / SyncAttempt.sq select with `LEFT JOIN … declined_devices`.
