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

/**
 * Composite cursor for stable pagination of room messages.
 *
 * Display ordering is `(createdAtEpochSeconds DESC, lamportClock DESC, messageId DESC)` — a total
 * order with no ties, so pagination is stable across live inserts and reloads. The cursor captures
 * the oldest row of the currently-loaded window so the next page begins strictly below it.
 */
data class MessagePageCursor(
    val createdAtEpochSeconds: Long,
    val lamportClock: Long,
    val messageId: String,
)

sealed interface IngestResult {
    val payload: MessagePayload
    val closedGapMissingPrevIds: List<String>
    data class Inserted(
        override val payload: MessagePayload,
        override val closedGapMissingPrevIds: List<String> = emptyList(),
    ) : IngestResult
    data class BecameOrphan(
        override val payload: MessagePayload,
        override val closedGapMissingPrevIds: List<String> = emptyList(),
        val missingPrevId: String,
    ) : IngestResult
}
