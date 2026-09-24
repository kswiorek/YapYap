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
authorization on the *author*, derived from the room DAG itself.

## 2. Core model

- **Room membership lives in the room DAG**, not in live tables. `room_members` becomes a
  materialized projection of the room event log (exactly as `accounts`/`devices` are
  projections of GLOBAL). A room fold is the sole writer of chain-derived membership.
- **Principals are accounts only.** Membership, roles, and authorization targets are
  account-level. Authorship verification stays device-level (payload signature + account
  binding via `classifyMessageAuthorship`); device misbehavior (bans, rotation) is the global
  tier's job and is invisible to the room fold.
- **`roomId` is self-certifying**: derived from the genesis node
  (`roomId = uuidFromHash(SHA-256("YapYapRoomIdV1" || genesisMessageId))`, mirroring
  `peerIdFromPublicKey`). A forged genesis derives a *different* room id — hijacking an
  existing room is structurally impossible, and genesis competition (the global tier's
  most-descendants root selection) cannot occur. The fold asserts the derivation (`derivationOk`-style), like the global
  fold does for self-certifying ids.
- **Room fold = pure function of (room message set + GLOBAL projection for authenticity
  only).** Signature keys, device↔account binding, and key liveness resolve from the live
  tables (chain-derived, permanent crypto facts — safe, same as chat verification today). **Authorization is purely
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

| Event          | Carries                              | Authorization (fold state at position)                                                         |
|----------------|--------------------------------------|------------------------------------------------------------------------------------------------|
| `RoomCreated`  | member account list, room name, type | Genesis (`prevIds == []`); valid signature + account exists (member-level — §2 no-status-gate) |
| `MemberAdd`    | target account id                    | Author's account `is_admin` at position (seal-subject)                                         |
| `MemberRemove` | target account id                    | Admin-gated for others; own-account leave always allowed                                       |
| `AddAdmin`     | target account id                    | Admin-gated (seal-subject)                                                                     |
| `RemoveAdmin`  | target account id                    | Admin-gated (seal-subject); room genesis admin irrevocable                                     |

Codec style mirrors `SystemPayload`/`GlobalEventPayload`: sealed interface, kind byte,
encode/decode per type, carried inside a new `MessagePayload.RoomEvent` variant (`MessagePayloadType.ROOM_EVENT(3)`).
Protocol note: old nodes fail decode on the new payload
type and NACK `DECODE_FAILED` without storing — acceptable pre-fleet; bump nothing, document it.

## 3. Message validity — ancestry-scoped positional membership

A chat message is valid iff its author was a member **at the message's position in the room
DAG**:

```
membersAt(msg) = genesisMembers ∪ {MemberAdd targets in ancestors(msg)}
                 − {MemberRemove targets in ancestors(msg)}   (voided events excluded, §4)
```

- `DagEngine.append` references the covering antichain, so any message authored after a
  `MemberAdd` necessarily has it as a transitive ancestor. "Membership grant in ancestry" ≡
  "member at append time" — free positional semantics, the room-tier analogue of the global
  fold's validity-at-position, with no scalar clock.
- **Ancestry is final once complete.** It only grows until it closes, so a verdict derived
  from it can never be invalidated by later arrivals — `REJECTED`-never-flips is safe here (this is what distinguishes
  it from a live-table membership check, which forks the mesh on
  sync-timing races).
- **Backdating tolerance is the doctrine, not a gap**: a removed member's backdated message is
  cryptographically indistinguishable from a legitimate pre-removal message that syncs late,
  and the latter *must* verify. Content exercises no authority, so there is nothing to seal (this is why rooms skip the
  global tier's revocation-seal machinery for chat messages —
  seals exist there because revoked principals smuggle *authority*; here they can only smuggle
  content, which the removal flag surfaces).

### Verdict table (`classifyVerification` + ancestry trigger)

| Crypto         | Ancestry                         | Membership at position                                 | Verdict                                     |
|----------------|----------------------------------|--------------------------------------------------------|---------------------------------------------|
| INVALID        | —                                | —                                                      | `REJECTED`                                  |
| UNKNOWN_AUTHOR | —                                | —                                                      | `PENDING` (reverify on `DeviceAdded`)       |
| VALID          | incomplete (orphan/unknown room) | —                                                      | `PENDING` (re-classify on gap closure)      |
| VALID          | complete                         | member                                                 | `VERIFIED`                                  |
| VALID          | complete                         | absent                                                 | `REJECTED`                                  |
| VALID          | complete                         | removed-before-position (valid earlier, removed later) | `VERIFIED` + GUI "from removed member" flag |

Orphan lifecycle (the motivating scenario): a message from a room we do not have arrives →
orphan by definition (genesis missing) → `PENDING` (membership unknown). Genesis arrives →
ancestry completes → re-classify against `membersAt` → `VERIFIED` or `REJECTED`. Same trigger
and same code path as the non-static future.

Structural rules:

