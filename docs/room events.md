# Room Events & Membership Fold — design decisions

This doc compiles the agreed design for chat-room membership as a room-DAG concern: the event
codec, the room fold, message validity semantics, the membership projection, and the
ping-threading fix for unknown-room sync. It extends the machinery of
[`global events.md`](global%20events.md) (canonical order, shadow state, seals, mutual
destruction, authenticity-only verdicts) to a second tier, and records why several simpler
designs were rejected. Written so implementation can resume without re-deriving the reasoning.

The sprint-4 landing this builds on: the responder-side sync gate (`DefaultSyncPayloadProvider`: serve only rooms in
`roomsOfPeer(requester)`, generic NACK on
denial) is implemented and tested; everything else here is not yet implemented except §6 (ping threading with the
universal accumulation rule, the author candidate, the
inert-gated re-open hook, the membership refresh, and the retry backoff — all
landed with tests; see §6 for the as-built deltas from the original plan).

Related: [`guide.md`](guide.md), [`e2ee.md`](e2ee.md), [`db schema.mmd`](db%20schema.mmd),
[`Synchronization diagram.mmd`](Synchronization%20diagram.mmd).

---

## 1. Problem & threat model

Three distinct "source" identities ride every message, and only one is authoritative:

| Layer    | Field                                                    | Meaning                                                          | Verified by                                                  |
|----------|----------------------------------------------------------|------------------------------------------------------------------|--------------------------------------------------------------|
| Packet   | `BinaryEnvelope.source`                                  | Immediate hop (relay re-wraps)                                   | Ban/unknown gate, dedup — sees the **relay**, not the author |
| Envelope | `MessageEnvelope.source`                                 | Transport sender (author on direct path, responder on sync path) | Envelope signature — proves the forwarder only               |
| Payload  | `senderAccountId` + `authorDeviceId` + `authorSignature` | DAG author                                                       | `classifyMessageAuthorship` — the only author proof          |

Payload level carries two author fields because they are claim + proof, not two authors:
`authorDeviceId` is the crypto principal (signature verifies against its key; device ids
rotate on re-add), `senderAccountId` is the stable principal (membership, display, typing
keys). The binding check (`getAccountIdForDevice(authorDeviceId) == accountId`, else
`INVALID`) prevents sibling-account spoofing.

The attacks that motivated this design:

- **Banned author via collaborator**: a banned device authors a payload; a collaborator (even
  a non-member) wraps it in a clean packet — the packet-level ban gate (`InboundEnvelopeProcessor`) sees only the
  collaborator and passes.
- **Stranger injection**: a never-member authors into a room id (unguessable UUIDs make this
  hard, but relaying launders transport).
- **Non-member sync target**: a peer requests sync for a room it has no right to see — closed
  by the landed responder gate.

Transport-layer forwarder checks cannot close the first two: store-and-forward relays are
legitimately non-members (picked by reliability score, no room filter), and sync responders
legitimately forward other authors' messages. The fix is payload-level: room membership
authorization on the *author*, derived from the room DAG itself — surfaced as render-time
flags over the fold projection (§3): content smuggles nothing (E2EE), so the flags carry
the information a verdict would.

## 2. Core model

- **Room membership lives in the room DAG**, not in live tables. `room_members` becomes a
  materialized projection of the room event log (exactly as `accounts`/`devices` are
  projections of GLOBAL). A room fold is the sole writer of chain-derived membership.
- **Principals are accounts only.** Membership, roles, and authorization targets are
  account-level. Authorship verification stays device-level (payload signature + account
  binding via `classifyMessageAuthorship`); device misbehavior (bans, rotation) is the global
  tier's job and is invisible to the room fold.
- **The room owner slot: creator at genesis, transferable by a deterministic handover.** The
  fold's shadow state carries one owner account (the `RoomCreated` author initially). The
  owner is an irrevocable admin — `RemoveAdmin` and admin-gated `MemberRemove` targeting the
  owner are ignored — so mutual-destruction collateral (§4) can never strip the room of its
  repair path. Non-owner admins may always self-leave: the owner invariant guarantees a
  remaining admin, so no gate is needed. The owner's own exit is the handover: a
  `MemberRemove(self)` carrying `successorAccountId`, honored only in that shape, valid iff
  the successor is a member at position and is not the leaver; on success the successor
  becomes admin + owner atomically and the self-leave seals the leaver's backdated
  admin-gated acts like any `MemberRemove`. Fail closed on every bad shape: owner self-leave
  without a valid successor → ignored (owner stays); any other `MemberRemove` carrying a
  successor (non-owner, or targeting another member) → ignored whole (the field is
  owner-only — no smuggling). One event, not a separate handover kind: a single atomic
  transition, no zero- or two-owner window; if two handovers ever compete, first in canonical
  order wins (the second's author is no longer a member at position). The projection maps the
  owner slot to a new `RoomMemberRole.OWNER` (single instance; enum + adapter addition over
  the TEXT column, no table migration) — a genuine role, unlike the rejected `REMOVED`-as-role (§11); the owner implies
  admin authority in the fold, so the projection never shows `OWNER`
  on a removed row.
- **`roomId` is self-certifying**: derived from the genesis node
  (`roomId = uuidFromHash(SHA-256("YapYapRoomIdV1" || genesisMessageId))`, mirroring
  `peerIdFromPublicKey`). A forged genesis derives a *different* room id — hijacking an
  existing room is structurally impossible, and genesis competition (the global tier's
  most-descendants root selection) cannot occur. The fold asserts the derivation (`derivationOk`-style), like the global
  fold does for self-certifying ids.
- **Room fold = pure function of the stored room set alone.** Authenticity is precomputed
  into the stored `verification_state` column by the engine (§3): a `VERIFIED` row's
  `senderAccountId` is binding-checked (`classifyMessageAuthorship` verifies
  device↔account before `VALID`), `REJECTED` is sticky, `PENDING`→`VERIFIED` is monotone
  with the reverify hooks — so the fold consults no oracle and no live table; the GLOBAL
  dependence is absorbed into the column. **Authorization is purely
  intra-room** — admin status comes from the room fold's own shadow
  state, never from GLOBAL positions. The two DAGs share no ordering, so positional validity
  cannot cross them (this is why the creator-ACTIVE check was rejected, §11).
- **No account-status gate on `roomCreated`.** A genuine pre-ban room creation synced after a
  ban must verify (late-sync rule); a live-table check would kill it permanently; a positional
  check is impossible cross-DAG. Residual, accepted: a banned account can mint *new* rooms via
  a collaborator relay — ghost rooms. Bounded: self-certifying ids (no hijack), no access to
  existing rooms, E2EE keeps content opaque, firewall blocks direct traffic. Same doctrine as
  "mesh re-entry as a fresh account is open by design and grants no access to existing rooms."
- **`RoomId.GLOBAL` is exempt** from everything here (owned by the global projector, as today).

### Event types (`RoomEventPayload`, mirroring `GlobalEventPayload`)

| Event          | Carries                                                   | Authorization (fold state at position)                                                                                                                                                                                         |
|----------------|-----------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `RoomCreated`  | member account list, room name, type, optional space UUID | Genesis (`prevIds == []`); valid signature + the author's device resolves (authenticity — member-level, §2 no-status-gate); member-list targets and the space id are ungated in the fold, deferred only at the projection (§5) |
| `MemberAdd`    | target account id                                         | Author's account `is_admin` at position (seal-subject)                                                                                                                                                                         |
| `MemberRemove` | target account id, optional successor (owner handover)    | Admin-gated for others (owner irrevocable); own-account leave always allowed; owner's own leave requires a valid successor                                                                                                     |
| `AddAdmin`     | target account id                                         | Admin-gated (seal-subject)                                                                                                                                                                                                     |
| `RemoveAdmin`  | target account id                                         | Admin-gated (seal-subject); owner irrevocable                                                                                                                                                                                  |

Codec style mirrors `SystemPayload`/`GlobalEventPayload`: sealed interface, kind byte,
encode/decode per type, carried inside a new `MessagePayload.RoomEvent` variant (`MessagePayloadType.ROOM_EVENT(3)`).
Protocol note: old nodes fail decode on the new payload
type and NACK `DECODE_FAILED` without storing — acceptable pre-fleet; bump nothing, document it.

## 3. Message validity — authenticity-only verdicts, membership as flags

Verdicts copy the global tier's discipline exactly: **authorization never touches the
verdict** — the global fold stores ignored authorization failures as `VERIFIED` and
reserves `REJECTED` for proven forgery, and proven forgeries are permanent crypto facts (which is why `VERIFIED` never
flips there). Rooms get the same discipline with a simpler
justification: **no self-reference**. GLOBAL must defer verdicts to its projector because
global authorship is defined by the global fold itself (the live tables are its own
committed output, stale at ingest — the engine literally cannot answer); room messages
verify against *foreign* state — the GLOBAL projection, already committed, unaffected by
the room's ingest. So the engine is the **sole verdict writer for every room message**
(`Text` and `RoomEvent` alike), at ingest:

| Crypto + structure (engine, at ingest)                                 | Verdict                               |
|------------------------------------------------------------------------|---------------------------------------|
| undecodable `RoomEvent` bytes / wrong payload type                     | `REJECTED` (never flips)              |
| non-`RoomCreated` with `prevIds == []` (forged genesis structure)      | `REJECTED` (never flips)              |
| `roomId` derivation mismatch on the `RoomCreated` node                 | `REJECTED` (never flips)              |
| bad signature / binding mismatch (`classifyMessageAuthorship` INVALID) | `REJECTED` (never flips)              |
| author device unknown globally (`UNKNOWN_AUTHOR`)                      | `PENDING` (reverify on `DeviceAdded`) |
| authentic                                                              | `VERIFIED`                            |

The `RoomEvent` additions to the engine's existing `Text` classification are all
message-local: decode the event bytes (the engine already reads the kind byte for the
structural rule); the genesis derivation check (`roomId == roomIdFromGenesis(messageId)`
on the `RoomCreated` node itself — self-certifying, so a property of that node alone;
non-genesis messages need no check: a wrong id is just a different room). Ignored,
sealed, unreachable and poisoned events all store `VERIFIED` — authorization is the
fold's business and never touches the verdict.

Membership is **render-time policy over the projection** — and the boundary is the
removal node's ancestor closure (the member era: the same boundary as the bounded
sync serve, §6): what we serve a removed member is what we show of them.
`ACTIVE` row → renders; `REMOVED` row → renders only inside the closure of the
row's `removal_node_id`; no row → hidden (the stranger-injection case — no
provenance at all; content is E2EE-opaque anyway, so the hide is defense-in-depth,
not a confidentiality boundary); room not folded yet → no rows → hidden until the
fold's `stateChanges` re-render. The rule is enforced in SQL
(`selectRenderablePageDesc` / `selectIsRenderable` in `Message.sq` — one recursive
CTE over `message_parents` joined against `room_members`, shared by the window
pages, `roomPreview`, and the single-message live-ingest check), so hidden rows
never leave the DB: no per-message ancestry computation in Kotlin, no stored flags,
no pagination holes, nothing for the GUI to re-derive. (The earlier badge-only
design and its `messageDisplayPolicy` function are superseded — §11.)

