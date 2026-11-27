# Code Review Report — file-branch cleanup (ff9d37f..f0494a7)

**Date**: 2026-11-27 **Path**: `core/src/commonMain/kotlin/org/yapyap/routing/file/**`, `routing/store/File*`,
`routing/candidate`, `routing/router/{RouterService,DefaultRouter,RouterConfig,FileLimits}`,
`wiring/{FileLimits,ConfigInvariants,Settings}`, protocol file types, the orchestrator file code
(`orchestrator/file/**`), and the docs (`docs/`, `.maister/docs/`)
**Scope**: all, with emphasis on quality and readability **Status**: ⚠️ Issues Found (no critical issues)

All paths below are relative to `core/src/commonMain/kotlin/org/yapyap/` unless they start with `docs/`, `.maister/` or
`core/`.

## Summary

- **Critical**: 0
- **Warnings**: 17
- **Info**: 27

Most of the agreed end states in cleanup.md §2 have landed:

- the §4 renames;
- `RouterService` (§2.2);
- `FilePackets` owning all four builders (§2.3);
- sessions and the mux (§2.4/§2.5.1);
- the two-phase seal registry with its lease guard (§2.6);
- store-owned candidate views, with no docks left (§2.7);
- `SMALL_FILE_MAX_BYTES` and `MAX_SEAL_BYTES` gone, and `maxSealBytes` derived and checked at `createSeal` (§2.8);
- the cadence and pacing values moved to config (§2.8.6);
- the large-path split, with the frame floor dropped (§2.11.2);
- no star imports left in the file tree;
- the orchestrator seam (§2.9).

The two TODOs that were meant to land are there: `presence/PresenceService.kt:28` and
`candidate/CandidateStrategy.kt:34`.

Governance and unit tests pass. `:core:jvmTest` ran with ArchitectureTest, PublicApiSurfaceTest, SealRegistryTest,
TransferEngineTest, FilePullProcessorTest, LargeFileMuxTest, FileStoreSemanticsJvmTest, ConfigInvariantsTest and
FileLimitsTest, and all were green. The integration suites were not run.

What remains:

- two behaviour regressions introduced by the restructure (W1, W2);
- one semantic change in cancellation that the docs misstate (W3);
- one place where the receiver limit check spends a ratchet seal (W4);
- partial application of §2.13 and §2.1 (W5–W7);
- duplicated try/catch "guarded" boilerplate across about 10 classes (W8);
- several long, deeply nested functions on the large path (W9–W11);
- the documentation follow-ups from cleanup.md §5, none of which have been done (W14–W17).

## Critical Issues

None.

## Warnings

### Correctness

**W1. The transfer-session tick is an idle timeout, not a periodic tick, so a busy mailbox starves it.**

- Where: `routing/file/transfer/OutgoingTransferSession.kt:118-120`
- What happens: the loop runs `withTimeoutOrNull(tickEvery()) { mailbox.receive() } ?: SessionMsg.Tick`, so every
  message restarts the timer. The old engine ticked every transfer on a fixed cadence.
- While messages keep arriving faster than `fileSweepInterval / 3` (15 s by default), `tick()` never runs. Examples are
  repeated same-coverage ACKs from a receiver whose contiguous prefix is stuck, or chunk Acked/Expired outcomes. While
  starved, `tick()`'s duties stop:
    - expiry (`now >= expiresAt`);
    - the lost-OFFER re-arm;
    - the `!linked` gone check;
    - the window refill after a quiet error.
- The KDoc at :55 ("The loop's idle timeout: the periodic per-transfer tick") contradicts itself.
- Fix: keep a `nextTickAt` deadline. Receive with `withTimeoutOrNull(nextTickAt - now)`, and run `tick()` whenever
  `now >= nextTickAt`, whatever message arrived.

**W2. A stalled PENDING file has its pull schedule reset to "now" on every orchestrator sweep, which defeats routing's
backoff.**

- Where: `orchestrator/file/FileRequestCoordinator.kt:334-337` together with
  `routing/file/FilePullProcessor.kt:112-114`.
- `startFilePull(immediate = true)` now calls `pullAttempts.advanceToNow` unconditionally. The coordinator's STALLED
  loop calls it for every STALLED + PENDING row on every pass (`fileSweepInterval`, 30 s).
