package org.yapyap.sync

import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultSyncPeerPolicyTest {

    private val localDevice = PeerId("policy-local-device")
    private val peerA = PeerId("policy-peer-a")
    private val peerB = PeerId("policy-peer-b")

    private fun stack(torLinked: List<PeerId> = emptyList()): SyncRoutingStack {
        val stack = buildSyncRoutingStack(localDevice = testDeviceIdentity(localDevice))
        torLinked.forEach { stack.ctx.peerLinks.bindTorEndpoint(it, TorEndpoint("${it.id}.onion", 80)) }
        return stack
    }

    @Test
    fun prefersWebRtcLinkOverTorLink() = runTest {
        val stack = stack(torLinked = listOf(peerB))
        stack.webRtc.openSession(peerA)
        // Opening is not usability: the policy prefers a usable channel.
        stack.webRtc.simulateEnvelopeChannelOpen(peerA)

        assertEquals(peerA, DefaultSyncPeerPolicy(stack.ctx).pickNextDevice(listOf(peerB, peerA), emptySet()))
    }

    @Test
    fun unlinkedDevicesAreNeverAsked() = runTest {
        // A device that merely looks online would leave the request waiting for a link.
        val stack = stack()

        assertNull(DefaultSyncPeerPolicy(stack.ctx).pickNextDevice(listOf(peerA, peerB), emptySet()))
    }

    @Test
    fun skipsDeclinedDevices() = runTest {
        val stack = stack(torLinked = listOf(peerA, peerB))

        assertNull(DefaultSyncPeerPolicy(stack.ctx).pickNextDevice(listOf(peerA, peerB), setOf(peerA, peerB)))
    }

    @Test
    fun unansweredDevicesGoLast() = runTest {
        val stack = stack(torLinked = listOf(peerA, peerB))
        val policy = DefaultSyncPeerPolicy(stack.ctx)

        assertEquals(peerB, policy.pickNextDevice(listOf(peerA, peerB), emptySet(), unanswered = setOf(peerA)))
        assertEquals(peerA, policy.pickNextDevice(listOf(peerA), emptySet(), unanswered = setOf(peerA)))
    }
}
