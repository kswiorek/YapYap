package org.yapyap.routing.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.protection.sampleTextPayload
import org.yapyap.protocol.*
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.defaultRouterUnderTest
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.tor.TorLinkId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** How the router uses links: adopting inbound Tor streams, replying on them, waiting for them. */
class RouterLinksTest {

    private val localPeer = PeerId("linkslocalaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remotePeer = PeerId("linksremotebbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val remoteTor = TorEndpoint("remote.onion", 80)
    private val account = AccountId("links-account")
    private val now = 10_000L

    private fun router(tor: RecordingTorTransport, outbox: TrackingPacketOutbox = TrackingPacketOutbox()) =
        defaultRouterUnderTest(
            tor = tor,
            identity = FakeIdentityResolverForRouter(
                localDevice = DeviceIdentityRecord(
                    deviceId = localPeer,
                    signing = IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
                    encryption = IdentityPublicKeyRecord(IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
                ),
                peersByAccount = mapOf(account to listOf(remotePeer)),
                torByPeer = mutableMapOf(remotePeer to remoteTor),
            ),
            outbox = outbox,
            clock = FakeClock(epochSeconds(now)),
            routerConfig = RouterConfig(retryLoopMaxIdlePoll = 1.seconds),
        )

    private fun inboundSystem(
        payload: SystemPayload,
        link: TorLinkId?,
        expiresAt: Long = now + 1_000,
        dispositionRequested: Boolean = false,
    ): TorIncomingEnvelope {
        val system = SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
            source = remotePeer,
            target = localPeer,
            createdAt = epochSeconds(now),
            nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 1 },
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = payload.encode(),
        )
        val envelope = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.SYSTEM,
            dispositionRequested = dispositionRequested,
            createdAt = epochSeconds(minOf(now, expiresAt - 60)),
            expiresAt = epochSeconds(expiresAt),
            source = remotePeer,
            target = localPeer,
            payload = system.encode(),
        )
        return TorIncomingEnvelope(remoteTor, envelope, link)
    }

    private fun ping() = SystemPayload.Ping(
        pingId = Uuid.random(),
        isReply = false,
        selfReportedAvailability = 0.5,
        roomFrontiers = emptyList(),
    )

    @Test
    fun authenticatedPingOnAStream_adoptsItAsThePeersLink() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val router = router(tor)
        router.start()
        try {
            tor.tryEmitIncoming(inboundSystem(ping(), link = TorLinkId(7)))
            withTimeout(5.seconds) { while (tor.adoptCalls.isEmpty()) yield() }
            assertEquals(listOf(TorLinkId(7) to remoteTor), tor.adoptCalls)
        } finally {
            router.stop()
        }
    }

    @Test
    fun otherAuthenticatedTraffic_doesNotAdoptTheStream() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val router = router(tor)
        router.start()
        try {
            tor.tryEmitIncoming(
                inboundSystem(
                    SystemPayload.PacketAck(Uuid.random(), PacketType.MESSAGE),
                    link = TorLinkId(7)
                )
            )
            delay(300.milliseconds)
            assertTrue(tor.adoptCalls.isEmpty(), "only a ping proves who dialed the stream")
        } finally {
            router.stop()
        }
    }

    @Test
    fun dispositions_goBackOnTheStreamThePacketArrivedOn() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val router = router(tor)
        router.start()
        try {
            // Expired on arrival: answered with a NACK, which must ride the arrival stream.
            tor.tryEmitIncoming(
                inboundSystem(ping(), link = TorLinkId(9), expiresAt = now - 1, dispositionRequested = true),
            )
            withTimeout(5.seconds) { tor.awaitMessageSendCount(1) }
            val nackIndex = tor.sends.indexOfFirst { (_, envelope) ->
                envelope.packetType == PacketType.SYSTEM &&
                        SystemEnvelope.decode(envelope.payload).decodePayload() is SystemPayload.PacketNack
            }
            assertEquals(TorLinkId(9), tor.sendVias[nackIndex])
        } finally {
            router.stop()
        }
    }

    @Test
    fun messageToAnUnlinkedPeer_waitsForTheLink_thenGoesAtOnce() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        tor.unlinkedEndpoints += remoteTor
        val router = router(tor)
        router.start()
        try {
            router.sendMessage(account, sampleTextPayload())
            withTimeout(5.seconds) { while (remoteTor !in tor.openSessionCalls) yield() }
            delay(300.milliseconds)
            assertEquals(0, tor.sendsExcludingHeartbeat().size, "no link yet: nothing dialed by the outbox")

            tor.setSessionState(remoteTor, SessionPhase.CONNECTED)
            withTimeout(5.seconds) { tor.awaitMessageSendCount(1) }
            assertEquals(remoteTor, tor.sendsExcludingHeartbeat().single().first)
        } finally {
            router.stop()
        }
    }
}
