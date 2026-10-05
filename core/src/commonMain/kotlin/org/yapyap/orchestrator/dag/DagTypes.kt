package org.yapyap.orchestrator.dag

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.RoomType
import org.yapyap.persistence.db.VerificationState
import org.yapyap.protocol.envelopes.GlobalEventPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RoomEventPayload
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

sealed interface MessageDraft {
    data class Text(val text: String) : MessageDraft
    data class GlobalEvent(val event: GlobalEventPayload) : MessageDraft

    /**
     * Room-tier admin op (MemberAdd / MemberRemove / AddAdmin / RemoveAdmin).
     * `RoomCreated` is excluded by contract — the genesis has its own engine path
     * ([DagEngine.createRoom]) because its roomId is derived, not supplied. The
     * engine rejects a smuggled `RoomCreated` here as a programming error.
     */
    data class RoomEvent(val event: RoomEventPayload) : MessageDraft
}

/**
 * Genesis input for [DagEngine.createRoom]. The roomId is derived from the minted
 * genesis messageId, never supplied — mirroring
 * [MessagePayload.RoomEvent.createGenesis].
 */
data class RoomCreatedDraft(
    val memberAccountIds: Set<AccountId>,
    val roomName: String,
    val roomType: RoomType,
    val spaceId: Uuid? = null,
) {
    init {
        require(roomType != RoomType.GLOBAL_CONTROL) { "chat rooms must not use GLOBAL_CONTROL" }
        require(roomType != RoomType.UNKNOWN) { "room type UNKNOWN is local-only" }
    }
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
     * The room holds messages but its chainable frontier is empty — every tip parked on
     * an open gap, unverified, or excluded by non-VERIFIED ancestry. Appending would fork
     * a second root, so nothing is written. Transient while gaps/verification resolve;
     * permanent while only REJECTED remains.
     */
    class FrontierUnavailable(val roomId: RoomId) : DagException(
        "Cannot append in room $roomId: chainable frontier is empty but the room holds messages",
    )

    /**
     * A locally minted genesis derived a room id that already holds messages.
     * Structurally impossible under the self-certifying derivation (the id hashes
     * a fresh random message id); fail closed defensively — a second genesis
     * must never fork an existing room.
     */
    class RoomAlreadyExists(val roomId: RoomId) : DagException(
        "Cannot create room $roomId: the room already holds messages",
    )
}
