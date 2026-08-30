package org.yapyap.orchestrator.dag

import org.yapyap.persistence.messaging.MessageCursor
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.uuid.Uuid

interface DagEngine {
    suspend fun append(roomId: RoomId, draft: MessageDraft): MessagePayload
    suspend fun ingest(payload: MessagePayload): IngestResult?
    suspend fun getMessagesInRoom(roomId: RoomId): List<MessagePayload>

    /**
     * Paginated room view ordered by display order
     * `(createdAtEpochSeconds DESC, lamportClock DESC, messageId DESC)` (newest first).
     *
     * @param before If non-null, return messages strictly older than this cursor
     *               (i.e. the next page below the oldest row of the previous page).
     *               If null, return the latest [limit] messages.
     * @return Up to [limit] messages in display order (newest→oldest). Callers that need
     *         oldest→newest rendering should reverse the result.
     */
    suspend fun getMessagesInRoom(
        roomId: RoomId,
        limit: Int,
        before: MessageCursor? = null,
    ): List<MessagePayload>

    suspend fun ancestorsOf(roomId: RoomId, messageId: Uuid, limit: Int): List<MessagePayload>
    suspend fun openGaps(roomId: RoomId): List<Gap>
    suspend fun openGaps(): List<Gap>  // optional: all rooms, for sync/boot
}