- The closure closes the backdated window for display: a backdated forgery chains
  onto pre-removal tips but is not itself an ancestor of the removal node, so it
  hides — alongside the provably-post-removal subset (descendants of the removal
  node). What stays visible of a removed author is exactly the history the room
  absorbed: anything anyone replied to pre-removal is transitively inside the
  closure, so there are no dangling replies. The accepted collateral is the
  concurrent legit window (in-flight or relay-held messages at removal time hide
  too) — reversible on re-add (the row flips `ACTIVE` and everything renders
  again), verdict-neutral throughout (stored `VERIFIED`, chainable, served).
- Orphans from removed authors hide unconditionally: the closure is rooted at a
  chainable removal node, whose ancestry is complete by construction — only
  ancestry-complete history is reachable from it, so no explicit orphan predicate
  is needed, and no orphan can chain *into* the closure (it is frozen at the
  removal node). ACTIVE-author orphans still render with gap warnings (sprint-2 UX).
- Sealed-out adds never count → no row ever → hidden (the §4 collateral case intact).
- Re-add after removal → row back to `ACTIVE` (full recompute per fold commit); the
  projection and the flags can never disagree (same source).
- Negative test (done-criteria d3): out-of-closure content is never returned by any
  render path — page, preview, live window insert, notification event — each path
  its own test (the CTE text is duplicated across the named queries; SQLDelight has
  no fragments).