- **Only `RoomCreated` may have `prevIds == []` in a chat room.** A non-`RoomCreated`
  empty-`prevIds` payload is a forged genesis attempt → fail closed (`REJECTED`, poisoned
  descendants), never the gap machinery (it would otherwise not be an orphan).
- **`ensureRoomExists` on ingest** (idempotent `INSERT OR IGNORE`) — unblocks the
  `messages.room_id` FK for pre-genesis orphans. Today only GLOBAL is seeded.
- Poisoning: descendants of `REJECTED` nodes stay `PENDING`, never fold into membership
  evidence (same rule as `replayFold`).
- GUI flags are render-time policy over fold output, coexisting with `VERIFIED` storage:
  "from removed member" (member at position, not member now — both derivable from the fold
  timeline) and "from removed device" (global `devices` status).

## 4. The fold — copied from global, minus the device tier

The global fold's complexity is concentrated in revocation-of- *authority* vs backdating:
`foldToFixpoint`, `mutuallyRevoked`, `sealed`, `effectiveAdmin`, oscillation ranking. Rooms
need exactly that machinery (admins are grant-derived) with a smaller principal model:

| Global tier                                                                          | Room tier                                                                                                                      |
|--------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| Device bans (full-scope terminal seals) + demotions (interval-scoped)                | **Demotion seals only** — one seal kind, the interval-scoped reversible one                                                    |
| Branch 1/2/3 `AddDevice`, `key_signature` binding, cascade-ban source sets           | Gone — `MemberAdd` has one admin-gated path                                                                                    |
| Genesis competition (most-descendants root selection)                                | Structurally dead — self-certifying `roomId` admits exactly one genesis per room                                               |
| `mutuallyRevoked` / `sealed` / `effectiveAdmin` / fixpoint loop / revocation ranking | **Copied as-is** — backdated grants by demoted admins, counter-demotion paradoxes, oscillation fixpoints all exist identically |
| `RemoveDevice(own)` / `RemoveAccount(own)`                                           | `MemberRemove(self)` — own-account op, untouched by seals                                                                      |
| `GrantAdmin` / `RemoveAdmin` / `RemoveDevice(other)` / `RemoveAccount(other)`        | `AddAdmin` / `RemoveAdmin` / `MemberAdd` / `MemberRemove(other)` — admin-gated, seal-subject                                   |

Verdict discipline copies verbatim: authenticity-only `REJECTED` (proven forgery: bad
signature, undecodable, derivation mismatch, wrong payload type); authorization-invalid /
sealed / duplicate events **ignored** (stored `VERIFIED`, no shadow effect — bans and policy
failures never fork the chainable graph); unresolvable author / incomplete ancestry /
poisoned → `PENDING`.

**Sealed `MemberAdd` collateral (accepted, tested):** an innocent account added by a
backdating demoted admin — the add is sealed out, the account was never validly a member, so
its messages land `REJECTED` once ancestry completes. Same accepted collateral as the global
tier's "concurrent-but-unsynced admin events die — rare, re-doable by a surviving admin."

**Global-ban interaction (open decision, §10):** cross-DAG, so no positional answer exists.
Two candidates recorded there; lean is room sovereignty.

## 5. Projection & storage

- `room_members` = projection of the fold output (current membership). The sprint-4 sync gate (`roomsOfPeer`), ping
  frontier snapshots, `DefaultMessagingService` fan-out, and sync
  candidate resolution all read it **unchanged**. `MemberRemove` cuts sync access for free.
- Commit is a merge for the `rooms` row (name/type from genesis; preserve local-only fields if
  any appear) and a recompute for `room_members` (no local-only fields today;
  `joined_timestamp` is recomputable or dropped from semantics). "Was member at position / was
  removed" comes from the fold timeline output, not from a `room_members` status column — the
  earlier `REMOVED`-status idea is superseded by this design (§11).
- Re-fold triggers: room insert (orphan creation included), gap closed, GLOBAL commit (authenticity inputs moved), once
  at boot. Full re-fold per trigger — correct and cheap at
  10–20 users, same argument as `global events.md §2`.
- Reverify hooks (the missing triggers found in the sprint-4 analysis):
    - `refreshAncestryDown` must collect rows whose `ancestry_complete` flipped true (plus
      cascaded children) and re-classify them — today it flips ancestry flags only, never
      verification state.
    - Fold commits re-classify room-`PENDING` messages (membership became known).
    - New repository query: pending-by-room (today only `findPendingByAuthor` /
      `findAllPending` exist).

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
fold, validity is a function of the payload and its ancestry — the forwarder is irrelevant,
and every threat it targeted is closed (§1 table → §3 verdicts). A hard block is also
forbidden: store-don't-drop (§6 of `global events.md`) means unsolicited messages must be
stored and judged, never refused, and relay deposits are unsolicited by definition.

**Survivor (post-PoC):** a provenance bit (solicited vs. unsolicited) plumbed from the
inbound handler, as policy metadata for rate limiting, GUI "unexpected message" surfacing,
and incident forensics. Never a drop condition.

## 8. Build order & work items

