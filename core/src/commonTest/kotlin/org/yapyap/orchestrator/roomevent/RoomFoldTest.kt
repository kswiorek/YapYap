package org.yapyap.orchestrator.roomevent

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.db.RoomType
import org.yapyap.protocol.envelopes.RoomEventPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Deterministic core tests (§9): hand-built worlds with true transitive closures pin the
 * exact seal semantics the dynamics fuzzer only stresses — the handover matrix, the
 * sealed-add collateral, mutual destruction, re-add, and positional reopening.
 */
private val TOwner = AccountId("t-owner")
private val TAdmin = AccountId("t-admin")
private val TAdminB = AccountId("t-admin-b")
private val TMember = AccountId("t-member")
private val TOutsider = AccountId("t-outsider")

private fun created(members: List<AccountId> = emptyList()) = RoomEventPayload.RoomCreated(
    initialMemberIds = members,
    roomName = "t-room",
    roomType = RoomType.TEXT_CHANNEL,
    spaceId = null,
)

/** Hand-built world: emission order is the canonical order; closures are transitive. */
private class WorldBuilder {
    val order = ArrayList<Uuid>()
    val nodes = HashMap<Uuid, RoomFoldNode>()
    private val prev = HashMap<Uuid, List<Uuid>>()

    fun emit(
        author: AccountId,
        event: RoomEventPayload?,
        prevIds: List<Uuid> = order.toList(),
    ): Uuid {
        val id = Uuid.random()
        order.add(id)
        nodes[id] = RoomFoldNode(id = id, authorAccountId = author, event = event)
        prev[id] = prevIds
        return id
    }

    /** Test-only accessor for ancestry-shape sanity checks. */
    fun prevOf(id: Uuid): List<Uuid> = prev.getValue(id)

    private fun closures(): Map<Uuid, Set<Uuid>> {
        val memo = HashMap<Uuid, Set<Uuid>>()
        fun anc(id: Uuid): Set<Uuid> = memo.getOrPut(id) {
            val seen = HashSet<Uuid>()
            val stack = ArrayDeque(prev.getValue(id))
            while (stack.isNotEmpty()) {
                val parent = stack.removeLast()
                if (seen.add(parent)) stack.addAll(prev[parent].orEmpty())
            }
            seen
        }
        return order.associateWith { anc(it) }
    }

    fun fold(
        genesis: RoomGenesisInfo,
        foldSet: Set<Uuid> = order.toSet(),
        onWalk: (walkIndex: Int, result: RoomReplayResult) -> Unit = { _, _ -> },
    ): RoomReplayResult = roomFoldToFixpoint(order, nodes, closures(), foldSet, genesis, {}, onWalk)
}

private fun WorldBuilder.genesis(
    author: AccountId = TOwner,
    members: List<AccountId> = emptyList(),
): RoomGenesisInfo {
    val id = emit(author, created(members), emptyList())
    return RoomGenesisInfo(id, author)
}

private fun memberOf(result: RoomReplayResult, account: AccountId): FoldRoomMember? =
    result.output.members[account]

class RoomFoldTest {

    @Test
    fun genesis_seeds_owner_and_members() {
        val w = WorldBuilder()
        val genesis = w.genesis(members = listOf(TAdmin, TMember))
        val result = w.fold(genesis)

        assertEquals(
            FoldRoomMember(TOwner, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            memberOf(result, TOwner),
        )
        assertEquals(
            FoldRoomMember(TAdmin, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(result, TAdmin),
        )
        assertEquals(TOwner, result.output.ownerAccountId)
    }

    @Test
    fun non_genesis_room_created_is_ignored() {
        val w = WorldBuilder()
        val genesis = w.genesis()
        w.emit(TOwner, created(listOf(TOutsider)))
        val result = w.fold(genesis)

        assertNull(memberOf(result, TOutsider))
        assertEquals(TOwner, result.output.ownerAccountId)
    }

    @Test
    fun non_admin_member_add_is_ignored() {
        val w = WorldBuilder()
        val genesis = w.genesis(members = listOf(TMember))
        w.emit(TMember, RoomEventPayload.MemberAdd(TOutsider))
        val result = w.fold(genesis)

        // Stored VERIFIED, no shadow effect, no projection row (asserted at 8.4).
        assertNull(memberOf(result, TOutsider))
    }

    @Test
    fun admin_member_add_lands() {
        val w = WorldBuilder()
        val genesis = w.genesis(members = listOf(TAdmin))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        w.emit(TAdmin, RoomEventPayload.MemberAdd(TOutsider))
        val result = w.fold(genesis)

        assertEquals(
            FoldRoomMember(TOutsider, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(result, TOutsider),
        )
    }

    @Test
    fun sealed_backdated_add_is_accepted_collateral() {
        // §4 collateral: an innocent account added by a backdating demoted admin — the add
        // is sealed out, the account never becomes a member. The removal's ancestry
        // deliberately excludes the add (outside-ancestry + at-or-before = sealed).
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TAdminB))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdminB))
        val add = w.emit(TAdmin, RoomEventPayload.MemberAdd(TOutsider), prevIds = listOf(g.nodeId))
        val removal = w.emit(TAdminB, RoomEventPayload.MemberRemove(TAdmin, null), prevIds = listOf(g.nodeId))
        // Sanity: the test only means what it says if the add is really outside the
        // removal's ancestry — both reference the genesis alone. (`add`/`removal` unused
        // beyond this: the fold output below is the assertion.)
        assertEquals(listOf(g.nodeId), w.prevOf(add))
        assertEquals(listOf(g.nodeId), w.prevOf(removal))

