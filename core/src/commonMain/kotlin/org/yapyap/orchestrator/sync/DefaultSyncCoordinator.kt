package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.time.EpochSecondsProvider
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

class DefaultSyncCoordinator(
    private val pipeline: InboundMessagePipeline,
    private val roomRepository: RoomRepository,
    private val identityResolver: IdentityResolver,
    private val pendingSyncRepository: PendingSyncRepository,
    private val timeProvider: EpochSecondsProvider,
): SyncCoordinator {

    @Volatile
    private var serviceScope: CoroutineScope? = null
    private var subscriptionJob: Job? = null

    override fun start(scope: CoroutineScope) {
        check(subscriptionJob == null) { "MessagingService already started" }
        serviceScope = scope
        subscriptionJob = scope.launch {
            pipeline.ingestResults.collect { result ->
                when (result) {
                    is IngestResult.BecameOrphan -> processOrphan(result)       // INSERT intent
                    is IngestResult.Inserted -> if (result.closedGapMissingPrevIds.isNotEmpty())
                        pendingSyncRepository.deleteByMissingAncestorIds(result.closedGapMissingPrevIds)
                }
            }
        }
    }

    override suspend fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob?.join()  // wait for pipeline collector to finish
        serviceScope = null
    }

    private suspend fun processOrphan(result: IngestResult.BecameOrphan){
        val candidateAccounts = roomRepository.membersOfRoom(result.payload.roomId).filter { it != identityResolver.getLocalAccountId() }
        val syncRequest = SyncRequest.GapSyncRequest(
            syncId = Uuid.random(),
            missingPrevId = result.missingPrevId,
            roomId = result.payload.roomId,
            orphanedMessageId = result.payload.messageId,
            maxMessages = 16,
        )
        //TODO: Some time in the future, determine what's best, add config
        pendingSyncRepository.insertSync(syncRequest, candidateAccounts, timeProvider.nowEpochSeconds()+60)
    }

    private suspend fun processRangeSync(roomId: String) {
        val sinceLamport = roomRepository.getLocalSeq(roomId) ?: -1L  // -1 if room empty → get everything
        val candidateAccounts = roomRepository.membersOfRoom(roomId)
            .filter { it != identityResolver.getLocalAccountId() }
        val syncRequest = SyncRequest.RangeSyncRequest(
            syncId = Uuid.random(),
            roomId = roomId,
            sinceLamport = sinceLamport,
            maxMessages = 16,
        )
        //TODO: Some time in the future, determine what's best, add config
        pendingSyncRepository.insertSync(syncRequest, candidateAccounts, timeProvider.nowEpochSeconds()+60)
        //TODO: Method to delete range sycns
    }
}