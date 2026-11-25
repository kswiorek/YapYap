## Events

The event-chain model: global (identity/RBAC) and room folds, verification verdicts, revocation, membership projection.
This file derives from docs/global events.md and docs/room events.md (the design documents for the event chains, quoted
normatively below) plus docs/db schema.mmd. Ownership of the projected tables is covered in
architecture/data-ownership.md; the sync lifecycle op placement also appears here because it is decided in room
events.md.

### GLOBAL room is the single source of truth for identity/RBAC

The global control room (`RoomId.GLOBAL`) is the single source of truth for identity and RBAC. The accounts/devices
tables are a materialized view (projection) of the global event log; the projector (`RbacProjector`) is the sole writer
of their chain-derived columns. Events contain as little information as possible — only keys and bindings that cannot be
derived; everything else (role, membership, removal state) is derived from the log. The projector runs on all nodes
including headless relays (relays need the projection for the 4d firewall).

Sources: docs (global events.md §1, §7) · confidence 91.
Evidence:

- global events.md §1: "The global control room (`RoomId.GLOBAL`) is the **single source of truth** for identity and
  RBAC. The `accounts` / `devices` tables are a **materialized view** (projection) of the global event log. The
  projector (`RbacProjector`) is the **sole writer** of their chain-derived columns."
- global events.md §1: "Events contain **as little information as possible**: only keys and bindings that cannot be
  derived."
- global events.md §7: "Runs on **all nodes including headless relays** (relays need the projection for the 4d
  firewall)."

### Always fold in canonical order, never arrival order

Event folds run in canonical order (topological over `prevIds` edges, `(created_at ASC, message_id ASC)` tiebreak),
never in arrival order — canonical order makes the fold deterministic and convergent for any node holding the same
message set. The fold is a pure function of the stored message set — it folds the chainable graph (reachable from the
winning genesis root, every ancestor VERIFIED) — and maintains its own shadow state; it cannot consult the live tables
mid-rebuild. A full re-fold per trigger is correct and cheap at this network scale; optimize with watermarks only if
ever needed.

Sources: docs (global events.md §2) · confidence 92.
Evidence:

- global events.md §2: "**Always fold in canonical order — never apply events in arrival order.** Concurrent opposing
  writes … resolve differently per node under arrival-order application."
- global events.md §2: "**The fold is a pure function of the stored message set — and it folds the chainable graph.**"
  and "The fold maintains its own **shadow state** … It cannot consult the live tables mid-rebuild — that is the whole
  point of replay."
- global events.md §2: "At 10–20 users the global log is tiny — a full re-fold on every trigger is correct and cheap."

### Authenticity-only verification verdicts

`verification_state` is authenticity-only: well-formed + signature valid + author ever existed. REJECTED is reserved for
proven forgery only (bad signature, undecodable, wrong payload type, id-derivation mismatch) — permanent, poisoning
structural descendants to PENDING. Authorization-invalid, cut, sealed or duplicate events are ignored — stored VERIFIED,
given no shadow effect — so bans and policy failures never fork the chainable graph. Unresolvable author means PENDING,
never REJECTED. Verdicts are per-chain, never whole-room. PENDING may flip to VERIFIED as the stored set grows; REJECTED
never flips.

Sources: docs (global events.md §2; room events.md §3) · confidence 92.
Evidence:

- global events.md §2: "**`verification_state` is authenticity-only** … REJECTED = proven forgery only …
  Authorization-invalid / cut / sealed / duplicate events are **ignored** — stored VERIFIED, given no shadow effect — so
  bans and policy failures never fork the chainable graph."
- global events.md §2: "**Unresolvable author → PENDING, never REJECTED.**" / "**Verdicts are per-chain, never
  whole-room.**"
- room events.md §3: room verdicts copy the global tier's discipline exactly: "**authorization never touches the
  verdict**."

### Two verification tiers via verificationPolicy, not an if-GLOBAL

Chat rooms are externally verified at ingest against the live (chain-backed) tables by the engine. The global room is
self-verifying: ingest stores global events PENDING and the projector owns verification during the fold. The tier
difference must be expressed as an explicit `verificationPolicy: (RoomId) -> VerificationMode`, not an
`if (roomId == GLOBAL)` in the engine.

Sources: docs (global events.md §4) · confidence 90.
Evidence:

