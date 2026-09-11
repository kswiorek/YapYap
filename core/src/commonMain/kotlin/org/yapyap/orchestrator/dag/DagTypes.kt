package org.yapyap.orchestrator.dag

import org.yapyap.persistence.db.VerificationState
import org.yapyap.protocol.envelopes.GlobalEventPayload
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

sealed interface MessageDraft {
    data class Text(val text: String) : MessageDraft
    data class GlobalEvent(val event: GlobalEventPayload) : MessageDraft
}

data class Gap(
    val missingPrevId: Uuid,
    val orphanedMessageId: Uuid,
)

/**
 * A stored message changed verification state (e.g. PENDING -> VERIFIED/REJECTED after identity
 * arrives). Emitted on [DagEngine.verificationStateChanges] — a message-related signal that is
 * *not* a new message.
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
        /** Parent IDs of [payload] that are absent locally (one causal hold each). */
        val missingPrevIds: List<Uuid>,
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

/**
 * Domain failures of the room DAG engine. State refusals are typed values in this
 * hierarchy; generic throws stay reserved for programming errors and infrastructure failures.
 */
sealed class DagException(message: String) : Exception(message) {
    /**
     * The room holds messages but its chainable frontier is empty — every tip is parked
     * on an open gap, or every stored message is REJECTED. Appending now would fork a
     * second root instead of chaining the room DAG, so the append is refused and nothing
     * is written. Transient while gaps are open (retry once they close); permanent while
     * the room holds only REJECTED messages.
     */
    class FrontierUnavailable(val roomId: RoomId) : DagException(
        "Cannot append in room $roomId: chainable frontier is empty but the room holds messages",
    )
}
