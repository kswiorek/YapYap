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

---

## Phase R1 — Seams (config, ports, wiring)

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
- **Tests:** each invariant rejects a violating config at load and as a settings change; `FileLimits` with default
  transports gives
  ≈220 KiB, and with a tiny transport fails; settings round-trip for the new keys.
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

---

## Phase R2 — Shared pieces

### Step R2.1 — One visibility rule (`orchestrator/sync`, `orchestrator/file`)

- **Changes:**
    - Extract the sync serve gate's membership rule into one component,
      `RoomVisibility.isVisible(messageId, account)`. ACTIVE sees the room; REMOVED sees the ancestor closure
      of its removal node.
    - Implement the closure with a recursive CTE (like `selectIsRenderable`), not a bounded BFS.
    - `DefaultSyncPayloadProvider` and the file serve gate both use it. Delete the copy in
      `FileRequestCoordinator` and its 512-step cap.
- **Tests:** one parity suite runs the same cases against sync serving and the file gate; a removed member's
  file deeper than 512 ancestors is served.
- **Settled (design §9):** the gate stays as designed. A message that references a file shares it with
  everyone who can see that message, its author included. Write that sentence into §9 so it reads as
  intended, not as a gap.

### Step R2.2 — The candidate core, generalised (`routing/candidate`)

- **Changes:**
    - Move `CandidateCore` out of `routing/sync` into `routing/candidate`.
    - The core takes interfaces (an attempt sender, a peer policy), not `OutboxProcessor` and `SyncPeerPolicy`.
    - Make **WAITING a core state**: an attempt's success may say "await the reply for `t`", replacing the
      `isAttemptPending` side effects and the public `noteSilent`.
    - Make **the attempt executor pluggable**. One executor enqueues an outbox packet (sync, small REQUEST).
      The other runs a suspending attempt (a large fetch) concurrently per row, with a bounded concurrency,
      and records the attempt *before* the long call.
    - The large file request runs on this core. Delete `FileLargeRequestLoop` and its copied backoff.
    - Separate small and large rows in SQL (a `class` column on the attempt row), so neither class's due query
      hides the other and the loop never spins.
    - **Restore step 15's pure-refactor promise.** A satisfied or gone sync is dropped before candidate
      selection again. Add the test the original step lacked.
- **Tests:**
    - `SyncRetryProcessorTest` unchanged and green;
    - a satisfied sync with no linked candidate is dropped;
    - ten due large rows don't hide a small one;
    - two large fetches run at once;
    - no due-loop iteration happens while a fetch runs.

### Step R2.3 — Outbox fixes (`routing/outbound`, `routing/store`, `Outbox.sq`)

- **Changes:**
    - **Fair due query.** Keep the blob-less due query, but exclude targets that are at their in-flight cap
      in SQL, rather than applying a global `LIMIT` that lets one capped peer hide every other peer. The
      `startable` check uses the same query.
    - **Tag kinds by consumer:**
        - `FILE_PUSH`: our transfers, routed to the pusher;
        - `FILE_FORWARD`: relay forwards, routed to the relay forwarder;
        - `FILE_REQUEST`: unchanged.

      Each outcome goes to exactly one consumer (guide §6).
    - **Retry policy by packet role, not by tag.** The OFFER marker and REQUEST rows keep the standard message
      retry (design §6.1); only chunk rows use the flat `fileChunkRetryDelay`. Mark chunk rows at enqueue (for example a
      `retry_class` column) instead of decoding the envelope.
    - **Remove `RelayBackupPolicy`.** Backup eligibility is already the row's `RelayBackup` value, set to
      PENDING only on authoring markers. Revert the `OutboundMessenger` change.
    - **Outcome handling never blocks the outcome collector.** The pusher and forwarder receive outcomes
      through their own channel and do their encryption and inserts on their own scope.
    - **Clear dedup when a handler throws** (guide §6, "never ACK before handling"). Today a handler
      exception leaves the dedup row, and the retry is ACKed as a duplicate.
- **Tests:**
    - against the real SQL outbox, not `TrackingPacketOutbox`: 40 due messages for a capped peer don't hide
      another peer's message;
    - a text message behind a full chunk window goes first;
    - the marker retries on the message schedule, and a chunk retries at the flat delay;
    - a handler exception followed by a retry is handled, not ACKed.

---

## Phase R3 — The small path

### Step R3.1 — The push engine (`routing/file/push`)

Replaces `FilePusher`.

