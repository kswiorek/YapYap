package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.protocol.RoomId
import kotlin.concurrent.Volatile
import kotlin.time.Clock
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
class DefaultSyncCoordinator(
    private val pipeline: InboundMessagePipeline,
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
    private val pendingSyncRepository: PendingSyncRepository,
    private val clock: Clock = Clock.System,
    private val orchestratorConfig: StateFlow<OrchestratorConfig>
) : SyncCoordinator {

    @Volatile
    private var serviceScope: CoroutineScope? = null
    private var subscriptionJob: Job? = null
    private val syncMutex = Mutex()

    override fun start(scope: CoroutineScope) {
        check(subscriptionJob == null) { "SyncCoordinator already started" }
        serviceScope = scope
        subscriptionJob = scope.launch {
            pipeline.ingestResults.collect { result ->
                when (result) {
                    is IngestResult.BecameOrphan -> processBecameOrphan(result)
                    is IngestResult.Inserted -> processInserted(result)
                }
            }
        }
    }

    override suspend fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob?.join()
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
        // Known room with no candidates (all members removed): still mint the
        // row as a placeholder — the membership refresh revives it if
        // candidacy returns, and nothing else would ever chase this gap.
        pendingSyncRepository.insertSync(
            syncId = Uuid.random(),
            roomId = roomId,
            targetMessageId = targetMessageId,
            candidateAccounts = candidates,
            nextAttemptAt = clock.now() + orchestratorConfig.value.syncGracePeriod,
        )
    }
}