- global events.md §4: "**Chat rooms are externally verified**: at ingest, against the live tables … **The global room
  is self-verifying**: its authors are defined by its own earlier content …" and "Express as an explicit
  `verificationPolicy: (RoomId) -> VerificationMode`, not an `if (roomId == GLOBAL)` in the engine."

### Engine is the sole verdict writer for room messages; projector writes zero

For every chat-room message (`Text` and `RoomEvent` alike), the engine classifies and writes the verdict at ingest; the
room projector writes zero verdicts and zero flags — not just for simplicity: fold-written PENDING would oscillate
against engine reverify flips forever (single writer kills the class). Structural rule: only `RoomCreated` may have
`prevIds == []` in a chat room; a non-RoomCreated empty-prevIds payload is a forged genesis attempt — fail closed at the
engine (REJECTED), never the gap machinery. `ensureRoomExists` (idempotent `INSERT OR IGNORE`) runs on ingest to unblock
the `messages.room_id` FK for pre-genesis orphans.

Sources: docs (room events.md §3) · confidence 90.
Evidence:

- room events.md §3: "the engine is the **sole verdict writer for every room message** (`Text` and `RoomEvent` alike),
  at ingest."
- room events.md §3: "The projector writes **zero** verdicts — not just simplicity, an oscillation trap … Single writer
  kills the class."
- room events.md §3 (Structural rules): "**Only `RoomCreated` may have `prevIds == []` in a chat room.** A non-
  `RoomCreated` empty-`prevIds` payload is a forged genesis attempt → fail closed at the engine (`REJECTED`), never the
  gap machinery."
- room events.md §3: "**`ensureRoomExists` on ingest** (idempotent `INSERT OR IGNORE`) — unblocks the `messages.room_id`
  FK for pre-genesis orphans."

### Revocation seals the frontier it was appended at

Because positions are self-declared and backdatable, every revocation additionally seals the frontier it was appended at
(its vouching set = transitive closure of its `prevIds`, fixed and signed at append time). Device bans seal everything
(full scope, terminal) — every event authored by the banned device that is not an ancestor of the ban is ignored.
Account-key-authorized AddDevices die with the account (absolute). Admin demotions seal admin authority
(interval-scoped, reversible — re-grants open a new interval). An invalidated AddDevice projects as an implied
`RemoveDevice` at the revocation's position — never a retraction to "never existed", which would make the device's
pre-ban messages unverifiable.

Sources: docs (global events.md §3) · confidence 89.
Evidence:

- global events.md §3 (Revocation seal): "Positions are self-declared: `prevIds` and `createdAt` are
  attacker-controlled … *A revocation seals the frontier it was appended at.*"
- global events.md §3: "1. **Device bans seal everything (full scope, terminal).** … *Every* event authored by D that is
  **not an ancestor of that ban node** is ignored"; "2. **Account-key adds die with the account (absolute, unchanged).**
  "; "3. **Admin demotions seal admin authority (interval-scoped, reversible).**"
- global events.md §3: "An invalidated AddDevice projects as an **implied `RemoveDevice` at the revocation's
  position** … — never a retraction to 'never existed', which would make the device's pre-ban messages unverifiable."

### Validity-paradox doctrine: the contested principal loses

The uniform doctrine for authorization paradoxes: in a validity paradox, the contested principal loses (fail-closed on
authorization, fail-closed on revocation-evasion). Depth-1 instances resolve as mutual destruction — concurrent opposing
revocations (each authorization-plausible) both stand; the plausibility gate is load-bearing (a non-admin's forged
counter-revocation forms no pair and cannot drag its revoker down). The owner is exempt: counter-revocations targeting
the owner's account or devices fail authorization outright, keeping the network's repair path alive. Deeper cycles are
decided by the deterministic restart loop keeping the visited fixpoint with the most revocations in effect.

Sources: docs (global events.md §1, §3) · confidence 88.
Evidence:

- global events.md §1: "The uniform doctrine: **in a validity paradox, the contested principal loses** (fail-closed on
  authorization, fail-closed on revocation-evasion)."
- global events.md §3 (Opposing pairs): concurrent opposing pairs "resolve as **mutual destruction: both revocations
  stand** … except against the owner" (owner irrevocable, owner devices unbannable).
- global events.md §3 (Deeper cycles): "the loop detects the repeated revocation-state and keeps the visited fixpoint
  with the most revocations in effect (ties: canonical node order …)."

### Duplicate adds are invalid; ids are self-certifying