- **Shape:**
    - **`FilePushEngine`:** one actor per live transfer (a coroutine fed by a channel). It owns that
      transfer's marker → chunks → terminal lifecycle. All inputs for the transfer are messages to its actor:
      outbox outcomes, coverage ACKs, COMPLETE, CANCEL, link events and the stall tick. That removes every
      race on `next` and `contiguousAcked`.
    - **Chunk source** is an interface with two implementations:
        - plaintext: encrypt and sign through protection;
        - stored envelope: the relay's DB store.

      The relay forward (R3.3) runs on the same engine.
    - **`FileSender`:** the thin, account-level fan-out, serve-with-supersede and cancel. It aggregates
      per-device outcomes into per-account events (design §12):
        - progress is the minimum across the account's devices;
        - COMPLETED once any device of the account completes;
        - a terminal state only when every device is terminal.
    - **Send state is a query, not a copy.** `FileCarrier.sendState(fileId): Map<AccountId, FileSendState>`
      aggregates `file_pushes` per account on demand. The routing table it reads is the durable truth, so it
      survives restarts. This is what replaces the orchestrator's `file_transfers` table (R4.4).
    - **`FileDepositPolicy`:** the gone-only signals of §6.3 (link closed, no link at claim, no chunk ACK at
      all for `chunkAckTimeout`) and the **durable claim**. A `deposited_at` column on the source push row is
      set with a conditional UPDATE, like `claimRelayBackup`, so a round happens once per transfer across
      sweeps and restarts.
- **Behaviour fixes (each with a test):**
    - **Window.** It counts the transfer's queued chunk rows (design §6.2), not coverage. Coverage ACKs only
      move the resume point and progress.
    - **Coverage ACK at the receiver.** `chunk k is covered ⇔ (k+1)·chunkSize ≤ P` (or P = total). A
      `Duplicate` also answers with a coverage ACK.
    - **Restart.** Rebuild the pending set from the outbox rows still queued; don't delete and re-seal. The
      marker is never re-sent under the same transferId: if an OFFER must be sent again, a fresh transfer
      starts.
    - **COMPLETE.** Any COMPLETE for (file, target) resolves the authoring row as COMPLETED and emits once;
      only DEPOSIT rows are cancelled.
    - **Authorisation.** ACK, COMPLETE and CANCEL are acted on only when `fileEnvelope.source` is the row's
      counterpart.
    - **A deferred seal re-arms** on the engine's own timer, not on the next `pushFile`.
    - **Size guard.** `pushFile` refuses large files; the engine is never asked to push them.
    - **Expiry and cleanup.** A sweep expires live rows past TTL and deletes terminal rows, together with their
      key. The same sweep prunes expired incoming-transfer rows. Keys never outlive their transfer (invariant
      5).
- **Tests (realistic):**
    - a 10 MB file over a direct link through two real routers with the default checkpoint config reaches
      READY;
    - a restart mid-push resumes without re-sealing;
    - deposits happen once across sweeps and restarts;
    - a deposit's COMPLETE before the direct one gives COMPLETED;
    - a forged CANCEL from a relay is ignored;
    - concurrent ACKs never send a chunk twice.

  These cover design §19's rows on mid-transfer restarts, deposit triggers, "stall → immediate deposit" and
  "late chunk after READY".

### Step R3.2 — The inbound handler, split (`routing/inbound/handlers`, `routing/file`)

- **Changes:** `FileInboundHandler` becomes a thin dispatcher over three handlers:
    - `FileTargetHandler`: local OFFER, CHUNK, ACK, COMPLETE and CANCEL;
    - `FileRelayDoor`: foreign target, admission;
    - `FileServeHandler`: REQUEST.

  It talks to the push engine through one `FileTransferSignals` interface, not five lambdas, and no longer
  needs `FileCarrier`.
    - **Validate the OFFER's public half** on the local path as the relay door does: `chunkSize > 0`, total
      bytes and start index in range.
    - **Map handler exceptions to `Defer`.** R2.3 adds the processor-level safety net.
- **Tests:** a re-sent OFFER for a known transferId is handled idempotently; a bad `chunkSize` is
  PERMANENT; the stale `foreignTarget_isDeclined` test is removed.

### Step R3.3 — The relay on the push engine (`routing/file/relay`)

- **Changes:**
    - Delete `FileRelayForwarder`. A whole relay transfer is forwarded by `FilePushEngine` with the
      stored-envelope chunk source (design §6, §7.2.4). It uses tag `FILE_FORWARD` and never deposits.
    - Admission, eviction and the forward's outbox cleanup happen in one `FileRelayStore` transaction. An
      evicted or door-cancelled transfer also deletes its forward outbox rows.
    - **Relay ACKs.** The relay ACKs each stored chunk with a coverage ACK, so the depositor's window (R3.1)
      advances.
    - **Idle reservation TTL** (design §7.2.3). Add `fileRelayIdleTtl` to `RouterConfig` (default 1 hour).
        - A relay transfer that is not yet whole and has received no chunk for that long is dropped, and its
          reserved quota is freed.
        - Whole transfers keep the full `fileTransferTtl`.
        - Measure it from `last_chunk_at`; `setStatus` stops touching that column.
        - A depositor that comes back after the idle TTL gets DECLINED on its next chunk and ends that
          deposit; the pull path covers the rest.
    - **CANCEL authorisation.** A door CANCEL deletes the transfer only when the CANCEL comes from its
      depositor.
    - **Inner source check.** Confirm that the relay checks the inner source is known and not banned (design
      §7.1); add the check if it is missing.