- Chainability: frontier = own-`VERIFIED` ∧ flag everywhere (`Message.sq`
  `selectRoomFrontier`). The flag is verdict-aware: ancestors present AND `VERIFIED`
  (same-room). An authentic message with a `PENDING`/`REJECTED` ancestor is `VERIFIED`
  on its own merits but never chainable — poisoned tips quarantine (never referenced,
  dead branches) instead of poisoning the fold. Harmless: stored regardless
  (store-don't-drop), being a parent launders nothing, and the covering antichain
  self-collapses over the chainable tips. Keeping forger tips out of the frontier is
  what the flag buys, without any verdict-flip machinery (§11).
- Advertisement vs serving: advertisement is policy (ping frontiers never promote
  poisoned tips), serving is convergence (the responder serves stored garbage so the
  requester can close holds — `SyncPayloadProvider` stays verdict-blind deliberately).
- Pre-genesis events need no special casing: missing genesis → missing ancestors →
  orphan → `ancestry_complete = 0` → not chainable — the same path `Text` orphans take
  today.
- The projector writes **zero** verdicts — not just simplicity, an oscillation trap:
  fold-written `PENDING` (e.g. for unreachable nodes) would be flipped back to `VERIFIED`
  by the reverify hooks (engine authenticity) and re-`PENDING`ed by the next fold,
  forever. Single writer kills the class.

Structural rules:

- **Only `RoomCreated` may have `prevIds == []` in a chat room.** A non-`RoomCreated`
  empty-`prevIds` payload is a forged genesis attempt → fail closed at the engine (`REJECTED`), never the gap machinery
  (it would otherwise not be an orphan).
- **`ensureRoomExists` on ingest** (idempotent `INSERT OR IGNORE`) — unblocks the
  `messages.room_id` FK for pre-genesis orphans. Today only GLOBAL is seeded.
- Poisoning is completeness-internal, superseding the earlier fold-internal draft: in
  rooms the engine enforces it via the verdict-aware flag (child of a `REJECTED` node is
  born incomplete and never promotes — the freeze-DoS closure); in GLOBAL the fold
  enforces it walk-internally (inline eligibility: reachable ∧ all ancestors `VERIFIED`).
  The *stored* verdict is untouched (authenticity-only: own bytes only, never flips down;
  `REJECTED` sticky, `PENDING`→`VERIFIED`/`REJECTED` monotone), so each descendant is
  judged on its own merits — a member's reply to garbage is a `VERIFIED` message with
  flag false (never chainable); the garbage hides by flag. Covering-antichain pressure
  is why distrust cannot be transitive content-distrust (it would brick rooms — honest
  appends are forced onto frontier tips): distrust lives in authority (the fold set),
  content judged on its own merits.
- Backdating tolerance stays the doctrine: a removed member's backdated message is
  cryptographically indistinguishable from a legitimate pre-removal message that syncs
  late, and the latter *must* verify. Content exercises no authority, so there is nothing
  to seal (seals exist in the global tier because revoked principals smuggle *authority*;
  here they can only smuggle content, which the badge/hide flags surface).

## 4. The fold — a sibling of the global fold, minus the device tier

The global fold's complexity is concentrated in revocation-of- *authority* vs backdating:
`foldToFixpoint`, `mutuallyRevoked`, `sealed`, `effectiveAdmin`, oscillation ranking. Rooms
need that machinery's doctrine (admins are grant-derived) with a smaller principal model —
written fresh, not copied (below):

| Global tier                                                                          | Room tier                                                                                                                                       |
|--------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Device bans (full-scope terminal seals) + demotions (interval-scoped)                | **Demotion seals only** — one seal kind, the interval-scoped reversible one                                                                     |
| Branch 1/2/3 `AddDevice`, `key_signature` binding, cascade-ban source sets           | Gone — `MemberAdd` has one admin-gated path                                                                                                     |
| Genesis competition (most-descendants root selection)                                | Structurally dead — self-certifying `roomId` admits exactly one genesis per room                                                                |
| `mutuallyRevoked` / `sealed` / `effectiveAdmin` / fixpoint loop / revocation ranking | **Rewritten fresh, same doctrine** — simpler: accounts-only, `revokesPrincipal` is a plain target match; kept honest by the paired fuzzers (§4) |
| `RemoveDevice(own)` / `RemoveAccount(own)`                                           | `MemberRemove(self)` — own-account op, untouched by seals; the owner's variant is the handover (§2)                                             |
| `GrantAdmin` / `RemoveAdmin` / `RemoveDevice(other)` / `RemoveAccount(other)`        | `AddAdmin` / `RemoveAdmin` / `MemberAdd` / `MemberRemove(other)` — admin-gated, seal-subject                                                    |

Shadow-effect discipline copies verbatim, one level down (verdicts are engine-assigned,
§3): authorization-invalid / sealed / duplicate events **ignored** (stored `VERIFIED`, no
shadow effect — policy failures never fork the chainable graph); unresolvable-author (`PENDING` rows),
incomplete-ancestry and poisoned rows are excluded from the fold set —
no shadow effect, stored verdict untouched.

**Sealed `MemberAdd` collateral (accepted, tested):** an innocent account added by a
backdating demoted admin — the add is sealed out, the account was never validly a member,
so it never gets a `room_members` row and its messages render "from non-member" (hidden by
default, §3). Same accepted collateral as the global
tier's "concurrent-but-unsynced admin events die — rare, re-doable by a surviving admin."

### Sibling folds, not a shared kernel (decided)

The authenticity-only verdict decision (§3) removed most of what a shared kernel would
have shared: the room fold needs no `FoldCrypto` (the stored verdict column is the
authenticity digest, §2), writes no verdicts, has no device dimension, no bans, no author
cuts, no genesis competition. A `SealEngine<P>` with tier callbacks would be abstraction
outweighing the shared core — and its main cost, refactoring landed, fuzz-tested
`GlobalFold` mid-sprint, now buys nothing. What is shared:

- **Graph helpers** — `canonicalOrder`, `childAdjacency`, `ancestorClosures`
  (+ `reachableFrom`): pure functions over stored rows with zero tier logic; both projectors
  call them (mechanical extraction, no tier logic moves). Equivalence is still proven by
  the global dynamics fuzzer + projector tests run unchanged. `foldInputSet` diverges by
  tier and is NOT shared: room = stored-flag filter (the adapter feeds the filtered
  order — `VERIFIED` ∧ stored flag, all payload types; the filter is ancestor-closed
  because the flag requires every ancestor `VERIFIED`, so the order doubles as the
  fold set and no separate set exists; `Text` rows ride it as no-ops, no poison
  recompute); global = inline
  eligibility (`reachable` descent only, core checks `ancestors.all VERIFIED` in one
  topological pass).
- **The fixpoint driver** — `foldToFixpoint`, `RevocationState`, the revocation ranking,
  oscillation detection, genericized over the principal type: small, no callbacks, the
  one piece where an oscillation-handling fix lands once.
- **Doctrine + paired fuzzers** — the seal algebra (`mutuallyRevoked` / `sealed` /
  `effectiveAdmin`) is written fresh in the room fold, per the same doctrine but simpler:
  accounts-only; `revokesPrincipal` is a plain target match (no `RemoveAccount`
  device-cascade disjunction); no exemption-vs-cut split. The two implementations are
  kept honest by the paired dynamics fuzzers, not by shared code: **a seal fix lands in
  both folds and both fuzzers re-run.** The room fuzzer needs no crypto stub (the fold
  reads the verdict column — synthetic rows suffice).
- **Rule of three for the seal algebra**: a servers/room-grouping tier (§10) would be
  room-shaped; abstracting the room-tier seal algebra from two real instances then beats
  abstracting now from one real instance and one hypothetical.

Room fold shape: the input adapter maps stored rows to `(id, prevIds, author account =
senderAccountId` — trusted via §2 — `, decoded event, position)` over the filtered
order only (`VERIFIED` `RoomEvent`-and-`Text` rows with the stored flag set —
verdict-aware `ancestry_complete`: ancestors present AND `VERIFIED`, same-room —
engine-written at ingest, up-cascaded on reverify; poisoned descendants never promote).
The filter is ancestor-closed, so the order doubles as the fold set: no separate set,
no reachability pass (every filtered row descends from the `VERIFIED` genesis by
construction), no poison recompute. `Text` rows ride the order as no-ops (needed for
closures and canonical positions, never authority); the output =
shadow-state sets only (members with role/status, the owner slot,
the ever-validly-member set) — no verdict map is written (§3). GLOBAL mirrors this with
the writer flipped: the fold owns both columns (verdict = own bytes, flag = reachable ∧
ancestors `VERIFIED`, monotone false→true at commit); the engine never writes flags there.

### Confirmed: mutual destruction closes the removal-forgery attack

A removed member forging a backdated `RemoveAdmin`/`MemberRemove` against the admin who
removed them is closed by `mutuallyRevoked` exactly as in the global tier: the forged event
and the honest removal concurrently revoke each other's principals, so neither seals the
other — both land — the forger stays removed and the honest admin loses admin/membership
too. Rooms need only the demotion-seal half of the machinery (no ban path, no
liveness-outranks-authority split), and the collateral profile is strictly better: room
mutual destruction is *repairable* (re-grant/re-add by a surviving admin) where global bans
are terminal, and the owner slot (§2) guarantees such an admin always exists — the room can
never be left adminless by forgery. Room fuzzer oracle: the forger never ends a member in
the picked fixpoint; worst case both principals lose; the owner is never removed or demoted
by others; the owner slot is never empty.

Supporting decisions:

- **`MemberRemove` mints a seal and strips admin** — one seal kind, minted by both
  `MemberRemove` and `RemoveAdmin`; a removed admin's backdated `MemberAdd`/`AddAdmin` sorts
  before the removal, outside its ancestry → sealed out (the collateral case above depends
  on this).
- **Re-add starts as `MEMBER`** — admin never resurrects on `MemberAdd` (the room-tier
  analogue of the global tier's fresh-key-set re-entry).
- **Never-admin removed members cannot run the attack** — their forged admin-gated events
  are authorization-invalid at position → ignored; the mutual machinery only ever fires
  between (ex-)admins.

**Global-ban interaction (decided — room sovereignty, §10):** the room fold ignores global
bans entirely; practical exile is the firewall plus a manual `MemberRemove` by surviving
room admins.

## 5. Projection & storage

- `room_members` = projection of the fold output, **with rows retained on removal**: a
  `status` column (`ACTIVE`/`REMOVED`) alongside the role (`OWNER`/`ADMIN`/`MEMBER`) —
  removal is a status, not a role (§11) — plus a `removal_node_id` column (nullable
  UUID): the defining (last honored) `MemberRemove` node per REMOVED member, the removal
  boundary for the bounded sync serve and the ping-contradiction re-push (§6 as built).
  Null on ACTIVE rows and on GLOBAL rows (no room-DAG removal exists there).
  Schema change, freely doable (no live DBs). Every *access* reader filters
  `ACTIVE` — the sprint-4 sync gate (`roomsOfPeer`), ping frontier
  snapshots, `DefaultMessagingService` fan-out, sync candidate resolution — so
  `MemberRemove` cuts sync access at the removal boundary: full serve for ACTIVE
  members, member-era history plus the removal event itself for REMOVED members (the bounded serve, §6 as built),
  generic NACK for never-members. The GUI member/flag queries read all
  rows (status is the badge/hide distinction, §3). The room fold is the sole writer of
  chat rows (including the boundary column); GLOBAL rows follow the same status semantics (`REMOVED` retained, never
  deleted) with the global projector as their sole writer. Every access reader (`roomsOfPeer` first among them) filters
  `ACTIVE`, so removal cuts sync access from
  the first `REMOVED` row — including the GLOBAL projector's own tombstone path, whose
  callers' devices may still be ACTIVE.
- Commit is a merge for the `rooms` row (name/type from genesis; preserve local-only fields if
  any appear) and a recompute for `room_members` (full recompute per commit — role +
  status + removal node from the fold's shadow state; `joined_timestamp` is recomputable or dropped from
  semantics; the commit asserts the boundary invariant, non-null exactly on REMOVED chat rows). The provisional row
  minted by
   ingest-time `ensureRoomExists` (type `RoomType.UNKNOWN`, empty name — a local-only
   provisional marker, never on the wire: the `RoomCreated` codec rejects the type, and
   `RoomService` filters `UNKNOWN` rooms from the GUI-facing room list until the
   genesis commit overwrites it) is merged via a targeted
  update — not `INSERT OR REPLACE`, which would clobber local-only columns. `space_id` is
  genesis-declared: a non-null `RoomCreated.spaceId` writes the column (deferred until the
  space row exists, like unknown-account member rows); a null one preserves any local value.
- **Projection deferral for unknown accounts** (the `room_members.account_id` FK stays):
  the fold counts unknown members normally (sovereignty + purity — account existence is
  GLOBAL sync state and must not gate fold authorization; a tombstoned account keeps its
  row, so its membership still counts and Option B's collateral cannot arrive through this
  door). Only the **commit** defers rows for accounts not yet in `accounts`; every
  GLOBAL-commit re-fold re-attempts them, so rows land as soon as identity does. Deferred
  rows are unobservable in the interim: every device-joined path (`roomsOfPeer`, fan-out
  via device resolution) needs devices, and devices imply the account row (`AddAccount` +
  `AddDevice` travel together in GLOBAL). The projection converges; no fork.
- Re-fold triggers: room insert (orphan creation included), gap closed, any
  `verificationStateChanges` in the room (reverify flips `PENDING` first — a fold
  reading a stale `PENDING` just gives no shadow effect until the next trigger
  recomputes), GLOBAL commit (lands deferred member rows), once at boot. Per-room mutex
  with `allChatRoomIds()` sweep (one room's fold never blocks another's); no genesis in
  store → skip commit (pre-genesis orphans fold only once the genesis lands). Full re-fold
  per trigger — correct and cheap at 10–20 users, same argument as `global events.md §2`.
- Verdict wiring (§3 — much smaller than the earlier re-classify plan):
    - The engine classifies every room message at ingest (`Text` exactly as today;
      `RoomEvent` = `classifyMessageAuthorship` + decode + structural + derivation
      checks). No defer branch; no new repository queries. The engine is the sole verdict
      writer for every room message, and writes both columns there: verdict = own bytes,
      flag = verdict-aware ancestry (present + same-room + complete + `VERIFIED` parents;
      cross-room prevId = missing parent → orphan+hold, and holds close same-room only —
      the hold survives the referenced id arriving in its own room). `refreshAncestryDown`
      up-cascades on gap closure; reverify up-cascades on `PENDING`→`VERIFIED` (engine mutex,
      before emit). Monotone up-only; no down-cascade needed (`PENDING` already blocks at birth).
    - The existing reverify hooks (`reverifyPendingFor` on `DeviceAdded`, boot
      `reverifyAllPending`) are unchanged and complete — they cover `RoomEvent` uniformly
      with `Text`.
    - The room projector writes zero verdicts and zero flags (flags are engine-owned in
      rooms); flags re-query the projection on fold `stateChanges`. GLOBAL is the mirror:
      the global projector writes verdicts + flags (monotone false→true), the engine never
      writes flags there (ingest inserts provisional false; `refreshAncestryDown` clears
       `is_orphaned` only). The earlier re-classify machinery (pending-by-room query,
       `refreshAncestryDown` re-classify, fold-commit re-classify) is deleted from the
       plan: membership never touches verdicts, so nothing re-classifies.
- **Read direction (binding).** The engine is the sole writer of message rows and
  exposes no reads — all lookups (GUI pages, sync, fold, diagnostics) go through
  the persistence repositories, each owning its table: `MessageRepository` for
  messages (including the render-policy queries), `CausalHoldRepository` for
  gaps, `RoomRepository` for membership.

## 6. Sync & discovery — ping threading

Problem today: `pingPayloads` carries `(roomId, tips)` with the sender discarded (`PingProvider.handlePing` →
`DefaultOrchestrator` collector → `requestFrontierSync`). A ping
advertising room `R` is the pinger's assertion that *we* are in `R` (frontiers are
`roomsOfPeer(recipient)`-filtered), so from the responder's perspective the requester is a
member and the responder gate passes — but on our side an unknown room yields
`candidateAccountsFor(R) = membersOfRoom(unknown) = ∅`, so the pending sync row exists and
retries forever without ever being sent (`pickNextDevice == null` → backoff loop).

Fix:

1. **Resolve the sender to an account in the router.** `PingProvider.handlePing` holds the
   authenticated `peerId` (post ban/unknown gate); resolve via
   `ctx.identityResolver.getAccountIdForDevice(peerId)` and emit
   `(senderAccount, roomId, tips)` on `pingPayloads` (peerId keeps flowing to
   `peerAvailabilityRegistry` separately, as today). Account granularity is correct:
   `PendingSyncRow.candidateAccounts` and `SyncRetryProcessor`'s
   `getAllPeerDevicesForAccounts` are already account-keyed.
2. **Unknown room → candidates = [senderAccount].** In `requestFrontierSync`: room known →
   existing members-minus-local path; room unknown (no `rooms` row / no members) → the ping
   sender is the candidate. Skip (and log) when the account is unresolvable — the row is not
   created; a later ping re-triggers once identity lands.
3. **Accumulate candidates across pings (extension).** Multiple devices/accounts may ping
   about the same unknown room before we hold its genesis. When a pending sync row already
   exists for `(room, target)`, append the newly seen account to its candidates (insert-if-absent; needs
   `PendingSyncRepository.addCandidateAccounts`). Redundancy speeds
   up the genesis fetch and survives one candidate being offline. Multi-device senders of one
   account dedup naturally. Known-room pings keep current behavior (sender is a member per
   our own projection, already a candidate).
4. **No responder-side exemption.** The gate reads the projection: a responder that has
   folded our `MemberAdd` serves us; fold-lag → generic NACK → convergence via
   the as-built mechanisms (§6 "As built"): the NACKed row keeps the responder
   as a candidate (append-only), the responder's next ping about the room
   re-opens it (inert-gated re-open), and an offline responder was never
   attempt-marked so its return accelerates the row straight into a re-ask (same convergence as the onboarding fold-lag
   case). Relay deposits and direct pushes are
   unaffected — discovery is push (creator sends `RoomCreated` + `MemberAdd` to the invitee),
   then normal frontier sync takes over.
5. Unrelated but adjacent TODO while touching this area: prune unsolvable pending-sync rows (`SyncRetryProcessor`
   `TODO`), which candidate accumulation makes more attractive to keep
   bounded.

### As built (sprint 4) — deltas from the plan above

The plan's core landed, plus four extensions and two decided TODOs. The unifying
rule that replaced items 2–3 above:

> **A pending sync row's candidates = members-at-mint ∪ accumulated evidence ∪
> refreshed members**, where evidence is any authenticated account that handed
> us room content or asserted room membership. All accumulation is append-only
> (`INSERT OR IGNORE`), never replace — one trigger's candidates never evict
> another's.

- **Universal ping-sender accumulation (replaces item 3's known/unknown
  split).** The ping sender is *always* a candidate for the rows its signal
  re-triggers — known rooms included. Item 3's "already a candidate" assumption
  is false exactly when it matters: at the start of an existing-room invite
  chase, our fold knows only the genesis member list, while the sender's
  membership event is part of the very history we're chasing. The ping is the
  certificate "my projection has you in this room" (the frontier filter), so
  the sender is always safe to ask; in the common case the append is a no-op.
- **Message-author candidates.** `processBecameOrphan` appends the orphan's
  `senderAccountId` to the minted rows (and to the room's existing rows, same
  universal rule). The author appended on their chainable frontier, so they
  hold the full ancestry — the one identity guaranteed to hold what the rows
  chase. Verdict-blind, like the rest of serving: a forger as candidate costs
  one NACK and nothing more. This and the ping accumulation jointly dissolve
  the invite-chase deadlock (existing room, later-added admin as adder, all
  genesis-list members lagging): the adder is both author and ping sender of
  everything the invitee first sees, so the rows are askable from the start.
- **Forwarder-as-candidate — deferred.** The `MessageEnvelope` sender (author on direct path, responder on sync path —
  never a relay) was analyzed
  and dropped: on direct/relay paths it *is* the author; in steady-state
  known-room sync the responder is already a member-candidate; in chase cases
  ping accumulation re-lands it within one ping interval, with author
  candidates content-perfect in the interim. Its unique coverage is a
  ≤ one-interval window — observe first. Revisit trigger: continuation rows
  idling on author NACK rounds during unknown-room history chases — plus the
  removed-member gap case below (a stale removed side's orphan-minted rows carry
  only the removal author as candidate; a long-unreachable author stalls its
  convergence, and the re-push's forwarder is exactly the missing third evidence
  source). If it lands,
  the shape is known and additive (resolve at the handler, `InboundMessage`
  through the pipeline, `forwarderAccountId` on the result — a third evidence
  source, nothing else moves).
- **Ping-contradiction re-open (the fold-lag recovery).** A ping about room R
  from device D is D's assertion that its projection has us in R —
  contradicting D's own gate-NACK — so the NACK is treated as stale: D is
  removed from R's *inert* rows' attempted sets (rows with no eligible device
  left). Gated on inertness deliberately: routine pings (every ~5 min, all
  shared rooms, probes and replies alike) would otherwise clear attempted
  mid-round and collapse rotation, letting the tier-preferred device hog every
  request while other candidates are never asked. The re-open is the
  evidence-gated version of the deferred blind re-ask: the ping is the proof a
  NACK is stale, and the backoff cap bounds the proof-triggered rate. No
  acceleration on re-open (the backoff schedule stays the rate governor; the
  existing accelerate-on-online composes with it naturally).
- **Membership refresh + boot sweep.** `SyncCoordinator.refreshCandidatesFor`
  re-appends the room's current ACTIVE members (minus local) to all its live
  rows — wired to the room projector's `stateChanges` (any change type; the
  boot `RoomCommitted` self-heals restarts) and to the global projector's
  `IdentityStateChange` for `RoomId.GLOBAL` only, plus an explicit boot sweep
  over `allChatRoomIds() + GLOBAL` (the global boot baseline is silent, so the
  sweep is the only cover for accounts that arrived while offline). Deferred
  member rows landing on GLOBAL-commit surface as `MemberAdded` through the
  room projector, so they ride the same path. Removals never prune candidates:
  the responder gate checks the *requester's* membership, not its own, so a
  removed member still serves.
