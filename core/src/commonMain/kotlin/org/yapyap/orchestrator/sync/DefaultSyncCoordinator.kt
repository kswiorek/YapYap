package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.persistence.sync.PendingSyncRow
import org.yapyap.time.EpochSecondsProvider
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

class DefaultSyncCoordinator(
    private val pipeline: InboundMessagePipeline,
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
    private val identityResolver: IdentityResolver,
    private val pendingSyncRepository: PendingSyncRepository,
    private val timeProvider: EpochSecondsProvider,
) : SyncCoordinator {

    @Volatile
    private var serviceScope: CoroutineScope? = null
    private var subscriptionJob: Job? = null

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

    override fun requestRangeSync(roomId: String) {
        val scope = serviceScope ?: return
        scope.launch {
            // De-dup: don't create a second range sync for the same room.
            if (pendingSyncRepository.hasRangeSyncForRoom(roomId)) return@launch

            val anchorLamport = roomRepository.getLocalSeq(roomId) ?: -1L
            val candidates = candidateAccountsFor(roomId)
            pendingSyncRepository.insertSync(
                syncId = Uuid.random(),
                roomId = roomId,
                maxMessages = MAX_MESSAGES,
                anchorLamport = anchorLamport,
                orphanLamport = RANGE_SYNC_SENTINEL,
                candidateAccounts = candidates,
                nextAttemptAt = timeProvider.nowEpochSeconds() + GRACE_PERIOD_SECONDS,
            )
        }
    }

    // ------------------------------------------------------------------
    // Ingest result handling
    // ------------------------------------------------------------------

    /**
     * A message arrived and became an orphan (its prevId is missing).
     *
     * Either:
     *  - No existing gap sync covers this lamport → insert a new gap sync
     *    [anchorLamport, L].
     *  - An existing gap sync [anchor, orphan] contains L:
     *    - If the sync's orphan is still open and L < orphan → **split**:
     *      shrink the existing sync's anchor to L ([L, orphan]) and create a new
     *      sync [anchor, L] for the new orphan.
     *    - If the sync's orphan is still open and L == orphan → no-op (the sync
     *      already targets this lamport; branching will resolve via dedup).
     *    - If the sync's orphan is resolved and L == orphan → the old orphan at
     *      this lamport was from a different branch and is now closed, but @L is
     *      a new orphan: delete the old sync, insert a new one [anchor, L].
     *    - If the sync's orphan is resolved and L < orphan → @L closed the old
     *      orphan's gap but is itself orphaned: shrink orphan to L ([anchor, L]).
     */
    private suspend fun processBecameOrphan(result: IngestResult.BecameOrphan) {
        val L = result.payload.lamportClock
        val roomId = result.payload.roomId
        val anchor = result.anchorLamport

        val existing = pendingSyncRepository.findGapSyncsByAnchor(roomId, anchor)
        if (existing.isEmpty()) {
            insertNewGapSync(roomId, anchor, L)
            return
        }

        // One sync per (anchor, room) expected; take the highest orphan.
        val sync = existing.maxByOrNull { it.orphanLamport }!!
        val orphan = sync.orphanLamport

        when {
            L > orphan -> {
                // Extend: new orphan above the gap, same connected prefix.
                // (L can't close orphan's gap: orphan's prev is at lamport < orphan < L.)
                pendingSyncRepository.updateOrphanLamport(sync.syncId, L)
            }
            L == orphan -> {
                // No-op: sync already targets this lamport (branching twin).
            }
            L < orphan -> {
                val orphanStillOpen = messageRepository.isOrphanAtLamport(roomId, orphan)
                if (orphanStillOpen) {
                    // Split: @L is a new orphan inside the gap.
                    pendingSyncRepository.updateAnchorLamport(sync.syncId, L)  // [L, orphan]
                    insertNewGapSync(roomId, anchor, L)                        // [anchor, L]
                } else {
                    // @L closed the old orphan's gap, but @L is itself orphaned.
                    pendingSyncRepository.updateOrphanLamport(sync.syncId, L)  // [anchor, L]
                }
            }
        }
    }

    /**
     * A message arrived and was inserted as non-orphaned (its prevId exists).
     *
     * If it falls inside an existing gap sync [anchor, orphan]:
     *  - If the sync's orphan is still open → shrink anchor to L ([L, orphan]).
     *  - If the sync's orphan is resolved → the gap is closed; delete the sync.
     *
     * If no sync contains L, there's nothing to do.
     */
    private suspend fun processInserted(result: IngestResult.Inserted) {
        val L = result.payload.lamportClock
        val roomId = result.payload.roomId

        val affected = pendingSyncRepository.findGapSyncsContaining(roomId, L)
        for (sync in affected) {
            val orphanStillOpen = messageRepository.isOrphanAtLamport(roomId, sync.orphanLamport)
            if (orphanStillOpen) {
                pendingSyncRepository.updateAnchorLamport(sync.syncId, L)
            } else {
                pendingSyncRepository.deleteSync(sync.syncId)
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun insertNewGapSync(roomId: String, anchorLamport: Long, orphanLamport: Long) {
        val candidates = candidateAccountsFor(roomId)
        pendingSyncRepository.insertSync(
            syncId = Uuid.random(),
            roomId = roomId,
            maxMessages = MAX_MESSAGES,
            anchorLamport = anchorLamport,
            orphanLamport = orphanLamport,
            candidateAccounts = candidates,
            nextAttemptAt = timeProvider.nowEpochSeconds() + GRACE_PERIOD_SECONDS,
        )
    }

    private suspend fun candidateAccountsFor(roomId: String): List<org.yapyap.crypto.identity.AccountId> {
        return roomRepository.membersOfRoom(roomId)
            .filter { it != identityResolver.getLocalAccountId() }
    }

    companion object {
        /** Maximum messages requested per sync. Mirrors SyncPayloadProvider's cap. */
        private const val MAX_MESSAGES = 16

        /** Grace period (seconds) before sending a gap sync, to allow out-of-order
         *  messages to arrive and close the gap without a round-trip. */
        private const val GRACE_PERIOD_SECONDS = 60L

        /** Sentinel value for [SyncRequest.orphanLamport] indicating a range sync. */
        private const val RANGE_SYNC_SENTINEL = -1L
    }
}