package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.messaging.RoomMembershipRepository
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.router.Router
import kotlin.concurrent.Volatile

class DefaultSyncCoordinator(
    private val dagEngine: DagEngine,
    private val router: Router,
    private val pipeline: InboundMessagePipeline,
    private val roomMembershipRepository: RoomMembershipRepository,
    private val identityResolver: IdentityResolver,
    private val pendingSyncRepository: PendingSyncRepository,
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

        //TODO periodic sync job etc
    }

    override suspend fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob?.join()  // wait for pipeline collector to finish
        serviceScope = null
    }

    private suspend fun processOrphan(result: IngestResult.BecameOrphan){
        val candidateAccounts = roomMembershipRepository.membersOfRoom(result.payload.roomId).filter { it != identityResolver.getLocalAccountId() }
        val syncRequest = SyncRequest.GapSyncRequest(
            missingPrevId = result.missingPrevId,
            roomId = result.payload.roomId,
            orphanedMessageId = result.payload.messageId,
            maxMessages = 16,
        )
        pendingSyncRepository.insertSync(syncRequest, candidateAccounts)
    }
}