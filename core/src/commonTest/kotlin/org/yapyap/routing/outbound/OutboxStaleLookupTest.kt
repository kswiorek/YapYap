package org.yapyap.routing.outbound


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.presence.FakePeerAvailabilityStore
import org.yapyap.routing.presence.PeerAvailabilityRegistry
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.PassthroughFakeEnvelopeProtectionService
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.testTorPeers
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
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
        val processor: OutboxProcessor,
        val registry: PeerAvailabilityRegistry,
        val clock: FakeClock,
    )

    private suspend fun setup(peerOnline: () -> Boolean): Setup {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val clock = FakeClock(now)
        val identity = FakeIdentityResolverForRouter(
            localDevice = DeviceIdentity(
                deviceId = localPeer,
                signingKey = SigningPublicKey(byteArrayOf(1)),
                encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
            ),
            peersByAccount = mapOf(AccountId("stale-lookup-account") to listOf(remotePeer)),
            torByPeer = mutableMapOf(remotePeer to TorEndpoint("peer.onion", 80)),
        )
        val routerConfig = RouterConfig()
        val configFlow = MutableStateFlow(routerConfig)
        val webRtc = RecordingWebRtcTransport()
        val ctx = RoutingContext(
            localIdentity = identity,
            localDeviceId = localPeer,
            devices = identity,
            torTransport = tor,
            webRtcTransport = webRtc,
            torPeers = testTorPeers(identity, tor),
            transportLimits = MutableStateFlow(testTransportLimits()),
            fileLimits = MutableStateFlow(FileLimits(220 * 1024, 220 * 1024, maxSealBytes = 64 * 1024)),
            envelopeProtections = PassthroughFakeEnvelopeProtectionService(),
            clock = clock,
            routerConfig = configFlow,
        )
        val registry = PeerAvailabilityRegistry(clock, configFlow, FakePeerAvailabilityStore())
        if (peerOnline()) registry.markReachable(remotePeer, clock.now())
        val processor = OutboxProcessor(
            ctx = ctx,
            dispatcher = EnvelopeDispatcher(ctx),
            transportPolicy = LinkPreferencePolicy(configFlow),
            packetOutbox = outbox,
            maxIdlePoll = MutableStateFlow(routerConfig.retryLoopMaxIdlePoll),
            peerAvailabilityRegistry = registry,
            linkKeeper = LinkKeeper(
                name = "stale-lookup-test",
                sessions = ctx.torPeers,
                targets = { emptyList() },
                ready = flowOf(false),
                config = MutableStateFlow(routerConfig.torLinkKeeperConfig),
                clock = clock,
            ),
            relaySelection = NoRelaySelection,
        )
        return Setup(tor, outbox, processor, registry, clock)
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
                routingHeader = byteArrayOf(1, 2, 3),
                content = ByteArray(0),
                signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
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
        s.registry.markReachable(remotePeer, s.clock.now())
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
