# Gap Analysis: FileService cleanup (routing/file TODOs + similar issues)

## TL;DR
The current `routing/file` tree is behaviorally sound and well-tested (~100+ per-module tests) but structurally messy: 13 user-flagged TODOs plus 14 same-family unflagged issues (naming collisions, duplicated helpers/builders/docks, insert-then-reread DB round-trips, an extension on the controlled `RoutingContext` type, serial crypto fan-outs under a single mutex, and a 1007-line `FileFetchService`). The desired state — per the TODOs and the binding Phase 1 clarifications — is a single-ownership, convention-conforming, file-per-type implementation with `FileFetchService` split into ~4–5 pieces and the two structural fixes (SealRegistry concurrent fan-outs, TransferEngine insert-then-reread) landed behind the existing test net. All gaps are internal to `routing/file` (port seam and orchestrator untouched); the gaps that remain are *approach* decisions, not unknowns.

## Key Decisions
- This is a refactor/structure cleanup, **not** a defect hunt — no TODO decodes to a reproducible bug; the only behavioral component is SealRegistry's concurrency change (latency under mutex, not incorrectness), so characteristics say `has_reproducible_defect: false`. — Sets the workflow shape (test-gated refactor, no red-failing test to write first).
- The two genuinely structural gaps (SealRegistry serial fan-outs, TransferEngine `start()` insert-then-reread) drive the medium risk level; everything else is mechanical consolidation pinned by existing suites. — Effort and sequencing follow from this split.
- FileService's missing dedicated unit test is a real coverage gap *because* the cleanup touches exactly its wiring (MessageService-shape convergence, ownership changes). — A harness decision must be made before the plan, not after.
- `involves_data_operations: true` but **no schema changes** — stores are read/written by new code paths (insert-then-reread removals change read/write *sequences*), rows keep their shapes, `queryOwners` and one-writer-per-table must be preserved. — Keeps DB migration machinery out of scope.

## Open Questions / Risks
- TransferEngine's `sourceFor` needs **both** `FileSealStore` (direct seals) and `FileRelayStore` (forwarded deposits) to rebuild `Running` state after restart; "single ownership of the seal store" therefore cannot mean the engine loses *all* store access — the ownership fix must be shaped carefully (see decision `engine-ownership`).
- SealRegistry's fan-out change is the most race-sensitive item: moving `createSeal` crypto outside the mutex changes interleavings that `serve`/`settle`/`deposit` currently rely on being serialized. The real-SQLite `SealRegistryTest`/`SealDepositTest` harness is the net, but the plan needs an explicit concurrency invariant ("state mutations serialized; only crypto/envelope work fan out").
- The `reported` dedup map is written from `report()` (outside `mutex.withLock` in `push`/`cancel`) and pruned under it in `sweep()` — it is a latent race today; folding it into the fan-out change is cheap, deferring it leaves a known wart.
- Constructor-coupled test harnesses (`SealTransferHarness`, `FileFetchServiceTest`, `FileRequestProcessorTest`, `FileInboundTestFixtures`) will churn with every signature change; the plan should batch signature-breaking steps to avoid repeated harness rework.

## Summary
- **Risk Level**: Medium
- **Estimated Effort**: High (13 TODOs + 14 unflagged issues + a 1007-line split, but each phase is mechanical behind a strong test net)
- **Detected Characteristics**: modifies_existing_code, creates_new_entities, involves_data_operations (no schema change)

