# File transfer: refactor plan

This plan reworks what phases A–F of [`plan.md`](plan.md) built, before Phase G is finished on top of it. It
comes out of a full review of the `files` branch (November 2026). [`design.md`](design.md) stays the source of
truth for *what* is built. Where the review found the design wrong, the step that fixes the code also fixes
the design (see "Design changes" at the end). Phase G of `plan.md` (steps 23–24) runs after this plan, on the
reworked code.

## Why a refactor and not fixes

The tests are green, but they never leave the conditions that hide the bugs:

- every small-file test uses 3–5 chunks, below the 16-chunk window;
- the large-fetch test uses an in-order fake transport and a 16 KiB checkpoint.

Outside those conditions:

- a push over about 3.5 MB stalls forever;
- relay deposits never complete, and deposit rounds repeat endlessly;
- large fetches can corrupt (frames reordered on JVM, frames dropped on DEFER) or hang (completion is never
  noticed);
- hash-mismatch recovery loops until FAILED;
- the checkpoint can lose ACKed data on a crash.

Most of these come from structure, not slips:

- the window keys on the wrong quantity;
- state has several writers and no per-transfer serialisation;
- the fetch rides the small path's staging;
- the plaintext source is stateless and re-hashes every block per read;
- the ports mix concerns, so the composition needs a `lateinit` cycle hack.

Fixing them in place would rewrite the same code twice. This plan rebuilds the components around the seams
the design already describes and fixes the bugs as part of that.

## Ground rules

Everything in [`plan.md`](plan.md)'s ground rules still holds. In addition:

**Order and scope**

- **Seams first, then engines.** Phase R1 changes no behaviour except where it says so; it moves config,
  ports and wiring so the later rewrites land in their final shape.
- **Each step lands green.** Every step ends with `ArchitectureTest`, `PublicApiSurfaceTest` and the full
  `jvmTest` green.
- **A rewrite replaces, it doesn't duplicate.** A step that rewrites a component deletes the old one in the
  same change. No `V2` classes and no parallel paths.

**Tests**

- **Tests run at realistic sizes.** Every engine step brings at least one test above the window, with the
  default checkpoint config, against the real store (in-memory SQLite) rather than a fake. The relevant
  [`design.md`](design.md) §19 rows are named in each step.
- **Fakes are allowed only at the seam under test.** A test of the pusher may fake the outbox, but it may not
  hand-drive ACK numbers the real receiver would never produce. Each engine gets a two-router test.

**Code style**

- **Errors propagate.** No `catch (Exception)` that turns a failure into a default value: no `false`, empty
  list, `NO_SPACE` or `Refused`. Use the pattern of the existing processors:
    - expected outcomes are typed results;
    - transient failures are `Defer`;
    - everything else propagates to the loop's `onProcessFailed` and is logged there.
- **Cancellation is never caught.** `runCatching` is banned around suspend calls.
- **File logging uses its own events.** Add `LogComponent.FILE` and `FILE_*` `LogEvent`s. File code never
  logs under `SYNC_*`, `OUTBOX_*` or `MESSAGE_*` events.
- **Comments describe the code, not its history.** No "step N" or "Phase X" in KDoc.
- **Schema.** Step R1.0 replaces migrations 6–15 with a single `6.sqm`. From then on, a step that changes a
  file table edits its `.sq` and that one `6.sqm`, never adding a new migration. No test database has been
  migrated past schema 5, and payload version 3 makes old messages unreadable, so every test data directory is
  reset anyway.

## For whoever executes this plan

Phases R1 and R2 are **done** (see their "As landed" notes). Start at R3.1 and go in the order of "Order and
parallelism".
Read this section in full before every step.

### Where things are now (after R1)

| Concern                       | File(s)                                                                                                                                                                                                                                                                                      |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Router lifecycle and services | `routing/router/Router.kt`, `MessageCarrier.kt`, `SyncCarrier.kt`, `BootstrapCarrier.kt`, `Presence.kt`; implemented as objects inside `DefaultRouter`                                                                                                                                       |
| Router's file assembly        | `routing/file/FileRouting.kt` (`FileRouting`, `FileRoutingPorts`)                                                                                                                                                                                                                            |
| Ports the orchestrator sees   | `routing/file/port/*` (`FileCarrier`, `FileEvent`, `FileFailureReason`, `FileIngestPort`, `FileRequestSource`, `FileServeGate`, `FilePlaintextSource`, `FileReceiveEnds`, `FileManifest`)                                                                                                    |
| Push                          | `routing/file/FilePusher.kt` (rewritten in R3.1)                                                                                                                                                                                                                                             |
| Relay                         | `routing/file/relay/FileRelayForwarder.kt`, relay door inside `routing/inbound/handlers/FileInboundHandler.kt` (rewritten in R3.2/R3.3)                                                                                                                                                      |
| Requests                      | `routing/file/FileRequestProcessor.kt` (`FileRequestStrategy`, `FileLargeRequestLoop`), core in `routing/sync/CandidateCore.kt`                                                                                                                                                              |
| Fetch                         | `routing/file/fetch/FileFetchManager.kt` (rewritten in R5.4)                                                                                                                                                                                                                                 |
| Receive engine                | `orchestrator/file/DefaultFileReceiveEngine.kt` (implements `FileIngestPort`, `FileCoverageReader`, `FileReceiveEnds`, `FileReceiveControl`; split in R4.1)                                                                                                                                  |
| Request trigger               | `orchestrator/file/FileRequestCoordinator.kt` (implements `FileRequestControl`)                                                                                                                                                                                                              |
| Request source and serve gate | `orchestrator/file/DefaultFileRequestSource.kt`                                                                                                                                                                                                                                              |
| Store                         | `orchestrator/file/FileStore.kt`, `FileDescriptorStore.kt` (reads: `FileDescriptorReader`)                                                                                                                                                                                                   |
| Receive rows                  | `orchestrator/file/FileReceiveStore.kt` (reads: `FileReceiveReader`)                                                                                                                                                                                                                         |
| GUI facade                    | `orchestrator/runtime/file/DefaultFileTransferService.kt`                                                                                                                                                                                                                                    |
| Config                        | `orchestrator/OrchestratorConfig.kt`, `routing/router/RouterConfig.kt`, `wiring/ConfigInvariants.kt`, `wiring/FileLimits.kt`, settings in `wiring/Settings.kt`                                                                                                                               |
| Wiring                        | `wiring/NodeGraph.kt`                                                                                                                                                                                                                                                                        |
| Schema                        | `persistence/*.sq` and the single `persistence/6.sqm`; checked by `persistence/FileMigrationJvmTest`                                                                                                                                                                                         |
| Test doubles                  | `commonTest/.../testfixtures/NoopRouter.kt` (`NoopMessageCarrier`, `NoopSyncCarrier`, `NoopBootstrapCarrier`, `NoopPresence`, `NoopRouter`), `NoopFileCarrier.kt`; `routing/router/RouterTestFixtures.kt` (`FakeFilePorts`, `RefusingFilePorts`, `NoFileReceiveEnds`, in-memory file stores) |

All paths are under `core/src/commonMain/kotlin/org/yapyap/` unless they start with a test source set.

### The loop for every step

1. Read the step, the design sections it names, and every file it names, in full. Do not start editing until
   you can say which classes are deleted, which are created, and who calls what afterwards.
2. Write the step's tests first, against the new interfaces. They must fail (or not compile) for the right reason.
3. Make the change. Delete what the step replaces in the same change.
4. Check each touched file with the IDE lint (`mcp__idea__lint_files`, errors first, then warnings in the files
   you touched). It is much faster than Gradle.
5. Run `./gradlew :core:jvmTest` and, when `commonMain` changed, `./gradlew :core:compileDebugKotlinAndroid`.
   Both must pass. Run the full suite, not a filtered one.
6. Tick the step's "Done when" list item by item, running the `grep` it gives where it gives one.
7. Add an "As landed" note to the step when anything differs from the text, with the reason.
8. Commit only when the user asks.

### Hard rules (a step that breaks one is not done)

- **No new migration file.** Schema changes edit the table's `.sq` and `6.sqm` together; `FileMigrationJvmTest`
  must stay green (it compares the two).
- **No `runCatching` around a suspend call, no `catch (e: Exception)` returning a default.** Inside a loop that
  must survive, catch, rethrow `CancellationException`, and log. Everywhere else let it propagate.
- **No `lateinit`, no `launch`/`collect` in wiring**, no post-construction setters. A component subscribes to its
  inputs in its own `start()`/`runIn(scope)`.
- **One writer per table** (guide §4). If a step needs another component to change a row, add a verb to the owner.
- **Orchestrator code never imports routing internals.** Only `routing.router` and `routing.file.port`
  (checked by `ArchitectureTest`). Never add to its allowlist or to `knownDebt`; restructure instead.
- **No fully qualified names inline** (`org.yapyap.x.Y(...)` inside code). Import it.
- **Do not change** `protocol` wire formats, the public API (`PublicApiSurfaceTest`) or the transport code,
  unless the step says so.
- **Do not weaken a test to make it pass.** If an existing test asserts old behaviour the step changes on purpose,
  change the assertion and say so in the "As landed" note.
- **Ask the user** instead of guessing when the step and the code disagree in a way the step doesn't cover.

---

## Phase R1 — Seams (config, ports, wiring) — done

### Step R1.0 — One file migration (`persistence`)

- **Changes:**
    - Delete `6.sqm`–`15.sqm` and write one `6.sqm`. It drops the placeholder `file_transfers` and
      `file_chunk_tracker` and creates the file tables as they stand.
    - Drop `file_transfers` in the same change (see R4.4); nothing reads it.
    - Replace the per-table migration tests (`File*MigrationJvmTest`) with one test from schema 5 that runs
      `foreign_key_check`.
    - Tell the team to clear their test data directories.
- **Done when:** a fresh data directory boots on the new schema, and a schema-5 database migrates.

### Step R1.1 — One orchestrator config (`orchestrator`, `wiring`, `routing/router`)

