# Room Events & Membership Fold — design decisions

This doc compiles the agreed design for chat-room membership as a room-DAG concern: the event
codec, the room fold, message validity semantics, the membership projection, and the
ping-threading fix for unknown-room sync. It extends the machinery of
[`global events.md`](global%20events.md) (canonical order, shadow state, seals, mutual
destruction, authenticity-only verdicts) to a second tier, and records why several simpler
designs were rejected. Written so implementation can resume without re-deriving the reasoning.

The sprint-4 landing this builds on: the responder-side sync gate (`DefaultSyncPayloadProvider`: serve only rooms in
`roomsOfPeer(requester)`, generic NACK on
denial) is implemented and tested; everything else here is not yet implemented.

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

Membership is **render-time policy over the projection** — the coarse distinction is
member-at-some-point vs never-member, not per-message positional replay (superseded, §11):

- A removed member's backdated forgery is indistinguishable from a legitimate pre-removal
  message by design (the §2 doctrine), so positional precision buys only the
  provably-post-removal subset — badge-worthy, not worth per-message fold replays or
  stored snapshots. The GUI reads the DB only: a join of the message author's account
  against `room_members` — `ACTIVE` row → normal; `REMOVED` row → "from removed member"
  badge (all their messages, any position); no row → "from non-member", hidden by default (the stranger-injection case —
  no provenance at all; content is E2EE-opaque anyway, so
  the flag is defense-in-depth, not a confidentiality boundary); room not folded yet →
  no rows → hidden until the fold's `stateChanges` re-render. Negative test (done-criteria
  d3): flagged content is never rendered as normal — the page query's `!= 'REJECTED'`
  filter includes flagged content, so the hide is render-layer, not SQL.
- Sealed-out adds never count → no row ever → hidden (the §4 collateral case intact).
- Re-add after removal → row back to `ACTIVE` (full recompute per fold commit); the
  projection and the flags can never disagree (same source).