- Before the cleanup, `startFileRequest(id, now)` was insert-or-ignore, so a repeat was harmless.
- The same row is also re-armed with `immediate = false` in the first loop of the same pass (:278-304, where STALLED
  falls through to `startFilePull(..., false)`). That is two calls per pass.
- Effect: capped-exponential backoff collapses to at most 30 s for stalled files, and attempts exhaust faster, so
  `RequestUnavailable` comes sooner.
- Fix: pass `immediate = true` only on the fresh stall (`markStalled` returned true, :325-330) and in the `null` branch
  (:339-342). Make the PENDING branch a no-op, since the first loop already re-arms it. Add a test that a stalled
  PENDING row is not advanced on later sweeps.

**W3. `TransferEngine.cancel` is now asynchronous, but callers and the docs still treat it as synchronous.**

- Where: `routing/file/transfer/TransferEngine.kt:87-90`.
- It only posts `SessionMsg.Cancel`. The CANCEL packet, the status row, the outbox purge and `Ended` all happen later in
  the session. The old `cancel` did all of that before returning.
- Consequences:
    - `SealRegistry` KDoc :62-66 and :484 say cancellations run in launches "joined before the entry point returns".
      Only the mailbox post is joined.
    - `SealRegistry.sweep` (:353-358) and `RelayForwards.drop` (`routing/file/relay/RelayForwards.kt:69-73`) delete
      transfer rows right after "cancelling". The live session then writes `setStatus`/`setProgress` to deleted rows and
      emits `Ended` for a transfer its owner has already forgotten. This is harmless today: the owners look up and
      return null. But it is exactly the kind of implicit ordering the cleanup set out to remove.
    - A transfer row with no session, for example because recovery failed for it inside `guarded`, is not ended by
      `cancel` at all.
- Fix, either:
    - (a) make `cancel` return a `Deferred`/`Job` that completes on `end()`, or `join` the session; or
    - (b) state the async contract in the docs, let the session's `end()` own row deletion for expired seals and dropped
      forwards, and drop the per-transfer `launch` in `SealRegistry.cancelTransfers` (:485-496). A mailbox post is "just
      an enqueue" under §2.11.1, so the launches add nothing.

  In both cases, make `cancel` end a row that has no session through `endWithoutSession`.

**W4. `TargetSide` enforces `smallFileMaxBytes` only after opening the seal, so an over-limit OFFER consumes a ratchet
message.**

- Where: `routing/file/inbound/TargetSide.kt:157-170` (the check in `sealOf`, after `openSeal`).
- The seal is opened and then refused without storing a row. Any retransmit of the same OFFER then fails as
  `ProtectionException.Duplicate` and is NACKed PERMANENT_PROTECTION_FAILED (:163-165) instead of DECLINED.
- The shape already carries the bound: `shape.byteTo` must equal `totalBytes` (:72).
- Fix: check `header.shape.byteTo > smallFileMaxBytes` in `offer()` next to the existing `isAcceptable()`/`maxSealBytes`
  gate (:64), the way `RelayDoor.offer` does (`relay/RelayDoor.kt:47`). Keep the post-open `totalBytes` check only as a
  consistency assert.

### §2 conformance and structure

**W5. §2.1 naming: `TargetSide`'s store field is `incoming`, not `incomingStore`.**

- Where: `routing/file/inbound/TargetSide.kt:51`.
- The rule is that stores are named for their type. `FileService` passes it as `incomingStore`, and every other store
  field was renamed.
- Fix: rename it to `incomingStore`.

**W6. §2.13 was not applied in `port/` and `large/`: data-only types still ride with behaviour files.**

- `port/FileIngestPort.kt` holds `FileOfferVerdict`, `FileRefusalReason`, `FileAppendResult` and `FileIngestResult` next
  to the interface.
- `port/FilePlaintextSource.kt:27` holds `PlaintextRead`.
- `large/LargeFileRequester.kt:31-50` holds `RequesterInput` and `RequesterAnswer`.
- `large/LargeFileHolder.kt:33-37` holds `HolderSession`, a mutable data holder that the mux also uses.
- §2.1 calls out exactly this smell ("FileCarrier.kt carries five seam types").
- Fix: move the port result types into `port/FilePortTypes.kt` and the large-path vocabulary into `FileTypes.kt`.
  `RequesterInput`/`RequesterAnswer` can stay only under the single-consumer exception, and both are also read by the
  mux's routing.

