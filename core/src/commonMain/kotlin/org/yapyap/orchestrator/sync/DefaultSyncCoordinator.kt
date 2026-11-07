package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.fold.global.IdentityStateChange
import org.yapyap.orchestrator.fold.room.RoomStateChange
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.RoomRepository
import org.yapyap.orchestrator.store.PendingSyncRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomMemberStatus
import org.yapyap.protocol.RoomType
import org.yapyap.routing.router.Router
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tracks "messages we are missing" as one pending-sync row per missing message ID.
 *
 * Orphan- and ping-triggered syncs share this single shape: a row targets exactly one
 * message (a gap parent from a causal hold, or a frontier tip learned from a ping),
 * and the responder serves the target plus its ancestry down to our known frontier.
 * Lifecycle is monotone insert (a new missing ID appears) + delete (the target
 * arrives) — there are no ranges to extend, shorten, or split.
 *
 * Fixpoint invariant: after any ingested batch, every still-missing parent of every
 * stored message has a sync row. Each delivered message reveals its own gaps, so the
 * sync never needs to know the shape of what is missing in advance.
 */
internal class DefaultSyncCoordinator(
    private val pipeline: InboundMessagePipeline,
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
    private val pendingSyncRepository: PendingSyncRepository,
    private val router: Router,
    /** Room-fold commits: every change re-appends the room's members to its live rows. */
    private val roomChanges: Flow<RoomStateChange>,
    /** Global-fold commits: any identity change re-appends the global members to GLOBAL's rows. */
    private val identityChanges: Flow<IdentityStateChange>,
    private val clock: Clock = Clock.System,
    private val orchestratorConfig: StateFlow<OrchestratorConfig>
) : SyncCoordinator {

    /** Last removal re-push per (account, room); see [rePushRemoval]. */
    private val lastRemovalPushAt = HashMap<Pair<AccountId, RoomId>, Instant>()

    @Volatile
    private var serviceScope: CoroutineScope? = null
    private val jobs = mutableListOf<Job>()
    private val syncMutex = Mutex()

    override fun start(scope: CoroutineScope) {
        check(jobs.isEmpty()) { "SyncCoordinator already started" }
        serviceScope = scope
        // The router asks for every pending sync; hand it the ones from before this run
        // (idempotent: a sync it already tracks keeps its attempt state).
        jobs += scope.launch {
            val now = clock.now()
            pendingSyncRepository.allSyncIds().forEach { router.startSync(it, now) }
        }
        jobs += scope.launch {
            pipeline.ingestResults.collect { result ->
                when (result) {
                    is IngestResult.BecameOrphan -> processBecameOrphan(result)
                    is IngestResult.Inserted -> processInserted(result)
                }
            }
        }
        jobs += scope.launch {
            router.pingPayloads.collect { ping ->
                ping.roomFrontiers.forEach { (roomId, tips) ->
                    // One room failing must not end this collector: every later ping's
                    // frontiers would go unsynced until restart.
                    try {
                        requestFrontierSync(roomId, tips, ping.senderAccount)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.error(
                            component = LogComponent.ORCHESTRATOR,
                            event = LogEvent.SYNC_PROCESS_FAILED,
                            message = "Frontier sync request from ping failed",
                            throwable = e,
                            fields = mapOf("roomId" to roomId, "senderAccount" to ping.senderAccount),
                        )
                    }
                }
            }
        }
        // Candidate freshness (docs/room events.md §6): rows freeze their candidates at mint
        // time, so every fold commit re-appends the room's current ACTIVE members to its live
        // rows (insert-if-absent: ping-sender and author candidates survive). The global room
        // has no room-fold changes, so any identity change refreshes it.
        jobs += scope.launch {
            roomChanges.collect { change -> refreshCandidatesFor(change.roomId) }
        }
        jobs += scope.launch {
            identityChanges.collect { refreshCandidatesFor(RoomId.GLOBAL) }
        }
        // Boot sweep for the same freshness: the folds' boot baselines are silent, so members
        // that arrived while we were offline would never refresh existing rows. Idempotent, so
        // racing a still-running boot fold is harmless (its commits re-trigger the collectors).
        jobs += scope.launch {
            for (roomId in roomRepository.allChatRoomIds() + RoomId.GLOBAL) {
                refreshCandidatesFor(roomId)
            }
        }
    }

    override suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        jobs.clear()
        serviceScope = null
    }

    // ------------------------------------------------------------------
    // Ping-triggered frontier sync
    // ------------------------------------------------------------------

    override suspend fun requestFrontierSync(roomId: RoomId, tips: List<Uuid>, senderAccount: AccountId?) {
        syncMutex.withLock {
            for (tip in tips) {
                if (messageRepository.findById(tip) == null) {
                    insertSyncForTarget(roomId, tip, senderAccount)
                }
                // Known tip: chainable (nothing to do), orphan (holds chase parents),
                // or quarantined (complete holds but non-VERIFIED ancestry; nothing to do).
            }
        }
        if (senderAccount == null) return
        // A ping about the room is the sender's assertion that its projection has us in the
        // room, contradicting any earlier decline (docs/room events.md §6): it may be asked again.
        pendingSyncRepository.syncIdsInRoom(roomId).forEach { router.reopenSync(it, senderAccount) }
        rePushRemoval(roomId, senderAccount)
    }

    /**
     * Ping-contradiction recovery (docs/room events.md §6): a ping advertising a room our
     * fold says the sender's account was removed from proves that device never saw its
     * removal — re-deliver the removal node so its orphan machinery mints sync rows and the
     * bounded serve gate fills the gap. Rate-governed per (account, room); self-extinguishing,
     * since a converged device stops advertising the room (shared-room ping filter).
     */
    private suspend fun rePushRemoval(roomId: RoomId, account: AccountId) {
        val row = roomRepository.memberRowOf(roomId, account) ?: return
        if (row.status != RoomMemberStatus.REMOVED) return
        val node = row.removalNodeId?.let { messageRepository.findById(it) }?.payload ?: return
        val now = clock.now()
        val key = account to roomId
        val backoff = orchestratorConfig.value.removalRePushBackoff
        val sendNow = syncMutex.withLock {
            val last = lastRemovalPushAt[key]
            if (last != null && now - last < backoff) {
                false
            } else {
                lastRemovalPushAt[key] = now
                true
            }
        }
        if (!sendNow) return
        // Store-and-forward carries this past the target's offline window (relay
        // deposits); beyond relay retention the next ping re-triggers.
        router.sendMessage(account, node)
        AppLog.debug(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.PING_HANDLED,
            message = "Re-pushed removal node to stale account",
            fields = mapOf("accountId" to account, "roomId" to roomId, "removalNode" to node.messageId),
        )
    }

    /**
     * Appends the room's current ACTIVE members (own devices included — they
     * are valid sync candidates) to all of its pending sync rows' candidates.
     * Idempotent and append-only — safe to run on every projector state change
     * and at boot.
     */
    override suspend fun refreshCandidatesFor(roomId: RoomId) {
        syncMutex.withLock {
            val members = roomRepository.membersOfRoom(roomId)
            if (members.isNotEmpty()) {
                pendingSyncRepository.appendCandidateAccountsForRoom(roomId, members)
                AppLog.debug(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.SYNC_CANDIDATES_UPDATED,
                    message = "Refreshed sync candidates from membership",
                    fields = mapOf("roomId" to roomId, "added" to members.size),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Ingest result handling
    // ------------------------------------------------------------------

    /**
     * A message arrived with missing parents. Each missing parent gets its own sync
     * row (insert-if-absent via the unique (room, target) key); orphans sharing a
     * missing parent collapse into one row automatically.
     *
     * The orphan's author is appended to the rows' candidates (insert-if-absent):
     * they appended on their chainable frontier, so they hold the full ancestry
     * (docs/room events.md §6) — the one identity guaranteed to hold what the
     * rows chase. Verdict-blind, like the rest of serving: a forger as candidate
     * costs one NACK and nothing more.
     */
    private suspend fun processBecameOrphan(result: IngestResult.BecameOrphan) {
        syncMutex.withLock {
            val roomId = result.payload.roomId
            val authorAccount = result.payload.senderAccountId
            // The orphan itself arrived: any sync fetching these bytes is satisfied, even
            // though its parents are still missing (they get their own rows below).
            // Without this, a sync target that arrives before its parents (out-of-order
            // delivery — normal for store-and-forward) keeps its row forever: later
            // Inserteds target other IDs, gap closure re-emits nothing for it, and
            // duplicate re-ingests return null.
            pendingSyncRepository.deleteSyncsByTarget(roomId, result.payload.messageId)
            for (missing in result.missingPrevIds) {
                insertSyncForTarget(roomId, missing, senderAccount = authorAccount)
            }
        }
    }

    /**
     * A message arrived complete. Any sync targeting it is satisfied and dies.
     * Gaps that remain (other missing parents of an orphan) already have their own
     * rows, created when the orphan arrived — no recreation needed.
     */
    private suspend fun processInserted(result: IngestResult.Inserted) {
        syncMutex.withLock {
            pendingSyncRepository.deleteSyncsByTarget(
                roomId = result.payload.roomId,
                targetMessageId = result.payload.messageId,
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * [senderAccount] is the account that revealed this target: the ping sender
     * for frontier tips, the orphan's author for gap parents, null when unknown
     * (onboarding, unresolvable ping senders).
     */
    private suspend fun insertSyncForTarget(
        roomId: RoomId,
        targetMessageId: Uuid,
        senderAccount: AccountId?,
    ) {
        val existing = pendingSyncRepository.findSyncByTarget(roomId, targetMessageId)
        if (existing != null) {
            // Universal accumulation: the sender is always a candidate for the
            // rows its signal re-triggers — insert-if-absent, so multi-device
            // senders of one account and repeat triggers dedup naturally.
            if (senderAccount != null) {
                pendingSyncRepository.addCandidateAccounts(existing.syncId, listOf(senderAccount))
            }
            return
        }
        val members = roomRepository.membersOfRoom(roomId)
        val candidates = (members + listOfNotNull(senderAccount)).distinct()
        if (candidates.isEmpty() && roomRepository.memberStatusesOfRoom(roomId).isEmpty()) {
            // Unknown room, unresolvable sender: a row could never be sent
            // (pickNextDevice over an empty candidate set is always null), so
            // minting it would only grow unbounded state. Skip and log — a
            // later ping re-triggers once identity lands (docs/room events.md §6).
            AppLog.info(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.SYNC_SKIPPED,
                message = "Skipped unknown-room sync with no candidates",
                fields = mapOf("roomId" to roomId, "targetMessageId" to targetMessageId),
            )
            return
        }
        // A room we only know from a ping (e.g. on a device just added to an account that
        // was in it before): give it the same provisional row ingest would, so the sync row
        // can reference it. The projector's genesis merge overwrites the UNKNOWN placeholder.
        if (roomId != RoomId.GLOBAL) {
            roomRepository.ensureRoomExists(roomId, RoomType.UNKNOWN, "")
        }
        // Known room with no candidates (all members removed): still mint the
        // row as a placeholder — the membership refresh revives it if
        // candidacy returns, and nothing else would ever chase this gap.
        val syncId = Uuid.random()
        pendingSyncRepository.insertSync(
            syncId = syncId,
            roomId = roomId,
            targetMessageId = targetMessageId,
            candidateAccounts = candidates,
        )
        router.startSync(syncId, firstAttemptAt = clock.now() + orchestratorConfig.value.syncGracePeriod)
    }
}
