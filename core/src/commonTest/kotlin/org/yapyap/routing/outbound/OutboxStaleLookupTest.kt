package org.yapyap.routing.outbound

import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.RecordingTorTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Tor remembers "descriptor not found" for minutes. When the router has evidence
 * the peer is online (recent ping), that miss is stale: the outbox must ask the
 * transport to drop it and retry soon, instead of failing instantly until Tor
 * forgets on its own.
 */
class OutboxStaleLookupTest {

    private val localPeer =
        PeerId("stalelookuplocalaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remotePeer =
        PeerId("stalelookupremotebbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val nowSeconds = 10_000L
    private val now = epochSeconds(nowSeconds)

    private class Setup(
        val tor: RecordingTorTransport,
        val outbox: TrackingPacketOutbox,
        val processor: OutboxProcessor
    )

    private fun setup(peerOnline: () -> Boolean): Setup {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val identity = FakeIdentityResolverForRouter(
            localDevice = DeviceIdentityRecord(
                deviceId = localPeer,
                signing = IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
                encryption = IdentityPublicKeyRecord(IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
            ),
            peersByAccount = mapOf(AccountId("stale-lookup-account") to listOf(remotePeer)),
            torByPeer = mutableMapOf(remotePeer to TorEndpoint("peer.onion", 80)),
        )
        val processor = outboxProcessorUnderTest(
            tor = tor,
            identity = identity,
            outbox = outbox,
            clock = FakeClock(now),
            isPeerOnline = { it == remotePeer && peerOnline() },
        )
        return Setup(tor, outbox, processor)
    }

    private suspend fun Setup.enqueueDue() {
        outbox.enqueue(
            envelope = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.MESSAGE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = epochSeconds(nowSeconds + 3_600),
                source = localPeer,
                target = remotePeer,
                payload = byteArrayOf(1, 2, 3),
            ),
            nextRetryAt = now,
        )
    }

    private fun Setup.failNextWithNotFound() {
        tor.nextSendError = TransportException.TorException.OnionNotFound("peer.onion")
    }

    @Test
    fun notFound_forOnlinePeer_refreshesAndRetriesSoon() = runBlocking {
        val s = setup(peerOnline = { true })
        s.enqueueDue()
        s.failNextWithNotFound()

        s.processor.processDue()

        assertEquals(1, s.tor.refreshOnionLookupsCalls)
        assertEquals(listOf(remotePeer to now + 10.seconds), s.outbox.setDueForTargetCalls)
    }

    @Test
    fun notFound_forOfflinePeer_waits_thenRefreshesWhenItComesOnline() = runBlocking {
        var online = false
        val s = setup(peerOnline = { online })
        s.enqueueDue()
        s.failNextWithNotFound()

        s.processor.processDue()
        assertEquals(0, s.tor.refreshOnionLookupsCalls, "offline peer: 'not found' is expected, nothing is stale")
        assertEquals(emptyList(), s.outbox.setDueForTargetCalls)

        online = true
        s.processor.onPeerOnline(remotePeer)
        assertEquals(1, s.tor.refreshOnionLookupsCalls)
        assertEquals(listOf(remotePeer to now), s.outbox.setDueForTargetCalls)
    }

    @Test
    fun throttledRefresh_keepsTheNormalBackoff() = runBlocking {
        val s = setup(peerOnline = { true })
        s.tor.refreshOnionLookupsResult = false
        s.enqueueDue()
        s.failNextWithNotFound()

        s.processor.processDue()

        assertEquals(1, s.tor.refreshOnionLookupsCalls)
        assertEquals(emptyList(), s.outbox.setDueForTargetCalls)
    }

    @Test
    fun successfulSend_forgetsTheMiss() = runBlocking {
        val s = setup(peerOnline = { false })
        s.enqueueDue()
        s.failNextWithNotFound()
        s.processor.processDue()

        s.enqueueDue()
        s.processor.processDue()
        s.processor.onPeerOnline(remotePeer)

        assertEquals(0, s.tor.refreshOnionLookupsCalls)
    }

    @Test
    fun otherFailures_neverRefresh() = runBlocking {
        val s = setup(peerOnline = { true })
        s.enqueueDue()
        s.tor.nextSendError = TransportException.TorException.SocksError("relay overloaded")

        s.processor.processDue()
        s.processor.onPeerOnline(remotePeer)

        assertEquals(0, s.tor.refreshOnionLookupsCalls)
    }
}
