# Global Events & RBAC Projector — design decisions

This doc compiles the agreed design for the global control DAG, the RBAC projector, and
identity/RBAC enforcement. It is written so implementation can resume without re-deriving
the reasoning. The verification-state substrate (tri-state ingest, store-don't-drop,
`reverifyPendingFor` / `reverifyAllPending`, the `verificationStateChanges` flow, GUI rejection
handling) is implemented and assumed here; the `DeviceAdded` → reverify hook and the boot sweep are
wiring steps that land with the projector (§9 step 0, §10).

Sprint-4 revisions: §2 (fold input — pure function of the stored set) and §3 (absolute ban rules)
supersede earlier drafts; §11 records the rejected alternatives so they are not re-derived.

Related: [`guide.md`](guide.md), [`e2ee.md`](e2ee.md), [`ban diagram.mmd`](ban diagram.mmd),
[`Onboarding diagram.mmd`](Onboarding diagram.mmd), [`db schema.mmd`](db schema.mmd).

---

## 1. Core model

- The global control room (`RoomId.GLOBAL`) is the **single source of truth** for identity and RBAC.
- The `accounts` / `devices` tables are a **materialized view** (projection) of the global event log.
  The projector (`RbacProjector`) is the **sole writer** of their chain-derived columns.
- Every message carries an author signature; security comes from being able to follow the chain of
  signed events back to the origin device. No event is trusted in isolation — validity is a function
  of the fold state at the event's position in the chain, with two position-independent (absolute)
  exceptions for device-add authorization (§3).
- Events contain **as little information as possible**: only keys and bindings that cannot be derived.
  Everything else (admin status, membership, removal state) is derived from the log.

### Event types (typed `GlobalEventPayload` codec, replacing the raw `eventBytes` TODO in `MessageEnvelope.kt`)

| Event | Carries (non-derivable only) | Notes |
|---|---|---|
| `AddAccount` | account_id (pub key), account signing pub key, display name | Genesis variant: `prevId == null` |
| `AddDevice` | device_id, signing + encryption pub keys, onion address, device_type, account_id, `key_signature` (account signs device keys) | Sponsor-appended; QR payload embedded in a signed message |
| `GrantAdmin` | target account_id | |
| `RemoveAdmin` | target account_id | |
| `RemoveAccount` | target account_id | Removes all its devices |
| `RemoveDevice` | target device_id | |

Codec style mirrors `SystemPayload` (sealed interface, kind byte, encode/decode per type).

## 2. Canonical order and the fold

- **Fold order**: `(lamport_clock ASC, created_at ASC, message_id ASC)`. This is always a valid
  topological order because `DagEngine.append` sets `lamport = prev.lamport + 1` exactly; ties only
  occur between concurrent siblings, resolved deterministically by the composite.
- **Always fold in canonical order — never apply events in arrival order.** Concurrent opposing
  writes (e.g. `GrantAdmin(A)` vs `RemoveAdmin(A)` as siblings from two admins) resolve differently
  per node under arrival-order application; canonical order makes the fold deterministic and
  convergent for any node holding the same message set.
- **The fold is a pure function of the stored message set.** Fold input = *all* global-room
  messages — no `is_orphaned` and no `verification_state` filtering. Orphans and REJECTED events
  are re-evaluated on every fold; `verification_state` is an *output* (a cache for display/sync
  consumers), never an input. No verdict is terminal: verdicts are re-derived each fold and may
  flip as the set grows (gap closure, late siblings) — deterministically and convergently, since
  the fold depends only on the set.
    - Why fold orphans: with a reachability/prefix rule ("fold only what chains back to genesis"), a
      permanently lost real event would freeze the control plane forever — every later event
      unfoldable, no automated recovery. The loss requires a double failure (the event's every holder
      dies while a descendant survives), but the failure mode is total and silent. Folding orphans
      degrades instead: events past a gap are evaluated against incomplete shadow state and
      self-correct if the gap ever closes.
    - Why this is safe: excluding orphans was never a security boundary. The chain is dense
      (`lamport = prev + 1` from genesis), so an attacker reaches *any* position with a non-orphan
      sibling forgery (real parent, `parent.lamport + 1`) that passes the structural check. The
      backdating hole is closed absolutely in §3, not by fold input filtering.
    - The fold re-derives the lamport structural check itself (parent present → `lamport ==
    parent.lamport + 1`), so the ingest-time check is a fast path, not the authority.
