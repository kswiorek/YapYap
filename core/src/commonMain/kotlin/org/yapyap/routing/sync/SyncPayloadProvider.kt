package org.yapyap.routing.sync

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.orchestrator.sync.SyncConfig
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest

interface SyncPayloadProvider {
    suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload>
}

class DefaultSyncPayloadProvider(
    private val messageRepository: MessageRepository,
    private val syncConfig: StateFlow<SyncConfig>,
) : SyncPayloadProvider {

    override suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload> {
        val roomId = syncRequest.roomId
        val anchor = syncRequest.anchorLamport
        val orphan = syncRequest.orphanLamport
        val limit = syncRequest.maxMessages.coerceAtMost(syncConfig.value.syncMaxMessages)

        // Gap sync: [anchor, orphan] inclusive to catch branching, but skip a
        // boundary lamport when there's exactly one message at it — the requester
        // already has that message (they knew the lamport value).
        val singleAtAnchor = messageRepository.countAtLamport(roomId, anchor) == 1L

        return messageRepository.findMessagesInLamportRange(
            roomId = roomId,
            lowerInclusive = if (singleAtAnchor) anchor+1 else anchor,
            upperInclusive = orphan,
            limit = limit,
        ).map { it.payload }
    }
}