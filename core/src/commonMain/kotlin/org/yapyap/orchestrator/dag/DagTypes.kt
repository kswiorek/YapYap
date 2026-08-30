package org.yapyap.orchestrator.dag

import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.jvm.JvmInline
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
        val anchorLamport: Long,
    ) : IngestResult
}
@JvmInline
value class RoomId(val value: Uuid) {
    companion object {
        /** The single global control room shared by every device. */
        val GLOBAL = RoomId(Uuid.NIL)
    }
}