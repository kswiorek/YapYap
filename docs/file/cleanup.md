# File-branch cleanup

This document records the review of the implemented file branch — the roadmap's owner-selected "review and
finalize the unreviewed file-transfer branch" item — and the cleanup that review ordered. It is the change
plan: [`design.md`](design.md) describes the steady state this cleanup produces, this file describes how the
code gets there. [`../envelope-redesign.md`](../envelope-redesign.md) remains the design of record for the
small path, and nothing here moves a mechanism: every wire value, every seal, hop and delivery rule stands.

The docs describe how we want the code to be, not necessarily how it is today: until the steps below land,
the code and these docs disagree — that is the intended state of a reviewed branch. The disagreement is a
known bug, and this document is its fix.

Review scope: `routing/file` (`FileService` and its tree — seals, transfers, relay, inbound, the request
loop, the large path), the routing file stores, the candidate core's file plugs, the file config, and the
seams to the orchestrator. Line references below are pre-cleanup.

## 1. What the review found

The mechanisms were confirmed sound — the seals/hops design, the gone-only deposit triggers, the
request loop on the shared candidate core, the large path's plain channel protocol. The maister docs'
standing caveat on this branch ("freshly redesigned and unreviewed — do not mine it for conventions") is
hereby answered: the patterns listed below were reviewed and are **not** conventions. What the review
found was naming drift, config indiscipline, and object shapes that hide a sound design:

- **`seals` meant three things**: a `SealRegistry` in `FileService` (FileService.kt:72), a `FileSealStore` in
  `SealRegistry` (SealRegistry.kt:41) and in `TransferEngine` (TransferEngine.kt:61). The same stores were
  `sealStore`/`transferStore` in `FileService`'s constructor, `seals`/`transfers` in the engines; the
  `FileRequestSource` port was `requests` in two files and `held` in a third (TargetSide.kt:52).
- **Three hand-rolled adapter wrappers** ("docks") each adapting an attempt store to the shared candidate
  core: an anonymous dock in `FileRequestProcessor` (FileRequestProcessor.kt:152), a near-identical one in
  `FileFetchStrategy` (FileFetchStrategy.kt:47), and `SyncAttemptDock` (MessagePullProcessor.kt:111, itself
  carrying a TODO to remove it). All three assemble the parent row plus its declined-children child table
  by hand, in consumer code.
- **A `RoutingContext` extension carrying file logic** (`FileControl.kt:15`): the context is ambient facts
  and carries no engines or policies (router.md, guide §2); the file family's envelope builder was
  bolted onto it as an extension, called from three files.
- **A router-internal service shape duplicated three times without an interface**: `offerOutcome` +
  `runIn(scope): List<Job>` exist verbatim on `MessageService`, `BootstrapService` and `FileService`, and
  `DefaultRouter` names the concrete classes to dispatch outcomes (DefaultRouter.kt:441-443).
- **Serial fanouts under a lock**: `SealRegistry` held its mutex across per-device crypto (sealing), store
  inserts and `engine.start` (push, serve) and across N relay deposits (SealRegistry.kt:64-70, 105-116,
  171-191) — a multi-device push blocked every serve, COMPLETE and settle behind it. The TODOs at
  SealRegistry.kt:50 and 104 said exactly this.
- **Invented size constants**: `SMALL_FILE_MAX_BYTES` (protocol) was policy wearing a protocol costume —
  used by the router to pick a path, by the orchestrator to skip pushes, and baked into the seal's
  `require` and the shape check; and `MAX_SEAL_BYTES = 64 KiB` (FileIds.kt:39) was a bare literal, part of
  no derivation chain, enforced only at receive-side admission (TargetSide.kt:62, RelayDoor.kt:50) while
  the seal codec accepted bigger seals at encode — an invented bound that both under-used the real
  envelope-derived budget and was never wired to it.
- **Magic timings**: the large path's 300 ms answer grace, 50/20 ms pacing loops, 100 ms defer retry and
  1 s parked-channel check (FileFetchService.kt:395, 811, 852, 999, 1005); sweep floors `maxOf(…, 10.seconds)`
  (SealRegistry.kt:150, RelayForwards.kt:92); the engine tick's `chunkAckTimeout / 3` with a 5 s floor
  (TransferEngine.kt:176); and `TargetSide`'s fully hardcoded 5-minute sweep (TargetSide.kt:280).
- **Star imports** — forbidden by .editorconfig, present throughout the branch (FileService.kt:17,30;
  TransferEngine.kt:26; SealRegistry.kt:3,11,14,19; FileFetchService.kt:12).
- **Type-file sprawl**: data-only types live one-per-file (`FileManifest.kt`, `FetchResult.kt`,
  `FileFetchLabels.kt` — a one-constant file) or ride with behavior classes (`FileCarrier.kt` carries
  five seam types; every store file carries its enums and row classes; `ChunkSink.kt` carries
  `IncomingHop`) — the structure standard's two-mode scheme exists for exactly this and was never
  applied decisively.
- **Engine shape**: `TransferEngine.start` inserted a row then re-read it through `withLive` and its
  recovery path (TransferEngine.kt:84-88); one serial tick held every transfer's work, so one hung
  plaintext read stalled them all.

## 2. The eleven points and their agreed end states

The review went through these one by one until each was agreed. They are recorded here at the depth they
were agreed, including the alternatives that were rejected, so the implementation cannot drift: where this
section and any later summary disagree, this section wins. The numbering is the review conversation's:
the six flagged TODOs first, then the similar issues found in the rest of the tree, then the large-path
split. Two sections sit outside that numbering: §2.12 records what the maister-standards check confirmed,
and §2.13 records a file-layout convention agreed while finalizing this document — both with the same
authority as the points. Each section names the change step that lands it.

### 2.1 Point 1 — naming: stores for their type, components for their concern

