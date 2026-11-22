package org.yapyap.routing.store

import org.yapyap.persistence.DatabaseConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Contract tests for the routing file stores on real in-memory SQLite: the exact semantics
 * the push engine and the relay door depend on — atomic deposit claims, chunk-index
 * recovery, atomic relay admission, and outbox tag queries. The engine tests run against
 * these same implementations.
 */
class FileStoreSemanticsJvmTest {

    private val connections = mutableListOf<DatabaseConnection>()

    private fun db() = openMemoryDatabase().also { connections += it }.database

    @AfterTest
    fun closeDbs() {
        connections.forEach { runCatching { it.driver.close() } }
        connections.clear()
    }

    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val device = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val relay = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val now = epochSeconds(10_000L)

    private fun pushRow(
        transferId: TransferId = TransferId(Uuid.random()),
        status: FilePushStatus = FilePushStatus.IN_FLIGHT,
    ) = FilePush(
        transferId = transferId,
        fileId = fileId,
        targetDevice = device,
        kind = FilePushKind.DIRECT,
        totalBytes = 10_000L,
        key = TransferKey(ByteArray(32) { it.toByte() }),
        chunkSizeBytes = 32 * 1024,
        startIndex = 0,
        nextChunkIndex = 1,
        contiguousAcked = -1,
        markerPacketId = null,
        status = status,
        createdAt = now,
        expiresAt = now + 2.days,
    )

    private fun relayRow(
        transferId: TransferId = TransferId(Uuid.random()),
        source: PeerId = device,
        target: PeerId = relay,
        reserved: Long = 3000L,
        lastChunkAt: Instant = now,
    ) = FileRelayTransfer(
        transferId = transferId,
        sourceDevice = source,
        targetDevice = target,
        chunkSizeBytes = 1000,
        totalBytes = 3000L,
        startIndex = 0,
        offerPayload = byteArrayOf(9),
        status = FileRelayStatus.RESERVED,
        reservedBytes = reserved,
        markerPacketId = null,
        createdAt = now,
        lastChunkAt = lastChunkAt,
        expiresAt = now + 2.days,
    )

    @Test
    fun claimDeposited_exactlyOnceAcrossConcurrentClaimants() = runTest {
        val pushes = DefaultFilePushStore(db())
        val id = TransferId(Uuid.random())
        pushes.insert(pushRow(id))

        // Ten concurrent claims: exactly one wins, even though all read-then-write.
        val results = (1..10).map {
            async { pushes.claimDeposited(id, now) }
        }.awaitAll()
        assertEquals(1, results.count { it }, "exactly one claimant wins the round")
        assertFalse(pushes.claimDeposited(id, now), "a second round is never claimable")
    }

    @Test
    fun pushChunks_recordAndRecoverAndCleanup() = runTest {
        val pushes = DefaultFilePushStore(db())
        val id = TransferId(Uuid.random())
        pushes.insert(pushRow(id))
        val p0 = Uuid.random()
        val p1 = Uuid.random()
        pushes.recordChunk(id, 0, p0)
        pushes.recordChunk(id, 1, p1)

        assertEquals(mapOf(0 to p0, 1 to p1), pushes.chunksByTransfer(id))
        assertEquals(id to 1, pushes.chunkByPacket(p1))

        pushes.deleteChunkByPacket(p0)
        assertEquals(mapOf(1 to p1), pushes.chunksByTransfer(id))

        // Deleting the row cascades to its chunk mappings (no orphan rows).
        pushes.delete(id)
        assertTrue(pushes.chunksByTransfer(id).isEmpty())
    }

    @Test
    fun allByFileAndTerminalHygiene() = runTest {
        val pushes = DefaultFilePushStore(db())
        val live = TransferId(Uuid.random())
        val dead = TransferId(Uuid.random())
        pushes.insert(pushRow(live, FilePushStatus.IN_FLIGHT))
        pushes.insert(pushRow(dead, FilePushStatus.ENDED).copy(expiresAt = now - 1.days))
        pushes.insert(pushRow(status = FilePushStatus.COMPLETED))

        val all = pushes.allByFile(fileId)
        assertEquals(3, all.size, "live and terminal rows alike are visible by file")
        val terminal = pushes.terminalBefore(now)
        assertEquals(listOf(dead), terminal.map { it.transferId })
    }

    @Test
    fun relayAdmit_evictsOldestFirst() = runTest {
        val relay = DefaultFileRelayStore(db())
        val first = TransferId(Uuid.random())
        relay.admit(relayRow(first, reserved = 3000L), 3000L, 4000L).let {
            assertTrue(it.admitted)
        }
        val second = TransferId(Uuid.random())
        val result = relay.admit(relayRow(second, reserved = 3000L), 3000L, 4000L)
        assertTrue(result.admitted)
        assertEquals(listOf(first), result.evicted)
        assertNull(relay.find(first), "the evicted mid-deposit transfer is gone")
        assertNotNull(relay.find(second))
    }