- **Layering principle (binding).** Every device-granular pending-sync
  lifecycle op lives at the point where the authenticated device id arrives,
  all in the routing layer: mark-attempted on NACK, accelerate-on-online,
  re-open-on-ping. The orchestrator is account-level end-to-end — no device id
  crosses into it. Identity resolution (`getAccountIdForDevice`) happens in the
  handlers, following the `TypingIndicator` precedent.
- **Prune TODO — decided: backoff, never age-prune.** An all-NACK row wakes on
  `computeBackoff` (exponential, 1h cap — one cheap query per interval) and is
  pruned only structurally (stale target). Age-based pruning would not cause a
  rediscovery loop (there is no timer-based rediscovery — only new traffic
  referencing the id re-mints rows) but silent chase-loss: the orphan and its
  holds stay, nothing ever asks for them again. The blind re-ask is subsumed
  by the re-open hook above.
- **Sync-limits TODO — decided: truncate, never refuse.** The target is always
  collected first (BFS root), partial batches converge via the hold-minted
  rows (each delivered message reveals its own gaps), and re-sent known
  messages dedup on ingest — so truncation is merely wasteful, never
  incorrect. Refuse was rejected: the requester cannot know which frontier
  subset lies on the target's descent path (that is exactly the unknown), and a
  refusal NACK would wrongly mark a peer attempted that may hold the messages.
  The bound is a DoS guard (appends reference the whole frontier, so the
  covering antichain stays tiny), not a paging mechanism.