## Task Characteristics
- Has reproducible defect: **no** — every flagged TODO decodes to a readability/ownership/latency issue; no failing-input scenario exists. The SealRegistry fan-outs are a *latency* defect shape (per-device crypto stalls the registry mutex), not a correctness bug, and there is no test that fails today because of it.
- Modifies existing code: **yes** — FileService.kt, FileControl.kt, TransferEngine.kt, SealRegistry.kt, RelayDoor.kt, RelayForwards.kt, TargetSide.kt, FileRequestProcessor.kt, FileFetchStrategy.kt, FileFetchService.kt (+ test harnesses).
- Creates new entities: **yes** — the FileFetchService split (~4–5 new files), a shared `guarded()` helper, a replacement for `RoutingContext.fileControl` (member or tiny internal sender type), a unified best-effort emission method, a shared `OutgoingTransfer` builder, and `TransferEvent` as its own file.
- Involves data operations: **yes** — code around 6 routing-owned stores (file_seals, transfers, incoming, relay, request-attempt, outbox) is restructured; **no schema changes**, no new tables, row shapes unchanged. Insert-then-reread removals (TransferEngine.start, RelayDoor.accept) change read/write sequences only. `queryOwners` (file queries only via routing.store `Default*` classes) and one-writer-per-table must hold after the change.
- UI heavy: **no** — everything in `routing.file` is `internal`; the GUI/orchestrator seam is `file/port/**` which is explicitly out of reshape scope. No PublicApiSurfaceTest impact.

## Gaps Identified

### Missing Features (do not exist at all)
- **Single `guarded()` helper**: triplicated as private functions (TransferEngine.kt:360, SealRegistry.kt:404, RelayForwards.kt:112) plus inline copies in TargetSide.runIn and FileFetchService's frame router. Nothing shared exists today.
- **Replacement for `RoutingContext.fileControl`**: the extension is the *only* extension on a controlled type in the codebase (FileControl.kt:14 TODO). No member/sender-type alternative exists yet; three call sites must migrate (TransferEngine.cancel:101, TargetSide.reply:262 / send:266, RelayDoor.accept:93).
- **A `ChunkSource` factory/ownership seam** for TransferEngine: today the engine reads `FileSealStore` and `FileRelayStore` directly (sourceFor, TransferEngine.kt:347–356) to rebuild sources — the double-ownership the FileService.kt:83 TODO flags. No factory/owner seam exists.
- **Dedicated FileService unit test/harness**: FileService's wiring (outcome mailbox dispatch, runIn job list, event-flow buffer, the race-sensitive "owners subscribe before engine runs" contract) has no direct test — only transitive multi-router coverage.
- **Shared `OutgoingTransfer` builder**: `SealRegistry.newTransfer` (:311–323) and `RelayForwards.onWhole` duplicate identical construction.
- **Unified best-effort emission**: `FileRequestStrategy.onExhausted` (FileRequestProcessor.kt:236–251) inlines what `FileFetchStrategy.emitBestEffort` (:138–151) has as a method.
- **Documentation of the outcome-mailbox idiom**: FileService.kt:154 TODO — per clarification 2, a KDoc line on `offerOutcome` naming the deliberate kernel seam (shared with MessageService:144 / BootstrapService:48) does not exist yet.