    @Test
    fun relayAdmit_evictsAsManyAsItTakes() = runTest {
        val relay = DefaultFileRelayStore(db())
        // Thirty small deposits fill the budget; one large OFFER needs twenty of them gone,
        // more than any fixed candidate page would offer.
        val small = (0 until 30).map { i ->
            TransferId(Uuid.random()).also { id ->
                relay.admit(relayRow(id, reserved = 300L, lastChunkAt = now + i.seconds), 300L, 9_000L)
            }
        }
        val large = TransferId(Uuid.random())
        val result = relay.admit(relayRow(large, reserved = 6_000L), 6_000L, 9_000L)
        assertTrue(result.admitted, "evicting twenty of thirty makes room")
        assertEquals(small.take(20), result.evicted, "oldest fed first, exactly as many as needed")
        assertNotNull(relay.find(large))
        assertEquals(10, relay.live().size - 1, "the ten newest deposits stay")
    }

    @Test
    fun relayStoreChunk_forGoneRow_writesNothing() = runTest {
        val relay = DefaultFileRelayStore(db())
        val id = TransferId(Uuid.random())
        relay.admit(relayRow(id), 3000L, 100L * 1024 * 1024)
        relay.delete(id)
        // An eviction or drop raced the door's read: no foreign-key failure, no write.
        assertNull(relay.storeChunk(id, 0, byteArrayOf(1), now))
        assertEquals(0L, relay.chunkCount(id))
    }

    @Test
    fun relayAdmit_refusedOversizeWritesNothing() = runTest {
        val relay = DefaultFileRelayStore(db())
        val first = TransferId(Uuid.random())
        relay.admit(relayRow(first, reserved = 3000L), 3000L, 4000L)
        // 5000 bytes fit nowhere, even after evicting everything on offer.
        val huge = TransferId(Uuid.random())
        val result = relay.admit(relayRow(huge, reserved = 5000L), 5000L, 4000L)
        assertFalse(result.admitted)
        assertTrue(result.evicted.isEmpty(), "a refused admission evicts nothing")
        assertNotNull(relay.find(first), "the held deposit is untouched")
        assertNull(relay.find(huge))
    }

    @Test
    fun relayStoreChunk_promotesWholeAtomically() = runTest {
        val relay = DefaultFileRelayStore(db())
        val id = TransferId(Uuid.random())
        relay.admit(relayRow(id), 3000L, 100L * 1024 * 1024)

        assertEquals(FileRelayStatus.RESERVED, relay.storeChunk(id, 0, byteArrayOf(1), now))
        assertEquals(FileRelayStatus.RESERVED, relay.storeChunk(id, 1, byteArrayOf(2), now))
        // Duplicate chunk stores are no-ops (PK dedup), not double counts.
        assertEquals(FileRelayStatus.RESERVED, relay.storeChunk(id, 0, byteArrayOf(1), now))
        assertEquals(FileRelayStatus.WHOLE, relay.storeChunk(id, 2, byteArrayOf(3), now))
        assertTrue(relay.promoteIfWhole(id))
    }

    @Test
    fun relaySetStatus_doesNotTouchLastChunkAt() = runTest {
        val relay = DefaultFileRelayStore(db())
        val id = TransferId(Uuid.random())
        relay.admit(relayRow(id), 3000L, 100L * 1024 * 1024)
        val before = relay.find(id)!!.lastChunkAt
        relay.setStatus(id, FileRelayStatus.WHOLE)
        // A status change is not activity: the idle TTL still measures chunk arrivals.
        assertEquals(before, relay.find(id)!!.lastChunkAt)
    }

    @Test
    fun packetOutbox_packetIdsByTagAndDeleteByTag() = runTest {
        val outbox = DefaultPacketOutbox(db())
        val tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, Uuid.random())
        val clock = FakeClock(now)
        fun envelope(): BinaryEnvelope = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = device,
            target = device,
            payload = byteArrayOf(1, 2, 3),
        )

        val e1 = envelope()
        val e2 = envelope()
        outbox.enqueue(e1, now, tag = tag, retryClass = RetryClass.FLAT)
        outbox.enqueue(e2, now, tag = tag, retryClass = RetryClass.FLAT)
        outbox.enqueue(envelope(), now)

        assertEquals(setOf(e1.packetId, e2.packetId), outbox.packetIdsByTag(tag).toSet())
        outbox.deleteByTag(tag)
        assertTrue(outbox.packetIdsByTag(tag).isEmpty())
        assertTrue(outbox.getByPacketId(e1.packetId) == null)
    }

    @Test
    fun incomingTransfer_pruneExpired() = runTest {
        val incoming = DefaultFileIncomingTransferStore(db())
        val live = TransferId(Uuid.random())
        val dead = TransferId(Uuid.random())
        suspend fun insert(id: TransferId, expiresAt: Instant) =
            incoming.insert(
                FileIncomingTransfer(
                    transferId = id,
                    fileId = fileId,
                    authorDevice = device,
                    key = TransferKey(ByteArray(32)),
                    chunkSizeBytes = 1024,
                    totalBytes = 1000L,
                    startIndex = 0,
                    createdAt = now,
                    expiresAt = expiresAt,
                ),
            )
        insert(live, now + 2.days)
        insert(dead, now - 1.hours)
        assertEquals(listOf(dead), incoming.pruneExpired(now).map { it.transferId })
        assertNotNull(incoming.find(live))
    }
}