Order follows the global tier's sequence (codec → fold → projection → wiring):

1. **Codec**: `RoomEventPayload` (5 kinds), `MessagePayload.RoomEvent` variant,
   `MessagePayloadType.ROOM_EVENT(3)`, `roomIdFromGenesis` derivation + fold-side assertion.
2. **Ingest substrate**: `ensureRoomExists` on ingest; genesis-only-`prevIds` rule (fail
   closed); unknown-room messages stored `PENDING`.
3. **Room fold core**: copy `GlobalFold` minus the device tier (~250–300 lines from 508) —
   shadow state (account → role/status), demotion seals, `mutuallyRevoked`, fixpoint loop,
   poisoning, derivation check. Keep the pure-function + oracle shape (`FoldCrypto`) so the
   dynamics fuzzer can drive it unchanged.
4. **Room projector**: fold source `findAllInRoom(roomId)` per room (all messages, no
   filtering — pure function of the stored set); commit `rooms` + `room_members` projection;
   `stateChanges` flow; re-fold triggers (§5); global-ban interaction per the §10 decision.
5. **Verification integration**: `classifyVerification` ancestry-scoped membership (§3
   table); `refreshAncestryDown` re-classify trigger; pending-by-room query; reverify on fold
   commits.
6. **Ping threading** (§6): flow type change `(accountId, roomId, tips)` through
   `Router.pingPayloads` / `PingProvider` / `DefaultOrchestrator` collector /
   `SyncCoordinator.requestFrontierSync`; `addCandidateAccounts` on `PendingSyncRepository`.
7. **Append path**: `MessageDraft.RoomCreated` (engine derives `roomId` from the minted
   genesis `messageId`), `MemberAdd`/`MemberRemove`/`AddAdmin`/`RemoveAdmin` appends via the
   runtime services; GUI exposure last (fold supports admins from day one; the GUI toggles
   come after membership + messaging work end-to-end).
8. **Tests** (§9) — including the sprint-4 negative-test discipline (done-criteria d3).

## 9. Test matrix

Ported from `global events.md §9` (verdict expectations follow the authenticity-only
discipline) plus room-specific cases:

- non-admin `MemberAdd` → ignored (stored `VERIFIED`, no effect);
- backdated `MemberAdd` by demoted admin → sealed out; the added account's messages
  `REJECTED` (the collateral case, explicitly tested);
- concurrent `AddAdmin`/`RemoveAdmin` siblings converge identically on all nodes;
- backdated counter-demotion → both demoted (mutual destruction); demote → re-grant →
  post-grant acts valid;
- `roomId` derivation mismatch → `REJECTED` + poisoned descendants;
- non-`RoomCreated` with `prevIds == []` in a chat room → fail closed, no gap machinery;
- unknown-room orphan → `PENDING` → genesis arrives → re-classified `VERIFIED` (author in
  genesis) and `REJECTED` (author absent) — both branches;
- never-member author, ancestry complete → `REJECTED`; `REJECTED` never flips;
- removed member: post-removal message (remove in ancestry) → `REJECTED`; pre-removal and *backdated-as-pre-removal*
  messages → `VERIFIED` + flag (indistinguishable-by-design,
  tested as such);
- `MemberRemove` cuts sync: removed account's sync request → generic NACK, no oracle;
- ghost room from a globally banned account: stored, folds, no crash, no effect on existing
  rooms;
- global ban of a room admin: per the §10 decision, both branches tested;
- ping with unknown room → sync row created with the pinger's account as candidate; second
  ping from a different account about the same room → candidate appended, deduped;
  unresolvable account → no row, retried on next ping;
- onboarding unaffected: GLOBAL exempt from the room fold; sponsor fold-lag → NACK → retry
  converges (regression from the sprint-4 analysis).

## 10. Open items

- **Global-ban interaction with room authority** — the one genuinely new decision. Option A (room sovereignty, lean):
  the room fold ignores global bans entirely; practical exile is
  the firewall plus a manual `MemberRemove` by surviving room admins. No collateral ("don't cut off the branch" holds; a
  banned genesis admin's room keeps functioning).
  Option B (cross-DAG cut): a tombstoned author's room events are ignored + re-fold on global
  commit — but that voids their *pre-ban* events too (no positional cross-DAG check exists),
  so a globally banned room admin retroactively loses all their grants, and a banned room
  genesis admin leaves the room adminless. Decide before the projector lands; Option A's cost
  is manual cleanup, Option B's cost is structural collateral.
- `RoomMemberRole.ADMIN` exposure in the GUI (fold supports it from the start; UI last).
- Servers / room-grouping tier (multiple rooms under server admins) — deliberately
  undesigned; the account-principal fold generalizes, but nothing here should pre-shape it.
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
- **`REMOVED` as a `RoomMemberRole` value / `room_members.status` column**: rejected /
  superseded — removal is a status, not a role (same reason `devices.status` is not a
  `DeviceType`), and the fold timeline derives "member at position / removed" without any
  column. A status column remains the fallback if the fold is descoped.
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