Duplicate `AddDevice` for an existing (or tombstoned) device_id is invalid; re-adding after removal requires a fresh key
set, hence a new device_id — this is what makes removal a ban. Duplicate `AddAccount` for an existing (active or
tombstoned) account_id is invalid. Device and account ids are self-certifying (`peerIdFromPublicKey`) and the fold
asserts the derivation, so a forged add carrying attacker keys for a victim id cannot match. Room ids are likewise
self-certifying: `roomId = uuidFromHash(SHA-256("YapYapRoomIdV1" || genesisMessageId))` — a forged genesis derives a
different room id, so hijacking an existing room is structurally impossible; only RoomCreated may have empty prevIds.

Sources: docs (global events.md §3; room events.md §2) · confidence 90.
Evidence:

- global events.md §3: "Duplicate `AddDevice` for an existing (or tombstoned) device_id → invalid. Re-adding after
  removal requires a fresh key set → new device_id → effectively a new device. This is what makes removal a ban. Device
  ids are self-certifying (`peerIdFromPublicKey`, as account ids are): the fold asserts the derivation."
- global events.md §3: "Duplicate `AddAccount` for an existing (active or tombstoned) account_id → invalid. Without
  this, re-adding a banned account is a trivial bypass of the account-key ban rule."
- room events.md §2: "**`roomId` is self-certifying**: derived from the genesis node
  (`roomId = uuidFromHash(SHA-256("YapYapRoomIdV1" || genesisMessageId))`, mirroring `peerIdFromPublicKey`) … hijacking
  an existing room is structurally impossible."

### Removal is a status flip, never a deletion

Removal events project to status fields — one shared ACTIVE/BANNED enum for both accounts and devices — never row
deletion. Tombstones keep historical message authorship verifiable, let the firewall distinguish banned from never-seen,
and let the fold deterministically reject reuse of a removed id. Room membership likewise: `room_members` keeps rows on
removal with a status column (ACTIVE/REMOVED) plus the defining `removal_node_id`; removal is a status, not a role (the
role column gains OWNER instead). Re-add after removal starts as MEMBER — admin never resurrects on MemberAdd.

Sources: docs (global events.md §5; room events.md §4, §5, §11) · confidence 91.
Evidence:

- global events.md §5: "Removal events project to **status** fields — settled: one shared `ACTIVE` / `BANNED` enum for
  both `accounts` and `devices` — never row deletion."
- room events.md §5: "`room_members` = projection of the fold output, **with rows retained on removal**: a `status`
  column (`ACTIVE`/`REMOVED`) alongside the role (`OWNER`/`ADMIN`/`MEMBER`) — removal is a status, not a role."
- room events.md §11: "**`REMOVED` as a `RoomMemberRole` value**: rejected — removal is a status, not a role (same
  reason `devices.status` is not a `DeviceType`)."
- room events.md §4: "**Re-add starts as `MEMBER`** — admin never resurrects on `MemberAdd`."

### Store-don't-drop; sync serves stored bytes regardless of verdicts

Store everything, flag state, never permanently drop — nothing is ever missing from the DB, so the sync machinery never
chases a message it will re-drop (the neverending sync loop is structurally impossible). Structural queries (gap
closure, counts, orphan checks) see everything; only behavioral queries (tail computation, display) filter. Sync serves
stored messages regardless of verification state — they are signed, tamper-evident bytes; each node verifies
independently. Never chain off a REJECTED message. Hard blocks on unsolicited traffic are forbidden — relay deposits are
unsolicited by definition; provenance may only be policy metadata, never a drop condition.

Sources: docs (global events.md §6; room events.md §7) · confidence 92.
Evidence:

- global events.md §6: "**Store everything, flag state, never permanently drop** — the orphan machinery philosophy,
  extended to verification."
- global events.md §6: "**Structural queries see everything; behavioral queries filter.** … Only tail computation
  (`selectRoomTail`) and display (`selectMessagesInRoomPageDesc`) exclude `REJECTED`. Never chain off a `REJECTED`
  message."
- global events.md §6: "**Sync serves stored messages regardless of verification state** — they are signed,
  tamper-evident bytes; each node verifies independently. Refusing to serve `REJECTED` exports the loop to peers."
- room events.md §7: "a hard block is also forbidden: store-don't-drop … means unsolicited messages must be stored and
  judged, never refused."

### No positional checks — causal order comes from prevIds at read time

