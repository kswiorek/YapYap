package org.yapyap.orchestrator.dag

import org.yapyap.protocol.envelopes.MessagePayload

interface DagEngine {
    suspend fun append(roomId: String, draft: MessageDraft): MessagePayload
    suspend fun ingest(payload: MessagePayload): IngestResult
    suspend fun getMessagesInRoom(roomId: String): List<MessagePayload>

    /**
     * Paginated messages ordered by lamport_clock descending (newest first).
     * @param beforeLamport If non-null, return messages with lamport_clock < this value.
     *                      If null, return the latest [limit] messages.
     * @return Up to [limit] messages, ordered oldest→newest within the page.
     */
    suspend fun getMessagesInRoom(
        roomId: String,
        limit: Int,
        beforeLamport: Long? = null,
    ): List<MessagePayload>

    suspend fun ancestorsOf(roomId: String, messageId: String, limit: Int): List<MessagePayload>
    suspend fun openGaps(roomId: String): List<Gap>
    suspend fun openGaps(): List<Gap>  // optional: all rooms, for sync/boot
}