package org.yapyap.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.PeerId
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RouterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultSyncPeerPolicyTest {

    private val localDevice = PeerId("policy-local-device")
    private val peerA = PeerId("policy-peer-a")
    private val peerB = PeerId("policy-peer-b")

    @Test
    fun prefersWebRtcSessionOverOnlinePeer() = runTest {
        val stack = buildSyncRoutingStack(localDevice = testDeviceIdentity(localDevice))
        stack.webRtc.openSession(peerA)
        val registry = PeerAvailabilityRegistry(
            stack.ctx.clock,
            MutableStateFlow(RouterConfig()),
            FakePeerAvailabilityStore()
        )
        registry.markReachable(peerB, stack.ctx.clock.now())
        val policy = DefaultSyncPeerPolicy(stack.ctx, registry)

        assertEquals(peerA, policy.pickNextDevice(listOf(peerA, peerB), emptySet()))
    }

    @Test
    fun prefersOnlinePeerOverOfflinePeer() = runTest {
        val stack = buildSyncRoutingStack(localDevice = testDeviceIdentity(localDevice))
        val registry = PeerAvailabilityRegistry(
            stack.ctx.clock,
            MutableStateFlow(RouterConfig()),
            FakePeerAvailabilityStore()
        )
        registry.markReachable(peerB, stack.ctx.clock.now())
        val policy = DefaultSyncPeerPolicy(stack.ctx, registry)

        assertEquals(peerB, policy.pickNextDevice(listOf(peerA, peerB), emptySet()))
    }

    @Test
    fun returnsNullWhenNoPeerOnlineOrSessioned() = runTest {
        val stack = buildSyncRoutingStack(localDevice = testDeviceIdentity(localDevice))
        val registry = PeerAvailabilityRegistry(
            stack.ctx.clock,
            MutableStateFlow(RouterConfig()),
            FakePeerAvailabilityStore()
        )
        val policy = DefaultSyncPeerPolicy(stack.ctx, registry)

        assertNull(policy.pickNextDevice(listOf(peerA, peerB), emptySet()))
    }

    @Test
    fun skipsAttemptedPeersEvenIfOnline() = runTest {
        val stack = buildSyncRoutingStack(localDevice = testDeviceIdentity(localDevice))
        val registry = PeerAvailabilityRegistry(
            stack.ctx.clock,
            MutableStateFlow(RouterConfig()),
            FakePeerAvailabilityStore()
        )
        registry.markReachable(peerB, stack.ctx.clock.now())
        val policy = DefaultSyncPeerPolicy(stack.ctx, registry)

        assertNull(policy.pickNextDevice(listOf(peerA, peerB), setOf(peerB)))
    }
}
