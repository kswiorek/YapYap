package org.yapyap.routing.sync

import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import kotlin.uuid.Uuid

interface SyncPayloadProvider {
    suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload>
}

class DefaultSyncPayloadProvider(
    val messageRepository: MessageRepository,
): SyncPayloadProvider {
    override suspend fun getMessages(syncRequest: SyncRequest): List<MessagePayload> {
        return when(syncRequest){
            is SyncRequest.GapSyncRequest -> {getPayloadsForGap(syncRequest)}
            is SyncRequest.RangeSyncRequest -> {getPayloadsForRange(syncRequest)}
        }
    }

    private suspend fun getPayloadsForGap(syncRequest: SyncRequest.GapSyncRequest): List<MessagePayload> {
        val maxMessages = if (syncRequest.maxMessages > 16) 16 else syncRequest.maxMessages
        val result = mutableListOf<MessagePayload>()
        var currentId: Uuid? = syncRequest.missingPrevId
        var steps = 0

        while (currentId != null && steps < maxMessages) {
            val row = messageRepository.findById(currentId) ?: break
            result.add(row.payload)
            currentId = row.payload.prevId
            steps++
        }
        return result
    }

    private suspend fun getPayloadsForRange(syncRequest: SyncRequest.RangeSyncRequest): List<MessagePayload>{
        val maxMessages = if (syncRequest.maxMessages > 16) 16 else syncRequest.maxMessages
        val messages = messageRepository.findMessagesInRoomAfterLamport(
            syncRequest.roomId,
            maxMessages,
            syncRequest.sinceLamport)
        return messages.map { it.payload }
    }

}