**W7. "Fetch" survives in identifiers on the large path.**

- Identifiers:
    - `LargeFileService.fetch` (`large/LargeFileService.kt:66`) and `.abortFetch` (:69). The latter is called by
      `FileService.abortLargeFile`, `FileService.kt:152`.
    - `LargeFileRequester.fetch` (:137).
    - the `fetchService` fields in `FileService.kt:101`, `FilePullProcessor.kt:52` and `LargeFileStrategy.kt:42`.
    - `LogEvent.FILE_FETCH_FAILED`.
    - the test class `core/src/jvmTest/.../routing/file/FileLargeFetchTwoRouterTest.kt:124`.
    - the test helper `idleFetchManager` (`core/src/commonTest/.../FilePullProcessorTest.kt:112`).
- Log messages and KDoc also still say "fetch": `LargeFileAdmissionGate.kt:36-100`, `LargeFileHolder.kt:90-257`,
  `LargeFileMux.kt:59,75,148`, `LargeFileChannelIo.kt:70-101`.
- §2.5.3 bans "fetch" only in class names, and §2.1 lists `fetchService` as an allowed noun. Even so,
  `abortLargeFile → fetchService.abortFetch → requester.abort` reads three ways.
- Fix: `pull`/`abortPull`, a field named `largeFiles`, `LogEvent.LARGE_FILE_FAILED`, and `FileLargePullTwoRouterTest`.

**W8. The guarded try/catch boilerplate is duplicated about 25 times across about 10 classes.**

- Each class has its own copy of
  `try { … } catch (CancellationException) { throw } catch (Exception) { AppLog.error(FILE, FILE_TASK_FAILED, …) }`:
    - `SealRegistry.guarded` (:576)
    - `TransferEngine.guarded` (:241)
    - `OutgoingTransferSession.guarded` (:322) plus the outer catch in `run` (:122-131)
    - `RelayForwards.guarded` (:110)
    - `TargetSide.runIn` (:250-262, inline) and `TargetSide.send` (:270-281)
    - `FileRequestStrategy.onExhausted` (:86-96)
    - `LargeFileStrategy.emitBestEffort`
    - `LargeFileMux.runIn` (two copies, :52-80)
    - `LargeFileAdmissionGate.admit` (five copies, :28-104)
    - `LargeFileHolder` (six copies)
    - `LargeFileRequester` (four copies)
    - `LargeFileChannelIo` (three near-identical sends, :51-105)
- This is the biggest readability cost left: in the gate, 85 of the 112 lines are catch blocks around five one-line
  lookups.
- Fix: one helper in `routing/file`, e.g.
  `internal suspend fun <T> fileGuarded(what: String, fields: Map<String, Any?> = emptyMap(), level = ERROR, block: suspend () -> T): T?`.
  Then `admit` becomes five `?: return Declined(NOT_ALLOWED)` lines, and `sendProgress` becomes `sendBestEffort`.

**W9. `LargeFileHolder.serveLoop` is about 130 lines with five levels of nesting.**

- Where: `large/LargeFileHolder.kt:166-295` (try → while → when → branch → try).
- Fix: extract `openStream()`, `awaitCredit(session, offset): Boolean` (the idle check, window wait and buffered wait),
  `sendData(session, bytes): Boolean`, and `parkUntilDone(session, total)`. W8's helper removes four of the catch
  blocks.

**W10. `LargeFileRequester.runRequester`'s close-grace branch nests about seven levels and duplicates the main
dispatch.**

- Where: `large/LargeFileRequester.kt:244-313`.
- The `ChannelClosed` branch re-implements the PullOk, Declined and Abort handling of the main `when` (:291-307) inside
  `withTimeoutOrNull { for { when { when` (:251-280).
- "Declined" is completed by hand in two places (:267-268, :294-295).
- Fix: extract `handleAnswerFrame(session, frame): Boolean` ("settled?"), called from both loops, and
  `settle(session, DECLINED)` as one call. Also remove the one-line wrapper `closeRequester` (:220-222), which only
  calls `session.closeInput()`.

**W11. `TargetSide.offer` is about 100 lines.**