There is no scalar clock to forge: causal order is derived from `prevIds` edges at read time (sync streaming, fold), so
a forged position is meaningless by construction. The one unorderable shape — a causality cycle — is detected
structurally: its members never become ready in a topological sort and replay last, keeping their ancestry incomplete.
Append refuses when the chainable frontier is empty but the room holds messages (`DagException.FrontierUnavailable`) —
the empty-frontier fallback would silently narrow a revocation's vouching set.

Sources: docs (global events.md §2, §3, §6) · confidence 88.
Evidence:

- global events.md §6: "**No positional checks.** There is no scalar clock to forge: causal order is derived from
  `prevIds` edges at read time …, so a forged position is meaningless by construction."
- global events.md §2: "Nodes that never become ready (a forged causality cycle) replay last, deterministically."
- global events.md §3: the append guard "must be resolved to refuse-and-error … (Landed:
  `DagException.FrontierUnavailable`.)" — a revocation appended through the fallback would seal out the revoked
  principal's legitimate subtree.

### Fold commit is a merge, never a blind swap

Projection commits preserve local-only fields (`is_local_account` / `is_local_device`, `reliability_score`,
`last_seen_timestamp`, `push_token`, and the `signed_prekeys` / `one_time_prekeys` tables); chain-derived fields are
overwritten. A live-updated onion endpoint on a confirmed row is preserved (Tor rotation has no chain event; the fold
must not clobber it). The `rooms` row is merged; `room_members` is fully recomputed per commit — via targeted update,
never `INSERT OR REPLACE`, which would clobber local-only columns. Absence from the fold output is ambiguous
(unverifiable vs. invalidated) and the fold disambiguates — a gap must never be projected as a removal.

Sources: docs (global events.md §7; room events.md §5) · confidence 90.
Evidence:

- global events.md §7: "**Commit is a merge, never a blind swap.** Preserve local-only fields: `is_local_account` /
  `is_local_device`, `reliability_score`, `last_seen_timestamp`, `push_token`, and the `signed_prekeys` /
  `one_time_prekeys` tables."
- global events.md §7: "**Absence is ambiguous; the fold disambiguates.** … a gap must never cause (a) [unverifiable] to
  be treated as (b) [invalidated]."
- room events.md §5: "Commit is a merge for the `rooms` row … and a recompute for `room_members` … via a targeted
  update — not `INSERT OR REPLACE`, which would clobber local-only columns."

### GLOBAL room must be seeded and its membership maintained by the projector

A `rooms` row for `RoomId.GLOBAL` (type GLOBAL_CONTROL, `local_seq_n = -1`) must be seeded once, idempotently (DB init
or boot). The projector maintains `room_members` rows for the GLOBAL room — on every `AccountAdded` insert (GLOBAL,
account, MEMBER); on `AccountRemoved` delete — so every existing join/query (sync candidates, ping frontiers, broadcast)
works unchanged. The fold source is `findAllInRoom(GLOBAL)`: all messages, no `is_orphaned` / `verification_state`
filtering (the fold is a pure function of the stored set).

Sources: docs (global events.md §9) · confidence 88.
Evidence:

- global events.md §9 (Step 0): "the projector maintains `room_members` rows for the GLOBAL room — on every
  `AccountAdded` insert `(GLOBAL, account, MEMBER)`, on `AccountRemoved` delete."
- global events.md §9: "A `rooms` row for `RoomId.GLOBAL` (`type = GLOBAL_CONTROL`, `local_seq_n = -1`) must also be
  seeded once, idempotently (DB init or boot)."
- global events.md §9: "The fold source is `findAllInRoom(GLOBAL)` — *all* messages, no `is_orphaned` /
  `verification_state` filtering."

### Room membership lives in the room DAG; principals are accounts only

Room membership is a room-DAG concern: `room_members` is a materialized projection of the room event log and the room
fold is the sole writer of chain-derived membership. Membership, roles and authorization targets are account-level
(humans join rooms, not devices); authorship verification stays device-level. The room fold is a pure function of the
stored room set alone; authorization is purely intra-room — admin status comes from the room fold's own shadow state,
never from GLOBAL positions (the two DAGs share no ordering). `RoomId.GLOBAL` is exempt from the room fold (owned by the
global projector). The room fold ignores global bans entirely (room sovereignty: practical exile is the firewall plus a
manual MemberRemove).

Sources: docs (room events.md §2, §4; db schema.mmd) · confidence 90.
Evidence:

- room events.md §2: "**Room membership lives in the room DAG**, not in live tables. `room_members` becomes a
  materialized projection of the room event log … A room fold is a sole writer of chain-derived membership."
