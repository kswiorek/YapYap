package org.yapyap.orchestrator.runtime.room

import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomType
import kotlin.time.Instant

/** GUI-facing room-list row (GLOBAL control room excluded). */
data class RoomSummary(
    val roomId: RoomId,
    /** Null → GUI synthesizes a title from the other member(s). */
    val name: String?,
    val type: RoomType,
    val memberIds: List<AccountId>,
    /** First ~80 chars of the latest message, mirroring IncomingMessageEvent. */
    val lastMessagePreview: String?,
    val lastActivityAt: Instant?,
)

/** Chat-header detail: members with roles. */
data class RoomDetails(
    val roomId: RoomId,
    val name: String?,
    val members: List<RoomMemberView>,
)

data class RoomMemberView(
    val accountId: AccountId,
    val role: RoomMemberRole,
)

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