- Where: `inbound/TargetSide.kt:56-155`.
- The Accept branch (:84-122) includes positional `IncomingTransfer(...)` arguments split one per line and two
  hand-wrapped `reply(...)` lists.
- Fix: extract `acceptOffer(env, header, seal, manifest)` and `alreadyReady(...)`, use named arguments, and reformat the
  single-line calls. `endReceive` (:233-241) has the same odd `for (…) send(\n…)` wrapping.

**W12. Coroutine discipline (§2.11.1): the serve path runs crypto and a transfer start on the shared inbound
collector.**

- Where: `inbound/FileServeHandler.kt:39` → `SealRegistry.serve` → `createSeal`, which runs `protection.seal`, a store
  insert and `engine.start` inline.
- `DefaultRouter.kt:331-347` collects each transport's inbound flow serially, so a slow serve stalls all inbound traffic
  on that transport.
- In `LargeFileRequester.kt:89-93` (`RequesterSession.onFrame`), the overrun path calls `io.closeQuietly(channel)`, a
  transport call, inline on the mux's shared frame collector.
- §2.5.2 keeps the receive side per-packet, but the serve is send work.
- Fix, either:
    - plan under the lock and answer ACK, then run `makeSeal` in a guarded launch. A failed seal then only costs the
      requester its `fileRequestWaitTimeout`, which is the same backstop as a lost OFFER. Or
    - document why serve stays inline.

  For the overrun, post `ChannelClosed` and let the requester loop close the channel.