Agreed rule: **stores are always named for their type** (`sealStore`, `transferStore`, `relayStore`,
`incomingStore`, `pullAttempts` — FileService's constructor reads this way); **live components
keep their domain noun** (`engine`, `forwards`, `relayDoor`, `target`, `pullProcessor`, `fetchService`).
Renamed fields: `TransferEngine.seals/transfers/relays` → `sealStore/transferStore/relayStore`;
`SealRegistry.seals/transfers` → `sealStore/transferStore`; same treatment in `RelayForwards` and
`RelayDoor`. The `FileRequestSource` port is named consistently (`requests`, not `held` in TargetSide).
The registry property in `FileService` keeps the name `seals` (the owner's pick): with no store anywhere
named `seals` it is unambiguous, and `seals.push(fileId, target)` reads naturally — the binding rule is
the store/component distinction, not that one name.

Lands in step 1.

### 2.2 Point 2 — the router-internal service interface

The flagged `offerOutcome` cannot be an override of `FileCarrier`: `OutboxOutcome` and `OutboxTag` are
router-internal vocabulary that must not cross the orchestrator seam. The agreed shape is a dedicated
router-internal interface, and it carries the whole shared service shape, not just the mailbox inlet:

```
internal interface RouterService {
    val outcomeTags: Set<OutboxTag.Kind>   // MESSAGE and SYNC both belong to the message service;
                                           // FILE_TRANSFER and FILE_REQUEST both to the file service
    fun offerOutcome(outcome: OutboxOutcome)  // mailbox inlet; never suspends
    fun runIn(scope: CoroutineScope): List<Job>
}
```

`MessageService`, `BootstrapService` and `FileService` implement it; `DefaultRouter` builds its tag→service
dispatch from the declared `outcomeTags` (replacing the hardcoded `when` at DefaultRouter.kt:441-443) and
starts all services uniformly through `runIn`. The services' *internal* kind splits stay: the file
service's own outcome loop keeps dispatching FILE_TRANSFER to the engine and FILE_REQUEST to the pull
processor (FileService.kt:156-162).

Deliberately kept out:

- **`inboundHandler` is not on the interface.** The handlers map (DefaultRouter.kt:254-260) is not 1:1 with
  services — `SIGNAL` is a standalone handler built from ctx alone, `SYSTEM` wraps the message service —
  and forcing them into the shape would fake the pattern. The map stays explicit in `DefaultRouter`.
- **`PresenceService` does not conform now.** It has no outcomes, no handler, and takes a constructor
  scope instead of `runIn`. A TODO lands with this step to consider conforming it later.

Lands in step 2.

### 2.3 Point 3 — every FILE envelope built in one place (`FilePackets`)

The flagged extension (`FileControl.kt`) is gone: `RoutingContext` is ambient facts and gains nothing —
this is the composition-root standard (router.md: "it carries no stores, engines or policies"), not taste.
Its replacement is a small class in `routing/file`, constructor-injected with `ctx`, that owns **all four**
FILE envelope builders, so "how a FILE envelope is framed" lives in exactly one place:

- `control(peer, header, expiresAt)` — today's `fileControl` (FileControl.kt:15; used from
  TransferEngine.kt:94, RelayDoor.kt:93, TargetSide.kt:262,266);
- `offer(transfer, offer)` — currently inline in `TransferEngine.sendOffer` (TransferEngine.kt:257-265);
- `chunk(transfer, index, ciphertext)` — currently inline in the window refill (TransferEngine.kt:237-244);
- `request(device, sealedContent, expiresAt)` — currently inline in `FileRequestStrategy.buildAttempt`
  (FileRequestProcessor.kt:203-210).

The transfer sessions, `RelayDoor`, `TargetSide` and the small-pull strategy take a `packets` dependency;
`FileControl.kt` is deleted. Absorbing the offer/chunk/request builders is the actual win beyond deleting
the extension — three parameter lists that duplicate framing knowledge disappear.

Lands in step 3.

### 2.4 Point 4 — `start` stops re-reading what it just wrote

`TransferEngine.start` inserts the row, then `withLive` re-reads it from the DB, takes the per-transfer
lock, and runs the full recovery path (re-querying chunk rows and the outbox) — for a transfer it just
created. The agreed shape: insert, then under the transfer's lock create the session's running state
directly from the inserted row (fresh source, empty queued set), register it, send the OFFER. No re-read,
no recovery on a fresh row, one less nesting level. The recovery path survives only where it is real:
rebuilding sessions from rows plus the outbox when the router starts.

Lands in step 4 (absorbed into the session restructure below).

### 2.5 Point 5 — all transfers treated alike: one architecture, the two axes, the pull umbrella

The largest discussion, in three parts.

**2.5.1 Sessions over a mux — the envelope path adopts the actor shape the large path already has.** The
chosen restructure (option B of the review; option A, a light in-place flattening, was rejected as not
worth stopping there): each outgoing transfer becomes an `OutgoingTransferSession` — its own mailbox
(`sealed SessionMsg { Tick, Ack(from, index), Cancelled(from, reason), Outcome, LinkDown, Cancel }`;
`Cancel` is our own end), its own
guarded coroutine, owning the row copy, its `ChunkSource`, the queued set, `offerSentAt`,
`lastProgressAt` and the gone flag; it ends itself (status row, event emit, source close). The engine
shrinks to the registry + start-time recovery + a mux that routes inbound ACK/CANCEL/outbox
outcomes/link-downs into session mailboxes and collects the `TransferEvent` flow. The per-transfer mutex
map and the shared serial tick go; the tick becomes per-session work at the configured cadence, so a
session that hangs (e.g. `plaintext.open` stuck on disk mid-refill) blocks only itself — today that same
hang stalls the tick for every transfer (TransferEngine.kt:198). Durable state stays the transfer rows
plus the outbox; a restart rebuilds sessions from them, never re-sending what is still queued.
`TransferEvent` (Progress/Gone/Ended) and every engine semantic are unchanged.

**2.5.2 Pattern-level unification, not type-level.** The review's starting claim — "once it gets to the
transfer itself, all transfers should be treated equally, no matter if we requested it, got a push or
whatever; there are just two different behaviours, sender and receiver" — turned out to already be the
architecture: the engine already runs ours and relay forwards alike (only the `ChunkSource` differs);
`TargetSide` and `RelayDoor` already share the `ChunkSink` receiver contract; a relay is literally door
(receiver) + forwards (sender); asking is already unified on `CandidateRequestCore`. What was missing was
the object shape, and that is what 2.5.1 fixes. The agreed limit, stated to prevent drift: **there is no
shared transfer class across the small and large paths.** They are different wire contracts —
envelopes/outbox rows/seals/relays vs plain channel frames with in-memory-only state — and merging them
would mean mode flags on "which transport am I", the exact mess the cleanup deletes (guide §2 keeps the
large path deliberately separate: "no envelopes, no outbox rows"). The receive side stays per-packet and
DB-backed: it has none of the outgoing window complexity that needed sessions. The unification that
lands is: the same session/mux/actor pattern with a consistent naming convention
(`OutgoingTransferSession`, `LargeFileRequester`, `LargeFileHolder`), not one type.

**2.5.3 The two axes: Large/Small, Push/Pull — "fetch" is banned.** Names state the axis they mean:
**large-file things carry `Large`**; direction words belong to direction; "fetch" disappears from class
names because it reads as "pull" while meaning neither reliably. Prose may still say a device fetches or
pulls bytes; classes do not. The wire frame names change with them (`LargeFileFrame` with kinds
`PULL`/`PULL_OK`/`DECLINED`, wire values 21-25 unchanged — name-only, permitted pre-fleet;
`LargeFileAbortReason`, `LargeFileDeclineReason`). The ask loop's **umbrella is pull** (the file sibling of
the message pull path, per guide §8's own phrase "the REQUEST/serve pull path"): `FilePullProcessor`,
`FilePullAttemptStore`, `FilePullAttempt`, `FilePullClass`, `FileCarrier.startFilePull`, and the tables
`file_request_attempts`/`file_request_declined_devices` → `file_pull_attempts`/`file_pull_declined_devices`
— the table rename was explicitly agreed. **No new migration file**: the files branch is unmerged, so
the branch's own schema and migration (`6.sq`/`6.sqm`) are amended directly — the owner's rule is that
pre-merge the branch's history is amendable, and once merged, the guide's ships-with-a-migration rule
resumes. Guide §4's ownership table and `ArchitectureTest`'s `queryOwners` update in the same change.
Unchanged **on purpose**, each because the name is already narrow-correct: `FileHeader.Request` (the small
wire verb), `FileRequestStrategy` (it builds that wire packet), `OutboxTag.Kind.FILE_REQUEST` (names the
same packet), `fileRequestAttemptTimeout`/`fileRequestWaitTimeout` (small-path REQUEST semantics),
`FileRequestSource` (the orchestrator port; renamable later at low value). Because the design of record
used the old vocabulary, `envelope-redesign.md` is updated in the same change — wording only, no wire
change.

Lands in steps 1, 4 and 7 (the split below follows from it).

### 2.6 Point 6 — the seal registry's fanouts go two-phase

Agreed: **plan under the mutex, execute outside it, per device, in guarded coroutines.** The mutex keeps
only the serialized reads: `push` decides which (file, device) pairs need a seal (the live-seal check)
and marks them in an in-memory in-flight set; `serve` collects the transfers to cancel and decides
under-way-ness; `cancel` collects the seals to end; `deposit` keeps its existing durable claim (`claimDeposit`) under
the lock and plans its relay set there. The heavy work — `createSeal` (crypto,
store insert, `engine.start`), the deposit's per-relay `engine.start`s, and `engine.cancel` — runs
outside the lock, one guarded launch per device/relay/transfer, joined before the entry point returns,
so a 10-device push stops blocking every serve, COMPLETE and settle. `settle`/`cancel`/`onComplete`
return the transfer ids to end and the caller fans them out outside the lock.

Two correctness details from the review, agreed as part of this:

- **The in-flight guard is in-memory, not a placeholder row.** Two overlapping pushes of the same
  (file, device) would both plan "no live seal" before either lands; the mutex-computed plan guards this
  with a cheap in-flight map, keyed by (file, device), holding a per-operation lease that only its taker
  releases (an outer clear cannot delete a lease taken later). It is cleared once the seal's row and first
  transfer land, and it covers `serve` too (a serve racing a push, or another serve): the operation for a
  pair already in flight plans nothing, `push` skipping that device and `serve` declining. The alternative
  — inserting a placeholder seal row under the lock to make the guard durable — was rejected: it drags the
  store back into the critical section the two-phase split exists to empty.
- **Cancel is a plan-time decision.** A `cancel` that runs after a push has planned but before its seal
  row lands cannot see it: the push still lands and may deliver. Accepted as the price of freeing the lock (the old
  shape serialised the whole push); a user cancel can simply be repeated, and the pull path is
  unaffected.
- **No per-call timeouts on top.** Everything in the execute phase is local CPU and DB (no network);
  the existing backstops already recover a mid-flight death: the sweep settles a seal whose transfers
  never started, expiry bounds everything, the durable rows rebuild, and the pull path covers the device.
  A timeout that tears rows mid-insert would buy nothing and risk partial state.

Lands in step 5.

### 2.7 Point 7 — store-owned candidate views, no docks

Agreed principle, in the owner's words: *we control all the interfaces; there is no need for wrappers.*
The dock pattern is real and worth standardizing — the view composes the parent attempt row with its
declined-children child table (plus the WAITING columns for the small class) — but that composition belongs
to **the store that owns the tables**, handed out as a named view, never assembled by consumers:

- `FilePullAttemptStore` grows `candidateStore(requestClass): CandidateAttemptStore<FileId>` — a named
  private view class in the store's file. Two views over one backing store is *why* the view exists at
  all (the interface methods are class-scoped: `findDue`/`earliestDueAt`/`start` filter by
  `FilePullClass`), so the adaptation is not removable — it is relocated and written once instead of
  twice, and owned by the store. The SMALL view maps the waiting columns to `CandidateWaiting`; the
  LARGE view's waiting methods are documented dead code (see below). Both file strategies take a plain
  `CandidateAttemptStore<FileId>` and their anonymous docks are deleted.
- `SyncAttemptStore` grows the same accessor for `CandidateAttemptStore<Uuid>`; `SyncAttemptDock` is
  deleted together with its own TODO (MessagePullProcessor.kt:110).
- **`setWaiting`/`clearWaiting` stay on the shared interface.** The flow, grounded in the core: an ACK
  whose `onSuccess` returns `AwaitUntil` arms it (CandidateRequestCore.kt:115-118); the due loop honors
  it by postponing the row until the wait ends, then clears it and treats the holder as silent
  (CandidateRequestCore.kt:228-235); a decline clears it; and it is durable — "the stored row is the only
  record of WAITING, across restarts too" (CandidateStrategy.kt:52-54). Only the small-file strategy ever
  returns `AwaitUntil`; the sync and large views implement the methods as no-ops under a documented
  contract.
- **The interface is the asking loop's row-state port, not a table store.** The review raised whether the
  due/scheduling methods belong on a "store" at all, and whether the outbox's due logic should be
  extracted and shared. The grounded answer: the due loop lives in `CandidateRequestCore`, the loop
  driver (`RetryLoop`) is already shared with the outbox processor (CandidateRequestCore.kt:61), and the
  backoff policy (`computeBackoff`) is in the core — what the port persists (next-attempt time, attempt
  count, declined children, WAITING) is the loop's row state, written through the same port as the
  scheduling calls. Splitting a pure-CRUD store from a scheduling port would give one table two write
  paths, push row composition into the generic loop, and change no behavior; unifying with the outbox's
  due machinery beyond `RetryLoop` was considered and rejected — the outbox's claim/per-target-cap/
  retry-class policy and the candidate loop's capped-exponential attempts are deliberately different
  contracts (guide §6). The interface's KDoc states its nature. No rename is decided — the one candidate
  floated ("ledger") read as worse than the mild lie of "Store" to the owner — and a TODO lands on the
  interface with this cleanup to consider a better name: whatever it becomes must say "the asking
  loop's row state", not "database rows".
- Layering note, so the guide's cross-layer view rule is not over-applied: "the *reading* layer declares
  the view" is for cross-layer reads (`DeviceDirectory`, `PeerKeyDirectory`). These views are
  intra-layer — a routing store handing routing's loops a composed view — and a store accessor is the
  right shape for that.

Lands in step 6.

### 2.8 Point 8 — sizes and config: the four kinds of number

The largest single discussion; sub-split here. One interim position was explicitly overruled during the
review and is recorded so it is not relitigated: the review's first pass recommended keeping the
small-file limit as a shared protocol constant, on the theory that both peers must agree. The owner's
design intent overruled it — network-common values are meant to be adjustable while the network runs
(settled through a future network-config fetch), and a shared constant cannot be. Everything below is the
settled state.

**2.8.1 The taxonomy.** *Arbitrary values which may be expected to change and do not stem from the
protocol itself are config; the protocol contract for this version does not change — constant; values that
must be the same everywhere and cannot be user-adjusted are network settings.* Concretely, four kinds of
number:

1. **Protocol constants** — change only with the format: the block-size rule and the `fileId` version tag,
   key lengths, decode-time allocation guards, `MIN_FILE_CHUNK_SIZE_BYTES` (2.8.4).
2. **Derived sizes** — chained by the composition root from the transport limits (`wiring/FileLimits`),
   never independently configured and never a constant checked after the fact: `chunkSizeBytes`,
   `largeFileFrameBytes`, `maxSealBytes` (2.8.3).
3. **Network values** — `RouterConfig` fields surfaced through the settings registry as
   `FieldSource.NETWORK` (`wiring/Settings.kt`, the `messageLifetime`/`dedupRetention` precedent; the
   future network-config fetch is the VaultGraph "[Sprint 7] fetch NetworkPolicy" TODO): written
   programmatically, never user-editable, settled network-wide. Today exactly one file value lives here:
   `smallFileMaxBytes` (2.8.2).
4. **Local mechanics** — plain `RouterConfig` fields, deliberately **not** in `FIELDS`: timeouts, sweep
   and tick cadences, delays, windows (2.8.6).

The router-config standard's "file-transfer sizes … are not router config" (router.md) is about kind 2 —
framing-derived sizes. Kind 3 is policy, not a size, and lives in `RouterConfig` legitimately; the
distinction is now stated in router.md and design.md §15 so the two rules read together without
contradiction.

**2.8.2 `smallFileMaxBytes` — a network value, receiver-enforced.** `SMALL_FILE_MAX_BYTES` is deleted from
protocol; the gate becomes `RouterConfig.smallFileMaxBytes = 10 MB` (init requires), a `NumberField` with
`source = FieldSource.NETWORK`, group "Files", following the attached Settings.kt pattern. Where it is
read: the sender gates (`SealRegistry.push`/`serve`, replacing the constant checks at SealRegistry.kt:63
and 82), the class pin in `FilePullProcessor.start` (replacing FileRequestProcessor.kt:118), and
**receiver-side admission**: `TargetSide.offer` checks `manifest.totalBytes`, `RelayDoor.offer` checks
`shape.byteTo` (a relay cannot open the seal) against *our configured* value — the owner's rule: *the
receiver enforces the limit if it differs for the two* (the expectation is it will only ever increase).
Disagreement is lossless: a DECLINED OFFER falls back to the pull path, and the receiver's own class pin
routes a file that is large *for it* to the fetch path, which no sender's limit can block. Protocol
cleanup rides along: `FileHeader.isAcceptable()` keeps only the chunk floor; `FileSeal`'s `require` keeps
only format checks.

**2.8.3 `MAX_SEAL_BYTES` was invented — replaced by a derived `maxSealBytes`.** The audit verdict: a bare
`64 * 1024` literal (FileIds.kt:39), part of no derivation chain, enforced only at receive-side admission
(TargetSide.kt:62, RelayDoor.kt:50) — never at encode — so a sender with a bigger seal built the OFFER and
learned only by DECLINED; and it under-used the real budget several-fold. The owner's rule: *it should not
be a constant that is checked after*, and *let's not invent a value just because it sounds correct* — the
invented `MAX_SEALED_FILE_BYTES` ceiling proposed mid-review was rejected on exactly that ground. The
true bound already exists and needs no new number: the seal must fit an OFFER through both transports.
So: `FileHeader` states its OFFER header size next to its codec (the `CHUNK_ENCODED_BYTES` precedent);
`wiring/fileLimitsFor` derives `maxSealBytes = BinaryEnvelope.maxContentBytes(transport.maxRoutableBytes,
OFFER_ENCODED_BYTES)` — pure subtraction; `FileLimits` carries it; `createSeal` checks its own encoded
seal against it right after `protection.seal` (over-budget = can't-seal, log, pull path), and admission
reads `ctx.fileLimits.value.maxSealBytes` instead of the constant. `wiring/ConfigInvariants` asserts the
chain's coherence. Real seals sit far below the budget by the block rule (≤1024 blocks × 32-byte hashes
≈ 38 KiB — the number envelope-redesign.md §5.9 already carried). Note the distinction this creates:
`maxSealBytes` is **identical on every peer by construction** (derived from shared transport constants) —
its admission check is a sanity gate with no disagreement case, unlike the adjustable
`smallFileMaxBytes` in 2.8.2.

**2.8.4 `MIN_FILE_CHUNK_SIZE_BYTES` stays a protocol constant.** What it does, from the audit: (a) the
receiver admission floor in `FileHeader.isAcceptable()` (FileHeader.kt:257-262) — and its comment's
"millions of stored chunks" is literal: a transfer's `chunkSizeBytes` is **peer-chosen** in its OFFER, and
`RelayDoor` stores one `file_relay_chunks` row per chunk, so a 10 MiB deposit declared with
`chunkSizeBytes = 1` means ten million rows; the floor is a storage-amplification bound on attacker-chosen
granularity; (b) the transport sanity assert in `wiring/FileLimits.kt:26`; (c) borrowed as the large-path
frame floor (`FileLimits.kt:30`) — a misuse, see below. Not config, for three agreed reasons: a
receiver-protection bound is a format contract, not a preference (a user knob would read "let strangers
choose my row amplification"); there is **no negotiation and no fallback granularity** — a receiver with a
higher floor refuses every small-path transfer *and serve* from that sender (a serve re-runs the push
engine with the sender's chunk size), so unlike 2.8.2 there is no escape route, and a value that must be
identical everywhere with no fallback is a shared constant, moving only with the format; and deriving it
would require inventing a ratio — the 2.8.3 mistake. Cleanup riding along: the definition in FileIds.kt:46
gets a KDoc stating the amplification rationale (the current rationale hides on `isAcceptable`), and the
large-path frame floor is **dropped** in the large rewrite — that path stores no rows, its window and
pacing bound it, and the transport payload limit is the real bound.

**2.8.5 `MAX_ROUTING_HEADER_BYTES` stays, in the key-length category.** The owner's categorization:
decode-side allocation guards are where the format's split has to happen — *technically they are
invented, like 32-byte keys, but we need to do the split at some point, and there is no point changing
the header's size.* No change.

**2.8.6 The magic timings become config.** From the audit: the large path's 300 ms answer grace,
50/20 ms pacing loops, 100 ms defer retry, 1 s parked-channel check (FileFetchService.kt:395, 811, 852,
999, 1005) become `RouterConfig` fields in the large rewrite; the sweep floors `maxOf(chunkAckTimeout,
10.seconds)` (SealRegistry.kt:150, RelayForwards.kt:92), the engine tick's `chunkAckTimeout / 3` with its
5 s floor (TransferEngine.kt:176), and `TargetSide`'s hardcoded 5-minute sweep (TargetSide.kt:280) are
replaced by one stated sweep cadence field (feeding the three sweeps; the tick may derive from it) —
local mechanics, kind 4, deliberately not in `FIELDS`. Constants left alone as legitimate: the block rule
and `fileId` version tag, `FileRequest.MAX_MISSING_RANGES` (wire payload shape),
`CHUNK_OVERHEAD_BYTES`, key lengths, `MIN_FILE_CHUNK_SIZE_BYTES`.

Lands in step 6 (and the pacing fields with step 7).

### 2.9 Point 9 — the orchestrator is size-blind

Three seams, one principle: *the orchestrator should not care about the sizes.*

- **`pushFile` is always called.** `FileSendService.startPushes` drops its `SMALL_FILE_MAX_BYTES` skip
  (FileSendService.kt:169); `SealRegistry.push` no-ops silently above the configured limit. The
  `FileCarrier.pushFile` KDoc states it: above the limit a silent no-op — a large file is pull-only, not a
  failed push; no `FileEvent.Failed`.
- **The ingest trigger's only size gate is `autoDownloadMaxBytes`** — a per-device USER setting about the
  user's attention and disk, *orthogonal to the router's class boundary* (the owner: *autoDownloadMaxBytes
  has nothing to do with whether the file is large or small according to the router; the orchestrator
  either wants the file immediately or waits for the user's permission*). `onIngest`
  (FileRequestCoordinator.kt:211-219) collapses to: at or below it, request; above it, only a user click.
- **The first-attempt schedule moves into routing, and the seam carries only the trigger's origin.**
  `FileCarrier.startFilePull(fileId, immediate = false)`. Automatic triggers (message ingest) omit the
  flag: routing pins the class by the file's total bytes (read through the port, as `FilePullProcessor`
  already does) and schedules — small files wait `filePullDelay`, the window given to the direct push
  before asking (the OFFER may be in flight or never come; the wait is the only thing telling "late"
  from "never" and errs on the quiet side), large files start at once (nothing is ever pushed).
  Explicit triggers set the flag for `now` at any size — the user's accept click and the stall re-open
  (FileRequestCoordinator.kt:263, 336-340) — because the push window exists to let an automatic
  trigger's OFFER arrive, not to delay explicit intent. The flag expresses who triggered the ask, never
  a size, so the orchestrator stays size-blind (it is today's immediacy semantics minus the size
  branch). `fileRequestFirstAttemptDelay` moves from `OrchestratorConfig` to `RouterConfig` as
  `filePullDelay` — it is push/pull coordination between two mechanisms routing owns.
- **`pending_file_requests` stops scheduling, aligned with syncs.** The sync precedent settles it: the
  sync coordinator passes one timestamp at creation (`now + syncGracePeriod`,
  DefaultSyncCoordinator.kt:305) and plain `now` at the startup re-fire (line 70 — *all* ids, no due
  filter), and routing's `sync_attempts` owns everything after; nothing schedules off the orchestrator's
  rows. Files follow: `next_attempt_at`'s only consumer was the coordinator sweep's due filter
  (FileRequestCoordinator.kt:275, 302); that re-arm becomes unconditional per pass — idempotent on
  routing's side, since `attempts.start` keeps an existing row's state, so a re-arm costs at most a
  `wake()` — and the pending row keeps only accept/decline/PENDING state for the user. The schedule
  lives in routing's attempt row alone.
- **`copyIntoStoreMaxBytes` is removed from the core** — it only decided the GUI's "keep a copy"
  checkbox default, a user-interface preference rather than network or storage policy. The send API's
  `keepCopy` is now a required choice the GUI supplies; the core never defaults it.

**Deliberate behavior change, confirmed:** today *small* files auto-request regardless of
`autoDownloadMaxBytes` (FileRequestCoordinator.kt:212 — the small branch never checks it); after the
flatten, **all** auto-fetching is gated by it, so a user who sets it below the small limit is asked before
small files download too. Confirmed intentional: it is the user's decision, nothing inherent in the
network.

An earlier candidate for this point — exposing the limit to the orchestrator through a
`FileCarrier` read — was dropped when the immediacy decision moved into routing: with the schedule and
the class pin both routing's, `smallFileMaxBytes` never crosses the seam at all.

Lands in step 6.

### 2.10 Point 10 — serial fanouts left alone on purpose

`TargetSide.endReceive` (TargetSide.kt:224-240) loops transfers sending control packets one by one, and
the sweeps lock per-row: left as they are. They are politeness traffic, low volume, individually guarded;
adding concurrency there buys nothing and risks ordering bugs. `RelayDepositor`'s "only used in
OutboxProcessor" TODO and the WebRTC link-keeper TODOs (DefaultRouter.kt:173, 216) are outside this
cleanup's scope. Agreed by omission in the review iteration.

### 2.11 Point 7's sibling discussions — coroutine discipline (point 9 of the review) and the large-path split (point 11)

**2.11.1 Coroutine discipline: nothing slow on the shared path.** The owner's rule, which the cleanup
implements structurally: *route all sends through separate coroutines — they can hang or freeze, so make
sure nothing blocks the whole loop — unless it is just an enqueue, which is fast and involves no actual
send.* Concretely: `outbox.enqueueAndWake` stays inline (a DB write plus a channel nudge — the normal path
every handler already uses); anything touching crypto, plaintext file IO or transport runs in its own
guarded coroutine (the existing catch-and-log `guarded` pattern). The session shape makes this
structural: each transfer's slow work already lives in its own coroutine (2.5.1), and the seal registry's
execute phase launches per device (2.6). No per-call timeouts on top — agreed with the backstop reasoning
in 2.6.

**2.11.2 The large-path split.** `FileFetchService` (~1005 lines, but with clean internal seams) becomes
`file/large/`:

- **`LargeFileService`** — the thin composition face `FileService` and the large strategy already talk to.
- **`LargeFileMux`** — the channel→session maps, the lock, `routeFrame`/`routeEvent`, the transport
  subscription. Dispatch only.
- **`LargeFileRequester`** — the promoted, named `RequesterActor` (FileFetchService.kt:55-77) plus its
  loop: `PULL_OK` handling, appends at P, window enforcement, settle, `abortLargeFile` lands by map
  lookup.
- **`LargeFileHolder`** — the promoted `HolderActor` plus the serve loop, `PROGRESS`, cleanup.
- **`LargeFileAdmissionGate`** — `gateAndServe`'s peer/account/status/quota checks (~120 lines), named for
  vocabulary parity with `RelayDoor`'s admission.
- **Shared channel IO** — `sendDeclined`/`sendAbort`/`sendBestEffort`/`closeQuietly`.

The two sides share only frame types and the transport, so this cuts along a real seam. The pacing
constants become config here (2.8.6); the frame floor is dropped here (2.8.4). Agreed with the owner's
caveat that the split *comes out of the design and may flex a bit as it is implemented* — the five-name
shape above is the agreed starting point, not a straitjacket; what may not flex is the seam itself
(never merge requester and holder state into one class, never route large-path frames through the
envelope pipeline).

Lands in step 7.

### 2.12 Cross-cutting: standards confirmations from the maister check

The maister docs were read against the agreed design; the design agrees, and the check surfaced items now
recorded so they are not "found" again:

- **The standards endorse two of the owner's own TODOs**: `RoutingContext` "carries no stores, engines or
  policies" (composition-root standard) confirms the `FileControl` extension was a hijack (2.3); the
  .editorconfig star-import standard makes the branch's `.*` imports a violation to fix in step 1's
  rewrites.
- **The "sizes are not router config" rule (router.md) and `RouterConfig.smallFileMaxBytes` coexist** —
  the first is about framing-derived sizes, the second is policy. The distinction is now written into
  router.md and design.md §15 (2.8.1) so the rules read together without contradiction.
- **The design of record used the FETCH vocabulary** (envelope-redesign.md §4/§5.9): the rename is
  wording-only, wire values unchanged, and envelope-redesign.md is updated in the same change (2.5.3).
- **Governance touchpoints**: `ArchitectureTest`'s `queryOwners` map and guide §4's ownership table follow
  the table rename (also fixing the stale "transferId-grain" wording — the attempts are file-grain);
  codec round-trip tests update in place for `LargeFileFrame` (no new wire types); everything stays
  `internal`, so `PublicApiSurfaceTest` is untouched.

### 2.13 File layout: data types group into `[Concern]Types.kt`

Agreed convention, applied decisively to the branch (it sharpens the structure standard's two-mode
scheme, which the codebase already follows for behavior but never applied to data):

- **Behavior — classes and interfaces that do work — keeps file-per-type**, PascalCase, unchanged.
  A file holding two behavior types splits: `FileRequestProcessor.kt` becomes `FilePullProcessor.kt` +
  `FileRequestStrategy.kt`.
- **Data-only types — sealed event/state vocabularies, enums, row/data classes, result types — go in
  one `[Concern]Types.kt` per concern.** The owner asked whether a single `FileTypes.kt` is too broad;
  it is not: `RoutingTypes.kt` already spans the whole router's vocabulary in one file, and five
  per-sub-package Types files would recreate the floating-file problem the rule exists to end.
- **A data class used by exactly one behavior type may live in that type's file** — but first suspect it
  as an unneeded wrapper (this cleanup's standing bias); most single-consumer data classes are.
- **Wire/protocol concept files stay**: `FileHeader.kt` (header + `TransferShape` + `FileSeal`), the
  renamed `LargeFileFrame.kt`, `FileIds.kt` — a codec beside the wire vocabulary it owns is the
  structure standard's "name the concept" mode, not sprawl.

The concrete moves in this branch:

- `routing/file/port/FilePortTypes.kt` — the orchestrator-facing seam types (`FileEvent`,
  `FileFailureReason`, `FileReceiveEnd`, `FileSendState`, `FileSendStatus`, `FileManifest`,
  `FileCoverage`): they stay in the port package so the orchestrator's import surface does not grow
  beyond `routing/file/port` (`FileCarrier.kt` shrinks to the interface, `FileManifest.kt` dissolves).
- `routing/file/FileTypes.kt` — the file concern's internal vocabulary: the transfer vocabulary
  (`TransferEvent` out of `TransferEngine.kt`; `TransferOffer` out of `ChunkSource.kt`, which keeps the
  source hierarchy — behavior; `IncomingHop` out of `ChunkSink.kt`, which keeps the interface), and the
  large path's `LargeFileResult` (`FetchResult.kt` dissolves). `FileFetchLabels.kt` is a one-constant
  file whose constant — the holder's buffered-amount resume watermark — becomes one of §2.8.6's pacing
  fields; the file dissolves in step 7.
- `routing/store/FileStoreTypes.kt` — the six file stores' enums and row types (`OutgoingSeal`,
  `FileSealKind`/`FileSealStatus`, `OutgoingTransfer`, `FileTransferStatus`, `IncomingSeal`,
  `IncomingTransfer`, `RelayTransfer`, `FileRelayStatus`, `AdmitResult`, `FilePullAttempt`,
  `FilePullClass`); each `File*Store.kt` keeps its interface + `Default*`. Row types stay in
  `routing/store` — column-mapped types live with the owning store's package (the `OpkStatus` precedent,
  guide §4). `SyncAttemptStore.kt` keeps its one row type: one type does not justify a new file.

Lands in step 1 — the renames rewrite every one of these files already.

## 3. The change steps

Each step lands test-green on its own, docs riding along per the guide's same-change rule. Steps 1-3 are
mechanical; 4-5 carry the behavior risk; 6-7 are the seams and the split. The agreed detail lives in §2;
the steps say only what moves when.

### Step 1 — Renames (§2.1, §2.5.3, §2.8.2's constant deletion deferred to step 6)

Store/component fields per §2.1; the pull umbrella and the `Large*` set per §2.5.3 and the rename table
(§4), including protocol (`LargeFileFrame` with `PULL`/`PULL_OK`/`DECLINED`, wire values unchanged); the
table rename amends the branch's `6.sqm` directly — no migration file while the branch is unmerged
(§2.5.3); the type-file consolidation per §2.13; expand the star imports while rewriting these files.
`SMALL_FILE_MAX_BYTES` and `MAX_SEAL_BYTES` are deleted **with** their replacement uses in step 6, not
before — one step, not two half-steps.
Gate: the full existing suite, unchanged (renames and file moves only).

### Step 2 — `RouterService` (§2.2)

The interface per §2.2; the three services implement it; `DefaultRouter` dispatches by declared tag kinds;
the `PresenceService` TODO lands.
Gate: `DefaultRouterOutboxIntegrationTest`, the live integration suites.

### Step 3 — `FilePackets` (§2.3)

The four builders per §2.3; the extension file deleted; `RoutingContext` untouched.
Gate: `FileSmallPushTwoRouterTest`, `FileRelayThreeRouterTest`, `FileInboundHandlerTest`.

### Step 4 — Transfer sessions + mux (§2.4, §2.5.1, §2.11.1's structural isolation)

`OutgoingTransferSession` and the shrunken engine per §2.5.1; `start` per §2.4; recovery on start
unchanged in semantics.
Gate: `TransferEngineTest`, the `SealTransferHarness` suites, both file router suites — behavior
unchanged.

### Step 5 — Seal registry two-phase fanouts (§2.6)

Plan/execute split, in-flight guard, per-device guarded launches, no added timeouts.
Gate: `SealRegistryTest`, `SealDepositTest`, the router suites.

### Step 6 — Limits and config (§2.7, §2.8, §2.9)

`RouterConfig.smallFileMaxBytes` as a NETWORK field and receiver enforcement; `SMALL_FILE_MAX_BYTES` and
`MAX_SEAL_BYTES` deleted here with their replacement uses; `maxSealBytes` in the `wiring/FileLimits` chain
with the `ConfigInvariants` assert; `isAcceptable`/`FileSeal` require narrowed; the orchestrator seam per
§2.9 (`pushFile` always, `onIngest` flattened with its confirmed behavior change,
`startFilePull(fileId, immediate)` with the trigger flag, `pending_file_requests` descheduled,
`filePullDelay` moved, `copyIntoStoreMaxBytes` removed (the GUI owns the checkbox default)); store-owned candidate views
per §2.7 (with the `CandidateAttemptStore` KDoc stating its nature, plus the TODO to reconsider its
name); sweep/tick cadence fields per §2.8.6; the `MIN_FILE_CHUNK_SIZE_BYTES` KDoc.
Gate: `FileStoreSemanticsJvmTest`; all file suites.

### Step 7 — The large path split (§2.11.2)

The five classes per §2.11.2; pacing → config; frame floor dropped.
Gate: `FileLargeFetchTwoRouterTest` (opt-in natives as usual); the fetch unit tests renamed with the
package.

## 4. The rename table

| Today (pre-cleanup)                                       | Steady state                                        |
|-----------------------------------------------------------|-----------------------------------------------------|
| `FileRequestProcessor`                                    | `FilePullProcessor`                                 |
| `FileRequestAttemptStore` / `FileRequestAttempt` / `FileRequestClass` | `FilePullAttemptStore` / `FilePullAttempt` / `FilePullClass` |
| `FileCarrier.startFileRequest(fileId, firstAttemptAt)`    | `startFilePull(fileId, immediate = false)`         |
| `FileRequestStrategy` (small plug)                         | unchanged (names the REQUEST wire packet)           |
| `FileFetchStrategy`                                       | `LargeFileStrategy`                                 |
| `FileFetchService` (package `file/fetch`)                 | `LargeFileService` (package `file/large`)           |
| `FetchResult` / `FileFetchLabels`                         | `LargeFileResult` / renamed with the package        |
| `FileCarrier.abortFileFetch`                              | `abortLargeFile`                                    |
| `RouterConfig.fileSessionOpenTimeout` / `fileStreamWindowBytes` | `largeFileSessionOpenTimeout` / `largeFileStreamWindowBytes` |
| `RouterConfig.maxConcurrentFetches`                       | `maxConcurrentLargeFiles`                           |
| `FileLimits.fetchFrameBytes`                              | `largeFileFrameBytes`                               |
| `FileFetchFrame` kinds `FETCH`/`FETCH_OK`/`FETCH_DECLINED` | `LargeFileFrame` kinds `PULL`/`PULL_OK`/`DECLINED` (wire values unchanged) |
| `FileFetchAbortReason` / `FileFetchDeclineReason`          | `LargeFileAbortReason` / `LargeFileDeclineReason`   |
| `file_request_attempts` / `file_request_declined_devices`  | `file_pull_attempts` / `file_pull_declined_devices` (one `.sqm`) |
| `protocol.SMALL_FILE_MAX_BYTES`                           | deleted — `RouterConfig.smallFileMaxBytes`          |
| `protocol.MAX_SEAL_BYTES`                                 | deleted — `FileLimits.maxSealBytes` (derived)      |
| Engine/registry store fields `seals`/`transfers`/`relays`  | `sealStore`/`transferStore`/`relayStore`             |
| `TargetSide.held` (the `FileRequestSource` port)           | `requests`                                          |

Unchanged on purpose: `FileHeader.Request`, `FileRequestStrategy`, `OutboxTag.Kind.FILE_REQUEST`,
`fileRequestAttemptTimeout`/`fileRequestWaitTimeout`, `FileRequestSource` (see §2.5.3 for each reason).

## 5. Governance and docs touched in this change

- `ArchitectureTest` `queryOwners` and guide §4's ownership table: the renamed tables (and the stale
  "transferId-grain" wording fixed to file-grain).
- Codec round-trip tests updated in place for `LargeFileFrame`; everything stays `internal` —
  `PublicApiSurfaceTest` untouched.
- Docs: this file; [`design.md`](design.md) (intro review note, §1, §6.6, §9, §12-§13, §14-§16, §19);
  [`../envelope-redesign.md`](../envelope-redesign.md) (§4, §5.2, §5.9, §11 note);
  [`../guide.md`](../guide.md) §4 table; [`../router.md`](../router.md) (service table, package layout,
  DefaultRouter notes); both `.mmd` diagrams — all already updated to the steady state as part of writing
  this plan.
- maister standards (`/maister/standards-update`) once the cleanup lands: store-owned child-table views,
  the four-kinds-of-number taxonomy, per-transfer sessions over shared serial loops,
  stores-named-for-types / components-for-concerns, and data types into `[Concern]Types.kt` (behavior
  keeps file-per-type).

## 6. Deferred (not in this cleanup)

- `PresenceService` conforming to `RouterService` (the TODO lands with step 2).
- Orchestrator-side umbrella naming (`FileRequestCoordinator`, `pending_file_requests`) — the same
  vocabulary drift on the other side of the seam; a follow-up outside this routing-focused pass.
- The GUI file surface (guide §8's not-done list) — untouched by this cleanup.
- A relay-server fallback for no-direct-connection pairs (design.md §18).
