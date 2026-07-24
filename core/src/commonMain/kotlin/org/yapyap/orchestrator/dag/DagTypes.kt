package org.yapyap.orchestrator.dag

import org.yapyap.protocol.envelopes.MessagePayload

sealed interface MessageDraft {
    data class Text(val text: String) : MessageDraft
    data class GlobalEvent(val eventBytes: ByteArray) : MessageDraft
}

data class Gap(
    val missingPrevId: String,
    val orphanedMessageId: String,
)

sealed interface IngestResult {
    data class Inserted(val payload: MessagePayload) : IngestResult
    data class Duplicate(val messageId: String) : IngestResult
    data class Rejected(val reason: String) : IngestResult
    data class BecameOrphan(
        val payload: MessagePayload,
        val missingPrevId: String,
    ) : IngestResult
}
