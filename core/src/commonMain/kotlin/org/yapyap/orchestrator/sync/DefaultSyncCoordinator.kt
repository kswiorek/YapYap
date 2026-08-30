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
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

class DefaultSyncCoordinator(
    private val pipeline: InboundMessagePipeline,
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
    private val identityResolver: IdentityResolver,
    private val pendingSyncRepository: PendingSyncRepository,
    private val timeProvider: EpochProvider = SystemEpochProvider,
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
    // Ping/pong-triggered range sync
    // ------------------------------------------------------------------
    /**
     * We know that a version of this room exists where the last messageLamport is pingLamport
     * Either:
     * our localSeqN agrees with the ping or the peer is outdated => ignore;
     * we are outdated so either:
     * a local sync does not exist => it needs to be created;
     * it does exist and it's orphanLamport is lower than pingLamport =>
     * the sync needs to be updated to get all the messages;
     * the sync does exist and includes pingLamport => ignore
     */

    override suspend fun requestRangeSync(roomId: RoomId, pingLamport: Long) {
        syncMutex.withLock {
            val localSeqN = roomRepository.getLocalSeq(roomId) ?: error("unknown room $roomId")
            if (localSeqN >= pingLamport) return
            val sync = pendingSyncRepository.findGapSyncByAnchor(roomId, localSeqN)

            if (sync == null) {
                insertNewGapSync(roomId, localSeqN, pingLamport)
            } else if (pingLamport > sync.orphanLamport) {
                pendingSyncRepository.updateOrphanLamport(sync.syncId, pingLamport)
            }
        }
    }

    // ------------------------------------------------------------------
    // Ingest result handling
    // ------------------------------------------------------------------

    /**
     * A message arrived and became an orphan (its prevId is missing).
     *
     * It must be ensured that each orphan has a sync running which will close it.
     * Anchor is the highest lamport of the message before the orphan (start of the gap).
     * A sync can target only a gap, there cannot be a message inside a sync range
     * apart from the anchor and orphan itself.
     * If a sync already exists for the anchor, it must be updated so that
     * the orphan is at most the border of the sync range.
     * L > sync.orphanLamport is an edge case where the orphan gets received and
     * its lamportClock is higher than any ping/pong sync requests.
     * L == sync.orphanLamport exists when a message was received from a separate branch
     * from the orphan that triggered the sync request,
     * or it is the last message from the sync triggered by ping/pong.
     * L < sync.orphanLamport if that message is an orphan, it must either:
     * satisfy the message at sync.orphanLamport,
     * then the sync is updated so that the sync.orphanLamport = L;
     * be a message from a separate branch or middle of the range (orphanStillOpen is true),
     * then the existing sync must be shortened so that sync.orphanLamport = L and
     * a new sync be created from the previous orphan to L;
     * be a message from the middle of a RangeSync where the message at sync.orphanLamport does not exist,
     * then the existing sync must be shortened so that sync.orphanLamport = L and
     * a new sync be created from the previous orphan to L.
     */
    private suspend fun processBecameOrphan(result: IngestResult.BecameOrphan) {
        syncMutex.withLock {
            val L = result.payload.lamportClock
            val roomId = result.payload.roomId
            val anchor = result.anchorLamport

            val sync = pendingSyncRepository.findGapSyncByAnchor(roomId, anchor)
            if (sync == null) {
                insertNewGapSync(roomId, anchor, L)
                return
            }

            // One sync per (anchor, room) expected; take the highest orphan.
            val orphan = sync.orphanLamport

            when {
                L > orphan -> {
                    pendingSyncRepository.updateOrphanLamport(sync.syncId, L)
                }

                L == orphan -> {
                    // No-op: sync already targets this lamport (branching twin).
                }

                L < orphan -> {
                    pendingSyncRepository.updateOrphanLamport(sync.syncId, L)

                    val orphanStillOpen = messageRepository.isOrphanAtLamport(roomId, orphan)
                    val messageAtOrphan = messageRepository.countAtLamport(roomId, orphan) == 0L

                    if (orphanStillOpen || messageAtOrphan) {
                        insertNewGapSync(roomId, L, orphan)                        // [anchor, L]
                    }
                }
            }
        }
    }

    /**
     * A message arrived and was inserted as non-orphaned (its prevId exists).
     *
     * it can either be a new message from a proper chain, where no sync exists,
     * or it is the anchorLamport of an existing sync. Syncs are identified by the anchorLamport,
     * so the old sync must be deleted.
     * If the orphan still exists or the sync continues past the received message,
     * a new sync must be created.
     * In the case that there is a branch at sync.orphanLamport (a message parallel to the current one),
     * we can assume it will arrive at some point or its child (orphan will arrive)
     */
    private suspend fun processInserted(result: IngestResult.Inserted) {
        syncMutex.withLock {
            val L = result.payload.lamportClock
            val roomId = result.payload.roomId

            val computedAnchor = messageRepository.maxLamportBelow(roomId, L) ?: -1L

            val sync = pendingSyncRepository.findGapSyncByAnchor(roomId, computedAnchor) ?: return

            pendingSyncRepository.deleteSync(sync.syncId)
            val orphanStillOpen = messageRepository.isOrphanAtLamport(roomId, sync.orphanLamport)
            val noMessageAtOrphan = messageRepository.countAtLamport(roomId, sync.orphanLamport) == 0L
            if (orphanStillOpen || noMessageAtOrphan) {
                insertNewGapSync(roomId, L, sync.orphanLamport)
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun insertNewGapSync(roomId: RoomId, anchorLamport: Long, orphanLamport: Long) {
        val candidates = candidateAccountsFor(roomId)
        pendingSyncRepository.insertSync(
            syncId = Uuid.random(),
            roomId = roomId,
            anchorLamport = anchorLamport,
            orphanLamport = orphanLamport,
            candidateAccounts = candidates,
            nextAttemptAt = timeProvider.nowEpochSeconds() + orchestratorConfig.value.syncGracePeriodSeconds,
        )
    }

    private suspend fun candidateAccountsFor(roomId: RoomId): List<org.yapyap.crypto.identity.AccountId> {
        return roomRepository.membersOfRoom(roomId)
            .filter { it != identityResolver.getLocalAccountId() }
    }
}