- **Tests:** two-router-plus-relay test (sender → relay → target) with a 10 MB file, covering design §19:
    - target offline;
    - deposit, wholeness, forward, READY;
    - two relays, both forward;
    - eviction mid-deposit leads to DECLINED at the next chunk;
    - a forwarding relay never re-deposits.

---

## Phase R4 — The receive side

### Step R4.1 — Receive engine, split (`orchestrator/file/receive`)

Replaces `DefaultFileReceiveEngine`.

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

### Step R4.2 — Store, quota and eviction (`orchestrator/file`)

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
      `selectAllFileDescriptors`, `FileTransferStore.findByFile`, and `FileIds.*ForBytes` (moved to test
      fixtures).
- **Tests:** quota eviction of a received file gives EVICTED, then a re-open fetches it (§19 quota row);
  re-importing a SOURCE_LOST file serves again; import of an existing REFERENCE with `keepCopy`.

### Step R4.3 — The coordinator and the GUI facade (`orchestrator/file`, `orchestrator/runtime/file`)

- **Changes:**
    - **The gap trigger counts time while this device runs** (design §8). It tracks the last P advance per
      file in memory, on a monotonic clock started at `start()`, and not against `updatedAt`.
    - **An accepted OFFER marks the pending request as covered by a progressing transfer,** so the grace
      period ends without a REQUEST while a push is progressing (design §9 trigger condition). A STALLED file
      re-opens it.
    - **A no-space refusal records DECLINED** through the engine (§14 PENDING → DECLINED), so the message
      trigger stops re-requesting.
    - **Lifecycle.** The coordinator and the engine keep their job handles; `NodeGraph.stop` stops them.
    - **`DefaultFileTransferService` only calls verbs** (`accept`, `decline`, `receiveState`, `localFilePath`).
      `declineFile` on an IN_FLIGHT receive aborts it and cleans up through the engine.
    - Delete the duplicated `findAttachment` and DECLINED-row building in favour of one owner.
- **Tests:**
    - restarting after a long sleep doesn't fire the gap trigger at once;
    - a slow push past the grace period gets no REQUEST;
    - declining an in-flight file leaves no `.part`;
    - no-space is DECLINED and not re-requested.

### Step R4.4 — Sending moves out of messaging (`orchestrator/file`, `orchestrator/runtime/message`)

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

---

## Phase R5 — The large path

### Step R5.1 — Append at P (`routing/file/port`, `orchestrator/file/receive`)

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

### Step R5.2 — Streaming plaintext source (`routing/file/port`, `orchestrator/file`)

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

### Step R5.3 — Transport: ordered, reliable file channels (`transport/webrtc`, `jvmMain` backend)

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

### Step R5.4 — The fetch, one actor per channel (`routing/file/fetch`)

Replaces `FileFetchManager`.

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

---

## Phase R6 — Protocol, protection, cleanup

### Step R6.1 — Protocol hardening (`protocol`)

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
    - **Guide:** update §2 (the service split), §4 (the file rows' single writers), §6 (tag kinds and retry
      classes) and §8 (feature status).
    - **Design:** apply the changes listed below.
    - **`plan.md`:** mark steps 1–22 as superseded in part by this plan and point steps 23–24 here.
- **Done when:** the guide's and the design's rows match the code, and the full suite is green.

---

## Order and parallelism

**The trunk is R1** (one migration, config, ports, events, Router split, single writers). Everything else
builds on its seams. Land R1.0 → R1.5 in order; R1.4 is the largest change.

**After R1:**

- **R2.1, R2.2 and R2.3** are independent of each other.
- **R3 (small path)** needs R2.3.
- **R4 (receive side)** needs only R1.
- **R5 (large path)** needs R2.2 and R4.1. R5.3 (transport) is independent and can start any time.
- **R6.1 and R6.2** can run beside R3–R5. R6.3 and R6.4 come last.

**If you want the critical bugs out first:** R6.1's decode bounds and R5.3's in-order delivery are
self-contained and safe to land early. Everything else in the review's critical list is fixed by the rewrite
it sits in.

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
