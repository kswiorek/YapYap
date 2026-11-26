# Codebase Analysis Report

**Date**: 2026-11-26
**Task**: Review FileService TODOs (user-flagged) and find similar issues across the FileService dependency tree; goal is a clean, easily understandable implementation — deliverable is a SKETCH of changes to agree on, not full code.
**Analyzer**: codebase-analyzer skill (3 Explore agents: File Discovery + Code Analysis, Context Discovery, Pattern Mining)

## TLDR

All 13 user-flagged TODOs in the routing/file tree are cataloged and decoded; 14 additional unflagged issues of the same families (duplication, naming collisions, insert-then-reread, extension-on-controlled-type, star imports) were found. The tree is behaviorally sound and strongly tested per-module (≈100+ tests), so this is a readability/ownership cleanup, not a defect hunt — except two genuinely structural items: SealRegistry's serial fan-outs under its mutex (latency), and TransferEngine's insert-then-reread `start()`. MessageService is the reviewed sibling template to converge on. Risk is contained: everything in routing.file is internal, invisible to the GUI, and constrained only by envelope-redesign.md §5.8/§5.10 and the port package (which is a design commitment and should NOT be reshaped).

## Key Decisions

- Keep the port package (file/port/) untouched — envelope-redesign.md §5.10 says "the seam was in the right place"; only the internal shape of routing.file may change.
- Converge FileService's shape on MessageService (162 lines, reviewed) — same kernel seams (`offerOutcome` + `runIn`), private event flows, lifecycle gating — rather than inventing a new structure.
- Order work from pure consolidation → naming/ownership → engine untangle → async fan-outs → FileFetchService split — each phase lands independently behind a strong test net.
- Fix TransferEngine/SealRegistry by untangling existing code, not rewriting — TransferEngineTest (7, incl. restart-rebuilds-queued) and SealRegistry/SealDeposit tests over real SQLite pin the behavior.
- Treat docs as part of the change: docs/router.md:48 is already stale ("push/" doesn't exist); envelope-redesign.md §5 must be updated in the same change per project standards.

## Open Questions / Risks

- **FileService has no dedicated unit test** — its wiring (event flow buffer, `offerOutcome` dispatch, `runIn` job list, `requireStarted` guards, the race-sensitive "owners subscribe before engine runs" contract) is only protected transitively by multi-router jvmTests. If any sketch touches FileService's wiring, those multi-router tests must stay green — consider adding a dedicated FileService harness first.
- **FileService.kt:154 "why not override?" has two readings** (see Conflicting Findings) — needs operator decision before the sketch: delete the TODO and accept the mailbox idiom, or introduce outcome consumption on a shared carrier interface.
- SealRegistry async fan-outs move per-device crypto *outside* the mutex — correct direction, but lock-semantic changes are the most race-sensitive item in the plan; the real-SQLite SealRegistryTest/SealDepositTest harness is the safety net.
- FileFetchService is 1007 lines and needs splitting more than rewriting — the split boundary (requester/actor/credit-window vocabulary) should be agreed in the sketch, but the protocol logic itself reads sound.
- Suggested-order step (e) (FileFetchService split) is the only step with meaningful refactor-without-behavior-change risk; everything before it is mechanical.

---

## Summary

The routing/file tree is a well-tested, internally-scoped file transfer subsystem built on the envelope redesign (§5 of docs/envelope-redesign.md, the design of record). The user's 13 TODOs are accurate self-diagnoses that cluster into five families: (1) naming/ownership confusion around `seals`, (2) an extension property on the controlled `RoutingContext` type, (3) insert-then-reread round trips in TransferEngine and RelayDoor, (4) serial per-device fan-outs holding SealRegistry's single mutex, and (5) a "why not override?" question about the outcome-delivery seam. Pattern mining found the same five families duplicated in 14 unflagged places, plus a canonical in-repo template (MessageService) showing the intended shape for nearly all of them. The messiest spots, ranked, and a suggested implementation order are given below. The deliverable the user asked for — a change sketch to agree on, not code — is well supported: every proposed change has a reviewed sibling example, an explicit constraint from the design docs, or a pinning test.

---

## Files Identified

All source paths relative to `core/src/commonMain/kotlin/org/yapyap/` unless noted.

### Primary Files (routing/file tree)

| File | Lines | Role |
|---|---|---|
| `routing/file/FileService.kt` | 176 | Second composition root: 8 components, 14–16 constructor params; owns the outcome mailbox loop; TODOs :83, :154 |
| `routing/file/FileControl.kt` | 22 | The flagged `RoutingContext.fileControl` extension; wraps ctx.envelope with file boilerplate |
| `routing/file/FileRequestProcessor.kt` | 252 | SMALL-path outbox REQUEST machinery; holds two classes; anonymous dock at :152-181; duplicated emission at :236-251 |
| `routing/file/FileFetchStrategy.kt` | 152 | LARGE-path entry; second anonymous dock :47-71; `emitBestEffort` :138-151 |
| `routing/file/FileFetchLabels.kt` | 4 | One-constant file, foldable |
| `routing/file/seal/SealRegistry.kt` | 418 | Seal ownership at origin + push/cancel/serve; TODOs :70, :124; mutable dedup map :331; `newTransfer` builder :311-323 |
| `routing/file/transfer/TransferEngine.kt` | 374 | Origin-side transfer actor over ChunkSource; TODOs :64, :93; holds TransferEvent + engine (file-per-type inconsistency) |
| `routing/file/transfer/ChunkSource.kt` | 97 | Chunk read abstraction |
| `routing/file/relay/RelayDoor.kt` | 103 | Admission/budget for relayed chunks; insert-then-reread at :85-95 |
| `routing/file/relay/RelayForwards.kt` | 126 | Relay forward path; duplicated `OutgoingTransfer` builder :46-58; third `guarded()` :112 |
| `routing/file/inbound/TargetSide.kt` | 282 | Inbound target handling; two answer paths (:261 reply vs :264 send) |
| `routing/file/inbound/FileInboundHandler.kt` | 87 | Kind-dispatch: OFFER/CHUNK/ACK/CANCEL/COMPLETE/REQUEST |
| `routing/file/inbound/FileServeHandler.kt` / `ChunkSink.kt` | — | Serve request handling / chunk sink |
| `routing/file/fetch/FileFetchService.kt` | 1007 | LARGE-path requester/holder actors, credit window; the biggest file in the tree |
| `routing/file/fetch/FetchResult.kt` | — | Answer/FetchResult vocabulary |
| `routing/file/port/*.kt` (6 files: FileCarrier, FileIngestPort, FileRequestSource, FilePlaintextSource, FileManifest, …) | — | Orchestrator-facing seam — **design commitment, do not reshape** |

### Related Files (kernel, siblings, constraints)

| File | Relationship |
|---|---|
| `routing/router/DefaultRouter.kt` | Sole constructor site of FileService (:227-245); `Router.files` :282; `routeOutcome → offerOutcome` :443; TODOs :173 (webrtc `ready = flowOf(true)`), :216 (presence vs webrtc keeper) |
| `routing/router/RoutingTypes.kt` | Second extension-on-controlled-type: `DeviceDirectory.isKnownNonBanned` :207 |
| `routing/router/OutcomeMailbox.kt` | The established outcome-delivery idiom (relevant to TODO :154) |
| `routing/message/MessageService.kt` (162) | **Primary template** — reviewed, maps 1:1 onto FileService's intended shape |
| `routing/bootstrap/BootstrapService.kt` (61) | Secondary template, minimal ceremony |
| `routing/message/…/MessagePullProcessor.kt` | Best shared-machinery plug-in (private strategy over CandidateRequestCore); carries its own unflagged TODO :110 |
| OutboxProcessor, OutboundMessenger, RelayDepositor, PeerLinks, CandidateRequestCore, RetryLoop, PingProvider, PeerAvailabilityRegistry | Kernel/shared machinery; OutboundMessenger shows the concurrent fan-out pattern SealRegistry should emulate; RelayDepositor shows the "tiny internal sender class" pattern for FileControl |
| `NodeGraph.kt` (orchestrator) | FileCarrier consumers: DefaultFileRequestCoordinator (:321), DefaultFileSendService (:377); ports implemented :240-262 |
| `docs/envelope-redesign.md` | **Design of record** for file structure (§5, §5.8 routing prescriptions, §5.10 orchestrator contract) |
| `docs/router.md` | Layout line 48 is stale (references non-existent "push/") |
| `docs/file/design.md` | Superseded for the small path by envelope-redesign.md |

---

## Current Functionality

### Key Components / Flows

- **Push path**: `FileService.pushFile` → `SealRegistry.push` (serial per-device `createSeal` under the registry mutex) → `TransferEngine.start` → OFFER → OutboxProcessor → ACK → `settle` → chunk window → Gone → deposit → RelayDoor/RelayForwards → HANDED_OFF/DELIVERED.
- **Inbound path**: `FileInboundHandler` dispatches by FileHeader kind: OFFER→TargetSide/RelayDoor, CHUNK→sink, ACK→engine, CANCEL→engine/target/door, COMPLETE→seals, REQUEST→FileServeHandler→SealRegistry.serve.
- **Fetch path**: FileRequestProcessor (SMALL via CandidateRequestCore+FileRequestStrategy, outbox REQUEST) / FileFetchStrategy (LARGE via webRtcLinks.await → FileFetchService.fetch → RequesterActor/HolderActor, credit window).
- **Outcome spine**: OutboxProcessor.onDisposition → outcomes flow → DefaultRouter collector → `routeOutcome` → `fileService.offerOutcome` → OutcomeMailbox → FileService.runIn loop → engine.onOutcome / requestProcessor.onAttemptOutcome.

### User-Flagged TODOs (all 13, decoded)

| # | Location | TODO | Decoded issue → fix direction |
|---|---|---|---|
| 1 | FileService.kt:83 | "seals object both here and inside the SealRegistry, confusing" | `FileSealStore` is a dependency of both TransferEngine and SealRegistry; `seals` means three things (FileService.seals = SealRegistry; SealRegistry.seals & TransferEngine.seals = FileSealStore). → Single ownership: engine shouldn't take the seal store directly; get ChunkSource/factory from the owner; rename to `*Store` convention. |
| 2 | FileService.kt:154 | "why not override?" | `offerOutcome` is a public method on concrete FileService, not on FileCarrier; DefaultRouter.routeOutcome dispatches to concrete fields. **Two readings — see Conflicting Findings.** |
| 3 | FileControl.kt:14 | "no extensions for types we control, context hijacked" | `RoutingContext.fileControl` is the ONLY extension on RoutingContext in the codebase; violates RoutingContext's KDoc contract ("No stores, engines, or policies"); used by TransferEngine.cancel, TargetSide.reply/send, RelayDoor.accept. → Member on owning component, or small internal sender type (RelayDepositor / OutboundMessenger.buildEnvelope are the in-repo examples). |
| 4 | TransferEngine.kt:64 | "logic seems ok, but messy, to be rewritten" | Two locks (registryLock + per-transfer Mutex); `withLive` does lock-get/DB-reread/recover/end; `settle` called from five places; `tick` uses labeled returns in lambdas (:206); tag↔transferId implicit encoding (`tag.id == transferId.value`) shared across three files. → Untangle, don't rewrite (behavior pinned by tests). |
| 5 | TransferEngine.kt:93 | "untangle nested lambdas: insert transfer, check things, look it up in the db again" | `start(transfer)` inserts the row, then `withLive` re-reads it from the store and may run full recover — though the caller holds the fresh row. → Construct Running state directly, call sendOffer without the DB round-trip. |
| 6 | SealRegistry.kt:70 | "async fanouts" | `push()` loops serially over devices under the single registry mutex; `createSeal` does expensive per-device crypto (generateSealKey + protection.seal) under the lock, stalling serve/cancel/settle/deposit/sweep. → Fan out per-device work concurrently outside the mutex (state changes stay serialized). Template next door: OutboundMessenger's `coroutineScope { peers.map { async } }.awaitAll()`. |
| 7 | SealRegistry.kt:124 | "async fanouts" | Same family for `cancel()`. |
| 8 | DefaultRouter.kt:173 | `ready = flowOf(true) //TODO webrtc health` | WebRTC LinkKeeper gated on always-ready; Tor keeper maps transport health. → Health signal from WebRtcTransport. (Router-level, separate change.) |
| 9 | DefaultRouter.kt:216 | `//TODO consider webrtc link keeper` | Presence nudges only the Tor keeper; decide whether presence's online evidence should nudge the WebRTC keeper, or record the deliberate no. |
| 10 | BootstrapSender.kt:54 | keying bootstrap outcomes by domain id like FILE_REQUEST's transferId | Bootstrap tags use packetId (resends get new ids; OutboxProcessor.isPendingBootstrapTo scans all rows); FILE_REQUEST mints a domain uuid. → File's pattern is the better one; adopt it. |
| 11–13 | MessagePullProcessor.kt:110 (SyncAttemptDock wrapper / AttemptStore rename); PeerLinks.kt:84 (peer-keyed tor sessions); RelayDepositor.kt:15 (used only by OutboxProcessor — can be private/moved) | Unflagged-in-this-review TODOs in adjacent kernel code. Agent 3 recommends **leaving PeerLinks:84 and RelayDepositor:15 alone**; MessagePullProcessor:110 is worth folding into the consolidation step. |

### Similar Unflagged Issues (14 — the "find similar issues" deliverable)

1. **Triplicated `guarded()` helper** — TransferEngine.kt:360, SealRegistry.kt:404, RelayForwards.kt:112, plus hand-rolled inline copies in TargetSide.runIn and FileFetchService.runIn collectors.
2. **Duplicated best-effort event emission** — FileRequestStrategy.onExhausted (FileRequestProcessor.kt:236-251) inlines what FileFetchStrategy.emitBestEffort (FileFetchStrategy.kt:138-151) has as a method.
3. **Two anonymous CandidateAttemptStore "docks"** — FileRequestProcessor.kt:152-181 vs FileFetchStrategy.kt:47-71, ~12 methods each, one carrying dead stubs. Both should become private strategy classes on the MessagePullProcessor model.
4. **Duplicated OutgoingTransfer construction** — SealRegistry.newTransfer (:311-323) vs RelayForwards.onWhole (:46-58), identical defaults.
5. **Star imports** — FileFetchService.kt:3,12 (plus CandidateRequestCore.kt:3, RetryLoop.kt:3, PeerAvailabilityRegistry.kt:3, PingProvider.kt:3, MessagePullProcessor.kt:9, BootstrapService.kt:13 outside the tree) — explicit coding-style violation.
6. **Late-bound mutable scope in FileFetchService** — `private var scope: CoroutineScope? = null` (:122), `checkNotNull(scope)` at :197, :283, :758; also the only component taking ctx pieces piecemeal instead of `ctx: RoutingContext` (FileService unpacks at :90-100).
7. **Duplicated Answer/FetchResult vocabulary + grace-window lambdas** — runRequester handles FetchOk/Declined/Abort twice (300ms grace loop :394-433 nested in withTimeoutOrNull/for/when/for, plus normal Frame branch :435-452).
8. **Copy-pasted try/catch wall in gateAndServe** — FileFetchService.kt:650-772, four near-identical catch-log-decline blocks; serveLoop :774-902 is 128 lines.
9. **RelayDoor.accept insert-then-reread** — RelayDoor.kt:85-95: stores chunk then re-reads deposit + hand-computes coverage with a while over a hash set — same shape as TransferEngine.kt:93.
10. **Two answer paths in TargetSide** — reply (:261-262, InboundSideffect.EnqueueDirect — sic, EnqueueDirect) vs send (:264-277, outbox.enqueueAndWake with own try/catch).
11. **SealRegistry.reported mutable dedup map** — :331, written :346, O(n) prune inside the mutex :251 — implicit ownership of "which account events were emitted".
12. **Second extension-on-controlled-type** — `DeviceDirectory.isKnownNonBanned` (RoutingTypes.kt:207), used at RelayDoor.kt:47 and MessageInboundHandler.kt:79. Same family as the flagged FileControl TODO.
13. **FileService is a second composition root** — 8 components, 14 params, vocabulary collisions; FileRequestProcessor.kt holds two classes; TransferEngine.kt holds TransferEvent + engine (file-per-type inconsistency vs project standards).
14. **One-constant file fetch/FileFetchLabels.kt** (4 lines) — foldable.

### Conflicting Findings (explicit)

**FileService.kt:154 "why not override?"** — the two agents disagree:

- **Agent 1 (structural reading)**: `offerOutcome` is an ad-hoc public method duplicated as the same seam on MessageService.kt:144 and BootstrapService.kt:48; DefaultRouter.routeOutcome dispatches to concrete fields. Fix direction: declare outcome consumption on a shared interface so the router stops naming concrete services.
- **Agent 3 (idiom reading)**: message and bootstrap services *deliberately* don't override — no interface method exists; the per-service mailbox loop is the established kernel seam, and the outcome reporting machinery is already correct (routeOutcome exhaustive when, offer never suspends). Fix direction: delete the TODO and document the idiom, OR adopt the shared interface — both readings are valid; **this is a decision point for the operator**, not a finding with one right answer.

Both agents agree the *mechanism* (mailbox + runIn loop) works and is tested via the multi-router suite; the question is purely whether to formalize the seam.

---

## Dependencies

### Imports (What the File Tree Depends On)

- **Routing ambient world**: ctx (RoutingContext), lifecycle (RouterLifecycle) — correct usage per KDoc, except the `fileControl` extension.
- **Orchestrator ports (3)**: FileIngestPort, FileRequestSource, FilePlaintextSource — all internal, implemented orchestrator-side in NodeGraph.
- **Routing-owned stores (5)**: including FileSealStore (file_seals), transfer/incoming/relay/request-attempt stores — each with a single writer (seals→SealRegistry, transfers→TransferEngine, incoming→TargetSide, relay→RelayDoor+Forwards, attempts→FileRequestProcessor), matching the one-writer-per-table convention.
- **Shared kernel** (consumed only — matches router.md "everything here serves at least two"): outboxProcessor, packetOutbox, relaySelection, peerPolicy, both LinkKeepers, maxIdlePoll.

### Consumers (What Depends on This)

- **FileService**: constructed ONLY at DefaultRouter.kt:227-245; exposed as `Router.files` (:282); FILE inbound handler registered (:257); `runIn` at start (:350); `routeOutcome → offerOutcome` (:443).
- **FileCarrier (port)**: consumed orchestrator-side by DefaultFileRequestCoordinator (NodeGraph.kt:321) and DefaultFileSendService (NodeGraph.kt:377).
- **Internal components**: SealRegistry ← TransferEngine/RelayDoor/FileServeHandler (via FileSealStore and serve); FileFetchService ← FileFetchStrategy; FileRequestProcessor ← FileService runIn loop.

**Consumer Count**: 1 construction site + 2 orchestrator consumers + DefaultRouter integration points.
**Impact Scope**: **Low externally / Medium internally.** ArchitectureTest restricts orchestrator imports to routing.router, routing.message.port, routing.file.port — reshaping routing.file internals is invisible to the GUI. Constraints: file queries must remain owned by routing.store Default* classes (queryOwners), and knownDebt is empty and must only shrink.

---

## Test Coverage (the safety net)

### Per-Module (STRONG)

- **SealRegistry**: SealRegistryTest (10) + SealDepositTest (6, incl. durable deposit claim) via SealTransferHarness over **real SQLite** — pins the engine/timing semantics SealRegistry TODOs will touch.
- **TransferEngine**: TransferEngineTest (7, incl. restart-rebuilds-queued-without-new-seals).
- **Relay**: FileRelayTest (18) + FileRelayThreeRouterTest (multi-router).
- **Fetch**: FileFetchServiceTest (15) + FileFetchStrategyTest (8) + transport-level FileChannelTransportTest/FileChannelInboxTest.
- **Inbound**: FileInboundHandlerTest (~27) + FileInboundServeTest (3) via FileInboundHarness.
- **Request path**: FileRequestProcessorTest (8); FileOutboxProcessorTest (4); FileStoreSemanticsJvmTest (3).
- **Composition net (multi-router jvmTests)**: FileSmallPushTwoRouterTest, FileRelayThreeRouterTest, FileLargeFetchTwoRouterTest, FileSmallPushLiveIntegrationTest.
- **Orchestrator side**: covered via NoopFileCarrier subclasses.

### Coverage Assessment

- **Test count**: ≈100+ across the tree.
- **Gap (important)**: **FileService itself has NO dedicated unit test** — only covered transitively via DefaultRouter in the multi-router tests. Its wiring (event flow buffer, offerOutcome dispatch, runIn job list, requireStarted guards, the race-sensitive "owners subscribe before engine runs" contract) is safe only while the multi-router tests stay in the loop. Any sketch step touching FileService's wiring should either add a dedicated harness or treat the multi-router suite as the gate.

---

## Coding Patterns

### Conventions (from guide.md, .maister standards, and the reviewed siblings)

- Router = lifecycle + one service per concern; router declares its own ports (file/port/ is the exemplar).
- File-per-type; `Area>Types.kt` vocabulary files; sealed vocabularies; exhaustive `when` with `else -> error()` only on open types.
- Stores suffixed `*Store` (the `seals` collision violates this — compare sibling `syncAttemptStore`).
- Constructor injection, no lateinit, no star imports; private `MutableSharedFlow` via `asSharedFlow()`; components subscribe to their own inputs; new outbox traffic gets a tag, not a callback path; exceptions cross boundaries as receiving-layer vocabulary.
- Per-account fan-out; one writer per table.
- Docs updated in the same change — envelope-redesign.md is design of record for file topics.

### Anti-patterns present in the tree (and in siblings — do NOT copy)

- Serial per-device loops under a shared mutex (SealRegistry.push/cancel) vs the correct sibling: OutboundMessenger's concurrent fan-out.
- One shared mutable event flow passed to three collaborators (FileService → SealRegistry/FileFetchService/FileRequestProcessor) vs MessageService's concentration of emission in the service class — **the message shape is easier to reason about; prefer it**.
- `internal` strategy classes where the sibling example is `private` (FileRequestStrategy vs MessagePullProcessor's private strategy).

---

## Complexity Assessment

| Factor | Value | Level |
|--------|-------|-------|
| File Size | 1007 lines (FileFetchService), 418 (SealRegistry), 374 (TransferEngine) | High |
| Dependencies | FileService constructor: 16 inputs | High |
| Consumers | 1 construction site; internal-only blast radius | Low |
| Test Coverage | ≈100+ tests per-module; FileService itself untested directly | Medium |

### Overall: Complex (internally), Low (externally)

The tree is large and internally dense, but the architectural seams confine the work: routing.file is internal, invisible to the GUI, and pinned by strong per-module tests. The complexity is in *reading* the code (naming collisions, duplication, insert-then-reread), not in hidden behavior risk — which is exactly what the user's goal ("clean and easily understandable") targets.

---

## Key Findings

### Strengths

- Every user-flagged TODO is a correct self-diagnosis; no TODO turned out to be wrong or already-fixed.
- Strong, harness-based test coverage per module (real SQLite for seals, restart tests for the engine, multi-router composition tests) — cleanup can proceed test-first.
- A reviewed, in-repo template (MessageService + MessagePullProcessor) exists for nearly every fix: naming, emission ownership, private strategies, concurrent fan-out (OutboundMessenger), sender classes (RelayDepositor).
- The port seam is proven: envelope-redesign §5.10 says the orchestrator contract survived the redesign "almost unchanged" — internal reshaping carries no GUI risk.

### Concerns

- FileService has no dedicated unit test; its wiring contract is race-sensitive and only transitively protected.
- SealRegistry's serial crypto under its single mutex is a real latency defect shape (not just readability) — pushes stall serve/cancel/settle/deposit/sweep.
- Tag↔transferId implicit encoding is shared across three files with no single owner.
- Two stale/superseded docs (router.md:48, file/design.md small path) plus the .maister docs already flag the file branch as provisional — this review is the roadmap's open item.

### Opportunities

- The duplication trio (docks, guarded(), emission, OutgoingTransfer builders) is pure consolidation: mechanical, test-pinned, immediately improves readability, and removes ~100+ lines before any riskier step.
- Converging FileService on the MessageService shape simultaneously resolves TODOs :83 and :154 and the composition-root vocabulary collisions.

---

## Impact Assessment

- **Primary changes (sketch scope)**: FileService.kt, FileControl.kt, SealRegistry.kt, TransferEngine.kt, RelayDoor.kt, RelayForwards.kt, FileRequestProcessor.kt, FileFetchStrategy.kt, FileFetchService.kt (split), FileFetchLabels.kt (fold), RoutingTypes.kt (second extension), TargetSide.kt (answer paths), docs (envelope-redesign.md, router.md).
- **Unchanged by design**: file/port/** (6 files — design commitment), NodeGraph/orchestrator (invisible via ArchitectureTest), PeerLinks.kt:84 and RelayDepositor.kt:15 TODOs (leave alone per Agent 3).
- **Test updates**: existing suites are the gate; new work needed only for a FileService harness (recommended before wiring changes) and for whatever SealRegistry fan-out concurrency requires.

### Risk Level: Medium

Readability consolidations are Low risk; the two items lifting overall risk to Medium are (a) SealRegistry lock-semantic changes (race-sensitive, though pinned by real-SQLite tests) and (b) FileService wiring edits with no dedicated test. The multi-router jvmTests are the composition net and must stay green at every step. knownDebt is empty and must only shrink — the sketch should not introduce new debt entries.

---

## Recommendations

The user asked for a sketch to agree on. The proposed sketch, in the order the agents converged on (each step lands independently):

### Phase A — Pure consolidation (mechanical, test-pinned, do first)
1. Extract the single `guarded()` helper; delete the two inline copies.
2. Unify best-effort event emission (one method, used by both strategies).
3. Replace both anonymous docks with private strategy classes on the MessagePullProcessor model; drop the dead stubs.
4. Share one OutgoingTransfer builder (identical defaults today).
5. Remove all star imports; fold FileFetchLabels.kt.
6. (Optional, adjacent) MessagePullProcessor.kt:110 SyncAttemptDock/AttemptStore rename.

### Phase B — Naming & ownership (the flagged FileService family)
7. Resolve the `seals` collision (TODO :83): `*Store` naming for stores, single ownership of FileSealStore, engine gets ChunkSource/factory from the owner.
8. Retire `RoutingContext.fileControl` (TODO FileControl.kt:14): member function or tiny internal sender class (RelayDepositor pattern); same treatment for `DeviceDirectory.isKnownNonBanned` if the operator agrees it's in scope.
9. Converge FileService wiring on the MessageService shape (private event emission in the service, KDoc ownership statements) — resolves the composition-root collision and prepares the :154 decision.
10. **Operator decision point — FileService.kt:154**: delete the TODO and document the mailbox idiom (Agent 3), or declare outcome consumption on a shared carrier interface (Agent 1). Both are internally consistent; the idiom reading preserves the proven kernel seam, the interface reading removes the router's dependence on concrete service types.

### Phase C — Engine untangle (behavior-preserving)
11. TransferEngine.start: construct Running directly, sendOffer without the DB round-trip (TODO :93); retire the labeled returns; name the tag↔transferId encoding once; split TransferEvent into its own file.
12. RelayDoor.accept: same insert-then-reread fix (unflagged issue #9).

### Phase D — SealRegistry async fan-outs (latency + readability)
13. push() and cancel() (TODOs :70/:124): per-device crypto concurrently outside the registry mutex, state changes serialized; emulate OutboundMessenger's async fan-out; also move the `reported` dedup map and its O(n) prune out of the mutex (unflagged issue #11).

### Phase E — FileFetchService split (agree the boundary in the sketch; biggest file)
14. Split requester/actor/credit-window/answer vocabulary into per-type files; unify the duplicated FetchOk/Declined/Abort handling; collapse the four catch-log-decline blocks; replace the late-bound `var scope` with lifecycle-passed scope and full-RoutingContext injection.

### Phase F — Router-level TODOs (separate changes, not part of this task's core)
15. DefaultRouter:173 webrtc health signal; :216 webrtc keeper vs presence decision; BootstrapSender:54 domain-id keying (adopt the FILE_REQUEST pattern).

### Sketch-level guardrails for the operator to confirm
- file/port/** stays untouched; envelope-redesign §5.8 prescriptions ("outbox carries no file logic", "one transfer actor over a ChunkSource", "one inbound handler over a ChunkSink", "seal registry owns seals, hops report") are treated as invariants — the sketch works *within* them, which the current code already respects structurally.
- Multi-router jvmTests green at every phase; consider a dedicated FileService harness before Phase B.
- docs updated in the same change; router.md:48 fixed; knownDebt only shrinks.

---

## Next Steps

1. Present this sketch (Phases A–F with the two decision points) to the operator for agreement — the user explicitly wants to agree on the sketch before code.
2. Resolve the two operator decisions: FileService.kt:154 (idiom vs interface) and whether RoutingTypes.kt's second extension is in scope.
3. Hand to gap-analyzer / specification with this report as input; Phase A is safe to plan immediately as it has no decisions attached.
