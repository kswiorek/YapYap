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
    val messageId: Uuid,
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
