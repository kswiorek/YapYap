package org.yapyap.orchestrator.fold.room

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.db.RoomType
import org.yapyap.protocol.envelopes.RoomEventPayload
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * Dynamics fuzzer for the room restart loop (§4): drives the real core
 * ([roomFoldToFixpoint]) with synthetic nodes and asserts the picked fixpoint keeps the
 * planted attacker out, the owner slot filled, and honestly-removed accounts removed.
 * No crypto stub exists — the fold reads no oracle, so synthetic rows suffice.
 *
 * Mirrors [org.yapyap.orchestrator.globalevent] `AbstractFoldDynamicsFuzzTest`: `ancestors`
 * are the emitted reference sets (not transitive closures — the dynamics stress, not the
 * seal-definition precision, which [RoomFoldTest] pins with real closures).
 *
 * Worlds are ancestor-closed by construction (every emitted ancestry ⊆ order), so
 * `order` doubles as the fold set per the core contract — there is no separate set.
 * Content rows interleave the order and the scrubbed-equivalence assertion pins their
 * no-op property on every seed.
 */
private fun roomFuzzAccount(i: Int) = AccountId("room-fuzz-acct-$i")

private data class RoomFuzzWorld(
    val order: List<Uuid>,
    val nodes: Map<Uuid, RoomFoldNode>,
    val ancestors: Map<Uuid, Set<Uuid>>,
    val genesis: RoomGenesisInfo,
    /** The planted forger: honestly removed mid-world, forges backdated counter-events. */
    val attacker: AccountId,
    /** Owner after the world's honest ops (handover seeds move it). */
    val expectedOwner: AccountId,
    /** Accounts honestly removed (attacker included): never ACTIVE in the fixpoint. */
    val expectedRemoved: Set<AccountId>,
    /** Never-member account: authors only content, and is targeted only by the planted
     * third-party grant forgery — must never gain a row. */
    val ghost: AccountId,
    /** Content rows (null events) interleaved in [order]: no-ops by construction. */
    val textIds: Set<Uuid>,
)

private fun genRoomWorld(seed: Int): RoomFuzzWorld {
    val r = Random(seed)
    val nAccounts = 3 + r.nextInt(3) // 3..5; account 0 = genesis owner.
    val owner0 = roomFuzzAccount(0)

    val order = ArrayList<Uuid>()
    val nodes = HashMap<Uuid, RoomFoldNode>()
    val ancestors = HashMap<Uuid, Set<Uuid>>()
    fun emit(author: AccountId, event: RoomEventPayload?, anc: Set<Uuid>): Uuid {
        val id = Uuid.random()
        order.add(id)
        nodes[id] = RoomFoldNode(id = id, authorAccountId = author, event = event)
        ancestors[id] = anc
        return id
    }

    fun frontier(): Set<Uuid> = order.toSet()

    // Genesis: every account an initial member; account 0 owns by definition.
    val genesisId = emit(
        owner0,
        RoomEventPayload.RoomCreated(
            initialMemberIds = (0 until nAccounts).map { roomFuzzAccount(it) },
            roomName = "fuzz-room",
            roomType = RoomType.TEXT_CHANNEL,
            spaceId = null,
        ),
        emptySet(),
    )
    val genesis = RoomGenesisInfo(genesisId, owner0)

    // Honest setup: owner grants one admin (never the attacker — picked below).
    val admin = roomFuzzAccount(1 + r.nextInt(nAccounts - 1))
    emit(owner0, RoomEventPayload.AddAdmin(admin), frontier())

    // Planted attacker: a plain member. Never-admin removed members cannot run the
    // mutual attack — their forged admin-gated events are ignored at position.
    val attacker = ((2 until nAccounts).toList() + listOf(1))
        .map { roomFuzzAccount(it) }
        .first { it != admin }
    var expectedOwner = owner0
    var currentAdmin = owner0
    val expectedRemoved = HashSet<AccountId>()

    // ~1/3 of seeds: honest owner handover mid-world; subsequent honest ops come from the
    // new owner. The old owner joins the expected-removed set.
    if (nAccounts >= 4 && r.nextInt(3) == 0) {
        val successor = (0 until nAccounts).map { roomFuzzAccount(it) }
            .first { it != expectedOwner && it != attacker }
        emit(
            expectedOwner,
            RoomEventPayload.MemberRemove(expectedOwner, successor),
            frontier(),
        )
        expectedRemoved.add(expectedOwner)
        expectedOwner = successor
        currentAdmin = successor
    }

    // Honest removal of the attacker with full ancestry.
    emit(currentAdmin, RoomEventPayload.MemberRemove(attacker, null), frontier())
    expectedRemoved.add(attacker)

    // Never-member content author: proves stranger content is inert (its author is
    // never read — content rows skip before the author is consulted).
    val ghost = AccountId("room-fuzz-ghost-$seed")

    // Content rows interleaved before the forgeries: null events ride the order as
    // no-ops (needed for closures/positions, never authority). Forgeries below may
    // chain over them — the production shape, where frontier tips are content.
    val textIds = HashSet<Uuid>()
    repeat(1 + r.nextInt(3)) {
        val author = listOf(attacker, ghost, roomFuzzAccount(r.nextInt(nAccounts))).random(r)
        val anc = if (r.nextBoolean()) frontier() else order.shuffled(r).take(r.nextInt(order.size + 1)).toSet()
        textIds.add(emit(author, null, anc))
    }

    // 0..4 forgeries with small stale ancestries (backdated/concurrent).
    val stalePool = order.toList()
    repeat(r.nextInt(5)) {
        val stale = stalePool.shuffled(r).take(r.nextInt(stalePool.size + 1)).toSet()
        when (r.nextInt(100)) {
            // Counter-demotion of the remover by the attacker.
            in 0 until 25 -> emit(attacker, RoomEventPayload.RemoveAdmin(currentAdmin), stale)
            // Counter-removal of the remover by the attacker.
            in 25 until 50 -> emit(attacker, RoomEventPayload.MemberRemove(currentAdmin, null), stale)
            // Third-party grief: removal / demotion / grant by the attacker.
            in 50 until 65 -> {
                val pool = (0 until nAccounts).map { roomFuzzAccount(it) }
                    .filter { it != attacker && it != currentAdmin }
                if (pool.isNotEmpty()) emit(
                    attacker,
                    RoomEventPayload.MemberRemove(pool.random(r), null),
                    stale,
                )
            }

            in 65 until 75 -> emit(
                attacker,
                RoomEventPayload.AddAdmin(roomFuzzAccount(r.nextInt(nAccounts))),
                stale,
            )
            // Malformed handover shapes: non-owner self-leave with a successor, and
            // non-owner removal carrying a successor — both ignored whole.
            in 75 until 90 -> if (r.nextBoolean()) {
                emit(
                    attacker,
                    RoomEventPayload.MemberRemove(
                        attacker,
                        roomFuzzAccount(r.nextInt(nAccounts)),
                    ),
                    stale,
                )
            } else {
                emit(
                    attacker,
                    RoomEventPayload.MemberRemove(currentAdmin, roomFuzzAccount(r.nextInt(nAccounts))),
                    stale,
                )
            }
            // Forged removal/demotion targeting the owner — irrevocable, ignored.
            else -> if (r.nextBoolean()) {
                emit(attacker, RoomEventPayload.MemberRemove(expectedOwner, null), stale)
            } else {
                emit(attacker, RoomEventPayload.RemoveAdmin(expectedOwner), stale)
            }
        }
    }

    // Planted third-party grant: the removed attacker adds the never-member ghost.
    // In-order (a VERIFIED ∧ complete row in production terms), but sealed or ignored
    // at position — the ghost must never gain a row. Exclusion now lives in the fold
    // logic, not in a separate set, so the plant is production-reachable.
    val stalePool2 = order.toList()
    emit(
        attacker,
        RoomEventPayload.MemberAdd(ghost),
        stalePool2.shuffled(r).take(r.nextInt(stalePool2.size + 1)).toSet(),
    )

    return RoomFuzzWorld(
        order = order,
        nodes = nodes,
        ancestors = ancestors,
        genesis = genesis,
        attacker = attacker,
        expectedOwner = expectedOwner,
        expectedRemoved = expectedRemoved,
        ghost = ghost,
        textIds = textIds,
    )
}

