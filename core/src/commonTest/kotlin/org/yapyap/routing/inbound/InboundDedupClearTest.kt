package org.yapyap.routing.inbound

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.link.PeerLinks
import org.yapyap.routing.outbound.EnvelopeDispatcher
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.presence.FakePeerAvailabilityStore
import org.yapyap.routing.presence.PeerAvailabilityRegistry
import org.yapyap.routing.presence.PingProvider
import org.yapyap.routing.router.FakeFrontierSnapshotProvider
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.PassthroughFakeEnvelopeProtectionService
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * R2.3 inbound hardening: a handler that throws on the first delivery clears its dedup
 * entry, so the retry is handled fresh — not replayed as a duplicate — and ACKed.
 */
class InboundDedupClearTest {

    private val localDevice = PeerId("dedup-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remoteDevice = PeerId("dedup-remote-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val remoteAccount = AccountId("dedup-remote-account")
    private val now = epochSeconds(10_000L)

    @Test
    fun throwingHandler_clearsDedup_retryIsHandledAndAcked() = runBlocking {
        val clock = FakeClock(now)
        val local = DeviceIdentity(
            localDevice,
            SigningPublicKey(byteArrayOf(1)),
            EncryptionPublicKey(byteArrayOf(2)),
        )
        val identity = FakeIdentityResolverForRouter(
            localDevice = local,
            peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
            torByPeer = mutableMapOf(remoteDevice to TorEndpoint("remote.onion", 80)),
        )
        val tor = RecordingTorTransport()
        val webRtc = RecordingWebRtcTransport()
        val config = MutableStateFlow(RouterConfig())
        val ctx = RoutingContext(
            localIdentity = identity,
            localDeviceId = localDevice,
            devices = identity,
            torTransport = tor,
            webRtcTransport = webRtc,
            peerLinks = PeerLinks(identity, webRtc, tor),
            transportLimits = MutableStateFlow(testTransportLimits()),
            fileLimits = MutableStateFlow(FileLimits(220 * 1024, 220 * 1024)),
            envelopeProtections = PassthroughFakeEnvelopeProtectionService(),
            clock = clock,
            routerConfig = config,
        )
        val dedup = InMemoryPacketDeduplicator()
        val dispatcher = EnvelopeDispatcher(ctx, identity)
        val policy = LinkPreferencePolicy(config)
        val outboxProcessor = outboxProcessorUnderTest(
            tor = tor,
            webRtc = webRtc,
            identity = identity,
            clock = clock,
            routerConfig = config.value,
        )
        val systemSender = SystemSender(ctx, policy, dispatcher, dedup)
        var calls = 0
        val handler = InboundEnvelopeHandler {
            calls++
            if (calls == 1) throw IllegalStateException("boom")
            InboundHandleResult.Success()
        }
        val availability = PeerAvailabilityRegistry(clock, config, FakePeerAvailabilityStore())
        val processor = InboundEnvelopeProcessor(
            ctx = ctx,
            systemSender = systemSender,
            routes = identity,
            packetDeduplicator = dedup,
            handlers = mapOf(PacketType.MESSAGE to handler),
            outboxProcessor = outboxProcessor,
            peerAvailabilityRegistry = availability,
            pingProvider = PingProvider(
                ctx,
                config,
                FakeFrontierSnapshotProvider(),
                systemSender,
                availability,
            ),
        )
        val packetId = Uuid.random()
        val inbound = BinaryEnvelope(
            packetId = packetId,
            packetType = PacketType.MESSAGE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 1.hours,
            source = remoteDevice,
            target = localDevice,
            payload = byteArrayOf(1, 2, 3),
        )

        assertFailsWith<IllegalStateException> { processor.handle(inbound, RouterTransport.TOR) }
        processor.handle(inbound, RouterTransport.TOR)
        assertEquals(2, calls, "the retry is handled fresh, not dropped as a duplicate")

        val acks = tor.sendsExcludingHeartbeat().filter { it.second.packetType == PacketType.SYSTEM }
        assertEquals(1, acks.size, "only the successful handling is ACKed")
        val ack = assertIs<SystemPayload.PacketAck>(SystemEnvelope.decode(acks.single().second.payload).decodePayload())
        assertEquals(packetId, ack.packetId)
        assertEquals(PacketType.MESSAGE, ack.packetType)
    }
}