        val result = w.fold(g)

        assertNull(memberOf(result, TOutsider), "sealed add must grant no membership")
        assertEquals(
            FoldRoomMember(TAdmin, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED),
            memberOf(result, TAdmin),
        )
    }

    @Test
    fun vouched_backdated_add_survives() {
        // Same shape, but the add sits inside the removal's ancestry (vouched) — it lands
        // permanently even though it sorts before the removal.
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TAdminB))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdminB))
        w.emit(TAdmin, RoomEventPayload.MemberAdd(TOutsider), prevIds = listOf(g.nodeId))
        val result = w.fold(g)
        // The removal references the full frontier, vouching the add.
        w.emit(TAdminB, RoomEventPayload.MemberRemove(TAdmin, null))

        val vouched = w.fold(g)
        assertEquals(
            FoldRoomMember(TOutsider, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(vouched, TOutsider),
        )
        assertEquals(
            FoldRoomMember(TAdmin, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED),
            memberOf(vouched, TAdmin),
        )
        // The removal affects only its target: every other row is identical.
        assertEquals(result.output.members - TAdmin, vouched.output.members - TAdmin)
    }

    @Test
    fun backdated_counter_removal_mutually_destructs_and_repairs_via_owner() {
        // Honest admin R removes ex-admin F; F's backdated counter-demotion is concurrent
        // with the removal → mutual destruction: neither seals the other, both land.
        // F stays removed, R loses admin too (repairable via the owner); the owner is intact.
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TMember))
        val grant = w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        // Forged counter-demotion, positionally before the honest removal and concurrent
        // with it (neither in the other's ancestry).
        w.emit(TMember, RoomEventPayload.RemoveAdmin(TAdmin), prevIds = listOf(g.nodeId))
        w.emit(
            TAdmin,
            RoomEventPayload.MemberRemove(TMember, null),
            prevIds = listOf(g.nodeId, grant),
        )
        var walks = 0
        val result = w.fold(g, onWalk = { _, _ -> walks++ })

        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED),
            memberOf(result, TMember),
            "forger stays removed",
        )
        assertEquals(
            FoldRoomMember(TAdmin, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(result, TAdmin),
            "honest admin loses admin too (repairable via the owner)",
        )
        assertEquals(
            FoldRoomMember(TOwner, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            memberOf(result, TOwner),
        )
        assertEquals(3, walks, "backdated seal caught on the restart loop")
    }

    @Test
    fun forgery_targeting_owner_is_ignored_without_collateral() {
        // An ex-admin's backdated demotion of the owner passes the author gate at position
        // (still admin there) but hits the irrevocable-owner rule — ignored, no collateral:
        // the honest admin keeps admin.
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TMember))
        val grantA = w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TMember))
        w.emit(TMember, RoomEventPayload.RemoveAdmin(TOwner), prevIds = listOf(g.nodeId, grantA))
        w.emit(
            TAdmin,
            RoomEventPayload.MemberRemove(TMember, null),
            prevIds = listOf(g.nodeId, grantA),
        )
        val result = w.fold(g)

        assertEquals(
            FoldRoomMember(TOwner, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            memberOf(result, TOwner),
        )
        assertEquals(
            FoldRoomMember(TAdmin, RoomMemberRole.ADMIN, RoomMemberStatus.ACTIVE),
            memberOf(result, TAdmin),
            "owner-targeted forgery causes no collateral",
        )
        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED),
            memberOf(result, TMember),
        )
    }

    @Test
    fun valid_handover_transfers_owner_atomically() {
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TMember))
        w.emit(TOwner, RoomEventPayload.MemberRemove(TOwner, TAdmin))
        val result = w.fold(g)

        assertEquals(TAdmin, result.output.ownerAccountId)
        assertEquals(
            FoldRoomMember(TAdmin, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            memberOf(result, TAdmin),
        )
        assertEquals(
            FoldRoomMember(TOwner, RoomMemberRole.MEMBER, RoomMemberStatus.REMOVED),
            memberOf(result, TOwner),
            "leaver removed; never OWNER on a removed row",
        )
    }

    @Test
    fun handover_seals_leaver_backdated_acts() {
        // The owner's backdated grant, concurrent with the handover, is sealed out.
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TMember))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TMember), prevIds = listOf(g.nodeId))
        w.emit(TOwner, RoomEventPayload.MemberRemove(TOwner, TAdmin), prevIds = listOf(g.nodeId))
        val result = w.fold(g)

        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(result, TMember),
            "backdated grant sealed by the handover self-leave",
        )
        assertEquals(TAdmin, result.output.ownerAccountId)
    }

    @Test
    fun handover_without_successor_is_ignored() {
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin))
        w.emit(TOwner, RoomEventPayload.MemberRemove(TOwner, null))
        val result = w.fold(g)

        assertEquals(
            FoldRoomMember(TOwner, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            memberOf(result, TOwner),
            "owner stays without a valid successor",
        )
    }

    @Test
    fun handover_to_non_member_or_self_is_ignored() {
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin))
        w.emit(TOwner, RoomEventPayload.MemberRemove(TOwner, TOutsider))
        val noMember = w.fold(g)
        assertEquals(
            FoldRoomMember(TOwner, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            memberOf(noMember, TOwner),
        )

        val w2 = WorldBuilder()
        val g2 = w2.genesis(members = listOf(TAdmin))
        w2.emit(TOwner, RoomEventPayload.MemberRemove(TOwner, TOwner))
        val selfSucc = w2.fold(g2)
        assertEquals(
            FoldRoomMember(TOwner, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            memberOf(selfSucc, TOwner),
        )
    }

    @Test
    fun non_owner_remove_carrying_successor_is_ignored_whole() {
        // The successor field is owner-only: smuggling it into any other MemberRemove
        // voids the whole event — the target stays ACTIVE.
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TMember))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        w.emit(TAdmin, RoomEventPayload.MemberRemove(TMember, TAdmin))
        val result = w.fold(g)

        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(result, TMember),
        )

        val w2 = WorldBuilder()
        val g2 = w2.genesis(members = listOf(TMember))
        w2.emit(TMember, RoomEventPayload.MemberRemove(TMember, TOutsider))
        val selfLeave = w2.fold(g2)
        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(selfLeave, TMember),
            "non-owner self-leave carrying a successor is ignored whole",
        )
    }

    @Test
    fun competing_handovers_first_in_canonical_order_wins() {
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TMember))
        w.emit(TOwner, RoomEventPayload.MemberRemove(TOwner, TAdmin))
        // Second handover sorts later; its author is no longer a member at position.
        w.emit(TOwner, RoomEventPayload.MemberRemove(TOwner, TMember))
        val result = w.fold(g)

        assertEquals(TAdmin, result.output.ownerAccountId)
        assertEquals(
            FoldRoomMember(TAdmin, RoomMemberRole.OWNER, RoomMemberStatus.ACTIVE),
            memberOf(result, TAdmin),
        )
        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(result, TMember),
        )
    }

    @Test
    fun readded_member_starts_plain() {
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TMember))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TMember))
        w.emit(TAdmin, RoomEventPayload.MemberRemove(TMember, null))
        w.emit(TAdmin, RoomEventPayload.MemberAdd(TMember))
        val result = w.fold(g)

        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(result, TMember),
            "admin never resurrects on MemberAdd",
        )
    }

    @Test
    fun demote_regrant_reopens_post_grant_acts() {
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TAdmin, TMember))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        w.emit(TOwner, RoomEventPayload.RemoveAdmin(TAdmin))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TAdmin))
        w.emit(TAdmin, RoomEventPayload.MemberAdd(TOutsider))
        val result = w.fold(g)

        assertEquals(
            FoldRoomMember(TAdmin, RoomMemberRole.ADMIN, RoomMemberStatus.ACTIVE),
            memberOf(result, TAdmin),
        )
        assertEquals(
            FoldRoomMember(TOutsider, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(result, TOutsider),
        )
    }

    @Test
    fun concurrent_grant_revoke_siblings_resolve_positionally() {
        // Concurrent siblings (neither in the other's ancestry): canonical position decides.
        val w = WorldBuilder()
        val g = w.genesis(members = listOf(TMember))
        w.emit(TOwner, RoomEventPayload.AddAdmin(TMember), prevIds = listOf(g.nodeId))
        w.emit(TOwner, RoomEventPayload.RemoveAdmin(TMember), prevIds = listOf(g.nodeId))
        val revokeWins = w.fold(g)
        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE),
            memberOf(revokeWins, TMember),
        )

        val w2 = WorldBuilder()
        val g2 = w2.genesis(members = listOf(TMember))
        w2.emit(TOwner, RoomEventPayload.RemoveAdmin(TMember), prevIds = listOf(g2.nodeId))
        w2.emit(TOwner, RoomEventPayload.AddAdmin(TMember), prevIds = listOf(g2.nodeId))
        val grantWins = w2.fold(g2)
        assertEquals(
            FoldRoomMember(TMember, RoomMemberRole.ADMIN, RoomMemberStatus.ACTIVE),
            memberOf(grantWins, TMember),
        )
    }
}
