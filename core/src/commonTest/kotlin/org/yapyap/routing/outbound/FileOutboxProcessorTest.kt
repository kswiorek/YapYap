package org.yapyap.routing.outbound

import org.yapyap.routing.router.TrackingPacketOutbox
import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.outboxProcessorUnderTest
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RetryClass
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Step 13 outbox behavior (design §6.2): non-FILE rows are claimed before FILE rows,
 * FILE rows retry at a flat delay outside the message budgets, and a due pass loads
 * blobs only for rows it claims.
 */
class FileOutboxProcessorTest {

    private val localPeer = PeerId("fileoutboxlocalaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remotePeer = PeerId("fileoutboxremotepeerbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    private fun identityFor(remoteTor: TorEndpoint = TorEndpoint("peer.onion", 443)) =
        FakeIdentityResolverForRouter(
            localDevice = DeviceIdentity(
                deviceId = localPeer,
                signingKey = SigningPublicKey(byteArrayOf(1)),
                encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
            ),
            torByPeer = mutableMapOf(remotePeer to remoteTor),
        )

    private fun envelope(
        packetId: Uuid,
        type: PacketType,
        tag: OutboxTag?,
        now: Long,
        payload: ByteArray = ByteArray(16) { 7 },
    ): BinaryEnvelope = BinaryEnvelope(
        packetId = packetId,
        packetType = type,
        dispositionRequested = true,
        createdAt = epochSeconds(now),
        expiresAt = epochSeconds(now + 3_600),
        source = localPeer,
        target = remotePeer,
        payload = payload,
    )

    private fun fileTag(transfer: Uuid = Uuid.random()) = OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer)

    @Test
    fun processDue_messagesGoOutBeforeFileChunks() = runTest {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val now = 10_000L
        val messageId = Uuid.random()
        // The chunks are queued first: the message still goes out ahead of them.
        repeat(3) {
            val chunkId = Uuid.random()
            outbox.enqueue(
                envelope = envelope(chunkId, PacketType.FILE, fileTag(), now),
                nextRetryAt = epochSeconds(now),
                tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, chunkId),
                retryClass = RetryClass.FLAT,
            )
        }
        outbox.enqueue(
            envelope = envelope(messageId, PacketType.MESSAGE, OutboxTag(OutboxTag.Kind.MESSAGE, Uuid.random()), now),
            nextRetryAt = epochSeconds(now),
            tag = OutboxTag(OutboxTag.Kind.MESSAGE, messageId),
        )
        val processor = outboxProcessorUnderTest(
            tor = tor,
            outbox = outbox,
            identity = identityFor(),
            clock = FakeClock(epochSeconds(now)),
        )

        processor.processDue()

        // Per-target cap is 2: the message plus the first chunk go out.
        assertEquals(2, tor.sends.size)
        assertEquals(PacketType.MESSAGE, tor.sends[0].second.packetType)
        assertEquals(PacketType.FILE, tor.sends[1].second.packetType)
    }

    @Test
    fun fileChunk_retriesAtFlatDelay_outsideMessageBudgets() = runTest {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val now = 10_000L
        val chunkId = Uuid.random()
        outbox.enqueue(
            envelope = envelope(chunkId, PacketType.FILE, fileTag(), now),
            nextRetryAt = epochSeconds(now),
            tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, chunkId),
            retryClass = RetryClass.FLAT,
        )
        // Burn past the message fast-retry budget: FILE rows must not fall back to standby.
        repeat(5) { outbox.recordAttempt(chunkId, epochSeconds(now), epochSeconds(now)) }
        val processor = outboxProcessorUnderTest(
            tor = tor,
            outbox = outbox,
            identity = identityFor(),
            clock = FakeClock(epochSeconds(now)),
            routerConfig = RouterConfig(),
        )

        processor.processDue()

        assertEquals(
            epochSeconds(now) + RouterConfig().fileChunkRetryDelay,
            outbox.getNextRetryAt(chunkId),
        )
    }

    @Test
    fun duePass_loadsNoBlobsForRowsItDoesNotClaim() = runTest {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val now = 10_000L
        repeat(5) {
            val id = Uuid.random()
            outbox.enqueue(
                envelope = envelope(id, PacketType.FILE, fileTag(), now),
                nextRetryAt = epochSeconds(now),
                tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, id),
                retryClass = RetryClass.FLAT,
            )
        }
        val processor = outboxProcessorUnderTest(
            tor = tor,
            outbox = outbox,
            identity = identityFor(),
            clock = FakeClock(epochSeconds(now)),
        )

        processor.processDue()

        // Per-target cap 2: exactly the two dispatched rows load their envelopes.
        assertEquals(2, tor.sends.size)
        assertEquals(2, outbox.getByPacketIdCalls.size)
    }

    @Test
    fun fileRows_retryIndefinitelyAtFlatDelay() = runTest {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val now = 10_000L
        val chunkId = Uuid.random()
        outbox.enqueue(
            envelope = envelope(chunkId, PacketType.FILE, fileTag(), now),
            nextRetryAt = epochSeconds(now),
            tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, chunkId),
            retryClass = RetryClass.FLAT,
        )
        val processor = outboxProcessorUnderTest(
            tor = tor,
            outbox = outbox,
            identity = identityFor(),
            clock = FakeClock(epochSeconds(now)),
            routerConfig = RouterConfig(fileChunkRetryDelay = 11.seconds),
        )

        processor.processDue()

        assertEquals(epochSeconds(now) + 11.seconds, outbox.getNextRetryAt(chunkId))
        // No hour-long standby for chunks, however many attempts they burned.
        assertTrue(outbox.getNextRetryAt(chunkId)!! < epochSeconds(now) + 60.seconds)
    }
}
