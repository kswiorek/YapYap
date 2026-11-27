package org.yapyap.routing.store

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.yapyap.persistence.DatabaseConnection
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.TransferShape
import org.yapyap.routing.candidate.CandidateWaiting
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
import kotlin.uuid.Uuid

/**
 * Contract tests for the routing file stores on real in-memory SQLite: the exact semantics
 * the seal registry, the transfer engine and the relay door depend on — atomic deposit claims,
 * chunk-index recovery, atomic relay admission, and outbox tag queries.
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

    private fun sealRow(
        sealId: ContentId = ContentId(ByteArray(32) { 1 }),
        status: FileSealStatus = FileSealStatus.SENDING,
        expiresAt: kotlin.time.Instant = now + 2.days,
    ) = OutgoingSeal(
        sealId = sealId,
        fileId = fileId,
        targetDevice = device,
        kind = FileSealKind.PUSH,
        sealBytes = byteArrayOf(9),
        key = SealKey(ByteArray(32) { 2 }),
        totalBytes = 10_000L,
        status = status,
        createdAt = now,
        expiresAt = expiresAt,
    )

    private fun transferRow(
        transferId: TransferId = TransferId(Uuid.random()),
        sealId: ContentId = ContentId(ByteArray(32) { 1 }),
        status: FileTransferStatus = FileTransferStatus.OFFERED,
    ) = OutgoingTransfer(
        transferId = transferId,
        sealId = sealId,
        relayTransferId = null,
        peer = device,
        shape = TransferShape(32 * 1024, 0, 10_000L),
        nextChunkIndex = 1,
        contiguousAcked = -1,
        offerPacketId = null,
        status = status,
        createdAt = now,
        expiresAt = now + 2.days,
    )

    private fun relayRow(
        transferId: TransferId = TransferId(Uuid.random()),
        origin: PeerId = device,
        finalTarget: PeerId = relay,
        reserved: Long = 3000L,
        lastChunkAt: kotlin.time.Instant = now,
    ) = RelayTransfer(
        transferId = transferId,
        sealId = ContentId(ByteArray(32) { 3 }),
        origin = origin,
        finalTarget = finalTarget,
        shape = TransferShape(1000, 0, 3000L),
        sealBytes = byteArrayOf(9),
        status = FileRelayStatus.RESERVED,
        reservedBytes = reserved,
        createdAt = now,
        lastChunkAt = lastChunkAt,
        expiresAt = now + 2.days,
    )

    @Test
    fun claimDeposit_exactlyOnceAcrossConcurrentClaimants() = runTest {
        val seals = DefaultFileSealStore(db())
        val sealId = ContentId(ByteArray(32) { 7 })
        seals.insert(sealRow(sealId))

        // Ten concurrent claims: exactly one wins, even though all read-then-write.
        val results = (1..10).map {
            async { seals.claimDeposit(sealId, now) }
        }.awaitAll()
        assertEquals(1, results.count { it }, "exactly one claimant wins the round")
        assertFalse(seals.claimDeposit(sealId, now), "a second round is never claimable")
    }

    @Test
    fun transferChunks_recordAndRecoverAndCleanup() = runTest {
        val transfers = DefaultFileTransferStore(db())
        val sealId = ContentId(ByteArray(32) { 7 })
        val id = TransferId(Uuid.random())
        transfers.insert(transferRow(id, sealId))
        val p0 = Uuid.random()
        val p1 = Uuid.random()
        transfers.recordChunk(id, 0, p0)
        transfers.recordChunk(id, 1, p1)

        assertEquals(mapOf(0 to p0, 1 to p1), transfers.chunks(id))
        assertEquals(id to 1, transfers.chunkByPacket(p1))

        transfers.deleteChunk(p0)
        assertEquals(mapOf(1 to p1), transfers.chunks(id))

        // Deleting the transfer drops its chunk mappings (explicit cleanup).
        transfers.deleteChunks(id)
        transfers.delete(id)
        assertTrue(transfers.chunks(id).isEmpty())
    }

    @Test
    fun sealLiveAndExpiredHygiene() = runTest {
        val seals = DefaultFileSealStore(db())
        val live = ContentId(ByteArray(32) { 11 })
        val dead = ContentId(ByteArray(32) { 12 })
        seals.insert(sealRow(live, FileSealStatus.SENDING))
        seals.insert(sealRow(dead, FileSealStatus.ENDED, expiresAt = now - 1.days))
        seals.insert(sealRow(ContentId(ByteArray(32) { 13 }), FileSealStatus.DELIVERED))

        val all = seals.byFile(fileId)
        assertEquals(3, all.size, "live and terminal rows alike are visible by file")
        val expired = seals.expiredAt(now)
        assertEquals(listOf(dead), expired.map { it.sealId })
    }

    @Test
    fun relayAdmit_evictsOldestFirst() = runTest {
        val store = DefaultFileRelayStore(db())
        val first = TransferId(Uuid.random())
        val admission = store.admit(relayRow(first, reserved = 3000L), 3000L)
        assertTrue(admission.admitted)
        val second = TransferId(Uuid.random())
        val result = store.admit(relayRow(second, reserved = 3000L), 3000L)
        assertTrue(result.admitted)
        assertEquals(listOf(first), result.evicted)
        assertNull(store.find(first), "the evicted mid-deposit transfer is gone")
        assertNotNull(store.find(second))
    }

    @Test
    fun relayAdmit_evictsAsManyAsItTakes() = runTest {
        val store = DefaultFileRelayStore(db())
        // Thirty small deposits fill the budget; one large OFFER needs twenty of them gone.
        val small = (0 until 30).map { i ->
            TransferId(Uuid.random()).also { id ->
                val row = relayRow(id, reserved = 300L, lastChunkAt = now + i.seconds)
                store.admit(row, 9_000L)
            }
        }
        val large = TransferId(Uuid.random())
        val result = store.admit(relayRow(large, reserved = 6_000L), 9_000L)
        assertTrue(result.admitted, "evicting twenty of thirty makes room")
        assertEquals(small.take(20), result.evicted, "oldest fed first, exactly as many as needed")
        assertNotNull(store.find(large))
        assertEquals(10, store.all().size - 1, "the ten newest deposits stay")
    }

    @Test
    fun relayStoreChunk_forGoneRow_writesNothing() = runTest {
        val store = DefaultFileRelayStore(db())
        val id = TransferId(Uuid.random())
        store.admit(relayRow(id), 100L * 1024 * 1024)
        store.delete(id)
        // An eviction or drop raced the door's read: no foreign-key failure, no write.
        assertNull(store.storeChunk(id, 0, byteArrayOf(1), now))
        assertEquals(listOf(), store.chunkIndices(id))
    }

    @Test
    fun relayAdmit_refusedOversizeWritesNothing() = runTest {
        val store = DefaultFileRelayStore(db())
        val first = TransferId(Uuid.random())
        store.admit(relayRow(first, reserved = 3000L), 4000L)
        // 5000 bytes fit nowhere, even after evicting everything on offer.
        val huge = TransferId(Uuid.random())
        val result = store.admit(relayRow(huge, reserved = 5000L), 4000L)
        assertFalse(result.admitted)
        assertTrue(result.evicted.isEmpty(), "a refused admission evicts nothing")
        assertNotNull(store.find(first), "the held deposit is untouched")
        assertNull(store.find(huge))
    }

    @Test
    fun relayStoreChunk_promotesWholeAtomically() = runTest {
        val store = DefaultFileRelayStore(db())
        val id = TransferId(Uuid.random())
        store.admit(relayRow(id), 100L * 1024 * 1024)

        assertEquals(FileRelayStatus.RESERVED, store.storeChunk(id, 0, byteArrayOf(1), now))
        assertEquals(FileRelayStatus.RESERVED, store.storeChunk(id, 1, byteArrayOf(2), now))
        // Duplicate chunk stores are no-ops (PK dedup), not double counts.
        assertEquals(FileRelayStatus.RESERVED, store.storeChunk(id, 0, byteArrayOf(1), now))
        assertEquals(FileRelayStatus.WHOLE, store.storeChunk(id, 2, byteArrayOf(3), now))
    }

    @Test
    fun packetOutbox_packetIdsByTagAndDeleteByTag() = runTest {
        val outbox = DefaultPacketOutbox(db())
        val tag = OutboxTag(OutboxTag.Kind.FILE_TRANSFER, Uuid.random())
        fun envelope(): BinaryEnvelope = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.FILE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 2.days,
            source = device,
            target = device,
            routingHeader = byteArrayOf(1, 2, 3),
            content = ByteArray(0),
            signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
        )

        val e1 = envelope()
        val e2 = envelope()
        outbox.enqueue(e1, now, tag = tag, retryClass = RetryClass.FLAT)
        outbox.enqueue(e2, now, tag = tag, retryClass = RetryClass.FLAT)
        outbox.enqueue(envelope(), now)

        assertEquals(setOf(e1.packetId, e2.packetId), outbox.packetIdsByTag(tag).toSet())
        outbox.deleteByTag(tag)
        assertTrue(outbox.packetIdsByTag(tag).isEmpty())
        assertNull(outbox.getByPacketId(e1.packetId))
    }

    @Test
    fun incomingTransfer_pruneExpired() = runTest {
        val incoming = DefaultFileIncomingStore(db())
        val sealId = ContentId(ByteArray(32) { 21 })
        // Seed an incoming seal so transfers can reference the file.
        incoming.insertSeal(
            IncomingSeal(
                sealId = sealId,
                origin = device,
                seal = org.yapyap.protocol.envelopes.FileSeal(
                    fileId = fileId,
                    totalBytes = 1000L,
                    blockSize = 1024,
                    blockHashes = listOf(ByteArray(32)),
                    fileName = "f",
                    mimeType = null,
                    key = SealKey(ByteArray(32)),
                ),
                expiresAt = now + 2.days,
            )
        )
        val live = TransferId(Uuid.random())
        val dead = TransferId(Uuid.random())
        suspend fun insert(id: TransferId, expiresAt: kotlin.time.Instant) {
            incoming.insertTransfer(
                IncomingTransfer(
                    transferId = id,
                    sealId = sealId,
                    sender = device,
                    shape = TransferShape(1024, 0, 1000L),
                    expiresAt = expiresAt,
                ),
            )
        }
        insert(live, now + 2.days)
        insert(dead, now - 1.hours)
        incoming.pruneExpired(now)
        assertNull(incoming.findTransfer(dead))
        assertNotNull(incoming.findTransfer(live))
    }

    @Test
    fun pullCandidateStore_small_composesWaitingAndDeclinedChildren() = runTest {
        val store = DefaultFilePullAttemptStore(db())
        val attemptId = Uuid.random()
        store.start(fileId, FilePullClass.SMALL, now - 1.seconds)
        store.setAttempt(fileId, attemptId)
        val view = store.candidateStore(FilePullClass.SMALL)
        view.addDeclined(fileId, relay)

        val until = now + 90.seconds
        view.setWaiting(fileId, device, until)

        val stored = assertNotNull(store.findById(fileId))
        assertEquals(device, stored.waitingDevice)
        assertEquals(until, stored.waitingUntil)
        assertEquals(attemptId, stored.attemptId, "the view resolves the row's request-attempt id")

        val due = view.findDue(now, limit = 10).single()
        assertEquals(fileId, due.id)
        assertEquals(setOf(relay), due.declined)
        assertEquals(CandidateWaiting(device, until), due.waiting)

        view.clearWaiting(fileId)
        assertNull(store.findById(fileId)?.waitingDevice)
        assertNull(store.findById(fileId)?.waitingUntil)
        assertNull(view.findDue(now, limit = 10).single().waiting)
    }

    @Test
    fun pullCandidateStore_large_hasNoWaitingState() = runTest {
        val store = DefaultFilePullAttemptStore(db())
        val attemptId = Uuid.random()
        store.start(fileId, FilePullClass.LARGE, now - 1.seconds)
        store.setAttempt(fileId, attemptId)
        val view = store.candidateStore(FilePullClass.LARGE)
        view.addDeclined(fileId, relay)

        val due = view.findDue(now, limit = 10).single()
        assertEquals(fileId, due.id)
        assertEquals(setOf(relay), due.declined)
        assertNull(due.waiting, "the large path's answer and bytes share a channel")

        view.setWaiting(fileId, device, now + 90.seconds)
        assertNull(store.findById(fileId)?.waitingDevice, "setWaiting is a no-op for LARGE")
        assertNull(store.findById(fileId)?.waitingUntil)
        view.clearWaiting(fileId)
        assertEquals(attemptId, store.findById(fileId)?.attemptId, "clearWaiting leaves LARGE untouched")
        assertNull(view.findDue(now, limit = 10).single().waiting)
    }

    @Test
    fun syncCandidateStore_composesRowsAndHasNoWaiting() = runTest {
        val store = DefaultSyncAttemptStore(db())
        val syncId = Uuid.random()
        store.start(syncId, now - 1.seconds)
        store.recordAttempt(syncId, now - 10.seconds)
        val view = store.candidateStore()
        view.addDeclined(syncId, device)

        val due = view.findDue(now, limit = 10).single()
        assertEquals(syncId, due.id)
        assertEquals(1, due.attempts)
        assertEquals(setOf(device), due.declined)
        assertNull(due.waiting)

        view.setWaiting(syncId, device, now + 90.seconds)
        assertNull(view.findDue(now, limit = 10).single().waiting, "sync has no WAITING")
        view.clearWaiting(syncId)
        assertNull(view.findDue(now, limit = 10).single().waiting)
    }
}