### Incomplete Features (exist but need completion/restructure)
- **FileFetchService split**: 1007 lines, star imports (:3, :12), late-bound `var scope` (:122) with `checkNotNull(scope)` at :197/:283/:758, duplicated Answer/FetchResult + grace-window double-handling (:394–452), a four-block try/catch wall in gateAndServe (:650–772). Per clarification 3 the *full split* is in scope — the split itself is the missing piece, boundaries to be agreed in the sketch.
- **TransferEngine readability**: two-lock choreography, `settle` from five call sites, labeled returns in `tick` (:206), `start()` insert-then-reread (:91–95), `tag↔transferId` implicit encoding shared across three files, `TransferEvent` cohabiting the engine file (file-per-type violation).
- **SealRegistry concurrency**: `push` (:72–92) and `cancel` (:125–136) loop serially over devices under the single registry mutex with per-device crypto (`createSeal` does `generateSealKey` + `protection.seal` inside the lock); `reported` dedup map (:331) with O(n) prune under the mutex (:251).
- **Anonymous docks**: two hand-rolled CandidateAttemptStore docks (FileRequestProcessor.kt:152–181, FileFetchStrategy.kt:47–71), one with dead stubs; the in-repo model (private strategy class, MessagePullProcessor) exists but is not applied here.
- **FileService composition-root shape**: diverges from the reviewed MessageService template (shared mutable `events` flow passed to three collaborators vs. service-concentrated emission; public-but-ad-hoc `offerOutcome`).
- **Naming**: `seals` means three things (FileService.seals = SealRegistry; SealRegistry.seals / TransferEngine.seals = FileSealStore) and `FileSealStore` lacks the established `*Store`-suffixed-use + single-owner shape.
- **Docs**: router.md:48 package layout is stale ("push/" doesn't exist; code has seal/transfer/relay/); envelope-redesign.md §5 must be updated in the same change per the guide.md same-change rule.

### Behavioral Changes Needed
- **SealRegistry.push/cancel**: per-device crypto must run concurrently *outside* the registry mutex (state mutations stay serialized). This is the only flagged TODO with a behavioral component — a concurrency-semantics change, not an observable-protocol change. Template: `OutboundMessenger.sendMessage`'s `coroutineScope { async } awaitAll`.
- Everything else is **behavior-preserving**: insert-then-reread removals, helper extraction, splits, renames, KDoc — pinned by the existing suites.

## Developer Journey Impact Assessment
(No GUI; "user" here is the maintaining developer — the task's stated goal is "clean and easily understandable".)

| Dimension | Current | After | Assessment |
|-----------|---------|-------|------------|
| Reachability of meaning (what does `seals` refer to?) | 3 different referents across 3 files | 1 referent, `*Store` convention, single owner | ✅ |
| Flow Integration (add a new file feature) | touch FileService (16 params) + read 1007-line FileFetchService to find the seam | per-concern files <300 lines, private strategies on the MessagePullProcessor model | ✅ |
| Convention consistency (project standards) | star imports, file-holds-two-types, public strategy, extension-on-controlled-type | conforms to guide.md / sibling templates | ✅ |
| Risk of misunderstanding the locking model | one mutex serializes everything (simple but stalling) | fan-out outside lock + serialized state — needs KDoc stating the invariant | ⚠️ (new invariant must be written down) |

## Data Lifecycle Analysis

### Entities: routing-owned file stores (code-around-store changes only; schemas unchanged)

| Store | Writer today | Writer after | Schema change | Notes |
|---|---|---|---|---|
| FileSealStore (file_seals) | SealRegistry | SealRegistry (unchanged ownership) — engine stops reading it directly for source recovery | none | Ownership TODO :83: engine gets sources via a seam, not the raw store. `queryOwners` test must stay green. |
| FileTransferStore | TransferEngine | TransferEngine | none | `start()` drops the re-read of the row it just inserted. |
| FileRelayStore | RelayDoor + RelayForwards | unchanged | none | RelayDoor.accept drops the deposit re-read (:85–95); engine still needs relay rows for forward recovery (see risk above). |
| FileIncomingStore | TargetSide | unchanged | none | untouched except guarded-helper consolidation. |
| FileRequestAttemptStore | FileRequestProcessor | unchanged (dock → private strategy class) | none | dead stubs removed. |
| PacketOutbox | OutboxProcessor/engine via tags | unchanged | none | tag↔transferId encoding gets one named owner. |

**Completeness**: n/a for feature CRUD (no new data entities); **constraint-completeness**: 100% only if `queryOwners`, one-writer-per-table, and `knownDebt` (empty, must only shrink) all hold after the change — these are the gates, all currently green.
**Orphaned Operations**: none.
**Missing Touchpoints**: none for data; the one missing test touchpoint is FileService's own wiring (see decision `file-service-test`).

## Defect Analysis (characteristic: not applicable — rationale)
No reproducible defect exists. Closest candidates examined:
- SealRegistry serial fan-outs — a latency/stall defect *shape* (crypto under mutex delays serve/cancel/settle/deposit/sweep), not a correctness bug; no test fails today.
- `reported` dedup map mutation outside the lock in `report()` vs. prune under it in `sweep()` — a latent race in principle, but no observed failure; it should be fixed as part of the fan-out change rather than treated as a bug ticket.

## Integration Points
1. **DefaultRouter.kt** — sole FileService construction site (:227–245), FILE handler registration (:257), `Router.files` exposure (:282), `routeOutcome → fileService.offerOutcome` (:443), `runIn` wiring (~:350). Any constructor/signature change lands here; kernel TODOs (:173, :216) stay untouched.
2. **file/port/** (6 files) — frozen seam (envelope-redesign §5.10); FileCarrier consumers orchestrator-side (NodeGraph.kt:321/:377) see zero change; ArchitectureTest import rules enforce this.
3. **Test harnesses coupled to constructors** — SealRegistryTest + SealDepositTest (SealTransferHarness over real SQLite), TransferEngineTest (incl. restart-rebuilds-queued), FileRelayTest, FileFetchServiceTest (signature changes with the split), FileFetchStrategyTest, FileRequestProcessorTest, FileInboundHandlerTest/FileInboundServeTest (FileInboundHarness + FileInboundTestFixtures). These churn with each signature change and gate every phase.
4. **Multi-router composition net (jvmTest)** — FileSmallPushTwoRouterTest, FileRelayThreeRouterTest, FileLargeFetchTwoRouterTest, FileSmallPushLiveIntegrationTest: the only coverage of FileService's wiring; must stay green at every step.
5. **ArchitectureTest** — routing import allowlist (transport/protection/protocol/crypto.identity), `queryOwners` (file queries only via routing.store Default*), `knownDebt` empty-and-shrinking.
6. **Callers of `fileControl`** — TransferEngine.cancel (:101), TargetSide.reply/send (:262/:266), RelayDoor.accept (:93): all three migrate when the extension retires.
7. **RoutingContext KDoc contract** — "no stores, engines, or policies"; the retirement of `fileControl` restores it.
8. **Docs** — docs/envelope-redesign.md (design of record, same-change update mandatory), docs/router.md:48 (stale layout line).
9. **MessageService / MessagePullProcessor / OutboundMessenger / RelayDepositor** — read-only templates: composition shape, private strategy, concurrent fan-out, tiny internal sender type.

## Issues Requiring Decisions

### Critical (must decide before spec/plan)
1. **Shape of the seal-ownership fix (TODO FileService.kt:83)** — the engine currently reads *both* FileSealStore and FileRelayStore for restart recovery; "single ownership" cannot simply delete the engine's store access.
   - Options: [A] Introduce a `ChunkSource` factory/seam owned by the constructors (SealRegistry + RelayForwards hand the engine a source factory; engine drops both stores), [B] Keep engine's recovery reads but rename to `*Store` convention + document the recovery contract (ownership of *writes* stays single, reads documented), [C] Engine keeps relay store only (forwards are engine-internal by §5.4) and drops only the seal store.
   - Recommendation: A — it matches the TODO's stated desire ("engine receives sources/factories, not the raw seal store"), makes restart recovery a single seam, and is pinned by TransferEngineTest's restart test.
2. **TransferEngine rewrite depth (TODO :64 + :93)** — "messy, to be rewritten" vs. tests pinning current behavior.
   - Options: [A] Minimal in-place untangle: construct `Running` directly in `start()` (no re-read), name the tag↔transferId encoding once, retire labeled returns, extract `settle`'s five call sites into explicit verbs; locks unchanged, [B] Per-transfer state object redesign (transfer actor holding its own lock/row/running): cleaner end-state, much bigger diff and harness churn.
   - Recommendation: A — behavior is pinned by 7 tests incl. restart-rebuilds; a state-object rewrite risks subtle ordering changes and doubles the diff on the riskiest file family.
3. **FileService test harness** — the cleanup touches FileService's wiring (MessageService-shape convergence) but FileService has no dedicated unit test; multi-router jvmTests are the only net.
   - Options: [A] Add a dedicated FileService harness before/with the wiring changes, [B] Rely on the multi-router jvmTests as the gate.
   - Recommendation: A — the wiring contract is race-sensitive ("owners subscribe before engine runs"); the marginal cost is small next to the total effort, and it converts the riskiest coverage gap into a pinned contract.

### Important (should decide)
1. **`guarded()` helper placement** — triplicated across three files plus inline copies.
   - Options: [A] One internal helper in `routing/router` (or a routing-internal util) shared by all, [B] Stay private-per-file (only the two inline copies fold into their file's existing helper).
   - Default: B — lowest churn; A adds a cross-package import to audit against ArchitectureTest. Upgrade to A only if the sketch already touches all three files' helper regions (it likely does — decide at spec).
2. **`reported` dedup map ownership (SealRegistry :331)** — mutable map with O(n) prune under the mutex, written from `report()` outside the lock.
   - Options: [A] Fold into the fan-out change as a small owned dedup type with its own lock, [B] Leave as-is this change.
   - Default: A — the lock-semantic change is already touching exactly this region; leaving it means keeping a known latent race the cleanup was meant to remove.
3. **FileFetchService split landing strategy** — ~4–5 new files, the biggest diff in the change.
   - Options: [A] One wave after all other phases land (big bang, single big review), [B] Two+ waves: vocabulary/Answer dedup + scope-var fix first, then the physical file split.
   - Default: B — each wave lands behind FileFetchServiceTest + FileLargeFetchTwoRouterTest; smaller diffs per review and an escape hatch between waves.
4. **`DeviceDirectory.isKnownNonBanned` (RoutingTypes.kt:207)** — second extension-on-controlled-type, same family as the flagged FileControl TODO, but lives in `routing/router` (outside the clarified routing/file scope) with two consumers inside file code (RelayDoor:47) and message code (MessageInboundHandler:79).
   - Options: [A] In scope: retire alongside fileControl, [B] Out of scope: documented follow-up (consistent with clarification 1's kernel-TODOs-are-follow-ups stance).
   - Default: B — the clarification scoped this change to routing/file; moving a router-level extension touches routing/router and the message path, doubling review surface for a non-flagged item.
5. **FileRequestStrategy visibility + FileFetchService taking `ctx: RoutingContext`** — both are convention alignments with existing sibling templates; no real alternatives, so no operator decision needed — recorded here only to confirm they ride along without a gate.

## Recommendations
- Sequence as the codebase analysis proposes, with the ownership decision (Critical 1) made first since it shapes the TransferEngine and SealRegistry diffs: consolidation (mechanical) → naming/ownership → engine untangle → SealRegistry fan-outs → FileFetchService split in two waves.
- State the SealRegistry concurrency invariant in KDoc at the top of the class ("state mutations serialized under `mutex`; per-device crypto/envelope work fan out concurrently") — the new locking model must be as readable as the code it replaces.
- Batch signature-breaking steps per file so constructor-coupled harnesses are reworked once, not per phase.
- Docs ride in the same change (guide.md rule): router.md:48 layout fix + envelope-redesign.md §5 update reflecting the final shape; knownDebt only shrinks.
- Kernel TODOs (DefaultRouter:173/:216, BootstrapSender:54, MessagePullProcessor:110, RelayDepositor:15) are already bindingly out of scope — record them as follow-ups in the spec's non-goals, not as decisions.

## Risk Assessment
- **Complexity Risk**: Medium-High — FileFetchService split (1007 lines) and TransferEngine choreography are the heavy reads, but both have reviewed templates and pinning tests.
- **Integration Risk**: Low — one construction site, internal-only types, frozen port seam, ArchitectureTest-enforced boundaries.
- **Regression Risk**: Medium — concentrated in (a) SealRegistry lock-semantic change (net: real-SQLite SealRegistryTest/SealDepositTest) and (b) FileService wiring edits (net: multi-router jvmTests, strengthened if the harness decision lands as recommended). Everything else is mechanical behind green suites.