Residuals (accepted): the boot-sweep race (the sweep may read the member set
before a still-running boot fold commits offline-arrived rows — self-heals on
the next change; an airtight version would need a boot-completion signal, not
worth the surface); fold-lag NACKs from members whose fold lags ours (narrow
post-add window, self-heals via the mechanisms above — §6.4's "retry covers
it" now names them: universal accumulation, accelerate-on-return for
never-marked devices, re-open-on-ping for NACKed ones).

### As built (8.7 + removal convergence) — deltas from §8 item 7

Item 7 landed with one shape change and one scope addition (the removed-member
dead zone, found while designing the courtesy push).

- **Append path as built.** The prescribed `MessageDraft.RoomCreated` became a
  dedicated engine method instead — `DagEngine.createRoom(RoomCreatedDraft)`:
  `append(roomId, draft)` takes a roomId that is meaningless for a genesis, and
  the `createGenesis` invariant ("the caller never supplies a roomId") argues
  for the separate method. The engine mints the id, derives, `ensureRoomExists`
  for the messages FK, stores VERIFIED + ancestry-complete, and refuses a
  derived room that already holds messages (`RoomAlreadyExists` — defensive
  second-genesis guard). `MessageDraft.RoomEvent` covers the four admin ops; a
  smuggled `RoomCreated` is rejected as a programming error. The projector
  publish mirrors `DefaultGlobalEventProjector.publish` exactly — append →
  synchronous `foldAndCommit` → broadcast to the folded ACTIVE set (local
  appends bypass `pipeline.ingestResults`, so the inline fold is load-bearing,
  same reason as global). The fold's synchronous output is what makes the
  single-path fan-out work: a just-added member is already ACTIVE in the
  projection, a just-removed one already excluded. `DefaultRoomService`
  implements the ops over it (`addMember`/`removeMember`/`grantAdmin`/
  `revokeAdmin`/`leaveRoom(roomId, successor?)`) with the refusal taxonomy in
  `RoomServiceTypes.kt`; unknown-member pre-checks double as the projection-
  deferral guard for fan-out (a deferred row would miss the push). GUI exposure
  stays last, as planned.
