package org.yapyap.orchestrator.dag

import org.yapyap.protocol.envelopes.MessagePayload

class DefaultDagEngine(): DagEngine {
    override suspend fun append(
        roomId: String,
        draft: MessageDraft
    ): MessagePayload {
        TODO("Not yet implemented")
    }

    override suspend fun ingest(payload: MessagePayload): IngestResult {
        TODO("Not yet implemented")
    }

    override suspend fun ancestorsOf(
        roomId: String,
        messageId: String,
        limit: Int
    ): List<MessagePayload> {
        TODO("Not yet implemented")
    }

    override suspend fun openGaps(roomId: String): List<Gap> {
        TODO("Not yet implemented")
    }

    override suspend fun openGaps(): List<Gap> {
        TODO("Not yet implemented")
    }
}