- room events.md §2: "**Principals are accounts only.** Membership, roles, and authorization targets are
  account-level." / "**Authorization is purely intra-room** — admin status comes from the room fold's own shadow state,
  never from GLOBAL positions."
- room events.md §4: "the room fold ignores global bans entirely; practical exile is the firewall plus a manual
  `MemberRemove` by surviving room admins."
- db schema.mmd (ROOM_MEMBERS): "account_pub_key FK 'Humans join rooms, not devices'."

### Membership visibility is render-time policy over the projection, in SQL

What we serve a removed member is what we show of them: the boundary is the removal node's ancestor closure (the member
era). ACTIVE row renders; REMOVED row renders only inside the closure of its `removal_node_id`; no row hides. The rule
is enforced in SQL (`selectRenderablePageDesc` / `selectIsRenderable` in `Message.sq` — one recursive CTE over
`message_parents` joined against `room_members`, shared by window pages, roomPreview, and the live-ingest check), so
hidden rows never leave the DB: no per-message ancestry computation in Kotlin, no stored flags, no pagination holes,
nothing for the GUI to re-derive. The serve gate is the matching trichotomy: ACTIVE → full serve; REMOVED → the ancestor
closure of the requester's removal node (member-era history plus the removal event itself); no row → generic NACK. Every
access reader (roomsOfPeer, fan-out) filters ACTIVE.

Sources: docs (room events.md §3, §6) · confidence 89.
Evidence:

- room events.md §3: "Membership is **render-time policy over the projection** — and the boundary is the removal node's
  ancestor closure (the member era …): what we serve a removed member is what we show of them."
- room events.md §3: "The rule is enforced in SQL … so hidden rows never leave the DB: no per-message ancestry
  computation in Kotlin, no stored flags, no pagination holes, nothing for the GUI to re-derive."
- room events.md §6 (As built, 8.7): "the gate is now a trichotomy: ACTIVE → full serve as today; REMOVED row → the
  ancestor closure of the requester's latest removal node …; no row → generic NACK, unchanged. This is *required*, not
  optional."

### Read direction: the engine writes, repositories read

The DAG engine is the sole writer of message rows and exposes no reads — all lookups (GUI pages, sync, fold,
diagnostics) go through the persistence repositories, each owning its table: `MessageRepository` for messages (including
the render-policy queries), `CausalHoldRepository` for gaps, `RoomRepository` for membership. Routing reads the
repositories, never the orchestrator (this also avoids a projector-router construction cycle).

Sources: docs (room events.md §5, §6) · confidence 89.
Evidence:

- room events.md §5: "**Read direction (binding).** The engine is the sole writer of message rows and exposes no reads —
  all lookups … go through the persistence repositories, each owning its table …"
- room events.md §6 (As built, 8.7): "the engine exposes no reads, so routing reads the repositories, never the
  orchestrator."

### Device-granular sync lifecycle ops live in routing; no device id crosses into the orchestrator

Every device-granular pending-sync lifecycle operation lives at the point where the authenticated device id arrives, all
in the routing layer: mark-attempted on NACK, accelerate-on-online, re-open-on-ping. The orchestrator is account-level
end-to-end — no device id crosses into it. Identity resolution (`getAccountIdForDevice`) happens in the handlers.

Sources: docs (room events.md §6) · confidence 90.
Evidence:

- room events.md §6 (As built): "**Layering principle (binding).** Every device-granular pending-sync lifecycle op lives
  at the point where the authenticated device id arrives, all in the routing layer: mark-attempted on NACK,
  accelerate-on-online, re-open-on-ping. The orchestrator is account-level end-to-end — no device id crosses into it."

### Pending-sync candidates are append-only; backoff, never age-prune; truncate, never refuse

A pending sync row's candidates = members-at-mint ∪ accumulated evidence ∪ refreshed members; all accumulation is
append-only (`INSERT OR IGNORE`), never replace — one trigger's candidates never evict another's. An all-NACK row wakes
on exponential backoff (1h cap) and is pruned only structurally (stale target) — never age-pruned (silent chase-loss).
Sync batches are truncated to the limit, never refused — a refusal NACK would wrongly mark a peer attempted that may
hold the messages; the bound is a DoS guard, not a paging mechanism.

Sources: docs (room events.md §6) · confidence 88.
Evidence:

- room events.md §6 (As built): "A pending sync row's candidates = members-at-mint ∪ accumulated evidence ∪ refreshed
  members … All accumulation is append-only (`INSERT OR IGNORE`), never replace."
