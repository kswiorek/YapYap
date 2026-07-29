package org.yapyap.orchestrator.dag

import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.uuid.Uuid

sealed interface MessageDraft {
    data class Text(val text: String) : MessageDraft
    data class GlobalEvent(val eventBytes: ByteArray) : MessageDraft
}

data class Gap(
    val missingPrevId: Uuid,
    val orphanedMessageId: Uuid,
)

sealed interface IngestResult {
    val payload: MessagePayload
    val closedGapMissingPrevIds: List<Uuid>
    data class Inserted(
        override val payload: MessagePayload,
        override val closedGapMissingPrevIds: List<Uuid> = emptyList(),
    ) : IngestResult
    data class BecameOrphan(
        override val payload: MessagePayload,
        override val closedGapMissingPrevIds: List<Uuid> = emptyList(),
        val missingPrevId: Uuid,
    ) : IngestResult
}
