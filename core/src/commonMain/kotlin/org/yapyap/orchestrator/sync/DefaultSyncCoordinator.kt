package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.persistence.sync.PendingSyncRepository
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
    private val identityResolver: IdentityResolver,
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

    override suspend fun requestFrontierSync(roomId: RoomId, tips: List<Uuid>) {
        syncMutex.withLock {
            for (tip in tips) {
                if (messageRepository.findById(tip) == null) {
                    insertSyncForTarget(roomId, tip)
                }
                // A known tip is either chainable (ancestry present — nothing to do)
                // or a local orphan (our causal holds already chase its parents).
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
     */
    private suspend fun processBecameOrphan(result: IngestResult.BecameOrphan) {
        syncMutex.withLock {
            val roomId = result.payload.roomId
            for (missing in result.missingPrevIds) {
                insertSyncForTarget(roomId, missing)
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

    private suspend fun insertSyncForTarget(roomId: RoomId, targetMessageId: Uuid) {
        if (pendingSyncRepository.findSyncByTarget(roomId, targetMessageId) != null) return
        val candidates = candidateAccountsFor(roomId)
        pendingSyncRepository.insertSync(
            syncId = Uuid.random(),
            roomId = roomId,
            targetMessageId = targetMessageId,
            candidateAccounts = candidates,
            nextAttemptAt = clock.now() + orchestratorConfig.value.syncGracePeriod,
        )
    }

    private suspend fun candidateAccountsFor(roomId: RoomId): List<org.yapyap.crypto.identity.AccountId> {
        return roomRepository.membersOfRoom(roomId)
            .filter { it != identityResolver.getLocalAccountId() }
    }
}
