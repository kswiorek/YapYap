package org.yapyap.routing.outbound

import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.time.FixedEpochProvider
import org.yapyap.transport.tor.ConcurrencyTrackingTorTransport
import org.yapyap.transport.tor.RecordingTorTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class OutboxProcessorTest {

    private val localPeer =
        PeerId("outboxproclocalpeeraaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remotePeer =
        PeerId("outboxprocremotepeerbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    @Test
    fun processDue_dispatchesDueEntriesInParallel() = runBlocking {
        val tor = ConcurrencyTrackingTorTransport(sendDelayMillis = 200)
        val outbox = TrackingPacketOutbox()
        val now = 10_000L
        val packetId1 = Uuid.random()
        val packetId2 = Uuid.random()

        outbox.enqueue(
            envelope = outboxMessageEnvelope(packetId1, source = localPeer, target = remotePeer, now = now),
            nextRetryAt = now,
        )
        outbox.enqueue(
            envelope = outboxMessageEnvelope(packetId2, source = localPeer, target = remotePeer, now = now),
            nextRetryAt = now,
        )

        val processor = outboxProcessorUnderTest(
            tor = tor,
            outbox = outbox,
            identity = identityFor(remoteTor = TorEndpoint("peer.onion", 443)),
            time = FixedEpochProvider(now),
        )

        processor.processDue()
        tor.awaitSendCount(2)

        assertEquals(2, tor.sends.size)
        assertEquals(
            setOf(packetId1, packetId2),
            tor.sends.map { it.second.packetId }.toSet(),
        )
        assertTrue(
            tor.maxConcurrentSends >= 2,
            "expected concurrent outbox dispatches but max was ${tor.maxConcurrentSends}",
        )
    }

    @Test
    fun processDue_dispatchFailure_stillRecordsAttempt() = runBlocking {
        val tor = RecordingTorTransport()
        tor.failNextSend = true
        val outbox = TrackingPacketOutbox()
        val packetId = Uuid.random()
        val now = 10_000L

        outbox.enqueue(
            envelope = outboxMessageEnvelope(packetId, source = localPeer, target = remotePeer, now = now),
            nextRetryAt = now,
        )

        val processor = outboxProcessorUnderTest(
            tor = tor,
            outbox = outbox,
            identity = identityFor(),
            time = FixedEpochProvider(now),
        )

        processor.processDue()

        assertEquals(1, outbox.recordAttemptCalls.size)
        assertEquals(packetId, outbox.recordAttemptCalls.single().first)
        assertEquals(1L, outbox.getAttempts(packetId))
    }

    private fun identityFor(remoteTor: TorEndpoint = TorEndpoint("peer.onion", 443)) =
        FakeIdentityResolverForRouter(
            localDevice = localDevice(),
            torByPeer = mutableMapOf(remotePeer to remoteTor),
        )

    private fun localDevice(): DeviceIdentityRecord =
        DeviceIdentityRecord(
            deviceId = localPeer,
            signing = IdentityPublicKeyRecord("ls", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("le", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )

    private fun outboxMessageEnvelope(
        packetId: Uuid,
        source: PeerId,
        target: PeerId,
        now: Long,
    ): BinaryEnvelope =
        BinaryEnvelope(
            packetId = packetId,
            packetType = PacketType.MESSAGE,
            dispositionRequested = true,
            createdAtEpochSeconds = now,
            expiresAtEpochSeconds = now + 3_600,
            source = source,
            target = target,
            payload = byteArrayOf(0x01, 0x02, 0x03),
        )
}