- room events.md §6: "**Prune TODO — decided: backoff, never age-prune.**"
- room events.md §6: "**Sync-limits TODO — decided: truncate, never refuse.** … Refuse was rejected: the requester
  cannot know which frontier subset lies on the target's descent path … a refusal NACK would wrongly mark a peer
  attempted that may hold the messages."

### Sibling folds, not a shared kernel; seal fixes land in both with both fuzzers re-run

The global and room folds are siblings kept honest by paired dynamics fuzzers, not a shared SealEngine kernel: the seal
algebra is written fresh per tier (same doctrine, simpler in rooms), and a seal fix lands in both folds and both fuzzers
re-run. Shared pieces are the pure graph helpers (canonicalOrder, childAdjacency, ancestorClosures, reachableFrom) and
the genericized fixpoint driver. When the room fold reaches for a global-fold part, extract it on demand as its own
mechanical commit with the equivalence proof (global dynamics fuzzer 20k seeds + projector tests run unchanged) — never
copy instead of extracting. `foldInputSet` diverges by tier and is never shared. Rule of three for further abstraction.

Sources: docs (room events.md §4, §8) · confidence 87.
Evidence:

- room events.md §4 (Sibling folds, not a shared kernel): "What is shared: **Graph helpers** … **The fixpoint driver** …
  **Doctrine + paired fuzzers** — … **a seal fix lands in both folds and both fuzzers re-run.**"
- room events.md §8: "the global-fold parts are extracted when the fold work reaches for them — never copied — each
  extraction its own commit with the equivalence proof attached … proven by the global dynamics fuzzer (20k seeds) +
  projector tests run **unchanged**."
- room events.md §4: "**Rule of three for the seal algebra**: a servers/room-grouping tier (§10) would be room-shaped;
  abstracting … then beats abstracting now from one real instance and one hypothetical."

### Genesis via a dedicated engine method; the caller never supplies a roomId

Room genesis uses a dedicated engine method, `DagEngine.createRoom(RoomCreatedDraft)` — `append(roomId, draft)` takes a
roomId that is meaningless for a genesis, and the createGenesis invariant is that the caller never supplies a roomId.
The engine mints the id, derives it, runs `ensureRoomExists` for the FK, stores VERIFIED + ancestry-complete, and
refuses a derived room that already holds messages (RoomAlreadyExists). A smuggled RoomCreated via `append` is rejected
as a programming error.

Sources: docs (room events.md §6, §9) · confidence 87.
Evidence:

- room events.md §6 (As built, 8.7): "The prescribed `MessageDraft.RoomCreated` became a dedicated engine method
  instead — `DagEngine.createRoom(RoomCreatedDraft)` … the `createGenesis` invariant ('the caller never supplies a
  roomId') argues for the separate method."
- room events.md §9: "engine: genesis into a room that holds messages refused, `RoomCreated` via `append` rejected as a
  programming error."

### INVITE is out-of-band only; the QR shared secret is one-time and never broadcast

Bootstrap payload kinds: INTRO (sponsor to newcomer, AEAD), INVITE (newcomer to sponsor, out-of-band QR/CLI only — never
on the wire; a wire-borne INVITE is rejected), RECOVERY_REQUEST. The one-time shared secret from the QR never appears in
the `AddDevice` event (that event is broadcast to the whole mesh), authenticates the sponsor out-of-band, and is burned
after initial sync. The `BootstrapEnvelope` header carries the protection scheme in plaintext (AAD-bound) — how to open
it — while the payload kind lives inside the payload.

Sources: docs (global events.md §8; gui-api.md) · confidence 90.
Evidence:

- global events.md §8: "`INVITE` (newcomer→sponsor, **out-of-band QR/CLI only — never on the wire**) … a wire-borne
  INVITE is rejected."
- global events.md §8: "The secret: never appears in the `AddDevice` event (that event is broadcast to the whole
  mesh …), authenticates the sponsor out-of-band …, is one-time; burn it after initial sync."
- global events.md §8.3: "`INVITE` is out-of-band only; `BootstrapEnvelope.init` rejects it on the wire."
- gui-api.md (Notes): "Invites travel as opaque bytes (`SetupResult.inviteBytes` → QR → `sponsorNewcomer(inviteBytes)`);
  undecodable bytes are refused as `MalformedInvite(MALFORMED_BYTES)`. The GUI never inspects invite contents."
