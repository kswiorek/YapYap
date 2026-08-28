package org.yapyap.routing.sync

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.router.RouterConfig

interface SyncPayloadProvider {
    suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload>
}

class DefaultSyncPayloadProvider(
    private val messageRepository: MessageRepository,
    private val routerConfig: StateFlow<RouterConfig>,
) : SyncPayloadProvider {

    override suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload> {
        val roomId = syncRequest.roomId
        val anchor = syncRequest.anchorLamport
        val orphan = syncRequest.orphanLamport
        // Page size is purely the responder's policy; the requester's retry loop
        // re-requests until the gap closes, so no per-request limit is needed.
        val limit = routerConfig.value.syncMaxMessages

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