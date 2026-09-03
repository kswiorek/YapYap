package org.yapyap.routing.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RouterConfig
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.sync.buildSyncRoutingStack
import org.yapyap.sync.testDeviceIdentity
import org.yapyap.time.FixedEpochProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelaySelectionPolicyTest {

    private fun peer(suffix: String) = PeerId("relay-$suffix")

    @Test
    fun selectRelaysByScores_takesSinglePeerWhenBudgetMet() {
        val selected = selectRelaysByScores(
            scored = listOf(peer("a") to 0.9, peer("b") to 0.8),
            targetProbability = 0.85,
            maxRelays = 3,
            minScore = 0.0,
        )
        assertEquals(listOf(peer("a")), selected)
    }

    @Test
    fun selectRelaysByScores_takesMoreUntilProbabilityMet() {
        val selected = selectRelaysByScores(
            scored = listOf(peer("a") to 0.6, peer("b") to 0.6),
            targetProbability = 0.95,
            maxRelays = 3,
            minScore = 0.0,
        )
        // 1 - (0.4 * 0.4) = 0.84 < 0.95: both needed; no more candidates.
        assertEquals(listOf(peer("a"), peer("b")), selected)
    }

    @Test
    fun selectRelaysByScores_respectsMaxRelays() {
        val selected = selectRelaysByScores(
            scored = listOf(peer("a") to 0.5, peer("b") to 0.5, peer("c") to 0.5),
            targetProbability = 0.99,
            maxRelays = 2,
            minScore = 0.0,
        )
        assertEquals(listOf(peer("a"), peer("b")), selected)
    }

    @Test
    fun selectRelaysByScores_skipsPeersBelowMinScore() {
        val selected = selectRelaysByScores(
            scored = listOf(peer("a") to 0.6, peer("b") to 0.05),
            targetProbability = 0.9,
            maxRelays = 3,
            minScore = 0.1,
        )
        assertEquals(listOf(peer("a")), selected)
    }

    @Test
    fun selectRelaysByScores_zeroScorePeerContributesNothing() {
        val selected = selectRelaysByScores(
            scored = listOf(peer("a") to 0.4, peer("b") to 0.4, peer("optedOut") to 0.0),
            targetProbability = 0.9,
            maxRelays = 3,
            minScore = 0.0,
        )
        assertEquals(listOf(peer("a"), peer("b")), selected)
    }

    @Test
    fun selectRelaysByScores_zeroTargetProbabilityReturnsEmpty() {
        val selected = selectRelaysByScores(
            scored = listOf(peer("a") to 0.9),
            targetProbability = 0.0,
            maxRelays = 3,
            minScore = 0.0,
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun selectRelays_selectsBestPeersExcludingSelfAndTarget() = runTest {
        val self = peer("self")
        val target = peer("target")
        val sibling = peer("sibling")
        val other = peer("other")

        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(self),
            peersByAccount = mapOf(
                AccountId("acct-t") to listOf(target, sibling),
                AccountId("acct-o") to listOf(other),
            ),
            time = FixedEpochProvider(1_000L),
        )

        val registry = PeerAvailabilityRegistry(
            timeProvider = FixedEpochProvider(1_000L),
            routerConfig = MutableStateFlow(RouterConfig()),
            store = FakePeerAvailabilityStore(),
        )
        seedEffectiveScores(registry, mapOf(sibling to 0.8, other to 0.6))
        // Target and self never asked for a score; self/target are excluded from candidates anyway.

        val policy = DefaultRelaySelectionPolicy(stack.ctx, registry, MutableStateFlow(RouterConfig()))
        val relays = policy.selectRelays(target)

        assertTrue(relays.isNotEmpty())
        assertFalse(relays.contains(self), "self must not be used as a relay")
        assertFalse(relays.contains(target), "target device must not be used as a relay to itself")
        // The target's sibling device is a legitimate relay and should be preferred.
        assertEquals(sibling, relays.first())
    }

    @Test
    fun selectRelays_skipsPeersWithoutAnyScore() = runTest {
        val self = peer("self")
        val target = peer("target")
        val known = peer("known")
        val unknown = peer("unknown")

        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(self),
            peersByAccount = mapOf(
                AccountId("acct-t") to listOf(target),
                AccountId("acct-k") to listOf(known, unknown),
            ),
            time = FixedEpochProvider(1_000L),
        )

        val registry = PeerAvailabilityRegistry(
            timeProvider = FixedEpochProvider(1_000L),
            routerConfig = MutableStateFlow(RouterConfig()),
            store = FakePeerAvailabilityStore(),
        )
        seedEffectiveScores(registry, mapOf(known to 0.6)) // "unknown" has no score

        val policy = DefaultRelaySelectionPolicy(stack.ctx, registry, MutableStateFlow(RouterConfig()))
        val relays = policy.selectRelays(target)

        assertTrue(relays.contains(known))
        assertFalse(relays.contains(unknown))
    }

    /** Boosts measured scores with one sweep, then sets reported so effective == [scores]. */
    private suspend fun seedEffectiveScores(registry: PeerAvailabilityRegistry, scores: Map<PeerId, Double>) {
        val t0 = 1_000L
        scores.keys.forEach { registry.markReachable(it, t0) }
        registry.sweep()
        scores.forEach { (peer, effective) ->
            val measured = registry.reliabilityScore(peer)!!
            registry.noteSelfReported(peer, effective * effective / measured)
        }
    }
}