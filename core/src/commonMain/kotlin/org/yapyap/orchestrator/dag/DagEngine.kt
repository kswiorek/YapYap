package org.yapyap.orchestrator.dag

import org.yapyap.protocol.envelopes.MessagePayload

interface DagEngine {
    suspend fun append(roomId: String, draft: MessageDraft): MessagePayload
    suspend fun ingest(payload: MessagePayload): IngestResult

    suspend fun ancestorsOf(roomId: String, messageId: String, limit: Int): List<MessagePayload>
    suspend fun openGaps(roomId: String): List<Gap>
    suspend fun openGaps(): List<Gap>  // optional: all rooms, for sync/boot
}