package org.yapyap.routing.outbound

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.router.*
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A recovering device asks a device it has no row for yet. That device's ACK can't be
 * signature-checked, so before this exception it was dropped, the request was re-sent
 * every minute and the user saw no progress. The exception is narrow: only an ACK/NACK
 * for a BOOTSTRAP packet in our outbox addressed to exactly the replying device, and
 * nothing is ever sent back.
 */
class UnknownSourceDispositionTest {

    private val local = PeerId("dispositionlocalaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val sponsor = PeerId("dispositionsponsorbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val stranger = PeerId("dispositionstrangercccccccccccccccccccccccccccccccccccccccccccccccc")
    private val sponsorTor = TorEndpoint("sponsor.onion", 80)
    private val now = 10_000L

    private class Setup(
        val router: DefaultRouter,
        val tor: RecordingTorTransport,
        val outbox: TrackingPacketOutbox,
        val delivered: MutableList<Uuid>,
    )

    /** A router that knows no peers, with one queued packet of [type] to [target] (not due yet). */
    private suspend fun setup(type: PacketType = PacketType.BOOTSTRAP, target: PeerId = sponsor): Pair<Setup, Uuid> {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val outbox = TrackingPacketOutbox()
        val identity = FakeIdentityResolverForRouter(
            localDevice = DeviceIdentityRecord(
                deviceId = local,
                signing = IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
                encryption = IdentityPublicKeyRecord(IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
            ),
            peersByAccount = emptyMap(),
            torByPeer = mutableMapOf(),
        )
        val router = defaultRouterUnderTest(
            tor = tor,
            identity = identity,
            outbox = outbox,
            clock = FakeClock(epochSeconds(now)),
            routerConfig = RouterConfig(retryLoopMaxIdlePoll = 1.seconds),
        )
        val packetId = Uuid.random()
        outbox.enqueue(
            envelope = BinaryEnvelope(
                packetId = packetId,
                packetType = type,
                dispositionRequested = true,
                createdAt = epochSeconds(now),
                expiresAt = epochSeconds(now + 3_600),
                source = local,
                target = target,
                payload = byteArrayOf(1),
            ),
            nextRetryAt = epochSeconds(now + 3_000),
            targetEndpoint = sponsorTor,
        )
        return Setup(router, tor, outbox, mutableListOf()) to packetId
    }

    private fun disposition(
        payload: SystemPayload,
        from: PeerId = sponsor,
        headerFrom: PeerId = from,
    ): TorIncomingEnvelope {
        val system = SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
            source = headerFrom,
            target = local,
            createdAt = epochSeconds(now),
            nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 3 },
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(64) { 9 }, // unverifiable by design: we don't know the sender's key
            payload = payload.encode(),
        )
        return TorIncomingEnvelope(
            sponsorTor,
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.SYSTEM,
                dispositionRequested = true,
                createdAt = epochSeconds(now),
                expiresAt = epochSeconds(now + 1_000),
                source = from,
                target = local,
                payload = system.encode(),
            ),
        )
    }

    private suspend fun Setup.deliver(envelope: TorIncomingEnvelope) {
        router.start()
        val collector = kotlinx.coroutines.GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            router.bootstrapDeliveries.collect { delivered += it }
        }
        tor.tryEmitIncoming(envelope)
        delay(400.milliseconds)
        collector.cancel()
        router.stop()
    }

    @Test
    fun ackForOurBootstrap_fromItsUnknownTarget_isAccepted() = runBlocking {
        val (s, packetId) = setup()
        s.deliver(disposition(SystemPayload.PacketAck(packetId, PacketType.BOOTSTRAP)))

        assertEquals(listOf(packetId), s.outbox.markDeliveredCalls, "the request stops being re-sent")
        assertEquals(listOf(packetId), s.delivered, "the newcomer side learns it was delivered")
        assertTrue(s.tor.sendsExcludingHeartbeat().isEmpty(), "nothing is ever sent back to an unknown device")
    }

    @Test
    fun terminalNack_removesTheRequest_butIsNoDelivery() = runBlocking {
        val (s, packetId) = setup()
        s.deliver(
            disposition(
                SystemPayload.PacketNack(packetId, PacketType.BOOTSTRAP, PacketNackReason.DECLINED, reasonText = null),
            ),
        )
        assertEquals(listOf(packetId), s.outbox.markDeliveredCalls)
        assertEquals(emptyList(), s.delivered)
    }

    @Test
    fun ackFromAnyoneElse_isDropped() = runBlocking {
        val (s, packetId) = setup()
        s.deliver(disposition(SystemPayload.PacketAck(packetId, PacketType.BOOTSTRAP), from = stranger))
        assertEquals(emptyList(), s.outbox.markDeliveredCalls)
        assertEquals(emptyList(), s.delivered)
    }

    @Test
    fun ackWhoseHeaderDisagreesWithTheEnvelope_isDropped() = runBlocking {
        val (s, packetId) = setup()
        s.deliver(disposition(SystemPayload.PacketAck(packetId, PacketType.BOOTSTRAP), headerFrom = stranger))
        assertEquals(emptyList(), s.outbox.markDeliveredCalls)
    }

    @Test
    fun ackForANonBootstrapPacket_isDropped() = runBlocking {
        val (s, packetId) = setup(type = PacketType.MESSAGE)
        s.deliver(disposition(SystemPayload.PacketAck(packetId, PacketType.MESSAGE)))
        assertEquals(emptyList(), s.outbox.markDeliveredCalls)
    }

    @Test
    fun ackForAnUnknownPacketId_isDropped() = runBlocking {
        val (s, _) = setup()
        s.deliver(disposition(SystemPayload.PacketAck(Uuid.random(), PacketType.BOOTSTRAP)))
        assertEquals(emptyList(), s.outbox.markDeliveredCalls)
        assertEquals(emptyList(), s.delivered)
    }
}