**W13. Hidden late-init in the large path, against the composition-root standard ("constructor injection only, no
lateinit").**

- `LargeFileMux.holder` is a nullable var set through `attach()` (`large/LargeFileMux.kt:33-42`), because the holder
  takes the mux.
- `scope` is set by `runIn`/`start` in the mux (:37), the holder (:52-56) and the requester (:126-131), and each is read
  through `checkNotNull(scope) { "... is not running" }`.
- Fix: break the cycle. Give the mux a `pullHandler: suspend (FileChannelId, Pull) -> Unit` constructor parameter (or a
  small `HolderInlet` interface), with the mux depending only on that. Pass the scope into `LargeFileService.runIn` and
  build the sessions' launcher there.

### Documentation (cleanup.md §5 and the stale caveats)

**W14. None of the five standards in cleanup.md §5 has been added to `.maister/docs/standards`.**

- Absent:
    - store-owned child-table views;
    - per-transfer sessions over shared serial loops, and the coroutine discipline;
    - stores named for their types, components for their concerns.
- Partial:
    - the four kinds of number: only kind 2 is covered (`architecture/config-and-limits.md:21-33`) and part of kind 1
      (`protocol/e2ee.md:163-168`); there is nothing on NETWORK values or local mechanics;
    - `[Concern]Types.kt`: `backend/structure.md:32-37` describes the pattern but does not state the rule.
- Fix: run `/maister:standards-update` for each.

**W15. The "unreviewed — do not mine it for conventions" caveat is still everywhere.**

- `.maister/docs/INDEX.md:24,31,38,141,193`
- `.maister/docs/project/vision.md:40-41,58-59` (the goal is still open)
- `.maister/docs/project/roadmap.md:21`, where `[ ] Review & finalize the file branch` is unchecked, and :39-40
- `.maister/docs/project/architecture.md:138-141`
- `.maister/docs/standards/protocol/files.md:5-6`
- `.maister/docs/standards/global/documentation.md:30-33`, which says "do not extract standards from docs/file/". This
  contradicts §5.
- Fix: mark the review done. Keep the design.md rewrite and the soak matrix as open roadmap items. Replace the caveat
  with "reviewed 2026-11-27, see docs/file/cleanup.md" and regenerate INDEX.

**W16. Pre-cleanup names in the standards.**

- `.maister/docs/standards/architecture/layering.md:83` says `startFileRequest, abortFileFetch`; the code has
  `startFilePull` and `abortLargeFile`.
- `.maister/docs/standards/protocol/files.md:145,152` quote envelope-redesign with "FETCH". The source now says PULL
  (`docs/envelope-redesign.md:159`).

**W17. cleanup.md and design.md still describe the code as not yet conforming.**

- `docs/file/cleanup.md:9-11` ("until the steps below land, the code and these docs disagree … known bug") and
  `docs/file/design.md:24-25`.
- Nothing marks cleanup.md as landed.
- `docs/file/design.md:1043` cites "cleanup.md §2.1" for the four kinds of number; it should be §2.8.
- Fix: add a status line ("Landed: ff9d37f..f0494a7, 2026-11-27") and reword both passages.

## Informational

### §2 conformance details

1. **§2.8.3: `ConfigInvariants` has no `maxSealBytes` assertion.**
    - Where: `wiring/FileLimits.kt:39-41` and `wiring/ConfigInvariants.kt:43-58`.
    - The plan said `ConfigInvariants` would assert the chain's coherence. The code instead argues in a comment that the
      chunk floor implies the seal budget, and `fileLimitsFor` does not even `require(maxSealBytes > 0)`.
    - Fix: add a `require(maxSealBytes > 0)` in `fileLimitsFor`, or turn the comment's claim into a checked one.
2. **The tick divisor is a magic number.** `OutgoingTransferSession.kt:150` uses `fileSweepInterval / 3`. §2.8.6 allows
   deriving the tick, but name the divisor (`SESSION_TICKS_PER_SWEEP`) or document it in RouterConfig. RouterConfig :156
   already says "a third".
3. **A local-mechanics value is still a constant.** `LargeFileHolder.MAX_SERVES_PER_PEER = 4` (:298) is kind 4 under
   §2.8.1 and belongs in `RouterConfig` (e.g. `maxLargeFileServesPerPeer`).
4. **`filePullAttemptTimeout` is reused on the large path.** It is a small-path REQUEST semantic by §2.5.3, but it also
   serves as the holder idle timeout (`LargeFileHolder.kt:191,206,284`) and the requester answer timeout
   (`LargeFileRequester.kt:170`).
    - Either add a `largeFileIdleTimeout`/`largeFileAnswerTimeout`, or document the reuse.
    - The comment at :187-190 ("Uses the attempt scope …") is hard to parse.
5. **The holder mixes wall-clock epoch milliseconds with Durations.** `HolderSession.lastProgressAt` is epoch millis
   from `Clock.System` (`LargeFileHolder.kt:36,122,151,186,284`) and is compared against `Duration.inWholeMilliseconds`.
   Use `Instant`/`TimeMark`, as the coordinator does.
6. **`FileRouterConfig` naming split.** `RouterConfig.fileSweepInterval` vs `OrchestratorConfig.fileSweepInterval` share
   a name with different meanings (RouterConfig.kt:155-158 has to explain it). Consider `fileRouterSweepInterval` or
   `fileRequestTriggerInterval` on the orchestrator side.
7. **The FileLimits KDoc is incomplete.** `routing/router/FileLimits.kt:4-5` names two of the three fields; add
   `maxSealBytes`.

### Readability and dead code

8. **Change-history wording in comments:**
    - `TransferEngine.kt:119` ("as the old shared tick did")
    - `SealRegistry.kt:121` ("still reports Failed as before")
    - `OutgoingTransferSession.kt:77-79` ("reproduces the engine's first pass")
    - `docs/file/design.md:1070-1072` ("moved to routing as `filePullDelay`")
9. **`TransferEngine.endWithoutSession` duplicates `OutgoingTransferSession.end`.** `TransferEngine.kt:226-237` repeats
   the status write, outbox purge, chunk delete, log and emit, and its `status` parameter is always `ENDED`. Fix: drop
   the parameter, or share one `endTransferRows(transfer, status)`.
10. **`OutgoingTransferSession` takes 12 constructor parameters and borrows a mutable set.** It receives its
    `queued: MutableSet<Int>` from the engine (:43). Fix: take `initialQueued: Set<Int>` and own a private copy.
    Bundling the six engine dependencies into one `SessionDeps` would halve the list.
11. **`started` is not thread-safe.** `OutgoingTransferSession.started` (:69) is a plain var, and `launch` can be
    reached from both `TransferEngine.start` (:79) and `recover` (:143) during the startup window. Use an atomic flag,
    or launch only from the engine under `registryLock`.
12. **`SealRegistry.serve` runs a `when` over a `withLock` lambda.** It uses `return@withLock null` as a third state (:
    167-200). Fix: extract `planServe(...)` returning `ServePlan?` and add a `ServePlan.InFlight` variant instead of
    `null`.
13. **`SealRegistry.makeSeal` interpolates the device into the label.** `guarded("seal ${plan.device}")` (:397) puts it
    in the message, while every other call passes `fields`.
14. **`SealRegistry.sweep` sets EXPIRED from a stale status.** At :350 it checks `seal.status == SENDING` on the row
    read before taking the lock, so a seal delivered in between can be overwritten to EXPIRED. Re-read with
    `sealStore.find` under the lock.
15. **`LargeFileHolder` has a dead coerce and an odd parameter name.** In `LargeFileHolder.kt:226-227`,
    `.coerceAtLeast(1)` is dead because `totalBytes - offset > 0` inside the loop. The parameter `info: FileManifest`
    (also in `LargeFileChannelIo.sendPullOk`) is a leftover name; use `manifest`.
16. **`LargeFileChannelIo.sendAbort`'s KDoc is wrong.** It says "every abort is followed by the channel's close" (:
    54-57), but the method does not close; callers must. Either close inside it, as `sendDeclined` does, or fix the
    KDoc.
