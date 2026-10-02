package org.yapyap.orchestrator.runtime.room

import org.yapyap.persistence.db.RoomMemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The render-time policy over the `room_members` projection
 * (docs/room events.md §3) is a pure function with exactly one owner. The GUI
 * and `MessagingService.roomPreview` apply it, never re-derive it.
 */
class MessageDisplayPolicyTest {

    @Test
    fun activeMember_rendersNormally() {
        assertEquals(MessageDisplayPolicy.NORMAL, messageDisplayPolicy(RoomMemberStatus.ACTIVE))
    }

    @Test
    fun removedMember_rendersWithBadge() {
        assertEquals(MessageDisplayPolicy.BADGE_REMOVED, messageDisplayPolicy(RoomMemberStatus.REMOVED))
    }

    @Test
    fun neverMember_isHidden_neverNormal() {
        // Negative test (d3): no row (never a member, deferred identity, or a
        // room that has not folded yet) must never render as normal content.
        val policy = messageDisplayPolicy(null)

        assertEquals(MessageDisplayPolicy.HIDDEN_NON_MEMBER, policy)
    }
}