- **Courtesy pushes (discovery + removal notice).** Two targeted sends outside
  the common fan-out, both unconditional and dedup-safe: `MemberAdd` pushes the
  stored genesis node to the target (they were never in the genesis list and
  hold nothing — a head start, not completeness; the middle history still
  rides frontier sync); `MemberRemove` pushes the removal node to the target (the fold just excluded them from the
  ACTIVE fan-out, so without this they
  would never hear at all). `createRoom` needs no courtesy leg — the common
  broadcast to the genesis member list covers it.
- **The removed-member dead zone (found, then closed).** A removed member's
  device gets no push (out of the fan-out), no ping (members filter it out of
  advertised frontiers), and no pull (gate NACK) — a stale device diverges
  silently, including our own devices after a self-leave. The ping frontier
  filter is recipient-based (`roomsOfPeer(recipient)`), so the stale device
  keeps *sending* pings about the room to current members even though it never *receives* any — that outbound
  advertisement is the beacon. Two changes use it:
- **Shared-room ping filter.** `latestRoomFrontiers` advertises a room iff *both* sides are ACTIVE members
  (`roomsOfPeer(recipient)` ∩ own rooms).
  Independently the right semantics for a frontier advertisement; and it makes
  the re-push below self-extinguishing — a converged device stops advertising
  the room. GLOBAL unaffected (the local account is ACTIVE there).
- **Ping-contradiction re-push (the mirror of the re-open hook).** A ping
  advertising R from a device whose account is REMOVED in R per our fold is
  D's assertion that its projection still tracks R as a live shared room —
  contradicting our fold — so the removal node is re-delivered to that device (`RemovalRePusher`, routing layer per the
  layering principle, device-
  granular via `OutboundMessenger.sendMessageToPeer`, per- (device, room) 1h
  backoff, dedup-safe, store-and-forward). The loop converges: re-push →
  the node orphans at a behind target → sync rows mint with the removal author (an ACTIVE member) as candidate → the
  bounded serve below fills the gap →
  the node chains → the target's fold flips its own row → it drops the room
  from its advertisements → re-pushes stop. Self-leave is symmetric with zero
  extra machinery (our stale device pings our converged device; the gate
  serves our own account the closure of our own self-removal). Re-add needs no
  special case either: status flips ACTIVE, the contradiction stops firing,
  and the device converges as an ordinary lagging member.
- **Bounded serve (removal cuts at the boundary, not at the door).** The gate
  is now a trichotomy: ACTIVE → full serve as today; REMOVED row → the
  ancestor closure of the requester's latest removal node (member-era history
  plus the removal event itself — everything they legitimately held, nothing
  after); no row → generic NACK, unchanged. This is *required*, not optional:
  the courtesy node orphans at a behind target, and the gap between its
  frontier and the removal node's parents must be servable or the node sits
  orphaned forever. No leak (never-member stays NACK; the removal target
  learning of its own removal reveals nothing to strangers), and §5's "cuts
  sync access" narrows accordingly. The REMOVED branch walks from the removal
  node itself with `knownIds` pruning — the bound is structural (the walk cannot
  leave the member-era closure), so no bound set is materialized at all; the
  ACTIVE branch keeps the missing-targets walk. Shared facts stay in the shared
  layer: the engine exposes no reads, so routing reads the repositories, never
  the orchestrator (same reason the boundary id lives in the projection column
  rather than behind a provider interface, which would also have created a
  projector↔router construction cycle).

Residuals (accepted): gap-row candidates are author-only — the re-pushing
member cannot mint the stale side's rows, so a long-unreachable removal author
stalls convergence (the forwarder-as-candidate revisit trigger above, now
concrete); true partitions (a device that never again exchanges a ping with
any room member) stay stale — same class as any partition residual, self-heals
on first contact; multiple folded members may re-push simultaneously (dedup
absorbs it, backoff bounds the drip).

## 7. The unsolicited-messages check — dropped

Recap (so it is not re-derived): the forwarder/solicitation check proposed accepting
non-author-forwarded payloads only when they matched an outstanding request (pending sync
row / causal hold / ping-learned tip), with direct-author traffic exempt. It was a
transport-level *proxy* for the missing payload-level authorization. With membership in the
fold projection, the verdict is a function of the payload and the GLOBAL projection, and the
membership flags of the fold's output — the forwarder is irrelevant,
and every threat it targeted is closed (§1 table → §3). A hard block is also
forbidden: store-don't-drop (§6 of `global events.md`) means unsolicited messages must be
stored and judged, never refused, and relay deposits are unsolicited by definition.

**Survivor (post-PoC):** a provenance bit (solicited vs. unsolicited) plumbed from the
inbound handler, as policy metadata for rate limiting, GUI "unexpected message" surfacing,
and incident forensics. Never a drop condition.

## 8. Build order & work items

Order is just-in-time: codec and ingest land first (no fold dependencies); the
global-fold parts are extracted when the fold work reaches for them — never copied —
each extraction its own commit with the equivalence proof attached. Prep (landed before
8.4): engine verdict-aware flag in rooms + provisional-false GLOBAL ingest, GLOBAL fold
reachable + inline eligibility with projector-owned flag promotion, sync trichotomy
comment. No extraction in prep — global first, `fold/graph/` stays deferred:

1. **Codec**: `RoomEventPayload` (5 kinds; `MemberRemove` carries the optional successor),
   `MessagePayload.RoomEvent` variant, `MessagePayloadType.ROOM_EVENT(3)`,
   `roomIdFromGenesis` derivation + engine-side genesis assertion (§3).
2. **Ingest substrate**: `ensureRoomExists` on ingest; engine-side classification of
   `RoomEvent` payloads (decode / structural genesis rule / derivation + the existing
   `classifyMessageAuthorship` path, §3); `RoomMemberRole.OWNER` + the
   `room_members.status` schema change (§5). Provisional rows use `RoomType.UNKNOWN`
   (§5); `removeMember` stays as the GLOBAL-tier op (the global projector owns GLOBAL
   rows — chat rows flip status via the room projector in step 4, never DELETE).
3. **Room fold core (fresh)**: accounts-only shadow state (role/status + owner slot +
   ever-member set), seal functions per the global doctrine (simpler — §4), the fixpoint
   loop, fold-set via the stored verdict-aware flag (no poison recompute — §3/§4), no
   crypto oracles (the verdict column is the input). Dynamics fuzzer: room world generator
   + oracle, no crypto stub (§4). **Extract-on-demand (deferred past prep)**:
   when the fold or projector reaches for a global-fold part —
   the graph helpers (`canonicalOrder`, `childAdjacency`, `ancestorClosures`,
   `reachableFrom`), the fixpoint driver if the room restart loop matches the global
   shape — extract it at that moment, as its own mechanical commit, proven by the global
   dynamics fuzzer (20k seeds) + projector tests run **unchanged**. Never copy instead
   of extracting: the drift risk the sibling-folds decision accepted (§4) is bounded by
   exactly this discipline. Shared boundary (decided in prep): `foldInputSet` diverges —
   room = stored-flag filter, global = inline eligibility — and is never shared; only the
   pure graph helpers move.
4. **Room projector**: fold source `findAllInRoom(roomId)` per room (pure function of the
   stored set; adapter feeds the filtered order — `VERIFIED` ∧ flag, all payload
   types, §4); commit the
   `rooms` merge + the `room_members` recompute (status + roles; deferral for unknown
   accounts, §5); `stateChanges` flow; per-room mutex over `allChatRoomIds()` with re-fold
   triggers (§5); no genesis → skip commit; zero verdict/flag writes; global-ban interaction
   per the §10 decision.
