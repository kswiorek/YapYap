package org.yapyap.routing.sync

import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest

interface SyncPayloadProvider {
    suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload>
}

class DefaultSyncPayloadProvider(
    private val messageRepository: MessageRepository,
) : SyncPayloadProvider {

    override suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload> {
        val roomId = syncRequest.roomId
        val anchor = syncRequest.anchorLamport
        val orphan = syncRequest.orphanLamport
        val limit = syncRequest.maxMessages.coerceAtMost(MAX_MESSAGES)

        // Range sync (orphanLamport == -1 sentinel): everything >= anchor.
        // No boundary-skip — we can't assume what the requester has.
        if (orphan < 0) {
            return messageRepository.findMessagesInLamportRange(
                roomId = roomId,
                lowerExclusive = anchor - 1,      // >= anchor
                upperExclusive = null,  // no upper bound
                limit = limit,
            ).map { it.payload }
        }

        // Gap sync: [anchor, orphan] inclusive to catch branching, but skip a
        // boundary lamport when there's exactly one message at it — the requester
        // already has that message (they knew the lamport value).
        val singleAtAnchor = messageRepository.countAtLamport(roomId, anchor) == 1L
        val singleAtOrphan = messageRepository.countAtLamport(roomId, orphan) == 1L

        return messageRepository.findMessagesInLamportRange(
            roomId = roomId,
            lowerExclusive = if (singleAtAnchor) anchor else anchor - 1,
            upperExclusive = if (singleAtOrphan) orphan else orphan + 1,
            limit = limit,
        ).map { it.payload }
    }

    private companion object {
        const val MAX_MESSAGES = 16
    }
}