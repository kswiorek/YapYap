package org.yapyap.routing.outbound

import kotlinx.coroutines.test.runTest
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.store.DefaultPacketOutbox
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RetryClass
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class OutboxSchedulingJvmTest {

    private var connection: org.yapyap.persistence.DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    private fun envelope(packetId: Uuid, target: PeerId, now: Long): BinaryEnvelope = BinaryEnvelope(
        packetId = packetId,
        packetType = PacketType.MESSAGE,
        dispositionRequested = true,
        createdAt = epochSeconds(now),
        expiresAt = epochSeconds(now + 3_600),
        source = PeerId("r23localaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        target = target,
        payload = ByteArray(16) { 7 },
    )

    @Test
    fun fairDue_busyTargetHidden() = runTest {
        connection = openMemoryDatabase()
        val outbox = DefaultPacketOutbox(connection!!.database)
        val now = epochSeconds(10_000L)
        val peerA = PeerId("r23peeraaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val peerB = PeerId("r23peerbaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        repeat(40) {
            outbox.enqueue(envelope(Uuid.random(), peerA, 10_000L), nextRetryAt = now)
        }
        val bId = Uuid.random()
        outbox.enqueue(envelope(bId, peerB, 10_000L), nextRetryAt = now)

        // A capped at 2 in flight: hide A, B must still be claimed.
        val due = outbox.listDueIds(now, nonFileLimit = 100, fileLimit = 100, excludeTargets = listOf(peerA))
        assertTrue(due.any { it.packetId == bId }, "B hidden behind capped A must still be due")
        assertTrue(due.none { it.target == peerA }, "busy target hidden")
    }

    @Test
    fun messageBeforeChunks_samePeer() = runTest {
        connection = openMemoryDatabase()
        val outbox = DefaultPacketOutbox(connection!!.database)
        val now = epochSeconds(10_000L)
        val peer = PeerId("r23peeraaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        repeat(16) {
            val id = Uuid.random()
            outbox.enqueue(
                envelope(id, peer, 10_000L).copy(packetType = PacketType.FILE),
                nextRetryAt = now,
                tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, Uuid.random()),
                retryClass = RetryClass.FLAT,
            )
        }
        val msgId = Uuid.random()
        outbox.enqueue(envelope(msgId, peer, 10_000L), nextRetryAt = now)

        val due = outbox.listDueIds(now, nonFileLimit = 100, fileLimit = 100)
        assertTrue(due.isNotEmpty())
        assertEquals(msgId, due.first().packetId, "text message goes out before chunk rows")
    }

    @Test
    fun markerUsesMessageDelay_chunkUsesFlat() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val outbox = DefaultPacketOutbox(db)
        val now = epochSeconds(10_000L)
        val peer = PeerId("r23peeraaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val tor = org.yapyap.transport.tor.RecordingTorTransport()
        val identity = org.yapyap.routing.router.FakeIdentityResolverForRouter(
            localDevice = org.yapyap.protocol.identity.DeviceIdentity(
                peer,
                org.yapyap.protocol.identity.SigningPublicKey(byteArrayOf(1)),
                org.yapyap.protocol.identity.EncryptionPublicKey(byteArrayOf(2)),
            ),
            torByPeer = mutableMapOf(peer to TorEndpoint("peer.onion", 80)),
        )
        val processor = org.yapyap.routing.router.outboxProcessorUnderTest(
            tor = tor,
            outbox = outbox,
            identity = identity,
            clock = FakeClock(now),
        )
        val markerId = Uuid.random()
        outbox.enqueue(
            envelope(markerId, peer, 10_000L).copy(packetType = PacketType.FILE),
            nextRetryAt = now,
            tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, Uuid.random()),
            retryClass = RetryClass.STANDARD,
        )
        val chunkId = Uuid.random()
        outbox.enqueue(
            envelope(chunkId, peer, 10_000L).copy(packetType = PacketType.FILE),
            nextRetryAt = now,
            tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, Uuid.random()),
            retryClass = RetryClass.FLAT,
        )
        // First attempt to record baseline, then retry to observe the delay branch.
        outbox.recordAttempt(markerId, now, now)
        outbox.recordAttempt(chunkId, now, now)
        processor.processDue()
        val markerNext = outbox.getByPacketId(markerId)?.nextRetryAt
        val chunkNext = outbox.getByPacketId(chunkId)?.nextRetryAt
        // Chunk retries at the flat file delay; marker follows the message schedule (Tor 60s here).
        assertEquals(now + org.yapyap.routing.router.RouterConfig().fileChunkRetryDelay, chunkNext)
        assertTrue(markerNext != null && markerNext != chunkNext, "marker uses message schedule, not flat")
    }
}
