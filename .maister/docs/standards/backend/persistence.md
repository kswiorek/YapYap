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
