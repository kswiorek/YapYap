package org.yapyap.orchestrator.runtime.room

import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.db.RoomType

/**
 * GUI-facing room-list row (GLOBAL control room excluded). Projection data only:
 * message-derived content (previews, last activity) comes from
 * `MessagingService.roomPreview` — the room list composes ordering from there.
 */
data class RoomSummary(
    val roomId: RoomId,
    /** Null → GUI synthesizes a title from the other member(s). */
    val name: String?,
    val type: RoomType,
    val memberIds: List<AccountId>,
)

/** Chat-header detail: members with roles and fold statuses. */
data class RoomDetails(
    val roomId: RoomId,
    val name: String?,
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