- **Unresolvable author → PENDING, never REJECTED.** An event whose author is not in shadow state
  (its credentials sit in a gap) is skipped and stays PENDING — the global-room analogue of
  `UNKNOWN_AUTHOR → PENDING` in the chat tier. We cannot confirm any of that author's signatures
  until the missing events arrive.
- **Provisional negatives while gaps are open.** If any orphan exists in the global room, negative
  verdicts persist as PENDING rather than REJECTED (a wrong REJECTED drops displayed items with no
  re-add path; re-derivation would fix the verdict later, but the UI damage is avoidable).
  Only a gap-free fold issues REJECTED. Verdict flips on re-fold are expected and handled by the
  retraction-aware commit (§7).
- **Re-fold triggers**: new global-room insert (orphan creation included), gap closed (the arriving
  parent is itself a global-room insert), and once at boot. At 10–20 users the global log is tiny —
  a full re-fold on every trigger is correct and cheap; optimize with watermarks only if ever needed.
- The fold maintains its own **shadow state** (accounts + devices maps with keys and RBAC). It cannot
  consult the live tables mid-rebuild — that is the whole point of replay.

## 3. Authorization rules (deterministic functions of fold state at the event's position)

- `AddDevice(A)` valid iff:
  - (A exists in fold state AND signer's account == A) — own-device add, **or**
  - (A absent AND an `AddAccount(A)` by the **same signer** appears earlier in canonical order) —
    new-account onboarding (sponsor appends the pair back-to-back), **or**
  - the event carries a `key_signature` that verifies under A's account pub key **at that fold
    position** — account-key-authorized device add (§8.2 recovery, where no device of A exists yet to
    sign; the signature is relayed as data by any member, and possession of the recovery key is the
    authorization). This is what makes recovery on a fresh device possible without a sponsor. It
    composes with the absolute ban rules below: a tombstoned account invalidates account-key-signed
    AddDevices *anywhere in the fold* — a leaked recovery key of a banned account re-enters nothing
    (positional validity alone was evadable by backdating).
- `GrantAdmin` / `RemoveAdmin` / `RemoveDevice(other)` / `RemoveAccount(other)`: signer's account
  `is_admin` **at that fold position**.
- `RemoveDevice(own)` / `RemoveAccount(own)`: signer belongs to the target account. Non-admins can
  remove their own devices/accounts, nobody else's.
- **Any existing device can sponsor an `AddAccount`** (member-level, matching the onboarding diagram's
  non-admin Sponsor). Deliberate RBAC decision — document in UI.
- **Genesis**: the `AddAccount` with `prevId == null` (DAG root) is admin by definition. GUI surfaces
  this as "create new network"; the counterpart "join existing network" is the same provisioning path
  minus the genesis event. (`insertLocalAccount` hardcodes `is_admin = false` — the projector's first
  fold corrects it from the genesis event; fold immediately after genesis append so the GUI reflects it.)
- **Don't cut off the branch**: validity is evaluated against fold state *at the event's position*.
  A removed admin's earlier grants remain valid; only its later events become invalid. Same principle
  applies to chat history (see §6). One deliberate exception: branch-1 AddDevice authorizations are
  absolute (see below).
- Duplicate `AddDevice` for an existing (or tombstoned) device_id → invalid. Re-adding after removal
  requires a fresh key set → new device_id → effectively a new device. This is what makes removal a ban.
- Duplicate `AddAccount` for an existing (active or tombstoned) account_id → invalid. Without this,
  re-adding a banned account is a trivial bypass of the account-key ban rule.

### Absolute ban rules (backdating defense)

Positions are self-declared: `lamport`, `prevId` and `createdAt` are attacker-controlled and signed
by the attacker's own valid key, and the chain is dense (`lamport = prev + 1` from genesis), so a
backdated event — sibling or orphan — can be placed *before* its author's removal while remaining
structurally valid. Positional validity therefore cannot stop a banned principal from smuggling one
last authorization past its ban, in any DAG shape. The fix makes the two device-add authorization
paths **absolute** (set-level, position-independent — still pure functions of the stored set, so
convergence holds):

1. **Branch-1 adds die with their author.** A branch-1 `AddDevice` (own-account, no `key_signature`
   — the device's signature *is* the authorization) is valid only if its author is not tombstoned
   **anywhere in the fold**. The kill is transitive: devices branch-1-added by a banned device, and
   devices added by *those* devices, form the authorization subtree rooted at the banned device and
   die with it (fixpoint closure over tombstoned authors).
2. **Account-key adds die with the account.** A `key_signature`-authorized `AddDevice` (branches 2/3)
   targeting account A is valid only if A is not tombstoned anywhere in the fold. The relay's
   authorship is transport, not authorization — the account key is — so device bans never touch
   these events, and account bans kill them at every position.

An invalidated AddDevice projects as an **implied `RemoveDevice` at the ban's position** (§5
machinery: status flip, keys stay resolvable, re-add requires a fresh device_id) — never a
retraction to "never existed", which would make the device's pre-ban messages unverifiable. The
removed device's own messages keep positional validity (pre-ban positions valid, post-ban invalid).

**Invariant:** after `RemoveDevice(D)` lands, D's account's device set is exactly its
account-key-authorized baseline — every device D's key authorized, before, after, or backdated
around the ban, is out. This kills both the backdated evasion and the forward one (a compromised
device quietly adding an evil sibling while still active: the ban cleans it up retroactively).

**Collateral (accepted, account-local):** devices branch-1-added by the banned device need re-adding
(via a surviving sibling, or the recovery code if none). Sponsored members and account-key-added
devices are unaffected — their AddDevices are authorized by their own account keys — so banning a
prolific sponsor (even the genesis device) is a routine single-account recovery, not a network
reset. `AddAccount` sponsorship is untouched: mesh re-entry as a *fresh* account is open by design
and grants no access to existing rooms (E2EE room membership is separate).

**Residual (accepted):** a removed *admin's* device backdating a `GrantAdmin` survives rules 1–2
(it is not a device-add) — bounded to one action the device could have taken honestly at that
position, revocable by living admins, auditable in the log. No data loss at the storage/sync layer
(store-don't-drop; re-added devices sync full history); readable *decrypted* history on a fresh
E2EE device is a key-sharing question (see [`e2ee.md`](e2ee.md)), separate from ban semantics.

## 4. Verification architecture (two tiers)

- **Chat rooms are externally verified**: at ingest, against the live tables — which are chain-derived
  once the projector owns them, so this is automatically chain-backed. Tri-state classification
  (`VALID` / `INVALID` / `UNKNOWN_AUTHOR`) → `VERIFIED` / `REJECTED` / `PENDING`. Already implemented.
- **The global room is self-verifying**: its authors are defined by its own earlier content, so
  ingest stores global events as `PENDING` (per-room verification policy) and the **projector owns
  verification**: during the fold it verifies each event's signature against its running shadow-state
  keys and writes verdicts via `updateVerificationState` — including flips as the stored set grows
  (`verification_state` is an output cache, never a fold input; §2).
- This is principled, not a special-case hack: the two tiers already differ by design
  (`RoomId.GLOBAL` is a fixed room with no membership list). Express as an explicit
  `verificationPolicy: (RoomId) -> VerificationMode`, not an `if (roomId == GLOBAL)` in the engine.
- **Bootstrap ordering is self-enforcing by causality**: a fresh device cannot request sync from
  peers it does not know (no onion, no keys), so the global room syncs first by itself. The only
  residual — a chat message arriving before the author's `AddDevice` — is the `PENDING` path,
  resolved by the re-verification trigger (implemented).

## 5. Removal = status flip, not deletion

- Removal events project to **status** fields — settled: one shared `ACTIVE` / `BANNED` enum for
  both `accounts` and `devices` — never row deletion. Reasons:
  1. The firewall (sprint 4d) must distinguish *banned* from *never seen* — absence is ambiguous.
     (The ban diagram itself specifies `UPDATE ... SET status = 'BANNED'`.)
  2. Historical message verification: `verifyMessageAuthorship` resolves author keys from the
     devices table. Deleting rows makes pre-removal delayed messages unverifiable → permanent
     un-closable causal holds. Tombstoned keys keep history verifiable while routing/firewall/PING
     ignore the device.
  3. Re-add detection: a tombstone lets the fold deterministically reject reuse of a removed device_id.
- Removal is still effectively a ban: returning requires a completely new key set → new device_id.
- Invalidated AddDevices (§3 absolute rules) project the same way: an implied `RemoveDevice` at the
  ban's position — a tombstone, never a "never-existed" retraction (the device's pre-ban messages
  must stay verifiable).
- `devices` needs a **status column** (schema change; `accounts` already has one). Also consider
  recording the canonical position of removal for audit.
- Enforcement layering (storage vs policy):
  - **Storage criterion** (must converge on all nodes): well-formed + signature valid + author ever
    existed (tombstones make this decidable). Retroactively rejecting post-removal messages would
    fork the DAG (nodes that stored vs dropped) and re-create neverending sync loops.
  - **Policy enforcement**: banned-source check near dedup (envelope `source`), socket-level firewall
    (sprint 4d), display policy. "People can read the messages, but cannot send new ones."
- Once status exists, **relay selection (sprint 4b) filters on it** — banned devices are not relay
  candidates.
- Relay policy decision (pick one and document): evict store-and-forward packets queued by a device
  at ban time, or deliver in-flight ones (legitimate at send time).

## 6. Sync / storage invariants (implemented substrate, restated as rules)

- **Store everything, flag state, never permanently drop** — the orphan machinery philosophy,
  extended to verification. Nothing is ever missing from the DB, so the sync machinery never chases
  a message it will re-drop (the neverending sync-loop is structurally impossible).
- **Structural queries see everything; behavioral queries filter.** Gap closure, lamport ranges,
  counts, orphan checks are unfiltered. Only tail computation (`selectRoomTail`) and display
  (`selectMessagesInRoomPageDesc`) exclude `REJECTED`. Never chain off a `REJECTED` message.
- **Sync serves stored messages regardless of verification state** — they are signed, tamper-evident
  bytes; each node verifies independently. Refusing to serve `REJECTED` exports the loop to peers.
- **Lamport structural check**: `child.lamport == prev.lamport + 1`, enforced at ingest (parent
  present) and at gap closure (parent arrives). Violation → `REJECTED` (kept in storage). This is not
  authentication — it preserves the invariant that lamport-sort is a valid topological order, which
  the fold relies on. Without it a forged lamport can fold a child *before* its causal parent and
  silently neutralize the parent's effect ("remove before create"). The fold re-derives this check
  itself (parent present → check), so the ingest-time check is a fast path, not the authority.
- The `messages` FKs to `accounts`/`devices` were removed: the log is the source of truth, the tables
  are derived, and authorship validity is position-dependent — a static row-existence FK was
  semantically wrong anyway. `room_id` and `causal_hold` FKs stay.

## 7. Projector component sketch

```
org.yapyap.orchestrator.rbac (or globalevents)

RbacProjector(
    pipeline: InboundMessagePipeline,       // collect ingestResults for triggers
    messageRepository: MessageRepository,   // fold source (global room)
    identityKeyRepository: IdentityKeyRepository,  // commit target (sole writer of identity/RBAC columns)
    cryptoProvider,                         // primitive only — fold verifies signatures against
                                            // shadow-state keys; NOT SignatureProvider (live-table backed)
)
```

- `stateChanges: Flow<RbacStateChange>` — emits `DeviceAdded`, `DeviceRemoved`, `AccountAdded`,
  `AccountRemoved`, `AdminGranted`, `AdminRevoked`. Consumers: banned-source check, sprint 4d socket
  firewall, sprint 5b key rotations, UI, and (once wired) the pending-reverify trigger.
- **Commit is a merge, never a blind swap.** Preserve local-only fields:
  `is_local_account` / `is_local_device`, `reliability_score`, `last_seen_timestamp`, `push_token`,
  and the `signed_prekeys` / `one_time_prekeys` tables. Chain-derived fields: account pub key,
  `is_admin`, status, display name; device keys, account binding, device_type, onion, status.
- **Absence is ambiguous; the fold disambiguates.** A row present in the DB but absent from the fold
  output is either (a) *unverifiable* — its Add event was skipped (credentials in a gap; stays
  PENDING) — leave untouched, or (b) *invalidated* — its Add event was processed and failed (explicit
  Remove, §3 absolute rule, or per-position authorization) — project the removal and emit the
  reversal `IdentityStateChange`. The fold knows which case applies; a gap must never cause (a) to
  be treated as (b).
- **Verdicts may flip as the stored set grows** (gap closure, late siblings). The commit overwrites
  chain-derived fields on every fold, so reversals are natural merges; `stateChanges` consumers must
  tolerate add-then-reverse sequences.
- Runs on **all nodes including headless relays** (relays need the projection for the 4d firewall).
  The global room syncs through the same gap machinery as any room.

## 8. Onboarding flow (deferred; agreed direction)

The onboarding family is three roles a node can play, all fed by one BOOTSTRAP flow on the router
(`Router.bootstrapPackets`, dispatched consumer-side by payload kind — the same
per-packet-type/per-variant rule as `incomingMessages`/`MessagePayload`). `BootstrapPayload` kinds:
`INTRO` (sponsor→newcomer, AEAD), `INVITE` (newcomer→sponsor, **out-of-band QR/CLI only — never on
the wire**), `RECOVERY_REQUEST` (recovering device→mesh node, plaintext + account signature). The `BootstrapEnvelope`
header carries the **protection scheme** in plaintext (AAD-bound) — how to open
it — while the payload kind lives inside the payload, mirroring `MessageEnvelope.securityScheme` vs its
payload type; a wire-borne INVITE is rejected.

| Role      | Component                   | Lifecycle                                       | Modes       |
|-----------|-----------------------------|-------------------------------------------------|-------------|
| Newcomer  | `OnboardingProvider`        | ephemeral — disables at COMPLETE, secret burned | all         |
| Sponsor   | runtime `OnboardingService` | on-demand, interactive (QR scan)                | FULL_CLIENT |
| Responder | `RecoveryResponder`         | standing — serves others forever                | all         |

1. Newcomer generates keys locally (existing provisioning), displays QR: account/device keys, onion,
   account→device `key_signature`, **and a one-time shared secret**.
2. Sponsor scans, appends `AddAccount` + `AddDevice` back-to-back into the global DAG, broadcasts.
3. Sponsor sends the newcomer an **initiating packet** containing the sponsor's identity, protected
   with AEAD (ChaCha20-Poly1305) under a key derived (HKDF) from the QR shared secret. The secret:
   - never appears in the `AddDevice` event (that event is broadcast to the whole mesh, and mesh
     members learn the newcomer's onion from it during the vulnerable pre-sync window),
   - authenticates the sponsor out-of-band (the signature alone is circular — the sponsor's key
     arrives inside the very packet being authenticated; the secret breaks the circle),
   - is one-time; burn it after initial sync.
   Bidirectional QR was rejected: desktops often lack cameras.
4. Newcomer syncs the global room from the sponsor, folds, and joins the mesh. Impersonation value is
   bounded either way: events are signed, so an attacker can only serve a stale, censored, or
   parallel-genesis DAG.

### 8.1 Sponsor-side branch (automated from the invite)

`sponsorNewcomer(invite, admin)` derives everything from the payload — no GUI mode flag (two sources
of truth can disagree):

- `invite.account == null` → **add-device**: append `AddDevice` bound to the *sponsor's own* account
  (by §3 only devices of A may add to A, so the sponsor's local account **is** the target). `admin`
  is rejected here — the account exists and its status doesn't change on device-add.
- `invite.account != null` → **new account**: re-derive `accountId` from the account pub key, append
  `AddAccount` + `AddDevice` back-to-back (§3: same signer); when `admin == true` also append
  `GrantAdmin` — valid only if the *sponsor* is admin at that fold position, so fail fast on the
  local `is_admin` (the GUI shows the toggle only to admins). `GrantAdmin` is a separate event, never
  a field of `AddAccount` (admin status is derived from the log, §1/§5). The newcomer's provisional
  peer row is written with `is_admin = admin` since it is known here.

### 8.2 Account recovery over the network (recovery key on a fresh device)

The device knows itself from the recovery key but no peers; the user supplies a mesh member's
**bootstrap endpoint** (`peerId` + onion + port — device id required so the standard `WRONG_TARGET`
check applies; a shared-string encoding comes later).

Three phases:

1. **Request** (newcomer → node): a `RECOVERY_REQUEST` payload — account (with pub key) + device +
   deviceType + onion + one-time `sharedSecret` + `accountSignature` (the **account signing key**
   over a canonical device binding, `accountSignedDeviceBindingBytes`). Queued through the outbox
   like every bootstrap packet, with the user-supplied composite endpoint (peerId + onion) as the
   endpoint override — no devices row needed for the target (`outbox`/`dedup` deliberately carry
   no FK to `devices`; the override is preferred over the DB lookup at dispatch). Disposition +
   short lifetime, as usual. The account key is online only on this fresh
   import (recovery-code import puts it in the keystore; wipe-after is future work).
   The secret's value here is not authentication (Tor delivery + the account sig already do that) but
   session binding: the reply is AEAD-bound to this specific request, gating stale/replayed intros —
   and it lets the reply reuse the *existing* `BootstrapIntroProtection`/`openIntro` path unchanged.
2. **Responder** (`RecoveryResponder`, standing on every node): verify the account signature
   (possession proof), check the account exists and is ACTIVE (a banned account must not re-enter),
   then **relay** — append `AddDevice` carrying the same signature as `key_signature` (the §3
   account-key branch, §3), fold, and **then** reply. The ordering is what removes all the "mess":
   the intro's `dagHead` already includes the newcomer's `AddDevice`, so the newcomer syncs and finds
   itself in the chain; the responder's device row for the newcomer is chain-derived (no manual
   provisional inserts, no out-of-projector writes); the newcomer's sync requests verify against that
   row. Any member can respond — the authorization is the account signature, not responder rank.
3. **Newcomer**: standard intro path — seed the responder's provisional rows, sync GLOBAL up to
   `dagHead`, find its own AddDevice, burn the secret, COMPLETE.

Convergence: a request replayed to several nodes yields duplicate `AddDevice`s for one device_id —
§3's duplicate rule converges (first in canonical order valid, rest invalid). Responder DoS (accepts
but refuses to relay) shows up as "no AddDevice for me in the synced DAG" → retry another endpoint.
Refuse-while-own-onboarding-active: a mid-onboarding node is a poor sync seed, so the responder
declines during that transient window (the request retries elsewhere).

### 8.3 Known seams (accepted)

- The intro/request is ACKed on handler `Success` *before* the orchestrator role acts — a failed
  persist or relay is invisible to the ACK. Deliberate, no sink-callback seam: policy refusals are
  answered pre-emit in the handler (own-session decline and non-ACTIVE account → DECLINED NACK,
  unknown account → Deferred so the sender's retry re-runs the check), and the newcomer's persisted
  session deadline (burn + TIMED_OUT) is the backstop for everything after the emit.
- `INVITE` is out-of-band only; `BootstrapEnvelope.init` rejects it on the wire.

## 9. Build order & test matrix

Order: **step 0 — GLOBAL room presence & membership** → typed codec → projector fold + commit →
`devices.status` migration → genesis wiring → onboarding handshake (last).

**Step 0 — the GLOBAL room must exist as a real room.** Nothing today seeds the `rooms` row for
`RoomId.GLOBAL` (only a JVM test fixture inserts rooms). Consequences: because `messages.room_id`
keeps an FK to `rooms` and FKs are ON, real-DB global-event inserts would fail; `getLocalSeq(GLOBAL)`
would be null so `requestRangeSync` would early-return; `membersOfRoom(GLOBAL)` would be empty so gap
syncs would get zero candidates; and `selectLocalSeqNForRoomsOfPeer` would hide the global room from
pings. The projector's broadcast path also needs "all accounts" as the recipient list.

Decision (agreed): **the projector maintains `room_members` rows for the GLOBAL room** — on every
`AccountAdded` insert `(GLOBAL, account, MEMBER)`, on `AccountRemoved` delete. This keeps every
existing join/query (sync candidates, ping lamports, broadcast) working unchanged — global-room
membership *is* chain-derived (every account is a member), so writing it in the commit is redundant
by choice but preserves the rest of the machinery with zero special-casing. A `rooms` row for
`RoomId.GLOBAL` (`type = GLOBAL_CONTROL`, `local_seq_n = -1`) must also be seeded once, idempotently
(DB init or boot), which alone fixes the `messages.room_id` FK and `local_seq_n` tracking.

The fold source is `findAllInRoom(GLOBAL)` — *all* messages, no `is_orphaned` /
`verification_state` filtering (§2: the fold is a pure function of the stored set) — sorted
canonically (`lamport ASC, created_at ASC, message_id ASC`) in memory (fine at 10–20 users).

Negative tests (done-criteria d3):
- non-admin `GrantAdmin` ignored;
- `AddDevice` to another member's existing account;
- `AddDevice` without prior `AddAccount` (same signer);
- removed device's post-removal events invalid, pre-removal events still valid (branch not cut);
- duplicate device_id re-add after removal;
- concurrent `GrantAdmin`/`RemoveAdmin` siblings converge to the same state on all nodes
  (the divergence case — most important);
- forged-lamport child sorts before its parent → structural check → `REJECTED`;
- message from tombstoned author: stored `VERIFIED`, history preserved, no sync loop;
- backdated branch-1 `AddDevice` by a banned device → invalid (direct, and via an intermediate
  device — the cascade case);
- backdated account-key `AddDevice` (branch-3 relay) for a removed account → invalid;
- `AddAccount` for a tombstoned (or already-active) account_id → invalid;
- sponsored members and account-key-added devices survive their sponsor's ban — including a
  genesis-device ban (single-account recovery, no network reset);
- wrong verdict issued during an open gap flips when the gap closes (no terminal `REJECTED` —
  the fold re-derives).

## 10. Implemented substrate & open items

- **Implemented** (assumed by this doc): `reverifyPendingFor` / `reverifyAllPending`,
  `DagEngine.verificationStateChanges`, GUI rejection handling (skip REJECTED at ingest; drop
  already-displayed items on PENDING/VERIFIED → REJECTED via `VerificationStateChange`).
  Global-room PENDING events are not touched by reverify (the projector folds them).
- **Not yet wired (land with the projector)**: the `stateChanges.DeviceAdded` →
  `reverifyPendingFor` hook, and the boot sweep (`reverifyAllPending` once after the first fold).
- SPK rotation / handshake edge cases (see [`e2ee.md`](e2ee.md)).
- Relay eviction policy for banned devices' queued packets (§5 — decide when implementing 4d).
- Push notifications (deferred project-wide).

## 11. Alternatives considered (sprint 4 — fold & ban semantics)

- **Prefix/reachability fold** (fold only what chains back to genesis): rejected — a permanently
  lost real event would freeze the control plane with no automated recovery. Superseded by the
  pure-function fold (§2).
- **Matrix-style forking** (append forks at the *complete* frontier): deferred. It gives every new
  event a complete, verifiable ancestry — sound verdicts even after permanent loss — but done
  properly it requires `prevIds[]` parent sets with merge events (a single-`prevId` tree fragments
  authority: a ban could never cross branches), a wire protocol change, frontier computation in
  append, and branch-aware sync (the scalar `local_seq_n` ping comparison cannot see divergent
  branches at equal lamport). The pure-function fold captures its survival property at fold-logic
  cost only; revisit `prevIds[]` if the mesh outgrows the linear model (the fold rule generalizes:
  "fold iff parent folded, else provisional").
- **Eliminating the relay path** (relays may only add devices to their own account): rejected —
  breaks branch-2/3 onboarding and recovery, and does not stop the actual attack: the relay's
  authorship is transport, not authorization (the account key is), and the ban evasion is a
  backdated *branch-1* add, which stays legal under that rule.
- **Unscoped absolute invalidation** (a ban kills *all* AddDevices authored by the banned device):
  rejected — banning a prolific sponsor (the genesis device) would reset the whole network's
  devices. Scoped to authorization source instead (§3): only branch-1 adds, where the device's
  signature is the authorization.