class RoomFoldDynamicsFuzzTest {
    @Test
    fun attacker_never_member_and_owner_intact_in_picked_fixpoint() {
        val seeds = 20000
        var oscillations = 0
        var maxWalks = 0
        var handovers = 0
        repeat(seeds) { seed ->
            val world = genRoomWorld(seed)
            if (world.expectedOwner != roomFuzzAccount(0)) handovers++
            var walks = 0
            val result = roomFoldToFixpoint(
                order = world.order,
                nodes = world.nodes,
                ancestors = world.ancestors,
                genesis = world.genesis,
                onOscillation = { oscillations++ },
                onWalk = { _, _ -> walks++ },
            )
            if (walks > maxWalks) maxWalks = walks
            // Content-row invariance: the same world with the no-op rows scrubbed
            // (from the order, the nodes, and every reference set) must fold
            // identically — content perturbs neither shadow state nor seals.
            val scrubbed = roomFoldToFixpoint(
                order = world.order.filter { it !in world.textIds },
                nodes = world.nodes.filterKeys { it !in world.textIds },
                ancestors = world.ancestors
                    .filterKeys { it !in world.textIds }
                    .mapValues { (_, anc) -> anc - world.textIds },
                genesis = world.genesis,
            )
            assertEquals(result, scrubbed, "seed $seed: content rows perturbed the fold")
            val members = result.output.members
            val active = members.filterValues { it.status == RoomMemberStatus.ACTIVE }
            if (world.attacker in active) {
                fail(
                    "seed $seed: attacker ${world.attacker} ACTIVE in picked fixpoint " +
                            "seals=${result.seals}",
                )
            }
            for (removed in world.expectedRemoved) {
                if (removed in active) {
                    fail("seed $seed: honestly-removed $removed ACTIVE in picked fixpoint")
                }
            }
            assertEquals(
                world.expectedOwner,
                result.output.ownerAccountId,
                "seed $seed: owner slot moved (expected ${world.expectedOwner})",
            )
            assertEquals(
                FoldRoomMember(
                    world.expectedOwner,
                    RoomMemberRole.OWNER,
                    RoomMemberStatus.ACTIVE,
                ),
                members[world.expectedOwner],
                "seed $seed: owner row not OWNER/ACTIVE",
            )
            if (world.ghost in members) {
                fail("seed $seed: planted third-party grant gave ${world.ghost} a member row")
            }
        }
        println("room-dynamics-fuzz: $seeds seeds, oscillations=$oscillations maxWalks=$maxWalks handovers=$handovers")
        // No oscillation assertion: unlike the global tier (whose ban-cut half can cycle),
        // room replays converge monotonically once the seals are carried — the restart loop
        // and the maximal-seal ranking stay as the safety net for pathological sets, with
        // the counter reported for the projector's 8.4 oscillation log. Restarts themselves
        // are routine: the honest removal always mints a seal on walk 0, forcing walk 1.
        assertTrue(maxWalks >= 2, "expected the restart loop to run (honest removal mints a seal)")
        assertTrue(handovers > 0, "expected some honest-handover seeds")
    }
}