17. **`LargeFileRequester.fetch` closes the channel twice.** `finishRequester` closes the channel and removes the
    requester, then the `finally` (:205-209) does both again. Keep the `finally` only.
18. **The small and large strategies duplicate four overrides.** `FileRequestStrategy` and `LargeFileStrategy` repeat
    `candidateAccounts`, `idsWithCandidate`, `isWanted` and `onExhausted` + emit, differing only in `FilePullClass`. The
    KDocs on `FilePullProcessor` (:25-38) and `LargeFileStrategy` (:28-36) repeat the same paragraph. A small shared
    base or helper would remove about 30 lines.
19. **`DefaultRouter` silently keeps the last duplicate outcome tag.** `DefaultRouter.kt:252-253` builds the map with
    `.toMap()`, so two services claiming one kind would go unnoticed. Add a `require` that the kinds are disjoint.
20. **`DefaultRouter`'s inbound collectors swallow `CancellationException`.** `DefaultRouter.kt:332,347` use
    `runCatching { … }`. This predates the cleanup but sits on the dispatch path the cleanup touched.

### Performance

21. **N+1 queries in the pull store's `findDue`.** `FilePullAttemptStore.findDue/findById/findByAttemptId`
    (`routing/store/FilePullAttemptStore.kt:85-102`) run a declined-devices query per row, outside a transaction. This
    is bounded by `limit` at this network size; a `GROUP_CONCAT` join or one transaction would tidy it.

### Orchestrator side (§2.9 verified)

- `pushFile` is always called (`orchestrator/file/FileSendService.kt:173-188`).
- `onIngest` is gated only by `autoDownloadMaxBytes` (`FileRequestCoordinator.kt:215-220`).
- `startFilePull(fileId, immediate)`:
    - automatic triggers pass false (:253, :304);
    - the accept click and stall re-open pass true (:265, :337, :341).
- `next_attempt_at` is removed from `pending_file_requests` (`PendingFileRequest.sq:10-14`, `6.sqm:58-62`), not just
  unused.
- The re-arm runs unconditionally per pass.
- `filePullDelay` is on RouterConfig (:153).
- `copyIntoStoreMaxBytes` is gone everywhere.
- `keepCopy` is required (`orchestrator/runtime/message/MessagingTypes.kt:64`, `FileStore.kt:49,77`).

22. **`FileRequestCoordinator.start` is about 145 lines with seven copies of the try/log block.** Where:
    `orchestrator/file/FileRequestCoordinator.kt:58-203` (also `answerTransferAuthors` :381-394). Extract
    `onReceiveEvent`, `seedMarks`, `sweepLoop` and one `logFailures` helper.
23. **`sweep` is about 75 lines and repeats `maybeRequest`'s status switch.** Where: `FileRequestCoordinator.sweep` (:
    274-347), repeating :230-248. It also calls `receives.active()` twice per pass (:311, :334). Extract
    `settledOrDrop(fileId)` and merge the two loops; this also fixes W2's double call.
24. **`else` on a sealed type.** `FileRequestCoordinator.kt:372` has `else -> Unit` on `FileEvent`, against the
    vocabularies standard.