- Chainability: an authentic non-member message is chainable (frontier = `VERIFIED` ∧
  `ancestry_complete`). Harmless: stored regardless (store-don't-drop), being a parent
  launders nothing, and the covering antichain self-collapses — the next member append
  references all tips including forger ones. Keeping forger tips out of the frontier is
  the one thing membership-in-the-verdict would buy; not worth the verdict-flip machinery (§11).
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
- Poisoning is fold-internal: a row whose ancestry includes a `REJECTED` row gets no
  shadow effect (never membership evidence — same rule as `replayFold`); the *stored*
  verdict is untouched (authenticity-only), so each descendant is judged on its own
  merits — a member's reply to garbage is a normal message; the garbage hides by flag.
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

- **Graph helpers** — `canonicalOrder`, `childAdjacency`, `ancestorClosures`,
  `foldInputSet`: pure functions over stored rows with zero tier logic; both projectors
  call them. Equivalence is still proven by the global dynamics fuzzer + projector tests
  run unchanged.
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
senderAccountId` — trusted via §2 — `, decoded event, position)`; the fold set =
`VERIFIED` `RoomEvent` rows, reachable from the genesis, ancestry-complete, no `REJECTED`
ancestor; the output = shadow-state sets only (members with role/status, the owner slot,
the ever-validly-member set) — no verdict map is written (§3).

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
  removal is a status, not a role (§11). Schema change, freely doable (no live DBs). Every *access* reader filters
  `ACTIVE` — the sprint-4 sync gate (`roomsOfPeer`), ping frontier
  snapshots, `DefaultMessagingService` fan-out, sync candidate resolution — so
  `MemberRemove` still cuts sync access for free. The GUI member/flag queries read all
  rows (status is the badge/hide distinction, §3). The room fold is the sole writer of
  chat rows; GLOBAL rows follow the same status semantics (`REMOVED` retained, never
  deleted) with the global projector as their sole writer. Every access reader (`roomsOfPeer` first among them) filters
  `ACTIVE`, so removal cuts sync access from
  the first `REMOVED` row — including the GLOBAL projector's own tombstone path, whose
  callers' devices may still be ACTIVE.
- Commit is a merge for the `rooms` row (name/type from genesis; preserve local-only fields if
  any appear) and a recompute for `room_members` (full recompute per commit — role +
  status from the fold's shadow state; `joined_timestamp` is recomputable or dropped from
  semantics). The provisional row minted by
  ingest-time `ensureRoomExists` (type `RoomType.UNKNOWN`, empty name — a local-only
  provisional marker, never on the wire: the `RoomCreated` codec rejects it, and the GUI
  filters `UNKNOWN` rooms until the genesis commit overwrites it) is merged via a targeted
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
- Re-fold triggers: room insert (orphan creation included), gap closed, GLOBAL commit (lands deferred member rows; the
  reverify hooks flip stale `PENDING`s first — a fold
  reading a stale `PENDING` just gives no shadow effect until the next trigger
  recomputes), once at boot. Full re-fold per trigger — correct and cheap at
  10–20 users, same argument as `global events.md §2`.
- Verdict wiring (§3 — much smaller than the earlier re-classify plan):
    - The engine classifies every room message at ingest (`Text` exactly as today;
      `RoomEvent` = `classifyMessageAuthorship` + decode + structural + derivation
      checks). No defer branch; no new repository queries.
    - The existing reverify hooks (`reverifyPendingFor` on `DeviceAdded`, boot
      `reverifyAllPending`) are unchanged and complete — they cover `RoomEvent` uniformly
      with `Text`.
    - The room projector writes zero verdicts; flags re-query the projection on fold
      `stateChanges`. The earlier re-classify machinery (pending-by-room query,
      `refreshAncestryDown` re-classify, fold-commit re-classify) is deleted from the
      plan: membership never touches verdicts, so nothing re-classifies.

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
   folded our `MemberAdd` serves us; fold-lag → generic NACK → requester retry covers it (same convergence as the
   onboarding fold-lag case). Relay deposits and direct pushes are
   unaffected — discovery is push (creator sends `RoomCreated` + `MemberAdd` to the invitee),
   then normal frontier sync takes over.
5. Unrelated but adjacent TODO while touching this area: prune unsolvable pending-sync rows (`SyncRetryProcessor`
   `TODO`), which candidate accumulation makes more attractive to keep
   bounded.

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
each extraction its own commit with the equivalence proof attached:

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
   loop, fold-set/poison exclusion, no crypto oracles (the verdict
   column is the input). Dynamics fuzzer: room world generator + oracle, no crypto stub (§4). **Extract-on-demand**:
   when the fold or projector reaches for a global-fold part —
   the graph helpers (`canonicalOrder`, `childAdjacency`, `ancestorClosures`,
   `foldInputSet`), the fixpoint driver if the room restart loop matches the global
   shape — extract it at that moment, as its own mechanical commit, proven by the global
   dynamics fuzzer (20k seeds) + projector tests run **unchanged**. Never copy instead
   of extracting: the drift risk the sibling-folds decision accepted (§4) is bounded by
   exactly this discipline. Implementation also reveals the true shared boundary (`foldInputSet`'s genesis input is a
   parameter — most-descendants resolution is
   global-only, the room's is the derivation assert; the room `RevocationState` may not
   match the driver's global shape — extraction then is a decision, not an assumption).
4. **Room projector**: fold source `findAllInRoom(roomId)` per room (all messages, no
   filtering — pure function of the stored set); commit the `rooms` merge + the
   `room_members` recompute (status + roles; deferral for unknown accounts, §5);
   `stateChanges` flow; re-fold triggers (§5); zero verdict writes; global-ban interaction
   per the §10 decision.
5. **Flags & GUI wiring**: the message-join against `room_members` (status → badge/hide,
   §3); membership-reader queries filter `ACTIVE`; negative tests for the hide-policy (done-criteria d3); reverify-hook
   regression (unchanged behavior).
6. **Ping threading** (§6): flow type change `(accountId, roomId, tips)` through
   `Router.pingPayloads` / `PingProvider` / `DefaultOrchestrator` collector /
   `SyncCoordinator.requestFrontierSync`; `addCandidateAccounts` on `PendingSyncRepository`.
7. **Append path**: `MessageDraft.RoomCreated` (engine derives `roomId` from the minted
   genesis `messageId`), `MemberAdd`/`MemberRemove`/`AddAdmin`/`RemoveAdmin` appends via the
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
- removed member: post-removal and *backdated-as-pre-removal* messages → `VERIFIED` +
  `REMOVED`-row badge (indistinguishable-by-design, tested as such); never-member author
  → `VERIFIED` + no row → hidden by default (negative test: never rendered as normal);
- projection: removed members keep `REMOVED` rows (badge source); access readers (`roomsOfPeer`, fan-out) filter
  `ACTIVE` — `MemberRemove` cuts sync: removed account's
  sync request → generic NACK, no oracle; re-add → `ACTIVE` again;
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
- onboarding unaffected: GLOBAL exempt from the room fold; sponsor fold-lag → NACK → retry
  converges (regression from the sprint-4 analysis).

## 10. Open items

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
- Prune unsolvable pending-sync rows (`SyncRetryProcessor` TODO) — more attractive once
  candidate accumulation lands.
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
  superseded by the coarse flags + status column (§3) — positional precision buys only
  the provably-post-removal subset (backdated forgeries are indistinguishable from legit
  pre-removal messages by design), at the cost of per-message fold replays or stored
  snapshots; the GUI reads the DB only.
- **Separate `OwnerHandover` event kind**: rejected — two events leave a zero- or two-owner
  window between them and an ordering ambiguity when both land; the successor field on the
  owner's self-leave is one atomic, deterministic transition.
