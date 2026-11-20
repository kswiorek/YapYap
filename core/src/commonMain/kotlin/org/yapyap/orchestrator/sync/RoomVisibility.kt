package org.yapyap.orchestrator.sync

import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.RoomRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomMemberStatus
import kotlin.uuid.Uuid

/**
 * What one account may see of one room (docs/room events.md §5): the whole room, its
 * member-era history up to and including its removal node, or nothing.
 */
internal sealed interface VisibilityScope {
    /** ACTIVE member: every message in the room. */
    data object All : VisibilityScope

    /** REMOVED member: [removalNode] and its ancestors, nothing after. */
    data class UpTo(val removalNode: Uuid) : VisibilityScope

    /** Never a member, or removed without a removal node: nothing. */
    data object None : VisibilityScope
}

/**
 * Who may see a message: the one rule sync serving and the file serve gate share.
 * [scope] decides per room, so a walk over many messages (sync serving) asks once;
 * [isVisible] answers for one message (the file serve gate).
 */
internal interface RoomVisibility {
    /** What [account] may see of [roomId]. */
    suspend fun scope(roomId: RoomId, account: AccountId): VisibilityScope

    /** True when [account] may see [messageId]; false for unknown messages and non-members. */
    suspend fun isVisible(messageId: Uuid, account: AccountId): Boolean
}

internal class DefaultRoomVisibility(
    private val messages: MessageRepository,
    private val rooms: RoomRepository,
) : RoomVisibility {
    override suspend fun scope(roomId: RoomId, account: AccountId): VisibilityScope {
        val member = rooms.memberRowOf(roomId, account) ?: return VisibilityScope.None
        return when (member.status) {
            RoomMemberStatus.ACTIVE -> VisibilityScope.All
            RoomMemberStatus.REMOVED -> member.removalNodeId?.let { VisibilityScope.UpTo(it) } ?: VisibilityScope.None
        }
    }

    override suspend fun isVisible(messageId: Uuid, account: AccountId): Boolean {
        val stored = messages.findById(messageId) ?: return false
        return when (val scope = scope(stored.payload.roomId, account)) {
            VisibilityScope.All -> true
            is VisibilityScope.UpTo -> messages.isAncestorOrSelf(messageId, scope.removalNode)
            VisibilityScope.None -> false
        }
    }
}