25. **Smaller coordinator issues:**

- :166-174 takes the lock by hand although `progressGuardLocked` exists;
- :365-370 logs a debug "Large fetch has no direct connection" under `FILE_TASK_FAILED`;
- "fetch" wording at :213, :308, :326;
- `FileSendService.kt:209` has a magic `16` (the maximum extension length);
- `FileSendService.kt` TODOs at :37, :91 (typo "whih") and :173.

26. **`ReceiveEngine` has two long functions.** `orchestrator/file/receive/ReceiveEngine.kt:483` (`ingestChunk`, about
    79 lines) and :576 (`appendBytes`, about 77 lines).
27. **Deferred §6 orchestrator naming that remains:**

- `FileRequestCoordinator`/`DefaultFileRequestCoordinator` (with `NodeGraph.kt:30,314,406,428,447`);
- `FileRequestControl`;
- `DefaultFileRequestSource`;
- `PendingFileRequestStore`/`PendingFileRequest`/`PendingFileRequestStatus`. The last is public and whitelisted in
  `PublicApiSurfaceTest.kt:122`, so renaming it changes the GUI API;
- the table `pending_file_requests` and its five queries, and the `pending_file_requestsAdapter`;
- `FileEvent.RequestSatisfied`/`RequestUnavailable`;
- `ReceiveEngine.commitFetchPrefix` (:653) and its "fetch" messages;
- `LogEvent.FILE_FETCH_FAILED`;
- "fetch" comments in FileReceiveStore, PartWriter, FileRetention, DefaultFilePlaintextSource, FileReceiveControl and
  FileTransferService.

The pull umbrella would make these `FilePullCoordinator`, `pending_file_pulls`, `FileEvent.PullSatisfied`/
`PullUnavailable`, `commitPullPrefix`, and so on.

### Docs that agree with the code

- guide §4 and `ArchitectureTest.queryOwners` agree (`file_pull_attempts` and `file_pull_declined_devices` are owned by
  routing.store).
- design.md §15's config names all exist in `RouterConfig`/`FileLimits`.
- The `.mmd` diagrams match.

Minor drift:

- router.md package layout omits `RouterService`;
- router.md still has pre-existing stale entries: `RelayCopies` (:40), `AckResponder` (:63,109-111), and
  `FileInboundHandler` "stub" (:129);
- design.md:904 names a nonexistent `FileEnvelopeProtection`, and :913 omits `largeFileFrameBytes`/`maxSealBytes`.

## Metrics

- Longest functions:
    - `FileRequestCoordinator.start`, about 145 lines;
    - `LargeFileHolder.serveLoop`, about 130;
    - `TargetSide.offer`, about 100;
    - `LargeFileRequester.fetch`, about 75;
    - `LargeFileRequester.runRequester`, about 70.
- Deepest nesting: about 7 levels (`LargeFileRequester.runRequester`, close-grace branch).
- Hand-rolled guarded catch blocks in `routing/file`: about 25.
- Security issues: none found. The admission gates fail closed, and the seal and decode bounds are in place.
- N+1 query risks: 1, minor (`FilePullAttemptStore.findDue`).
- Governance and unit tests run: all green.

## Prioritized Recommendations

1. Fix W2 (the stall re-arm resets backoff) and W1 (the session tick starves under a busy mailbox). Both are behaviour
   regressions from the restructure and each needs a test.
2. Settle W3: give `TransferEngine.cancel` an explicit async-or-joined contract, and correct the SealRegistry and
   RelayForwards row deletion and KDoc to match.
3. Move the small-file check in `TargetSide.offer` before `openSeal` (W4).
4. Add one guarded/best-effort helper and collapse the duplicated catch blocks (W8). Then split `serveLoop`,
   `runRequester` and `TargetSide.offer` (W9–W11).
5. Finish §2.13/§2.1/§2.5.3: the port and large type files, `incomingStore`, and the fetch identifiers (W5–W7). Remove
   the large-path late-init (W13).
6. Docs: run standards-update for the five §5 standards, remove the "unreviewed" caveats and mark the roadmap item done,
   fix `layering.md:83` and `files.md:145,152`, and mark cleanup.md as landed (W14–W17).
7. Orchestrator follow-ups: shorten the coordinator functions and schedule the §6 rename pass (items 22–27).