- **Changes:**
    - Merge `FileServiceConfig` into `OrchestratorConfig` and delete `FileServiceConfig.kt`. The fields are
      `copyIntoStoreMaxBytes`, `storeQuotaBytes`, `maxStagedBytes`, `fileHashRetryLimit`,
      `autoDownloadMaxBytes`, `fileCheckpointBytes` and `fileCheckpointInterval`, plus their `init` checks.
    - Move `fileStallTimeout` from `RouterConfig` to `OrchestratorConfig`; only the orchestrator reads it.
    - **The orchestrator never reads `RouterConfig`.** On master it doesn't; this branch added it in four
      places.
        - Its only lasting use is the unreferenced-file cleanup (invariant 25). That gets its own value,
          `OrchestratorConfig.unreferencedFileRetention` (default 2 days).
        - The other uses go with `file_transfers` (R4.4).
        - `fileTransferTtl` stays routing's alone. Drop `routerConfig` from the runtime, the messaging
          service, the coordinator and the engine.
        - Drop `routing.router.RouterConfig` from the orchestrator's `ArchitectureTest` allowlist.
    - **Cross-layer relations live in wiring.** Add `wiring/ConfigInvariants.kt`, applied when `ConfigStore`
      loads a config and on every settings change (a violating change is refused as an invalid update).
      It holds every relation between two layers' values, each with a comment saying why:
        - `router.fileTransferTtl < crypto.supersededSessionRetention`;
        - `orchestrator.unreferencedFileRetention ≥ router.fileTransferTtl`: a pushed or relayed file can
          arrive up to a transfer TTL before its message;
        - `orchestrator.maxStagedBytes ≥ router.maxInFlightChunks × chunkSizeBytes`;
        - `router.fileStreamWindowBytes > orchestrator.fileCheckpointBytes`;
        - `orchestrator.fileStallTimeout > router.chunkAckTimeout`: moved out of `RouterConfig.init`, which
          can no longer see it;
        - `orchestrator.onboardingSessionLifetime > router.bootstrapIntroLifetime`: today only a KDoc note.

      Single-layer checks stay in each config's own `init`.
    - Every file component takes `StateFlow<OrchestratorConfig>` and reads `.value` at use, including the
      engine's checkpoint interval.
    - Add a separate coordinator sweep interval (`fileSweepInterval`, > 0). The sweep must not reuse
      `fileRequestFirstAttemptDelay`, which may be 0 and would turn the loop into a busy spin.
    - **Storage pledge: a routing value with a routing split.** Both stores it pays for are routing's: the
      message relay cache (the outbox's relay rows, pruned by `pruneRelayOverCapacity`) and the file relay
      store. So the split is routing policy; it doesn't belong in wiring or in `RuntimeConfig`, which stays a
      pure aggregate.
        - `RouterConfig` keeps `relayStoragePledgeBytes` and adds `fileRelayShare` (default 0.8).
        - It derives two read-only properties, `fileRelayBudgetBytes` and `messageRelayBudgetBytes`, with
          the message share being the rest.
        - `init` requires the share to be in (0, 1).
        - Delete `outboxMaxSizeBytes`: it was the message relay-cache cap under a misleading name.
          `PacketStoreMaintenance` and the file relay admission read the two derived budgets.
        - The settings registry replaces the `outboxMaxSizeBytes` entry with one user entry for the pledge.
          The share stays out of the settings registry unless someone needs to tune it.
    - Rename `supersededRetention` to `supersededSessionRetention`, as design §15 says.
    - **`FileLimits`.**
        - Derive the chunk size from the layers' own overheads, the way `MessageLimits` chains them: `FileEnvelope`
          CHUNK framing, the AEAD tag and `BinaryEnvelope` framing. Each layer states its own overhead next to
          its code.
        - Fail at startup if the result is below a floor; never clamp it up past the transport frame.
        - Add a separate `fetchFrameBytes` for large-path DATA frames, derived from the WebRTC frame limit
          alone.
    - **Settings registry:** add user entries for `autoDownloadMaxBytes`, `copyIntoStoreMaxBytes`,
      `storeQuotaBytes` and the pledge. (Step 23 of `plan.md` then only documents them.)
    - Remove the config TODOs from `Router.kt` and `RouterConfig.kt`.
  - `PickedFile.keepCopy` becomes nullable: null applies `copyIntoStoreMaxBytes` (the threshold was never read).
- **Tests:** each invariant rejects a violating config at load and as a settings change; `FileLimits` with default
  transports gives ≈249 KiB (the 250 KiB WebRTC frame less 475 bytes of framing), and with a tiny transport
  fails; settings round-trip for the new keys.
- **Done when:**
    - no orchestrator code imports `RouterConfig`;
    - every relation between two layers' config values is in `ConfigInvariants`;
    - changing a file setting in `userSettings.toml` reaches the engine without a restart.

### Step R1.2 — Split the file ports (`routing/file`, `orchestrator/file`)

- **Changes:** replace `FileIngestPort` and `FileIngestEngine` with ports that each have one implementer.

  | Port (declared by routing) | Methods | Implemented by |
    |---|---|---|
  | `FileIngestPort` | `checkOffer`, `ingest` (small path), `append` (fetch, step R5.1) | receive engine |
  | `FileRequestSource` | `heldCoverage`, `requestCandidates`, `requestTotalBytes` | request coordinator |
  | `FileServeGate` | `canServe(fileId, requester)` | request coordinator |
  | `FilePlaintextSource` | unchanged until R3.3 | plaintext source |

    - Merge `FileOfferInfo` and `FileDescriptorInfo` into one `FileManifest` (fileId, total bytes, block size,
      block list, name, mime). It is used by the OFFER check, `describe` and `FETCH_OK`.
    - Put every type the orchestrator may see in one package, `routing/file/port`. `ArchitectureTest` then
      allows that package instead of twelve listed classes. Drop the redundant `routing.router.RouterConfig`
      entry.
    - `FileIngestPort.checkOffer` and `ingest` return `Defer` on transient failures (see B7 of the review);
      only real ENOSPC is `NO_SPACE`.
- **Tests:** existing tests move to the new ports; `ArchitectureTest` allowlist shrinks.

### Step R1.3 — Events stop looping through routing (`routing/file`, `orchestrator/file`)

- **Changes:**
    - Split `FileEvent` by owner.
        - Routing emits only what it knows:
            - per-transfer outcomes, aggregated per account (see R3.1);
            - request outcomes (satisfied, unavailable, no direct connection);
            - serve started.
        - The receive engine owns `IncomingProgress`, `Ready` and receive failures on its own flow, which
          the coordinator and the facade read.
    - Remove `FileCarrier.emit`.
    - Add `ENDED` to `FileFailureReason`; routing never reports an ENDED transfer as `FAILED` (design §6.1,
      §14).
    - A target's CANCEL keeps its reason: no space reads as `DECLINED` (design §8).
- **Tests:** the orchestrator's event consumers see each event once, from its owner.

### Step R1.4 — The Router split (`routing/router`, `wiring`, every orchestrator caller)

- **Changes:** `Router` keeps only its lifecycle and the per-concern services, each a narrow interface
  declared in routing:

  | Service | Today on `Router` |
    |---|---|
  | `MessageCarrier` | `sendMessage`, `incomingMessages`, `deliveries`, `sendTypingIndicator`, `typingIndicators` |
  | `SyncCarrier` | `startSync`, `reopenSync`, `pingPayloads`, `announceOnline` |
  | `BootstrapCarrier` | `sendBootstrap`, `bootstrapPackets` |
  | `Presence` | `onlineAccounts`, `accountReach`, `localLinkStatus`, `localTorEndpoint` |
  | `FileCarrier` | `pushFile`, `cancelFile`, `fileReady`, `cancelIncoming`, `startFileRequest`, `abortFileFetch`, events |

    - Each orchestrator component takes only the service it uses (for example, `DefaultFileTransferService`
      no longer takes the whole `Router`).
    - **Break the construction cycle.**
        - Wiring builds the file stores, the receive engine and the coordinator first, against routing's port
          interfaces. Then it builds the router with the ports.
        - The engine and coordinator need `FileCarrier` only for actions (`fileReady`, `startFileRequest`).
          Wiring passes it through a constructor-injected interface that the router implements, built in two
          phases like `Vault`/`Node` if the cycle remains. The default is to make the engine's outbound
          actions events the router subscribes to, so the cycle disappears.
        - Delete `DelegatingFileIngestPort` and the `lateinit` in `FileRequestStrategy`.
    - `DefaultRouter` stops constructing file internals inline. It takes a `FileRouting` assembly (pusher,
      handler, request processor, relay, fetch) built by wiring, the same way it takes stores today. No
      fully-qualified names inline.
    - Revert the unrelated churn:
        - the `IoDispatcher` expect/actual: use `Dispatchers.IO` from common code, which restores the Tor
          backends and keeps the Tor selector off Default on iOS;
        - the `DataDirLock.android.kt` test fallback (fix the test instead).

      Keep the `announceOnline` move out of wiring, but call it through `SyncCarrier`.
    - Update [`../router.md`](../router.md) and guide §2 for the service split.
- **Tests:** existing router and orchestrator tests compile against the services; `NoopRouter` becomes per-service
  no-ops.
- **Done when:** no `lateinit` remains in file code or wiring, and `Router` has no file, sync or message methods
  of its own.
- **As landed:**
    - The router's file machinery is assembled in `routing/file/FileRouting`, which `DefaultRouter` builds from its
      shared pieces (routing context, outbox, session opener, peer policy). Wiring passes only `FileRoutingPorts`
      (the stores and orchestrator ports). Building the assembly in wiring would mean pulling the router's
      internals out with it; R3 can revisit this once the push engine replaces the pusher.
    - The cycle is gone by splitting the coordinator. `DefaultFileRequestSource` implements `FileRequestSource` and
      `FileServeGate` (reads only, built before the router). The coordinator keeps the trigger loop and takes
      `FileCarrier`. The engine reports ended receives on a routing-declared port, `FileReceiveEnds` (a buffered,
      single-consumer flow the router collects), instead of calling `fileReady`/`cancelIncoming`, which left
      `FileCarrier`.
    - `FileRequestStrategy`'s `lateinit core` became `CandidateStrategy.attemptState(): AttemptState`
      (`Pending` / `None` / `Unanswered(device)`); the core records the silent device itself.
    - `Node.announceOnline()` replaces reaching through `node.setup.router`; `LocalSetup` takes `Presence` and
      `BootstrapCarrier`.
    - `DataDirLockTest` moved to `jvmTest` (Android local unit tests stub `android.os.Process`).

### Step R1.5 — One writer per file table (`orchestrator/file`, `orchestrator/runtime`)

- **Changes:**
    - `file_receives` is written only by the receive engine. The coordinator and the facade call engine verbs:
      `markStalled`, `decline`, `reopen` (accept), `evicted`. Each verb is a guarded transition under the
      engine's lock. Example: `markStalled` only acts on IN_FLIGHT, so the gap trigger can never overwrite
      READY.
    - `file_descriptors` is written only by `FileStore`. The plaintext source reports a bad block to it
      (`FileStore.markSourceLost`), and the engine hands it a finished file (`FileStore.adopt`).
    - `pending_file_requests` is written only by the coordinator. `acceptFile` and `declineFile` go through it.
    - `DefaultOrchestratorRuntime` gets the engine, coordinator and store as verb interfaces, not five
      write-capable stores.
    - Remove the test-only write `MessageRepository.insertAttachment` and the test-only read
      `findFileIdsByMessageId`. Tests seed through the real `insert`.
- **Tests:** the gap trigger racing a completion leaves the file READY (design §19 gap-trigger row).
- **Done when:** guide §4's file rows name exactly one writer each, and the code matches.
- **As landed:** the routing row of guide §4 still lists several writers (the carrier also drops incoming rows when
  it answers an ended receive); R3.2 settles it. The `evicted` verb is left to R4.2, which is the first step that
  writes EVICTED. `FileStore` also gained `forget` (the unreferenced-receive cleanup's descriptor delete).

---

## Phase R2 — Shared pieces — done

### Step R2.1 — One visibility rule (`orchestrator/sync`, `orchestrator/file`, `orchestrator/store`)

**Goal:** one function decides "may account A see message M", used by both sync serving and the file serve
gate, with no depth cap.

- **Read first:** `orchestrator/sync/DefaultSyncPayloadProvider.kt` (the membership gate, around the
  `memberRowOf` / `removalNodeId` code), `orchestrator/file/DefaultFileRequestSource.kt` (`isVisibleTo`, a BFS
  capped at 512 steps), `orchestrator/store/RoomRepository.kt` (`RoomMember`), and the recursive CTE in
  `persistence/Message.sq` used by `selectIsRenderable` (copy its shape).
- **Create** `orchestrator/sync/RoomVisibility.kt`:
  ```kotlin
  /** Who may see a message: the one rule sync serving and the file serve gate share. */
  internal interface RoomVisibility {
      /** True when [account] may see [messageId]: ACTIVE in its room, or REMOVED and the message is
       *  the removal node or one of its ancestors. False for unknown messages and non-members. */
      suspend fun isVisible(messageId: Uuid, account: AccountId): Boolean
  }
  internal class DefaultRoomVisibility(messages: MessageRepository, rooms: RoomRepository) : RoomVisibility
  ```
- **Add one query** to `Message.sq` (no schema change, so no `6.sqm` edit):
  ```sql
  isAncestorOrSelf:
  WITH RECURSIVE ancestors(id) AS (
      SELECT :removalNode
      UNION
      SELECT parent_id FROM message_parents JOIN ancestors ON message_parents.message_id = ancestors.id
  )
  SELECT EXISTS (SELECT 1 FROM ancestors WHERE id = :messageId);
  ```
  Expose it as `MessageRepository.isAncestorOrSelf(messageId, of: removalNode): Boolean` (implement it in
  `DefaultMessageRepository` and in `testfixtures/Repositories.kt` `FakeMessageRepository` with a plain graph walk).
- **Rule:** `memberRowOf(room, account)`; ACTIVE → true; REMOVED with a `removalNodeId` →
  `isAncestorOrSelf(messageId, removalNodeId)`; anything else → false. Do not catch exceptions; let them
  propagate.
- **Use it:**
    - `DefaultSyncPayloadProvider`: replace its inline membership check with `RoomVisibility` (keep the
      behaviour: what it served before it serves now).
    - `DefaultFileRequestSource.canServe`: for each message id from `findMessageIdsByFileId`, return true on the
      first `isVisible`. Delete `isVisibleTo` and the BFS.
    - Wire one `DefaultRoomVisibility` in `NodeGraph` and pass it to both.
- **Tests** (`commonTest/.../orchestrator/sync/RoomVisibilityTest.kt`, real repositories on in-memory SQLite):
    - ACTIVE member sees any message of the room; a non-member sees nothing; an unknown message is false.
    - REMOVED member sees the removal node and its ancestors, not messages after it.
    - A chain of 1000 messages: the REMOVED member sees the first one (the old BFS stopped at 512).
    - Parity: the same fixture through `DefaultSyncPayloadProvider` serving and `DefaultFileRequestSource.canServe`
      gives the same yes/no for every case.
- **Design:** in `design.md` §9, after the serve-gate rule, add: "A message that references a file shares it
  with everyone who can see that message, its author included. This is intended."
- **Done when:**
    - `grep -rn "512" core/src/commonMain/kotlin/org/yapyap/orchestrator` finds nothing related to visibility;
    - `isVisibleTo` no longer exists;
    - both callers take `RoomVisibility`.
- **As landed:** `RoomVisibility` also has `scope(roomId, account): VisibilityScope` (`All` / `UpTo(removalNode)` /
  `None`), and `isVisible` is built on it. Sync serving asks `scope` once per request and keeps its old structure
  (`None` → empty answer, `UpTo` → walk from the removal node, `All` → walk from the missing ids). A first version
  called `isVisible` per walked message: three queries plus a recursive query each, and a non-member could make us
  walk the whole room. The file serve gate uses `isVisible`. `RoomVisibilityTest` lives in jvmTest.

### Step R2.2 — The candidate core, generalised (`routing/candidate`, `routing/sync`, `routing/file`)

**Goal:** one asking loop for sync, small file requests and large file fetches. Today the large path has its
own copy (`FileLargeRequestLoop`) with copied backoff, and small and large rows hide each other in one due query.

- **Read first:** `routing/sync/CandidateCore.kt` (`CandidateStrategy`, `CandidateAttemptStore`, `CandidateRow`,
  `BuiltAttempt`, `AttemptState`, `CandidateRequestCore`), `routing/sync/SyncRetryProcessor.kt`,
  `routing/file/FileRequestProcessor.kt` (`FileRequestStrategy`, `FileLargeRequestLoop`), `FileRequestAttempt.sq`,
  `routing/store/FileRequestAttemptStore.kt`, and design §9.
- **Move** `CandidateCore.kt` to package `routing/candidate` (file `routing/candidate/CandidateCore.kt`). Update
  imports; no behaviour change from the move itself.
- **Narrow the core's dependencies.** `CandidateRequestCore` takes:
    - `executor: AttemptExecutor<K>` instead of `OutboxProcessor`;
    - `peerPolicy: SyncPeerPolicy` (already an interface; keep it);
- **Pluggable executor.** Add to `CandidateCore.kt`:
  ```kotlin
  /** How one attempt leaves: queued through the outbox, or run as a suspending call. */
  internal sealed interface AttemptExecutor<K> {
      /** Queue [BuiltAttempt] through the outbox; the outcome comes back via onAttemptOutcome. */
      class Outbox<K>(val outbox: OutboxProcessor) : AttemptExecutor<K>
      /** Run the attempt as a call (a large fetch). At most [maxConcurrent] run at once; the core
       *  records the attempt (recordAttempt) before calling, so a crash mid-call never re-asks at once. */
      class Call<K>(val maxConcurrent: Int, val run: suspend (id: K, device: PeerId) -> CallOutcome) : AttemptExecutor<K>
  }
  internal sealed interface CallOutcome {
      data object Satisfied : CallOutcome          // drop the row
      data object Declined : CallOutcome           // addDeclined(device), ask the next one
      data object Unanswered : CallOutcome         // noteSilent(device), backoff
      data object NoDirectConnection : CallOutcome // keep PENDING, backoff (design §6.6)
  }
  ```
  With `Call`, the core launches each attempt in its own child job (bounded by a `Semaphore(maxConcurrent)`)
  and never blocks the due loop on it; a row with a running call is skipped by the due loop (keep an in-memory
  `running: MutableSet<K>` guarded by a mutex).
- **WAITING becomes a core state.** Replace `AttemptState` with a richer success: `onSuccess(id, device)`
  returns `SuccessAction`:
  ```kotlin
  internal sealed interface SuccessAction {
      data object Done : SuccessAction                       // drop the row (sync)
      data class AwaitUntil(val until: Instant) : SuccessAction // file REQUEST ACK: WAITING for the OFFER
  }
  ```
  The core persists WAITING through two new `CandidateAttemptStore` methods, `setWaiting(id, device, until)` and
  `clearWaiting(id)`; when `until` passes with no `drop`, the core itself calls `noteSilent(id, device)` and asks
  the next candidate. Delete `AttemptState.Unanswered` and the WAITING logic in
  `FileRequestStrategy.attemptState`; `attemptState` returns only `Pending`/`None` (queued in the outbox or not).
  Make `noteSilent` private.
- **Drop before choosing a candidate.** Add `suspend fun isWanted(id: K): Boolean` to `CandidateStrategy`. The
  core calls it first in `processDueRow`; false → `attempts.drop(id)` and return. Sync implements it as
  `syncs.syncRequest(id) != null`; small files as `requests.heldCoverage(id) != null`; large files the same.
  This restores what the step-15 refactor broke: a satisfied sync whose candidates all lack a link was never
  dropped.
- **Separate classes in SQL.** Add `request_class TEXT NOT NULL` (`SMALL` | `LARGE`) to `file_request_attempts`
  in `FileRequestAttempt.sq` **and** `6.sqm`. `start(id, class, firstAttemptAt)` sets it (the caller knows the
  class from `FileRequestSource.requestTotalBytes` vs `SMALL_FILE_MAX_BYTES`). `findDue(now, limit, class)` filters
  by it. Two core instances run, one per class, each with its own due query. Delete the `isSmall`/`isLarge`
  in-memory filters.
- **Large path on the core.** A `FileFetchStrategy` (in `routing/file`) with `AttemptExecutor.Call`, whose `run`
  opens the session (`sessionOpener.awaitSession(device, fileSessionOpenTimeout)`), calls
  `FileFetchManager.fetch(...)` and maps the result to `CallOutcome`. Delete `FileLargeRequestLoop` and its backoff.
  `maxConcurrent` comes from a new `RouterConfig.maxConcurrentFetches` (default 2, `> 0` in `init`).
- **Tests:**
    - `SyncRetryProcessorTest` passes unchanged (only imports may change);
    - a satisfied sync whose only candidate has no link is dropped on its next due pass;
    - ten due LARGE rows and one due SMALL row: the SMALL REQUEST is enqueued in the first pass;
    - two large fetches run at once (a `Call` that suspends on a `CompletableDeferred`; both start before either
      completes);
    - while a fetch runs, its row is not processed again (count `run` invocations);
    - a REQUEST ACK sets WAITING; when it expires the next candidate is asked and the first is tried last.
- **Done when:** `FileLargeRequestLoop` and `routing/sync/CandidateCore.kt` no longer exist;
  `grep -rn "isSmall\|isLarge" routing/file`
  finds nothing; `noteSilent` is private.
- **As landed:**
    - WAITING lives only in the stored row: `CandidateRow.waiting: CandidateWaiting?` is read from
      `waiting_device`/`waiting_until`, and the core has no in-memory copy. A WAITING row sleeps until its deadline,
      then its holder goes silent and the next candidate is asked (after a restart too).
    - `processDue` wakes itself only after a full page. It used to wake unconditionally (inherited from master's
      `SyncRetryProcessor`), so every candidate loop spun without sleeping. `earliestDueAt` and the link-up id list
      are per request class, and a row with a running call is postponed, not skipped.
    - `AttemptExecutor.Call.maxConcurrent` is a `Flow<Int>` read live (`RouterConfig.maxConcurrentFetches`). Calls run
      as children of the due loop's job; there is no stored scope.
    - `FileRequestProcessor` always takes the session opener and fetch manager, and routes `onDeclined` and OFFER
      arrival to the loop that owns the row. Unexpected fetch failures propagate to the core, which logs them and
      counts the attempt unanswered.

### Step R2.3 — Outbox fixes (`routing/outbound`, `routing/store`, `routing/inbound`, `Outbox.sq`)

**Goal:** the outbox treats file traffic fairly and predictably, each outcome has one consumer, and a failing
handler never turns a retry into a silent duplicate.

- **Read first:** `Outbox.sq` (`getDueIdsNonFile`, `getDueIdsFile`), `routing/store/DefaultPacketOutbox.kt`
  (`dueIds`, around line 137), `routing/outbound/OutboxProcessor.kt` (claim loop around line 273, `startable`
  around 326, retry delay around 370), `routing/policy/RelayBackupPolicy.kt`, `routing/store/PacketOutbox.kt`
  (`OutboxTag.Kind`), `routing/inbound/InboundEnvelopeProcessor.kt` (`handle`, around line 226), and
  `routing/file/FileRouting.kt` (`onOutboxOutcome`).
- **1. Fair due query.** The per-target in-flight cap is applied in memory after a global `LIMIT`, so 40 due rows
  for one capped peer hide every other peer.
    - Add a `busyTargets: Collection<PeerId>` parameter to both due queries:
      `AND target_device_id NOT IN :busyTargets`. SQLDelight expands collections; when the set is empty pass a
      sentinel that matches nothing (SQLite rejects `IN ()`), e.g. `listOf(PeerId(""))`.
    - `OutboxProcessor` computes `busyTargets` from `inFlight` (targets at `outboxMaxInFlightPerTarget`) under
      `inFlightLock` and passes it to `PacketOutbox.dueIds`. `startable` uses the same call, so it can never say
      "nothing to do" while another peer has due rows.
- **2. One consumer per tag.** Split `OutboxTag.Kind.FILE` into `FILE_PUSH` (our transfers → pusher) and
  `FILE_FORWARD` (relay forwards → relay forwarder). Keep `FILE_REQUEST`. Give the new kinds new codes (do not
  reuse FILE's code for one of them, so a stale row can't be misrouted; old rows are gone anyway because data
  directories are reset). The pusher enqueues `FILE_PUSH`, the forwarder `FILE_FORWARD`.
  `FileRouting.onOutboxOutcome` routes each kind to exactly one of them; delete the "pusher ignores unknown ids,
  forwarder drains its own" double delivery. The due-query split becomes "file kinds" (`FILE_PUSH`, `FILE_FORWARD`)
  vs the rest.
- **3. Retry by packet role.** Only chunk rows retry at the flat `fileChunkRetryDelay`; OFFER markers and REQUESTs
  use the message schedule (design §6.1).
    - Add `retry_class INTEGER NOT NULL DEFAULT 0` to `outbox` (0 = standard, 1 = flat). The outbox is not a file
      table, so the change is an `ALTER TABLE outbox ADD COLUMN ...` statement **appended to `6.sqm`** plus the
      column in `Outbox.sq`'s `CREATE TABLE`. Add `"outbox"` to the compared tables in `FileMigrationJvmTest` and an
      `ALTER TABLE outbox DROP COLUMN retry_class` to `revertFileMigration`.
    - `PacketOutbox.enqueue` gets `retryClass: RetryClass` (enum `STANDARD`, `FLAT`, default `STANDARD`).
      `FileOutbox.enqueue` passes `FLAT` only for chunk envelopes (the pusher and forwarder know what they built;
      never decode the envelope to find out).
    - The retry branch in `OutboxProcessor` reads the row's class instead of the tag kind.
- **4. Remove `RelayBackupPolicy`.** Whether a row backs up is already its `RelayBackup` value (PENDING only on
  authoring markers and messages). Delete `routing/policy/RelayBackupPolicy.kt`, its uses in `OutboxProcessor` and
  `OutboundMessenger`, and the `isOfferMarker` decoding. The retry branch backs up when the row's `RelayBackup`
  is PENDING; check `git diff master -- routing/outbound/OutboundMessenger.kt` and restore master's behaviour there.
- **5. Outcomes never block the collector.** `DefaultRouter.onOutboxOutcome` runs on the single outcome collector;
  the pusher encrypts and inserts inside it. Give `FileRouting` a `Channel<OutboxOutcome>(UNLIMITED)`;
  `onOutboxOutcome` only `trySend`s, and a loop started in `FileRouting.runIn` consumes it (catching and logging
  per outcome, rethrowing `CancellationException`). (R3.1 replaces this with per-transfer actors.)
- **6. Clear dedup when a handler throws.** In `InboundEnvelopeProcessor.handle`, wrap `handler.handle(inbound)`:
  on `CancellationException` rethrow; on any other exception call
  `ctx.packetDeduplicator.clearPacket(inbound.packetId, inbound.source)`, log `ENVELOPE_HANDLE_FAILED`, and rethrow.
  Nothing is ACKed (an exception never reaches the disposition code).
- **Tests** (against the real SQL outbox: `DefaultPacketOutbox` on `openMemoryDatabase()`, not `TrackingPacketOutbox`):
    - 40 due messages for peer A (capped at 2 in flight) and 1 for peer B: B's message is claimed in the first pass;
    - a text message queued behind 16 due chunk rows to the same peer is claimed first;
    - an OFFER marker's second attempt is scheduled at the message retry delay, a chunk's at `fileChunkRetryDelay`;
    - a `FILE_PUSH` outcome reaches only the pusher, a `FILE_FORWARD` outcome only the forwarder;
    - a handler that throws on the first delivery and succeeds on the retry: the retry is handled and ACKed, not
      treated as a duplicate;
    - `FileMigrationJvmTest` still green with `outbox` compared.
- **Done when:** `grep -rn "RelayBackupPolicy\|Kind.FILE\b" core/src` finds nothing; the retry branch has no tag check.
- **As landed:** `OutboxEntry` carries the row's `relayBackup`; the retry, no-link and no-ACK-in-time paths only try a
  backup for PENDING rows (the claim query stays the gate), so chunks start no backup timer.

### Step R2.4 — Each carrier reports its own deliveries (`routing/router`, `orchestrator/runtime/message`,

`orchestrator/onboarding`)

**Goal:** `MessageCarrier.deliveries` carries bootstrap ACKs too, so the onboarding provider depends on the message
service only to see them. Each carrier reports the ACKs of what it sent. Independent of the other R2 steps.

- **Read first:** `routing/router/MessageCarrier.kt`, `BootstrapCarrier.kt`, `RoutingTypes.kt` (`Delivery`,
  `DeliverySubject`), `DefaultRouter.onOutboxOutcome` (the MESSAGE and BOOTSTRAP branches),
  `orchestrator/runtime/message/DeliveryTracker.kt`, `orchestrator/onboarding/DefaultOnboardingProvider.kt`.
- **Change:**
    - `MessageCarrier.deliveries: Flow<MessageDelivery>` with
      `data class MessageDelivery(val messageId: Uuid, val account: AccountId)`.
    - `BootstrapCarrier.deliveries: Flow<BootstrapDelivery>` with
      `data class BootstrapDelivery(val packetId: Uuid, val account: AccountId?)`.
    - Delete `Delivery` and `DeliverySubject`. `DefaultRouter` keeps two flows and emits into the right one in
      `onOutboxOutcome`.
    - `DeliveryTracker` reads `MessageCarrier.deliveries`; `DefaultOnboardingProvider` reads
      `BootstrapCarrier.deliveries` and no longer takes `MessageCarrier`. Update `NodeGraph`, the
      `NoopMessageCarrier`/`NoopBootstrapCarrier` doubles, and the KDoc that points between the two carriers.
- **Tests:** existing `DeliveryTrackerTest` and `DefaultOnboardingProviderTest` adapted to the new types; one router
  test that a BOOTSTRAP ACK appears only on `bootstrap.deliveries` and a MESSAGE ACK only on `messages.deliveries`.
- **Done when:** `grep -rn "DeliverySubject" core/src` finds nothing; `DefaultOnboardingProvider` has no
  `MessageCarrier` parameter.
- **As landed:** the types are named `MessageAck` (non-null account) and `BootstrapAck`, so they don't clash with the
  public `orchestrator.runtime.message.MessageDelivery`.

---

## Phase R3 — The small path

### Step R3.1 — The push engine (`routing/file/push`)

**Goal:** replace `routing/file/FilePusher.kt` (about 830 lines, shared mutable state, a window keyed on coverage)
with a per-transfer actor engine whose window counts queued chunk rows. Needs R2.3 (tag `FILE_PUSH`, retry
class, outcome channel).

- **Read first:** `FilePusher.kt` in full (it is the behaviour you are replacing; list every public method and
  who calls it before deleting it), `FileRouting.kt`, `routing/store/FilePushStore.kt` + `FilePush.sq`,
  `routing/file/FileControlEnvelopes.kt`, the CHUNK/ACK handling in `routing/inbound/handlers/FileInboundHandler.kt`,
  design §3.3–§3.6, §5, §6.1–§6.4, §12, §14, invariants in §16.
- **New package `routing/file/push`, files:**
    - `PushInput.kt` — the actor's mailbox:
      ```kotlin
      internal sealed interface PushInput {
          data class Outcome(val outcome: OutboxOutcome) : PushInput
          data class CoverageAck(val contiguousChunks: Int, val missing: List<ChunkRange>) : PushInput
          data object Complete : PushInput
          data class Cancelled(val reason: FileCancelReason) : PushInput  // from the target
          data object UserCancel : PushInput
          data object LinkClosed : PushInput
          data object Tick : PushInput                                     // stall/deposit/expiry check
      }
      ```
    - `ChunkSource.kt` —
      `interface ChunkSource { suspend fun offer(): BinaryEnvelope; suspend fun chunk(index: Int): ChunkResult }`
      with `ChunkResult.Envelope(BinaryEnvelope)` / `ChunkResult.Deferred` (seal not possible now) /
      `ChunkResult.SourceLost`. Implementations: `PlaintextChunkSource` (reads `FilePlaintextSource`, encrypts with
      the row key through protection, builds the envelope with `buildFileControlEnvelope`-style code) and, in R3.3,
      `StoredEnvelopeChunkSource`.
    - `TransferActor.kt` — one coroutine per live `file_pushes` row, reading a `Channel<PushInput>(UNLIMITED)`.
      State it owns (in memory, rebuilt on start): `queued: Set<Int>` (chunk indices with a row in the outbox),
      `nextToQueue: Int`, `resumeFrom: Int` (from coverage), `lastAckAt: Instant`.
      Rules:
        - after the marker is ACKed, enqueue chunks while `queued.size < maxInFlightChunks` and
          `nextToQueue < chunkCount`; a chunk leaves `queued` when its outcome arrives (ACK, terminal NACK, or expiry);
        - a coverage ACK sets `resumeFrom = max(resumeFrom, contiguousChunks + 1)` and drops indices below it from
          the "still to send" range; it never changes the window count;
        - persist progress with `setProgress(transferId, nextToQueue, resumeFrom - 1)` (the columns keep their names;
          only the window's meaning changes);
        - terminal transitions write `setStatus` once and call `deleteByTag(FILE_PUSH, transferId)`.
    - `FilePushEngine.kt` — owns the actor map (`Mutex`-guarded `Map<TransferId, TransferActor>`), routes inputs by
      `transferId` (outcomes by `tag.id`), starts actors for `pushes.live()` on `runIn`, and runs one ticker that
      sends `Tick` to every actor every `chunkAckTimeout / 3` (at least 5 s, as today).
    - `FileSender.kt` — the account-level side, implementing `FilePushOps` (`pushFile`, `cancelFile`, `serveFile`):
      fan-out to the account's devices, serve-with-supersede (a SERVE for (file, device) cancels a live push of the
      same pair first), and the per-account event aggregation:
        - `OutgoingProgress` = minimum covered bytes across the account's live device transfers;
        - `OutgoingComplete` once, when the first device of the account completes;
        - `Failed` only when every device transfer of the account is terminal and none completed, with the reason
          of the last one (ENDED wins over EXPIRED when mixed).
    - `FileDepositPolicy.kt` — the gone-only triggers (design §6.3): `LinkClosed` input, no link at claim time (from the
      outbox's no-link outcome), and no chunk ACK for `chunkAckTimeout` while chunks are queued. A round
      happens once per source transfer: add `deposited_at INTEGER` to `file_pushes` (`FilePush.sq` and `6.sqm`) and
      claim it with `UPDATE file_pushes SET deposited_at = :now WHERE transfer_id = :id AND deposited_at IS NULL`;
      only a claim that changed one row deposits.
- **Send state.** Add to `FileCarrier`: `suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState>`, with
  `FileSendState` (in `routing/file/port`)
  `data class FileSendState(val status: FileSendStatus, val sentBytes: Long, val totalBytes: Long)`
  and `enum class FileSendStatus { SENDING, COMPLETED, ENDED, EXPIRED, DECLINED, CANCELLED, FAILED }`. It is computed
  from `file_pushes` (non-DEPOSIT rows) with the same aggregation rules as the events. No new table.
- **Receiver-side fix (in `FileInboundHandler`, kept for R3.2 to move):** the coverage ACK says chunk `k` is covered
  iff `(k + 1) * chunkSize <= P` or `P == totalBytes`; a `FileIngestResult.Duplicate` also answers with a coverage
  ACK (today it answers nothing useful).
- **Other fixes, each with a test:**
    - restart rebuilds `queued` from outbox rows still tagged `FILE_PUSH` + transferId (`PacketOutbox` needs
      `packetIdsByTag(tag)`; the chunk index is recoverable because the actor records `packetId → index` in a new
      `file_push_chunks(transfer_id, chunk_index, packet_id)` table — add it to `FilePush.sq` and `6.sqm`, written
      only by the engine); it never deletes and re-seals;
    - an OFFER is never re-sent under the same transferId; if one must go again, `FileSender` starts a fresh transfer;
    - any COMPLETE for (file, target device) resolves the authoring row COMPLETED and emits once; DEPOSIT rows of the
      same source are then cancelled politely;
    - ACK, COMPLETE and CANCEL are acted on only when `fileEnvelope.source` is the row's target device (or, for a
      deposit, its relay); otherwise ignored (logged at debug);
    - `ChunkResult.Deferred` re-arms on the actor's own `Tick`, not on the next `pushFile`;
    - `pushFile` refuses files above `SMALL_FILE_MAX_BYTES` (log and return; no row);
    - the ticker also expires live rows past `expires_at` (EXPIRED) and deletes terminal rows older than
      `fileTransferTtl` together with their key (invariant 5).
- **Wire:** `FileRouting` builds `FilePushEngine` + `FileSender` instead of `FilePusher`; `depositByMarkerPacket`
  moves to `FileDepositPolicy`. Delete `FilePusher.kt` and its tests (`FilePusherTest`), replacing them with
  `FilePushEngineTest`, `FileSenderTest`, `FileDepositPolicyTest`.
- **Tests (realistic):**
    - a 10 MB file between two real routers (copy the setup of `FileSmallPushTwoRouterTest`) with default
      `OrchestratorConfig` reaches READY — this is the case that deadlocks today;
    - a restart mid-push (stop router, rebuild from the same stores, start) resumes without new seals (count
      `encryptChunk` calls);
    - deposits happen once across ten ticks and a restart;
    - a deposit's COMPLETE before the direct one gives COMPLETED once;
    - a CANCEL whose source is a relay is ignored;
    - 50 concurrent coverage ACKs never enqueue a chunk index twice.
  These cover design §19's rows on mid-transfer restarts, deposit triggers, "stall → immediate deposit" and
  "late chunk after READY".
- **Done when:** `FilePusher` does not exist; `grep -rn "contiguousAcked" routing/file/push` shows it is used only for
  progress, never in the window check.

### Step R3.2 — The inbound handler, split (`routing/inbound/handlers`, `routing/file`)

**Goal:** `FileInboundHandler` (one class doing the target side, the relay door and serving) becomes a thin
dispatcher, and `file_incoming_transfers` gets exactly one writer. Needs R3.1.

- **Read first:** `routing/inbound/handlers/FileInboundHandler.kt` in full,
  `routing/store/FileIncomingTransferStore.kt`,
  `routing/file/DefaultFileCarrier.kt` (`endIncoming` deletes incoming rows today: the second writer),
  `FileRouting.kt`, design §3.5–§3.6, §7.1.
- **One writer for `file_incoming_transfers`.** Create `routing/file/FileIncomingTransfers.kt`, the only holder of
  `FileIncomingTransferStore`:
  ```kotlin
  /** Owner of file_incoming_transfers: the target side's per-transfer rows (keys included). */
  internal class FileIncomingTransfers(private val store: FileIncomingTransferStore, private val clock: Clock) {
      suspend fun find(transferId: TransferId): FileIncomingTransfer?
      suspend fun accept(row: FileIncomingTransfer)                      // OFFER accepted
      suspend fun end(transferId: TransferId)                            // CANCEL from the author, chunk refused
      suspend fun endAllFor(fileId: FileId): List<FileIncomingTransfer>  // receive ended: returns the rows to answer
      suspend fun expire(now: Instant): List<FileIncomingTransfer>       // the ticker's TTL sweep
  }
  ```
  `DefaultFileCarrier.onReceiveEnd` calls `endAllFor` and answers the returned authors; it no longer holds the store.
  Update guide §4's routing row so each routing file table names one writer.
- **Split the handler** into three classes in `routing/inbound/handlers`:
    - `FileTargetHandler` — envelopes whose inner target is this device: OFFER (open the sealed half, `checkOffer`,
      `accept`), CHUNK (decrypt, `ingest`, coverage ACK), ACK / COMPLETE / CANCEL (to the push engine);
    - `FileRelayDoor` — inner target is another device: admission and storage (R3.3 moves the forward);
    - `FileServeHandler` — REQUEST: serve gate, then `FilePushOps.serveFile`.
      `FileInboundHandler` keeps only decode-and-dispatch (inner target local or foreign; REQUEST vs the rest).
- **Signals to the push engine through one interface**, replacing the five lambdas:
  ```kotlin
  internal interface FileTransferSignals {
      suspend fun coverageAck(transferId: TransferId, from: PeerId, contiguousChunks: Int, missing: List<ChunkRange>)
      suspend fun complete(transferId: TransferId, from: PeerId)
      suspend fun cancelled(transferId: TransferId, from: PeerId, reason: FileCancelReason)
  }
  ```
  `FilePushEngine` implements it (the `from` parameter is what R3.1's authorisation checks). `onOfferReceived`
  becomes a call on the request processor through `FileRouting`, and `onRelayStored` goes away in R3.3.
- **Validate the OFFER's public half** on the target path exactly as the relay door does: `chunkSizeBytes > 0`,
  `totalBytes > 0`, `0 <= startIndex <= chunkCount`; violation → `Rejected(PERMANENT_INVALID)` (use the existing
  NACK reason the door uses).
- **Exceptions:** the handlers let exceptions propagate; R2.3's processor-level clear-dedup covers them. Expected
  transient states return `Deferred` explicitly.
- **Tests:** a re-sent OFFER for a known transferId is handled idempotently (one incoming row, ACKed); a
  `chunkSizeBytes = 0` OFFER is rejected permanently; the stale `foreignTarget_isDeclined` test is deleted; a receive
  end deletes the incoming rows and answers each author once.
- **Done when:** only `FileIncomingTransfers` holds `FileIncomingTransferStore`
  (`grep -rn "FileIncomingTransferStore" core/src/commonMain`
  shows only the store, `FileIncomingTransfers`, `FileRoutingPorts` and wiring); `FileInboundHandler` is under 150
  lines.

### Step R3.3 — The relay on the push engine (`routing/file/relay`)

**Goal:** a relay forwards a whole deposit with the same engine that pushes our own files, and admission,
eviction and cleanup can never leave orphan rows. Needs R3.1 and R3.2.

- **Read first:** `routing/file/relay/FileRelayForwarder.kt`, `routing/store/FileRelayStore.kt` + `FileRelay.sq`,
  the relay door (now `FileRelayDoor`), design §6, §7.1–§7.2.4.
- **Forward on the engine.** Delete `FileRelayForwarder`. When a relay transfer becomes WHOLE, `FileRelayDoor` asks
  `FilePushEngine` to start a forward actor with `StoredEnvelopeChunkSource` (replays the stored OFFER payload and
  chunk blobs verbatim, so the author's inner signature stays intact). Forward actors enqueue with tag
  `FILE_FORWARD`, never run `FileDepositPolicy`, and on COMPLETE/terminal delete the relay transfer (forget after
  delivery). They are rebuilt on restart from `file_relay_transfers` rows in FORWARDING/STREAMING.
- **One transaction per state change** in `FileRelayStore` (use `database.transaction { }` inside the store):
    - `admit(offer, reserveBytes, budget)`: evict oldest whole transfers until it fits, insert the new row, return the
      evicted transfer ids — all in one transaction;
    - `evict(transferId)` and `delete(transferId)` also delete that transfer's `FILE_FORWARD` outbox rows. The outbox
      is another table owner, so the store returns the ids and `FileRelayDoor` calls `PacketOutbox.deleteByTag` right
      after the commit; document the order (relay rows first, then outbox rows; a crash in between leaves outbox rows
      whose outcome the engine ignores because no actor exists).
- **Relay ACKs.** The door answers each stored chunk with a coverage ACK built from the chunks stored so far (same
  rule as the target: contiguous stored chunks from `startIndex`), so the depositor's window advances.
- **Idle reservation TTL** (design §7.2.3):
    - add `fileRelayIdleTtl: Duration = 1.hours` to `RouterConfig` (`> 0` in `init`), and to `ConfigInvariants`:
      `router.fileRelayIdleTtl < router.fileTransferTtl` is single-layer, so put it in `RouterConfig.init`;
    - the engine's ticker drops relay transfers that are not WHOLE and whose `last_chunk_at + fileRelayIdleTtl <= now`,
      freeing their reservation; whole transfers keep `expires_at`;
    - `setStatus` must not touch `last_chunk_at` (today it does; fix the SQL in `FileRelay.sq`);
    - a depositor's chunk for a dropped transfer gets DECLINED, which ends that deposit (R3.1 already treats a chunk
      DECLINED as ENDED); the pull path covers the rest.
- **CANCEL authorisation.** A CANCEL at the door deletes the transfer only when `fileEnvelope.source` is the
  transfer's `source_device` (the depositor). Otherwise ignore it and still ACK.
- **Inner source check** (design §7.1): before admission, the inner source must be a known device (`ctx.devices`) and
  not banned/removed; otherwise `Rejected(DECLINED)`. Check whether the door does this today;
  add it if not, with a test either way.
- **Tests:** a three-router test (sender, relay, target; extend the two-router harness with a third node and a
  pump that routes by envelope target) with a 10 MB file, covering design §19:
    - target offline: the sender deposits, the relay becomes WHOLE, the target comes online, the relay forwards, READY;
    - two relays both receive the deposit; both forward; the target reaches READY once and both relay copies are gone;
    - eviction mid-deposit: the next chunk is DECLINED and the sender's deposit ends ENDED;
    - a forwarding relay never deposits (no outbox row to a third relay);
    - an idle partial transfer is dropped after `fileRelayIdleTtl` (fake clock) and its bytes are freed;
    - a CANCEL from a non-depositor is ignored.
- **Done when:** `FileRelayForwarder` does not exist; every multi-row relay change happens inside one store transaction.

---

## Phase R4 — The receive side

### Step R4.1 — Receive engine, split (`orchestrator/file/receive`)

Replaces `DefaultFileReceiveEngine`.

- **Read first:** `orchestrator/file/DefaultFileReceiveEngine.kt` in full (list every interface it implements:
  `FileIngestPort`, `FileCoverageReader`, `FileReceiveEnds`, `FileReceiveControl`, plus `receiveEvents`, `start`,
  `checkpoint`), `FileReceiveStore.kt` + the receive tables in `File.sq`, `FileStore.kt`, `io/FileAccess.kt`,
  design §8, §13, §14, invariants 9–12 and 25.
- **Package and classes** (`orchestrator/file/receive`):
    - `Coverage.kt` — `object Coverage` with pure functions over `List<StagedSpan>`
      (`data class StagedSpan(val start: Long, val length: Long)`): `uncovered(start, end, p, staged)`,
      `extendsPrefix(p, staged, add)`, `drainPlan(p, staged): DrainPlan` (which rows append, which trim, new P),
      `heldCoverage(p, staged, cap): FileCoverage`. No suspend, no I/O.
    - `PartWriter.kt` — `class PartWriter(files: FileAccess, crypto: CryptoProvider)`: `append(path, at, bytes)`,
      `blockHasher(path, blockStart)` rebuilt from disk on demand, `verify(blockIndex, expected): BlockCheck`
      (`Verified` / `Mismatch(blockIndex)`). The running hash advances only after the DB commit of that drain.
    - `ReceiveEngine.kt` — `class ReceiveEngine` implementing the same four interfaces as today plus
      `receiveEvents`. One `Mutex` per file (`Map<FileId, Mutex>` guarded by a small global mutex), a separate
      global `Mutex` only around the free-space reservation.
    - `FileRetention.kt` — the unreferenced-receive sweep (reads `MessageRepository.findMessageIdsByFileId`, calls
      engine verb `forgetUnreferenced(fileId)` and `FileStore.forget`); the engine no longer takes `MessageRepository`.
      Delete `DefaultFileReceiveEngine.kt` and move its tests to `ReceiveEngineTest`, `CoverageTest`, `PartWriterTest`,
      `FileRetentionTest`.

- **Shape:**
    - **`Coverage`:** pure arithmetic with no I/O, unit-tested on its own:
        - which bytes of a range are uncovered;
        - whether a range extends P;
        - the drain plan;
        - held coverage and its cap.
    - **`PartWriter`:** the `.part` file and the running hash of the block at P. Append, verify the block on
      completion, and report `Verified` / `Mismatch(blockIndex)`. The hash advances only after the commit
      succeeds.
    - **`ReceiveEngine`:**
        - the transitions of design §14, as verbs, under a **per-file** lock;
        - a checkpoint drains only its own file;
        - one global lock only for the free-space reservation.
    - **`FileRetention`:** the unreferenced-file TTL and quota handling, moved out of the engine. The engine
      no longer depends on `MessageRepository`.
- **Behaviour fixes (each with a test):**
    - **Atomic checkpoint.** One store transaction advances P, deletes drained rows and trims the straddling
      row; P moves before anything is deleted. `completeRow` is atomic in the same way. Staging a chunk is one
      transaction.
    - **Mismatch.** Delete every staged row that ends past the bad block's start; trim a row that straddles
      it. Fail exactly at `fileHashRetryLimit` mismatches.
    - **Same device first.** Record the author device of the transfers that fed the bad block. The next
      REQUEST asks that device first (design §8/§9). Today this is a TODO in `FileRequestProcessor`.
    - **Completion is reported.**
        - The engine checkpoints at completion, not on the timer.
        - `ingest` returns `Ready`, a new result, when the file completes, so routing learns it on the chunk
          that finished it.
        - `FilePlaintextSource` stops being involved.
    - **Recovery** runs under the engine's lock **before** the ingest port accepts calls. The port answers
      `Defer` until recovery is done.
    - **Free-space reservation.** An OFFER's check subtracts the outstanding `total − P` of every active
      receive.
    - **Content we already hold.** `checkOffer` and the request trigger also consult `file_descriptors`, so a
      held `fileId` answers `AlreadyReady` and needs no download.
    - **Cleanup.**
        - FAILED, DECLINED and unreferenced receives drop their `.part` and staged rows.
        - The unreferenced-file TTL covers every status, not just READY (invariant 25).
    - **Store schema.**
        - Derive `part_path` and `final_path` from the `fileId` and the store layout; drop the columns.
        - With no paths to fake, the DECLINED placeholder rows with empty paths go away.
        - `origin_message_id` becomes `TEXT AS Uuid`.
      - Edit `File.sq` and `6.sqm` together; `FileMigrationJvmTest` checks they match. Paths come from one
        function, `ReceivePaths(incomingDir, storeDir).part(fileId)` / `.final(fileId)`, used by the engine and
        `FileStore`. The `declinedRow` helper in the engine then needs no placeholder paths.
    - **Staged-range queries** return offsets and lengths only. Blobs are loaded only at drain.
- **Tests:**
    - a straddling mismatch recovers;
    - the retry limit holds exactly;
    - a crash between every pair of checkpoint statements loses nothing ACKed (run with a crash-injecting
      store);
    - `recover()` itself is exercised;
    - concurrent ingest and checkpoint;
    - two concurrent OFFERs that fit only one at a time;
    - an unreferenced `.part` is deleted;
    - a held file answers `AlreadyReady`.

  These cover design §19's checkpoint-crash, torn-drain, block-mismatch and unreferenced-receive rows.

- **Done when:** `DefaultFileReceiveEngine.kt` does not exist; `ReceiveEngine` does not import `MessageRepository`;
  `grep -n "partPath\|finalPath" core/src/commonMain` finds nothing outside `ReceivePaths`.

### Step R4.2 — Store, quota and eviction (`orchestrator/file`)

- **Read first:** `orchestrator/file/FileStore.kt`, `FileDescriptorStore.kt` + `file_descriptors` in `File.sq`,
  `io/FileAccess.kt` and its JVM/Android actuals, `ReceiveEngine` (R4.1), design §10, §13, invariant 11.
- **Schema:** `last_access_at INTEGER NOT NULL` and `storage_kind TEXT NOT NULL` (`PATH` | `CONTENT_URI`) on
  `file_descriptors`, in `File.sq` and `6.sqm`.
- **New engine verb** on `FileReceiveControl`: `suspend fun evicted(fileId: FileId, now: Instant)` — READY → EVICTED
  under the file lock, deletes the final file through `PartWriter`/`FileAccess`. `FileStore.enforceQuota` picks the
  least recently accessed COPY descriptors and calls `evicted` for received files (and deletes the descriptor via
  `forget`); it never runs inside a completion transaction (call it after `adopt` returns).
  `FileReceiveControl.reopen` already treats EVICTED as terminal-and-reopenable.
- **`touch(fileId)`** on `FileStore`: called by `DefaultFilePlaintextSource` on a successful read and by the facade's
  `localFilePath`.

- **Changes:**
    - **True LRU.** Add a `last_access_at` column, touched on serve and open.
    - **Eviction.**
        - Eviction never touches a file in its own completion transaction.
        - It goes through the engine's `evicted` verb, which sets EVICTED.
        - `acceptFile` re-opens EVICTED (invariant 11: a user re-open fetches it again; nothing does
          automatically).
    - **Import of an existing `fileId`:**
        - a SOURCE_LOST descriptor whose hash matches again goes back to HELD;
        - `keepCopy` on an existing REFERENCE upgrades it to COPY instead of discarding the copy just made.
    - **Typed storage.** Store `FileImportSource` typed (a `storage_kind` of PATH / CONTENT_URI), not
      sniffed from a `content://` prefix.
    - **Atomic moves.** `FileAccess.move` fails instead of falling back to a non-atomic move. Fsync the
      directory after the READY rename.
    - **`FileAccess` is suspend** and switches to the IO dispatcher internally, the way the stores use
      `dbDispatcher`. No blocking fsync on a router or node coroutine.
    - Delete the dead code: `updateBlockList`, `resolve`, `storePath()`, `openContentSource`,
      `selectAllFileDescriptors`, and `FileIds.*ForBytes` (moved to test fixtures). Check each is unused with
      `grep` first; if one is used, ask before deleting.
- **Tests:** quota eviction of a received file gives EVICTED, then a re-open fetches it (§19 quota row);
  re-importing a SOURCE_LOST file serves again; import of an existing REFERENCE with `keepCopy`.

- **Done when:** a received file evicted by quota shows EVICTED and comes back after `acceptFile`; `FileAccess` has no
  blocking public function; the dead code listed above is gone (`grep` each name).

### Step R4.3 — The coordinator and the GUI facade (`orchestrator/file`, `orchestrator/runtime/file`)

- **Already done in R1.5:** the facade only calls verbs (`FileRequestControl.acceptFile`/`declineFile`, plus reads);
  the DECLINED row is built only in the engine; `findFileAttachment` is the one attachment lookup. Do not redo these.
- **Read first:** `orchestrator/file/FileRequestCoordinator.kt`, `ReceiveEngine` (R4.1), `wiring/NodeGraph.kt`
  (`start`/`stop`), design §8 (gap trigger), §9 (trigger condition), §14.

- **Changes:**
    - **The gap trigger counts time while this device runs** (design §8). It tracks the last P advance per
      file in memory, on a monotonic clock started at `start()`, and not against `updatedAt`.
        - Use `kotlin.time.TimeSource.Monotonic` (inject a `TimeSource` for tests; `TestTimeSource` exists).
        - Feed it from `receiveEvents` (`Progress` with a larger `contiguousBytes` resets that file's mark).
        - On `start()`, every active receive gets a fresh mark, so a long sleep never fires the trigger at once.
        - `markStalled` then takes no `idleSince` from `updatedAt`; pass the coordinator's own cutoff (change the verb's
          KDoc accordingly; the engine still re-checks IN_FLIGHT under its lock).
    - **An accepted OFFER marks the pending request as covered by a progressing transfer,** so the grace
      period ends without a REQUEST while a push is progressing (design §9 trigger condition). A STALLED file
      re-opens it.
    - **A no-space refusal records DECLINED** through the engine (§14 PENDING → DECLINED), so the message
      trigger stops re-requesting.
    - **Lifecycle.** The coordinator and the engine keep their job handles; `NodeGraph.stop` stops them.
      Also replace the coordinator's `runCatching { onFileEvent(...) }` and `runCatching { sweep(...) }` loops with
      try/catch that rethrows `CancellationException` and logs (ground rules).
    - **Errors propagate in `DefaultFileRequestSource`.** Remove its
      `catch (e: Exception) { return emptyList()/false/null }`
      blocks; routing's callers already handle exceptions (R2.3).
    - `declineFile` on an IN_FLIGHT receive: the engine's `decline` verb also deletes the `.part` and staged rows and
      sends CANCEL (DECLINED) through `FileReceiveEnds`.
- **Tests:**
    - restarting after a long sleep doesn't fire the gap trigger at once;
    - a slow push past the grace period gets no REQUEST;
    - declining an in-flight file leaves no `.part`;
    - no-space is DECLINED and not re-requested.
- **Done when:** no `runCatching` in `orchestrator/file`; `FileRequestCoordinator` never reads `updatedAt`.

### Step R4.4 — Sending moves out of messaging (`orchestrator/file`, `orchestrator/runtime/message`)

- **Already done in R1:** `file_transfers`, `FileTransferStore` and its enums are gone; `PickedFile.keepCopy` is
  nullable (null applies `copyIntoStoreMaxBytes`). Skip the bullet about them below.
- **Read first:** `orchestrator/runtime/message/DefaultMessagingService.kt` (`sendMessage`, the import loop and the
  push loop), `MessagingTypes.kt` (`Message`, `PickedFile`, `SendRefusal`, `SendMessageResult`), `FileStore.import`,
  `PublicApiSurfaceTest` (this step changes public types: update the test's list deliberately and say so in the note),
  design §10, §12. This step changes the GUI API, so read `docs/gui-api.md` and update it in the same change.
- **Interface:**
  ```kotlin
  internal interface FileSendService {
      /** Imports [files] and returns their attachments, or the refusal. Never throws for unreadable input. */
      suspend fun prepare(files: List<PickedFile>, onProgress: (hashedBytes: Long, totalBytes: Long) -> Unit): PrepareResult
      suspend fun release(attachments: List<MessagePayload.Attachment>)   // the append failed
      suspend fun startPushes(attachments: List<MessagePayload.Attachment>, members: Collection<AccountId>)
  }
  sealed interface PrepareResult { data class Ready(val attachments: List<MessagePayload.Attachment>) : PrepareResult
                                   data class Refused(val refusal: SendRefusal) : PrepareResult }
  ```
  `DefaultMessagingService` takes `FileSendService` instead of `FileStore` and `FileCarrier`.

- **Changes:**
    - **A new `FileSendService` in `orchestrator/file`** owns import, classification and starting pushes.
      `DefaultMessagingService` calls it.
    - **Order of work.** Check the attachment count, then import. If the DAG append fails, release the
      imported copies.
    - **Errors are refusals.** Import errors become `SendRefusal` values (`FileUnreadable`, `NoSpace`), not
      exceptions.
    - **Remove `Message.attachments`** (raw GUI `fileId` strings). Forwarding an attachment, if wanted
      later, takes a typed handle the core vended and checks that a HELD descriptor exists.
    - **No `file_transfers` table** (dropped in R1.0). The send state the GUI shows comes from routing's
      `FileCarrier.sendState` (R3.1) plus live progress events. Delete `FileTransferStore`, its enums and
      every write to it in messaging and the coordinator.
    - **"Preparing" (design §10).** Sending becomes asynchronous.
        - `sendMessage` checks the attachment count and returns at once with a local send id. Importing and
          hashing then run on the file service's scope.
        - The message is appended to the DAG when every `fileId` is known; until then the GUI shows a
          preparing item with hashing progress.
        - If the app stops mid-hash, the send is lost: the preparing item is in memory only, and a partial
          copy is deleted on the next start. For v1 that is acceptable, since only large files take long.
          Persisting pending sends is a later option.
        - Cancelling a preparing send stops the hash and deletes the partial copy.
        - The message's place in the room is the moment hashing finished, not the moment the user pressed
          send.
- **Tests:**
    - too many attachments refuses before any import;
    - a failed append leaves no orphan copy;
    - an unreadable file is a refusal, not an exception;
    - a send returns before hashing ends and the message appears once hashing finishes;
    - cancel mid-hash leaves no copy;
    - a partial copy from a killed send is cleaned at start.
- **Done when:** `DefaultMessagingService` imports nothing from `orchestrator.file` except `FileSendService` and its
  result types; `Message.attachments` does not exist; `docs/gui-api.md` describes the preparing state.

---

## Phase R5 — The large path

### Step R5.1 — Append at P (`routing/file/port`, `orchestrator/file/receive`)

- **Needs:** R4.1 (`PartWriter`, `ReceiveEngine`). **Read first:** `routing/file/port/FileIngestPort.kt`,
  `ReceiveEngine`, the requester side of `routing/file/fetch/FileFetchManager.kt` (`handleData`), design §6.6, §8.
- **Signature:**
  ```kotlin
  suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult
  sealed interface FileAppendResult {
      data class Appended(val committedBytes: Long) : FileAppendResult   // P after this call (may be unchanged)
      data object Ready : FileAppendResult
      data class Mismatch(val blockIndex: Int) : FileAppendResult
      data object NoSpace : FileAppendResult
      data object Defer : FileAppendResult
  }
  ```
  Put `FileAppendResult` in `FileIngestPort.kt`. `offset != P (uncommitted end)` throws `IllegalArgumentException`.

- **Changes:**
    - **Add `FileIngestPort.append(fileId, offset, bytes)`** for the fetch. It returns:
        - `Appended(P)`, where P is the committed checkpoint, or the same P as before;
        - `Ready`;
        - `Mismatch(blockIndex)`;
        - `NoSpace`;
        - `Defer`.
    - **Bytes go straight into the `PartWriter`** at P, without staging, with checkpoints by bytes and time.
      It never skips: `offset ≠ P` is an error.
    - **The fetch stops calling `ingest`.**
- **Tests:** a fetch writes no `file_receive_chunks` rows; checkpoint by bytes and by time; mismatch reports
  the block.

- **Done when:** `FileFetchManager` (or its R5.4 replacement) never calls `ingest`.

### Step R5.2 — Streaming plaintext source (`routing/file/port`, `orchestrator/file`)

- **Read first:** `routing/file/port/FilePlaintextSource.kt`, `orchestrator/file/DefaultFilePlaintextSource.kt`, the
  readers (`PlaintextChunkSource` from R3.1, the holder in `FileFetchManager`), `io/FileAccess.kt` (positioned reads),
  design §9, invariant 22.
- **Signature:**
  ```kotlin
  interface FilePlaintextSource {
      suspend fun describe(fileId: FileId): FileManifest?
      suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream?   // null: not held
  }
  interface PlaintextStream : AutoCloseable {
      /** Up to [maxBytes] verified bytes from the current position; empty at EOF. */
      suspend fun read(maxBytes: Int): PlaintextRead
  }
  sealed interface PlaintextRead { data class Bytes(val bytes: ByteArray) : PlaintextRead
                                   data object Eof : PlaintextRead
                                   data class SourceLost(val blockIndex: Int) : PlaintextRead }
  ```
  Delete `read(fileId, offset, maxBytes)` and `FileReadResult`. Buffer size: a constant 256 KiB in the implementation.

- **Changes:**
    - **Replace the stateless `read(fileId, offset, maxBytes)`** with
      `open(fileId, fromOffset): PlaintextStream`, closed by its user.
    - **Read each block twice, keeping memory flat.**
        - Pass 1 hashes the block and compares it with the block list; nothing is handed out yet.
        - Pass 2 re-reads the block with a positioned read and streams it in bounded buffers, from the
          requested offset when the serve starts mid-block.
        - Pass 2 hashes too, and compares at the block's end. A referenced original edited between the two
          passes then ends the serve at that block. The receiver's own block check would also catch it, but
          the holder must stop serving it (invariant 22).
        - Memory is one read buffer, not one block (blocks reach 128 MiB). Disk reads double, which is fine,
          since the disk outpaces the network.
    - Push chunks and fetch frames both read through it, so each block is verified once per serve instead of
      once per chunk.
    - **On a mismatch** the stream reports `SourceLost(block)`, and the plaintext source tells `FileStore`
      (R1.5).
- **Tests:**
    - a sequential serve of a 1 GiB file reads each byte at most twice (counted by the fake `FileAccess`),
      with memory bounded by the buffer size;
    - a mid-block start;
    - a rotten block stops at that block;
    - a block changed between the two passes stops the serve.

- **Done when:** `FileReadResult` does not exist; the test counting reads passes for a 1 GiB sparse file.

### Step R5.3 — Transport: ordered, reliable file channels (`transport/webrtc`, `jvmMain` backend)

- **This step changes transport code** (allowed here only). **Read first:**
  `transport/webrtc/transport/WebRtcTransport.kt`
  and `DefaultWebRtcTransport.kt` (file-frame and channel parts), `jvmMain/.../JvmWebRtcBackend.kt`
  (`emitIncomingData`, which launches a coroutine per frame — the reorder bug), the Android backend if present (apply
  the same ordering fix), `routing/file/fetch/FileFetchLabels.kt`.

- **Changes:**
    - **Ordered delivery from the backend.** `JvmWebRtcBackend` delivers each data channel's messages in
      order: the callback does `trySend` into one per-channel `Channel`, drained by one coroutine. No
      `launch` per message. Apply the same to channel events.
    - **File channels are always reliable.** Force `ordered = true` and no retransmit or lifetime limit,
      whatever the shared config says.
    - **The transport mints channel handles.** `openFileChannel(peer): FileChannelId`. Routing never builds
      label strings; delete the duplicated prefix and `fileChannelLabel()`.
    - **File frames get their own type and flow,** separate from envelope delivery. A full file-frame buffer
      can never stall envelopes.
    - **Backpressure.** Expose `awaitBufferedBelow(channel, threshold)`, a suspend function backed by the
      low-threshold callback. Delete `BufferedLow` events and the polling.
- **Tests:**
    - a backend-level test (JVM, two real peer connections if the CI can run them, otherwise the backend's
      callback layer with a multi-threaded dispatcher): 10 000 frames arrive in order;
    - a file channel with an unreliable shared config is still reliable;
    - a stalled file consumer doesn't delay envelopes.

- **Done when:** `grep -n "launch" JvmWebRtcBackend.kt` shows no per-message launch; routing has no channel label
  strings.

### Step R5.4 — The fetch, one actor per channel (`routing/file/fetch`)

Replaces `FileFetchManager`.

- **Needs:** R2.2 (the core's `Call` executor runs fetches), R5.1, R5.2, R5.3. **Read first:** `FileFetchManager.kt` in
  full, `FileFetchOutcome.kt`, `protocol` fetch frames, the `FileFetchStrategy` from R2.2, design §6.6, §3.5.
- **Classes:** `FetchHolder` (one per incoming FETCH channel), `FetchRequester` (one per outgoing fetch),
  `FileFetchService` (routes frames by `FileChannelId` to the actor, exposes
  `suspend fun fetch(fileId, device): FetchResult` for R2.2's `Call`, and `abortFetch(fileId, reason)`).
  `enum class FetchResult { SATISFIED, DECLINED, UNANSWERED, NO_DIRECT_CONNECTION, NO_SPACE, MISMATCH }`; map to
  `CallOutcome` in `FileFetchStrategy` (`NO_SPACE` → satisfied-and-drop plus engine `decline`; `MISMATCH` → retry same
  device first).

- **Holder actor:**
    - receives FETCH and runs the serve gate (through `FileServeGate`, by the peer device's account; also
      reject removed or revoked devices);
    - sends `FETCH_OK` and only then starts DATA, from a `PlaintextStream`;
    - stays within `fileStreamWindowBytes` past the last PROGRESS and the transport's buffered threshold;
    - sends ABORT on SourceLost;
    - ends on channel close, or after an idle timeout with no PROGRESS.
- **Requester actor:**
    - sends FETCH and checks the block list against the `fileId`;
    - feeds DATA to `append` in order and sends PROGRESS only when P moves;
    - finishes on `Ready`;
    - on `Mismatch` it ABORTs and the request goes back to PENDING, asking the same holder first;
    - on `NoSpace` it ABORTs and the request ends (DECLINED locally);
    - on `Defer` it suspends: the window holds the holder back, and no frame is dropped.
- **Lifecycle:**
    - `fetchFile` is bounded: it ends when its channel ends;
    - a local refusal never counts against the holder; only a block list that doesn't match the `fileId` is
      a decline;
    - `abortFetch(fileId)` aborts only our requester for that file, with the real reason (`STALLED` for the
      gap trigger, `CANCELLED` for the user), and never our serves of the same file;
    - outcomes are enums, not strings, and unused state and types are deleted.
- **Tests (realistic):** two-router large fetch of 64 MB with the **default** checkpoint and window config,
  over a transport fake that runs callbacks on a multi-threaded dispatcher. Plus:
    - a slow-disk receiver holds the holder at the window;
    - a session drop resumes from P with a positioned read;
    - a mismatch at the source ABORTs;
    - a local no-space ends the request without blacklisting;
    - two concurrent fetches from one peer.

  These cover design §19's large-file rows.

- **Done when:** `FileFetchManager.kt` does not exist; no `runCatching` in `routing/file/fetch`; the 64 MB test passes
  with default config.

---

## Phase R6 — Protocol, protection, cleanup

### Step R6.1 — Protocol hardening (`protocol`)

- **This step changes wire decoding (not encoding).** **Read first:** `protocol/envelopes/FileEnvelope.kt` (all
  `decode` functions; `FileFetchFrame` lives at the end of it, with `Data.ENCODED_OVERHEAD_BYTES` that `FileLimits`
  uses — keep that constant when moving), `protocol/ByteReaderWriter.kt` (`readByteArray(maxSize)` exists; use it),
  `protocol/EnvelopeObservability.kt`, `protocol/FileIds.kt`.

- **Changes:**
    - **Bound every decoded count and length before allocating:** REQUEST ranges, SealedOffer and `FETCH_OK`
      block lists. Hashes are exactly 32 bytes.
    - **One `fileName` cap** shared by attachments, the sealed OFFER and `FETCH_OK`.
    - **Move the fetch frames** to their own file (`protocol/FileFetchFrame.kt`).
    - **Rename `Ack.contiguousChunks`** to what it holds (`highestContiguousIndex`), or make it a count.
    - **Revisit the observability profile** honestly:
        - the OFFER public half, the CHUNK index, ACK, COMPLETE and the CANCEL reason are visible to relays;
        - drop CANCEL's free-form `reasonText` from the wire;
        - make `assertObservabilityContract` check payload visibility, not just header keys.
- **Tests:** oversized counts, short hashes and long names are rejected without allocation; fixed
  `fileId` vectors (empty, one block, exact multiple, short last block, the 1024-block doubling) computed
  independently, not round-tripped (design §19).

### Step R6.2 — Protection (`protection`, `crypto/keys`)

- **Read first:** `protection/envelope/FileProtection.kt` (keep `CHUNK_OVERHEAD_BYTES`; `FileLimits` uses it),
  `SignedAndEncryptedFileProtection.kt`, `BaseProtection` (message path),
  `protection/service/EnvelopeProtectionService.kt`,
  every caller in `routing/file` (after R3 that is `PlaintextChunkSource`, `FileTargetHandler`, `FileServeHandler`,
  `DefaultFileCarrier`).

- **Changes:**
    - **A `TransferKey` type** (wraps the bytes, never prints) replaces raw `ByteArray` keys through
      protection and the routing rows' adapters (guide §4).
    - **`SignedAndEncryptedFileProtection` extends `BaseProtection`.** Generalise the base's open-result type
      if needed; no copied wrappers.
    - **A narrow `FileEnvelopeProtection` port** is handed only to file routing, with
      `protectOffer(publicHalf, manifest, key, ctx)` sealing and signing in one call. Remove the five file
      methods and the nullable `transferId` from `EnvelopeProtectionService`/`EnvelopeProtectContext`.
    - **`decryptChunk` takes the index from the chunk only.**
    - **No crypto exceptions leave protection:** `require` failures map to PERMANENT, and
      `CancellationException` is rethrown, never mapped. Fix the same `CancellationException` bug on the
      message path while here.
- **Tests:**
    - a real AAD-binding test: ciphertext from index 7 presented as index 8 fails;
    - same transferId and index twice gives different ciphertext (random IV);
    - the protected wire bytes of every FILE kind, including the sealed REQUEST, don't contain the `fileId`.

### Step R6.3 — Typing, logging, comments (all file packages)

- **Read first:** `logging/LogComponent.kt`/`LogEvent.kt`. Known wrong events today: the receive engine logs under
  `MESSAGE_DECODE_FAILED`/`MESSAGE_FETCHED`, the coordinator under `SYNC_PROCESS_FAILED`, the pusher/carrier under
  `OUTBOX_MESSAGE_QUEUED`. Add `LogComponent.FILE` and events such as `FILE_OFFER_ACCEPTED`, `FILE_CHUNK_DEFERRED`,
  `FILE_RECEIVE_READY`, `FILE_RECEIVE_FAILED`, `FILE_PUSH_ENDED`, `FILE_DEPOSITED`, `FILE_FETCH_FAILED`,
  `FILE_TASK_FAILED` (loop safety nets), and replace every file-code log call.

- **Changes:**
    - A `TransferId` column adapter replaces the hand-written hex conversions.
    - `OpenedFileEnvelope.source` and `target` become `PeerId`.
    - Drop the single-value `FileIncomingStatus` and the always-true `isLive`.
    - Wire in the `FILE_*` log events.
    - Remove "step N" and "Phase X" from KDoc, and remove inline fully-qualified names.
- **Done when:** a grep for `catch (e: Exception)` and `runCatching` in file code returns only sites that
  rethrow or map to a typed result, each with a reason.

### Step R6.4 — Update the docs

- **Changes:**
    - **Guide:** §2 (the service split) and §4's orchestrator rows were updated in R1; check them against the code,
      finish §4's routing rows (R3.2 should have done it), and update §6 (tag kinds `FILE_PUSH`/`FILE_FORWARD`/
      `FILE_REQUEST`
      and retry classes) and §8 (feature status).
    - **Design:** apply the changes listed below.
    - **`plan.md`:** mark steps 1–22 as superseded in part by this plan and point steps 23–24 here.
- **Done when:** the guide's and the design's rows match the code, and the full suite is green.

---

## Order and parallelism

**R1 and R2 are done.** Their seams and shared pieces are what every later step builds on; continue at step 5 below.

**Dependencies after R1:**

- R2.1, R2.2, R2.3 and R2.4 are independent of each other.
- R3 (small path) needs R2.3; R3.2 needs R3.1; R3.3 needs R3.1 and R3.2.
- R4 (receive side) needs only R1; R4.2–R4.4 need R4.1; R4.4 needs R3.1 (`sendState`).
- R5 needs R2.2 and R4.1; R5.3 (transport) can go any time; R5.4 needs R5.1–R5.3.
- R6.1 and R6.2 can go beside R3–R5. R6.3 and R6.4 come last.

**One executor, one step at a time:** follow this sequence exactly. Each step lands green before the next starts.

1. ~~R2.1 — one visibility rule~~ (done)
2. ~~R2.4 — per-carrier deliveries~~ (done)
3. ~~R2.3 — outbox fixes~~ (done)
4. ~~R2.2 — candidate core~~ (done)
5. R3.1 — push engine
6. R3.2 — inbound handler split
7. R3.3 — relay on the engine
8. R4.1 — receive engine split
9. R4.2 — store, quota, eviction
10. R4.3 — coordinator and facade
11. R4.4 — sending out of messaging
12. R5.3 — ordered, reliable file channels
13. R5.1 — append at P
14. R5.2 — streaming plaintext source
15. R5.4 — fetch actors
16. R6.1 — protocol hardening
17. R6.2 — protection
18. R6.3 — typing, logging, comments
19. R6.4 — docs

## Design changes

Fold these into [`design.md`](design.md) in the step named.

- **§15 config (R1.1):**
    - `fileStallTimeout` lives in the orchestrator config;
    - the orchestrator has its own `unreferencedFileRetention` and never reads `RouterConfig`;
    - cross-layer relations are listed as wiring invariants, replacing the "startup checks" paragraph;
    - `FileServiceConfig` is merged into `OrchestratorConfig`;
    - add `fileSweepInterval`;
    - the pledge and its split are `RouterConfig`'s (both budgets are routing stores), not the composition
      root's;
    - `FileLimits` derives from layer overheads, with `fetchFrameBytes` separate.
- **§12 layering (R1.2–R1.4):**
    - the port table (`FileIngestPort`, `FileRequestSource`, `FileServeGate`, `FilePlaintextSource`);
    - receive events are the engine's, not routing's;
    - the Router service split.
- **§6.2 outbox (R2.3):**
    - tag kinds `FILE_PUSH` and `FILE_FORWARD`;
    - the marker and REQUEST keep the message retry, and only chunk rows are flat;
    - the fair due query.
- **§6.4 deposits (R3.1):** the claim is durable on the source row.
- **§3.2 / §8 (R3.1):** the exact coverage-ACK rule; a duplicate also answers with a coverage ACK.
- **§7.2 relay (R3.3):** the relay ACKs stored chunks with coverage; `fileRelayIdleTtl` for transfers that
  are not yet whole.
- **§9 (R2.1, R4.3):**
    - the serve gate covers every account that can see a referencing message, its author included;
    - "an accepted OFFER covers the grace period";
    - same device first is recorded per bad block.
- **§10 sending (R4.4):** asynchronous send with a preparing state; the message is appended when hashing
  finishes.
- **§11 file I/O (R5.2):** the two-pass plaintext stream (verify, then stream with a re-hash), and
  `FileAccess` being suspend.
- **§12 / §13 send state (R3.1, R4.4):** no `file_transfers` table; per-account send state is a routing query
  over `file_pushes`.
- **§13 schema (R4.1, R4.4):**
    - paths are derived;
    - typed `origin_message_id`;
    - `deposited_at`;
    - `last_access_at`;
    - `retry_class`.
- **§6.6 large path (R5.1, R5.4):**
    - the fetch appends through its own port;
    - `Defer` suspends rather than drops;
    - only a bad block list counts as a holder's decline.