5. **Flags & GUI wiring**: the display policy lives in the render queries
    (`selectRenderablePageDesc` / `selectIsRenderable` in `Message.sq`) — the
    earlier `messageDisplayPolicy` function is deleted; the window pages,
    `roomPreview`, and the live ingest check all read renderable rows only, and
    `RoomService`'s status read serves the member list + removal banner (never
    message visibility). `MessagingService.sendTextMessage` refuses with
    `NOT_A_MEMBER` when the local row is `REMOVED` (no row keeps the previous
    behavior); typing indicators from non-`ACTIVE` senders are ignored inbound
    (outbound already fans out `ACTIVE`-only); membership-reader queries filter
    `ACTIVE`; negative tests for the hide-policy (done-criteria d3); reverify-hook
    regression (unchanged behavior).
6. **Ping threading** (§6 as built): flow type change `(senderAccount, roomFrontiers)` through
   `Router.pingPayloads` / `PingProvider` / `DefaultOrchestrator` collector /
   `SyncCoordinator.requestFrontierSync` (nullable sender; onboarding passes
   none); unknown-room candidates + skip-and-log for unresolvable senders;
   universal ping-sender accumulation; message-author candidates;
   `addCandidateAccounts` / `appendCandidateAccountsForRoom` /
   `reopenAttemptedPeerForRoom` on `PendingSyncRepository` (the last with the
   inertness gate); `refreshCandidatesFor` + projector collectors + boot sweep;
   exponential backoff on the null-device path (the prune TODO, decided).
7. **Append path** — landed; see §6 as built (8.7 + removal convergence). Deltas from
   the plan as written here: the prescribed `MessageDraft.RoomCreated` became
   `DagEngine.createRoom(RoomCreatedDraft)` (dedicated engine method — the roomId
   parameter of `append` is meaningless for a genesis); plus the courtesy pushes,
   the bounded serve, the re-push hook and the shared-room ping filter, which
   were found while designing the removal notice. `MemberAdd`/`MemberRemove`/
   `AddAdmin`/`RemoveAdmin` appends via the
   room-event projector's publish path (mirroring `DefaultGlobalEventProjector.publish`);
   `RoomService` membership ops (`addMember`/`removeMember`/`grantAdmin`/`revokeAdmin`/
   `leaveRoom`), outcomes + refusals in `RoomServiceTypes.kt` mirroring `GlobalEventOutcome`.
   GUI exposure last (fold supports admins from day one; the GUI toggles come after
   membership + messaging work end-to-end).
8. **Tests** (§9) — including the sprint-4 negative-test discipline (done-criteria d3).

## 9. Test matrix

Ported from `global events.md §9` (verdict expectations follow the authenticity-only
discipline) plus room-specific cases:

- non-admin `MemberAdd` → ignored (stored `VERIFIED`, no effect, no projection row);
- backdated `MemberAdd` by demoted admin → sealed out; the added account never gets a
  `room_members` row; its messages render "from non-member" (the collateral case,
  explicitly tested);
- mutual destruction: removed member's backdated counter-`RemoveAdmin`/counter-
  `MemberRemove` against the removing admin → forger stays removed, the honest admin
  also loses admin/membership (repairable via the owner); forged removal/demotion
  targeting the owner → ignored, no collateral;
- owner handover: owner self-leave with a member successor → atomic transfer (successor
  admin + irrevocable; leaver removed + sealed); owner self-leave without a successor →
  ignored (owner stays); successor not a member or is the leaver → ignored; non-owner
  `MemberRemove` carrying a successor → ignored whole; two competing handovers → first in
  canonical order wins; re-added member starts `MEMBER` (no admin resurrection);
- concurrent `AddAdmin`/`RemoveAdmin` siblings converge identically on all nodes;
- backdated counter-demotion → both demoted (mutual destruction); demote → re-grant →
  post-grant acts valid;
- engine classification at ingest: undecodable `RoomEvent` bytes → `REJECTED`;
  non-`RoomCreated` with `prevIds == []` → `REJECTED`, no gap machinery; `roomId`
  derivation mismatch on `RoomCreated` → `REJECTED`; unknown author → `PENDING` →
  `DeviceAdded` reverify flips (regression: `RoomEvent` covered uniformly with `Text`);
  `REJECTED` never flips;
- unknown-room message → verdict immediate (authenticity); the orphan machinery gates
  chainability; genesis arrives → fold runs → flags resolve member / non-member — both
  branches; the projector never writes a verdict (regression: no reverify ping-pong);
- verdict-aware flag: child of a `REJECTED` node is born incomplete and never promotes
  (freeze-DoS: next admin event still lands on the remaining frontier); child of a
  `PENDING` (unknown-author) node is born incomplete, promotes on `DeviceAdded` reverify
  up-cascade; cross-room prevId → orphan+hold, surviving the referenced id arriving in
  its own room; `refreshAncestryDown` clears orphan flags but never flags in GLOBAL;
  GLOBAL remote ingest lands flag false → fold promotes monotone false→true; reachable
  graft on a `PENDING` root stays `PENDING`, never frontier;
- removed member: in-closure messages → `VERIFIED` + renders (member-era history);
  out-of-closure messages (post-removal, backdated, concurrent, orphan) → `VERIFIED` +
  stored + chainable + served, but never returned by any render path (page, preview,
  live window insert, notification event — each path its own negative test);
  removed-author orphan stays hidden after chaining (the closure is frozen at the
  removal node) and renders on re-add (row `ACTIVE` again — nothing lost); member
  replies descending from hidden messages render normally (no collateral);
  never-member author → `VERIFIED` + no row → hidden by default (negative test:
  never rendered as normal);
- local send refusal + typing: REMOVED local row → `sendTextMessage` refuses
  `NOT_A_MEMBER` before any write (nothing appended, no fan-out); no row keeps
  the previous behavior; typing indicators from removed or never-member senders
  are ignored inbound (outbound already fans out `ACTIVE`-only);
- projection: removed members keep `REMOVED` rows (badge source) carrying the defining
  removal node (the removal boundary — re-add clears it, a second removal overwrites it);
  access readers (`roomsOfPeer`, fan-out) filter
  `ACTIVE` — `MemberRemove` cuts sync at the boundary, not at the door: removed account's
  sync request → member-era history plus the removal event itself, nothing after (no oracle for never-members — still
  generic NACK); re-add → `ACTIVE` again;
- deferred rows: room folded with an unknown-account member → no `room_members` row;
  `AccountAdded` → re-fold → row lands; `roomsOfPeer` never matches an account before its
  devices exist;
- ghost room from a globally banned account: stored, folds, no crash, no effect on existing
  rooms;
- global ban of a room admin: sovereignty (§10) — room fold unaffected, the admin keeps
  room authority (banned-device resolution intact — `resolvePeerIdentityRecord` has no
  status check; the "from removed device" flag reads global `devices` status);
- ping with unknown room → sync row created with the pinger's account as candidate; second
  ping from a different account about the same room → candidate appended, deduped;
  unresolvable account → no row, retried on next ping;
  known-room ping sender appended to re-triggered rows (universal accumulation);
  orphan's author appended to minted rows; refresh appends later-added members
  while preserving ping-sender/author candidates (append-only negative test:
  unknown-room row keeps ping-sender candidates after the genesis lands);
  restart case → boot sweep refreshes without any `MemberAdded` event; GLOBAL
  row + `AccountAdded` → refreshed; ping from a NACKed device re-opens it on
  inert rows only (mid-round ping re-opens nothing — rotation preserved;
  re-open is room- and device-scoped); all-NACK row backs off exponentially (30s, 60s, …, 1h cap);
  ping advertising a room the sender's account was removed from → removal node
  re-pushed to that device (backoff-bounded, dedup-safe; silent once converged);
  shared-room ping filter: a converged removed member advertises nothing about the
  room (no rows minted from its frontiers, no re-push triggered); stale devices
  converge independently — each device's own pings trigger its own re-push,
  including our own devices after a self-leave;
