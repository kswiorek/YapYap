package org.yapyap.orchestrator.runtime.room

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType

/**
 * A room as the GUI sees it (GLOBAL control room excluded): room-list rows and
 * chat headers share this one type. Projection data only: message-derived content
 * (previews, last activity) comes from `MessagingService.roomPreview` — the room
 * list composes ordering from there.
 *
 * Empty [name] means the room is unnamed — the GUI renders participant names
 * instead. Names travel verbatim from the genesis declaration; the backend
 * never invents one.
 */
data class RoomDetails(
    val roomId: RoomId,
    val name: String,
    val type: RoomType,
    val members: List<RoomMemberView>,
)

data class RoomMemberView(
    val accountId: AccountId,
    val role: RoomMemberRole,
    val status: RoomMemberStatus,
)

/**
 * Render-time policy over the `room_members` projection (docs/room events.md §3).
 *
 * Sole owner of the hide/badge rule: the GUI and `MessagingService.roomPreview`
 * apply this function, never re-derive it. Input is the author's fold-committed
 * row status, or null when the fold committed no row for the author (never a
 * member, deferred identity, or a room that has not folded yet).
 */
enum class MessageDisplayPolicy {
    /** ACTIVE row: render normally. */
    NORMAL,

    /** REMOVED row: render with a "from removed member" badge (all messages). */
    BADGE_REMOVED,

    /** No row: hidden by default (the stranger-injection case). */
    HIDDEN_NON_MEMBER,
}

fun messageDisplayPolicy(memberStatus: RoomMemberStatus?): MessageDisplayPolicy =
    when (memberStatus) {
        RoomMemberStatus.ACTIVE -> MessageDisplayPolicy.NORMAL
        RoomMemberStatus.REMOVED -> MessageDisplayPolicy.BADGE_REMOVED
        null -> MessageDisplayPolicy.HIDDEN_NON_MEMBER
    }

/** Outcome of the GUI-facing room-creation flow. */
sealed interface CreateRoomResult {
    data class Created(val roomId: RoomId) : CreateRoomResult

    /** Refused before any write — nothing created. */
    data class Refused(val reason: CreateRoomRefusal) : CreateRoomResult
}

enum class CreateRoomRefusal {
    /** [RoomService.createRoom] called with an empty member set. */
    EMPTY_MEMBERS,

    /** A member has no row in the identity tables yet. */
    UNKNOWN_MEMBER,
}

/** Outcome of the GUI-facing room membership publishes.
 * Mirrors `org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome`. */
sealed interface RoomEventOutcome {
    /** Appended to the room DAG + folded + broadcast; local fold already committed. */
    data object Published : RoomEventOutcome

    /** Refused before any write — nothing appended, nothing broadcast. */
    data class Refused(val reason: RoomEventRefusal) : RoomEventOutcome
}

sealed interface RoomEventRefusal {
    /** No folded room row locally. */
    data object RoomNotFound : RoomEventRefusal

    /** Local account lacks admin authority per our own projection (incl. the revocation race). */
    data object NotAdmin : RoomEventRefusal

    /** The local or target account is not an ACTIVE member of the room. */
    data object NotMember : RoomEventRefusal

    /** Target has no row in the identity tables yet. */
    data object UnknownMember : RoomEventRefusal

    /** Malformed owner-handover shapes (§2 fail-closed): successor missing, not a
     * member, or the leaver; or a successor on a non-owner leave. */
    data object InvalidSuccessor : RoomEventRefusal

    /** Admin op targeting the owner (irrevocable — the room's repair path). */
    data object OwnerIrrevocable : RoomEventRefusal

    /** Room frontier unchainable — still syncing. */
    data object NotReady : RoomEventRefusal
}
