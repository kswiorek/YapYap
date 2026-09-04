package org.yapyap.orchestrator.dag

import org.yapyap.persistence.db.VerificationState
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

/**
 * A stored message changed verification state (e.g. PENDING -> VERIFIED/REJECTED after identity
 * arrives, or VERIFIED/-> REJECTED on a structural check at gap closure). Emitted on
 * [DagEngine.verificationStateChanges] — a message-related signal that is *not* a new message.
 */
data class VerificationStateChange(
    val messageId: Uuid,
    val roomId: RoomId,
    val fromState: VerificationState,
    val toState: VerificationState,
)

sealed interface IngestResult {
    val payload: MessagePayload
    val closedGapMissingPrevIds: List<Uuid>
    val verificationState: VerificationState

    data class Inserted(
        override val payload: MessagePayload,
        override val closedGapMissingPrevIds: List<Uuid> = emptyList(),
        override val verificationState: VerificationState = VerificationState.VERIFIED,
    ) : IngestResult

    data class BecameOrphan(
        override val payload: MessagePayload,
        override val closedGapMissingPrevIds: List<Uuid> = emptyList(),
        val missingPrevId: Uuid,
        val anchorLamport: Long,
        override val verificationState: VerificationState = VerificationState.VERIFIED,
    ) : IngestResult
}
@JvmInline
value class RoomId(val value: Uuid) {
    companion object {
        /** The single global control room shared by every device. */
        val GLOBAL = RoomId(Uuid.NIL)
    }
}