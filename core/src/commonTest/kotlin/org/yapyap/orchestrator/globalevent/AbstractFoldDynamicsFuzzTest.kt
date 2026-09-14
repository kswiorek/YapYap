package org.yapyap.orchestrator.globalevent

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.GlobalEventPayload
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * Dynamics fuzzer for the restart loop (§3/§6.2): drives the real core
 * ([foldToFixpoint]) with synthetic nodes and asserts the picked fixpoint always
 * has the planted attacker banned. Counters report the observed dynamics shape.
 */
private sealed interface FuzzKind {
    data class Ban(val target: Int) : FuzzKind
    data class Demote(val targetAcct: Int) : FuzzKind
    data class Grant(val targetAcct: Int) : FuzzKind
}

private data class FuzzWorld(
    val order: List<Uuid>,
    val nodes: Map<Uuid, FoldNode>,
    val ancestors: Map<Uuid, Set<Uuid>>,
    val genesis: GenesisInfo,
    val genesisKey: GenesisKey,
    val attacker: PeerId,
)

private object StubCrypto : FoldCrypto {
    override suspend fun verifyAuthor(key: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = true
    override suspend fun verifyBinding(key: ByteArray, binding: ByteArray, sig: ByteArray): Boolean = true
}

private fun fuzzAccount(i: Int) = AccountId("fuzz-acct-$i")
private fun fuzzDevice(i: Int) = PeerId("fuzz-dev-$i")
private fun fuzzKey(i: Int, tag: Byte) = ByteArray(32) { j -> (i * 37 + j * 11 + tag).toByte() }.also {
    require(it.isNotEmpty())
}

private fun genWorld(seed: Int): FuzzWorld {
    val r = Random(seed)
    val nDevices = 3 + r.nextInt(4) // 3..6
    val nAccounts = 2 + r.nextInt(2) // 2..3
    // Device 0 = genesis device on account 0 = genesis (admin, irrevocable).
    val deviceAccount = (0 until nDevices).associateWith { if (it == 0) 0 else r.nextInt(nAccounts) }.toMutableMap()
    for (a in 1 until nAccounts) deviceAccount[r.nextInt(nDevices)] = a
    deviceAccount[0] = 0

    val admins = mutableSetOf(0)
    if (r.nextBoolean()) admins.add(1 + r.nextInt(nAccounts - 1))

    val order = ArrayList<Uuid>()
    val nodes = HashMap<Uuid, FoldNode>()
    val ancestors = HashMap<Uuid, Set<Uuid>>()
    fun emit(author: Int, event: GlobalEventPayload?, anc: Set<Uuid>): Uuid {
        val id = Uuid.random()
        order.add(id)
        nodes[id] = FoldNode(
            id = id,
            authorDeviceId = fuzzDevice(author),
            senderAccountId = fuzzAccount(deviceAccount.getValue(author)),
            prevIds = anc.toList(),
            authorSignature = byteArrayOf(0x01),
            signingBytes = byteArrayOf(0x02),
            event = event,
            derivationOk = true,
        )
        ancestors[id] = anc
        return id
    }

    fun frontier(): Set<Uuid> = order.toSet()

    // Genesis + registration: every account added, every device key-added
    // (branch 3 shape; the stub oracle accepts all bindings).
    val genesisId = emit(
        0,
        GlobalEventPayload.AddAccount(fuzzAccount(0), fuzzKey(0, 1), "genesis"),
        emptySet(),
    )
    emit(
        0,
        GlobalEventPayload.AddDevice(
            accountId = fuzzAccount(0),
            deviceId = fuzzDevice(0),
            signingPublicKey = fuzzKey(0, 2),
            encryptionPublicKey = fuzzKey(0, 3),
            torEndpoint = TorEndpoint("fuzz-0.onion"),
            deviceType = DeviceType.DESKTOP,
            keySignature = fuzzKey(0, 4),
        ),
        frontier(),
    )
    for (a in 1 until nAccounts) {
        emit(0, GlobalEventPayload.AddAccount(fuzzAccount(a), fuzzKey(100 + a, 1), "a$a"), frontier())
    }
    for (d in 1 until nDevices) {
        val a = deviceAccount.getValue(d)
        emit(
            0,
            GlobalEventPayload.AddDevice(
                accountId = fuzzAccount(a),
                deviceId = fuzzDevice(d),
                signingPublicKey = fuzzKey(d, 2),
                encryptionPublicKey = fuzzKey(d, 3),
                torEndpoint = TorEndpoint("fuzz-$d.onion"),
                deviceType = DeviceType.DESKTOP,
                keySignature = fuzzKey(a, 4),
            ),
            frontier(),
        )
    }
    for (a in admins - 0) {
        emit(0, GlobalEventPayload.GrantAdmin(fuzzAccount(a)), frontier())
    }

    val attacker = 1 + r.nextInt(nDevices - 1)
    // Honest banner: an admin device, or a sibling of the attacker (own-account
    // path) — anything else would make the "honest" ban itself invalid.
    // Device 0 (genesis account, always admin) guarantees a non-empty pool.
    val banner = (
            (0 until nDevices).filter { (deviceAccount[it] ?: -1) in admins } +
                    (0 until nDevices).filter { it != attacker && deviceAccount[it] == deviceAccount[attacker] }
            ).filter { it != attacker }.toSet().random(r)

    // Honest ban with full ancestry.
    emit(banner, GlobalEventPayload.RemoveDevice(fuzzDevice(attacker)), frontier())

    // 0..4 forgeries with small stale ancestries (backdated/concurrent).
    val stalePool = order.toList()
    repeat(r.nextInt(5)) {
        val stale = stalePool.shuffled(r).take(r.nextInt(stalePool.size + 1)).toSet()
        when (r.nextInt(100)) {
            // Counter-ban of the banner by the attacker.
            in 0 until 40 -> emit(attacker, GlobalEventPayload.RemoveDevice(fuzzDevice(banner)), stale)
            // Third-party ban by attacker.
            in 40 until 60 -> {
                val pool = (0 until nDevices).filter { it != attacker && it != banner }
                if (pool.isNotEmpty()) emit(
                    attacker,
                    GlobalEventPayload.RemoveDevice(fuzzDevice(pool.random(r))),
                    stale
                )
            }
            // Counter-demotion of the banner's account (or ban if genesis).
            in 60 until 75 -> {
                val bannerAcct = deviceAccount.getValue(banner)
                if (bannerAcct != 0) emit(attacker, GlobalEventPayload.RemoveAdmin(fuzzAccount(bannerAcct)), stale)
                else emit(attacker, GlobalEventPayload.RemoveDevice(fuzzDevice(banner)), stale)
            }
            // Backdated grant by attacker.
            in 75 until 90 -> emit(attacker, GlobalEventPayload.GrantAdmin(fuzzAccount(r.nextInt(nAccounts))), stale)
            // Sibling-revenge: attacker bans a same-account device.
            else -> {
                val sibs = (0 until nDevices).filter {
                    it != attacker && deviceAccount[it] == deviceAccount[attacker]
                }
                if (sibs.isNotEmpty()) emit(
                    attacker,
                    GlobalEventPayload.RemoveDevice(fuzzDevice(sibs.random(r))),
                    stale
                )
                else emit(attacker, GlobalEventPayload.RemoveDevice(fuzzDevice(banner)), stale)
            }
        }
    }
    return FuzzWorld(
        order = order,
        nodes = nodes,
        ancestors = ancestors,
        genesis = GenesisInfo(genesisId, fuzzAccount(0)),
        genesisKey = GenesisKey(fuzzDevice(0), fuzzKey(0, 2)),
        attacker = fuzzDevice(attacker),
    )
}

class AbstractFoldDynamicsFuzzTest {
    @Test
    fun attacker_always_banned_in_maximal_fixpoint() = runTest {
        val seeds = 20000
        var oscillations = 0
        var maxWalks = 0
        repeat(seeds) { seed ->
            val world = genWorld(seed)
            var walks = 0
            val result = foldToFixpoint(
                order = world.order,
                nodes = world.nodes,
                ancestors = world.ancestors,
                foldSet = world.order.toSet(),
                genesis = world.genesis,
                genesisKey = world.genesisKey,
                crypto = StubCrypto,
                onOscillation = { oscillations++ },
                onWalk = { _, _ -> walks++ },
            )
            if (walks > maxWalks) maxWalks = walks
            if (world.attacker !in result.tombstonedDevices) {
                fail(
                    "seed $seed: attacker ${world.attacker} NOT banned in picked fixpoint " +
                            "bans=${result.bans} demotions=${result.demotions}",
                )
            }
        }
        println("dynamics-fuzz: $seeds seeds, oscillations=$oscillations maxWalks=$maxWalks")
        assertTrue(oscillations > 0, "expected some oscillations (counter-ban seeds)")
    }
}