- append path: `createRoom` publishes the genesis (derived id, creator OWNER,
  broadcast to the genesis list); `MemberAdd` broadcasts plus the genesis courtesy
  to the target (unconditional — re-add dedups); `MemberRemove` broadcasts plus
  the removal courtesy to the target; non-admin op → local refusal, and
  published-and-ignored (stored `VERIFIED`, no shadow effect, no row — negative
  test); owner handover via `leaveRoom(successor)` end-to-end (atomic OWNER
  transfer; bad shapes refused); engine: genesis into a room that holds messages
  refused, `RoomCreated` via `append` rejected as a programming error;
- onboarding unaffected: GLOBAL exempt from the room fold; sponsor fold-lag → NACK → retry
  converges (regression from the sprint-4 analysis).

## 10. Open items

- **Residual (accepted): `PENDING`-author window.** Garbage authored by an unknown device is
  provably garbage only after identity lands (reverify flips it `REJECTED`, children stay
  incomplete). Bounded and pre-existing in GLOBAL; rooms inherit the same window via the
  engine flag (child born incomplete, never promotes off a `REJECTED` parent).
- **Global-ban interaction with room authority — decided: Option A, room sovereignty.** The
  room fold ignores global bans entirely; practical exile is the firewall plus a manual
  `MemberRemove` by surviving room admins. No collateral ("don't cut off the branch" holds; a
  banned owner's room keeps functioning). Option B (cross-DAG cut) rejected: voiding a
  tombstoned author's room events voids their *pre-ban* events too (no positional cross-DAG
  check exists), retroactively stripping a banned room admin's grants and potentially leaving
  the room adminless — structural collateral for a manual-cleanup cost. If that cost ever
  bites, the future is joining the DAGs — an anchoring semantics (room events carrying global
  tips or vice versa) creating the shared partial order positional checks need — never a
  live-table check; deliberately undesigned (it couples room verification to global ancestry
  completeness, re-opening the late-sync wounds §2's rules exist to avoid).
- `RoomMemberRole.ADMIN`/`OWNER` exposure in the GUI (fold supports both from the
  start; UI last).
- Servers / room-grouping tier (multiple rooms under server admins) — deliberately
  undesigned; the account-principal fold generalizes, but nothing here should pre-shape it.
  One reservation: `RoomCreated` already carries an optional `spaceId` (nullable UUID,
  creator-declared at genesis, immutable — a room never moves between spaces). The room
  fold never validates or consumes it; it only feeds the `rooms.space_id` merge (§5).
  Null until spaces exist.
- E2EE sender keys (sprint 5) consume the same fold-derived member list — sequence the fold
  before 5b key rotation work.
- Provenance bit (§7) and unsolicited-traffic rate limiting — post-PoC.
- Prune unsolvable pending-sync rows (`SyncRetryProcessor` TODO) — **decided in
  sprint 4, see §6 "As built"**: backoff, never age-prune; the blind re-ask is
  subsumed by the ping-contradiction re-open. Candidate accumulation (now
  universal) and the membership refresh keep rows live instead.
- Old-node decode behavior for `ROOM_EVENT` (NACK `DECODE_FAILED`, not stored) — fine
  pre-fleet; revisit if any deployment exists before the codec ships.

## 11. Alternatives considered (and rejected)

- **Creator-only RBAC** (no admin grants): rejected — the server/multi-admin UX goal needs
  grant-derived admins, and grant-derived authority is exactly when the seal machinery
  becomes necessary; better to copy the developed, fuzzed logic than to invent a simpler one
  and re-derive seals later. Retained as the *degradation* if the fold copy slips a sprint:
  creator-only is a strict subset.
- **Live-table membership checks** (author ∈ `membersOfRoom` at ingest against a
  hand-maintained table): rejected — membership views diverge by sync timing, so hard
  verdicts fork the mesh permanently; and `REJECTED`-never-flips makes the fork
  unrepairable. Superseded by fold-derived membership where "member at position" is a pure
  function of the stored set.
- **`REMOVED` as a `RoomMemberRole` value**: rejected — removal is a status, not a role (same reason `devices.status` is
  not a `DeviceType`); the role column gains `OWNER`
  instead (§2). The earlier rejection of the `room_members.status` *column* is itself
  superseded: the column is now the chosen projection shape (§5) — the fold remains the
  sole writer, and the column is the render cache of the coarse member-at-some-point
  distinction (§3). What stays rejected is the column as the fold's *source* of
  membership truth (that was the live-table check, above).
- **Forwarder-membership check** (envelope author must be in the room): rejected — relays are
  legitimately non-members and sync forwarders are members only from *their* projection's
  view; breaks offline delivery, adds nothing once the author check is fold-derived.
- **Solicitation hard block** (drop messages we did not ask for): rejected — violates
  store-don't-drop (forks the mesh, re-creates neverending sync loops) and breaks relay
  deposits, which are unsolicited by definition. Superseded by payload-level verdicts; policy
  metadata only (§7).
- **Creator-ACTIVE gate on `RoomCreated`**: rejected — positional validity cannot cross DAGs (no shared ordering), and a
  live check permanently kills genuine pre-ban rooms synced late.
  Residual ghost-room minting accepted and bounded (§2).
- **Out-of-band membership seeding for the PoC GUI** (no protocol change, GUI writes
  `room_members` rows): viable ~20-line stopgap, rejected for the pre-GUI milestone only
  because the user-facing flows (create room, invite, kick) need `MemberAdd`/`MemberRemove`
  to function, and seeding would be thrown away. Keep as the emergency fallback if the fold
  slips past the GUI milestone.
- **Verbatim fold copy** (the original §4 plan): superseded — first by the `SealEngine`
  kernel, then by sibling folds (§4). The copy's real cost — seal-algebra fixes applied
  twice by hand — is answered by the paired fuzzers (both re-run on any seal fix) and
  the shared doctrine; residual drift risk is accepted, with the rule of three as the
  extraction trigger.
- **Shared `SealEngine<P>` kernel** (the second §4 plan): superseded by sibling folds —
  the authenticity-only verdict decision removed the shared components (crypto oracles,
  verdict skeleton/output, device dimension, author cuts), leaving too little common core
  to justify callback-based abstraction plus a mid-sprint refactor of the landed global
  fold. Revisit at the third room-shaped tier (§10).
- **Per-message positional membership in the verdict** (`membersAt` at position):
  superseded — first by the coarse flags + status column, then by the removal-closure
  boundary (§3): the boundary is not a per-message fold replay but one recursive CTE
  over stored edges, evaluated at render time inside the queries. It closes the
  backdated window for display (backdated forgeries sit outside the closure) at the
  accepted cost of the concurrent legit window (reversible on re-add,
  verdict-neutral). What stays rejected is positional precision *in the verdict* —
  verdicts remain authenticity-only, and membership never touches them.
- **Separate `OwnerHandover` event kind**: rejected — two events leave a zero- or two-owner
  window between them and an ordering ambiguity when both land; the successor field on the
  owner's self-leave is one atomic, deterministic transition.
- **Verdict-aware completeness via stored `REJECTED`-only**: superseded by
  all-ancestors-`VERIFIED`. Checking only "no `REJECTED` ancestor" would promote children of
  `PENDING` (unresolvable-author, unreachable, gap-parked) nodes into the frontier —
  re-opening the freeze-DoS and the `PENDING`-window. The flag requires every ancestor
  `VERIFIED`, not merely non-`REJECTED`.
