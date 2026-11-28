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
 * chunk-index recovery, atomic relay admission and coverage, and outbox tag queries.
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
        sourceTransferId = null,
        peer = device,
        shape = TransferShape(32 * 1024, 0, 10_000L),
        nextChunkIndex = 1,
        contiguousAcked = -1,
        offerPacketId = null,
        status = status,
        createdAt = now,
        expiresAt = now + 2.days,
    )

    /**
     * A relay hop. With the default empty shape its reservation is exactly its seal's size, so a
     * budget test can say how many bytes each hop holds.
     */
    private fun relayHop(
        transferId: TransferId = TransferId(Uuid.random()),
        shape: TransferShape = TransferShape(1000, 0, 0),
        expiresAt: kotlin.time.Instant = now + 2.days,
    ) = IncomingTransfer(
        transferId = transferId,
        sealId = ContentId(ByteArray(32) { 3 }),
        sender = device,
        finalTarget = relay,
        shape = shape,
        expiresAt = expiresAt,
    )

    private fun sealOf(size: Int) = ByteArray(size) { 9 }

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
        val store = DefaultFileIncomingStore(db())
        val first = TransferId(Uuid.random())
        val admission = store.admitRelay(relayHop(first), sealOf(3000), 3000L, now)
        assertTrue(admission.admitted)
        val second = TransferId(Uuid.random())
        val result = store.admitRelay(relayHop(second), sealOf(3000), 3000L, now + 1.seconds)
        assertTrue(result.admitted)
        assertEquals(listOf(first), result.evicted)
        assertNull(store.findTransfer(first), "the evicted mid-deposit hop is gone")
        assertNull(store.relaySealBytes(first), "its ciphertext goes with it")
        assertNotNull(store.relayHop(second))
    }

    @Test
    fun relayAdmit_evictsAsManyAsItTakes() = runTest {
        val store = DefaultFileIncomingStore(db())
        // Thirty small hops fill the budget; one large OFFER needs twenty of them gone.
        val small = (0 until 30).map { i ->
            TransferId(Uuid.random()).also { id ->
                store.admitRelay(
                    relayHop(id),
                    sealOf(300),
                    9_000L,
                    now + i.seconds
                )
            }
        }
        val large = TransferId(Uuid.random())
        val result = store.admitRelay(relayHop(large), sealOf(6_000), 9_000L, now + 1.days)
        assertTrue(result.admitted, "evicting twenty of thirty makes room")
        assertEquals(small.take(20), result.evicted, "oldest fed first, exactly as many as needed")
        assertNotNull(store.relayHop(large))
        assertEquals(10, store.relayHops().size - 1, "the ten newest hops stay")
    }

    @Test
    fun relayAdmit_ordersByLastStoredChunk() = runTest {
        val store = DefaultFileIncomingStore(db())
        val older = TransferId(Uuid.random())
        val fed = TransferId(Uuid.random())
        val shape = TransferShape(1000, 0, 2000L)
        store.admitRelay(relayHop(older, shape), sealOf(1), 100L * 1024 * 1024, now + 1.seconds)
        store.admitRelay(relayHop(fed, shape), sealOf(1), 100L * 1024 * 1024, now)
        // Admitted first, but fed later: a stored chunk is what counts as activity.
        store.storeRelayChunk(fed, 0, byteArrayOf(1), now + 1.hours)

        assertEquals(listOf(older, fed), store.relayHops().map { it.transfer.transferId })
        assertEquals(now + 1.hours, store.relayHop(fed)?.lastStoredAt)
    }

    @Test
    fun relayStoreChunk_forGoneHop_writesNothing() = runTest {
        val store = DefaultFileIncomingStore(db())
        val id = TransferId(Uuid.random())
        store.admitRelay(relayHop(id, TransferShape(1000, 0, 3000L)), sealOf(1), 100L * 1024 * 1024, now)
        store.deleteTransfer(id)
        // An eviction or drop raced the door's read: no foreign-key failure, no write.
        assertNull(store.storeRelayChunk(id, 0, byteArrayOf(1), now))
        assertNull(store.relayChunk(id, 0))
    }

    @Test
    fun relayAdmit_refusedOversizeWritesNothing() = runTest {
        val store = DefaultFileIncomingStore(db())
        val first = TransferId(Uuid.random())
        store.admitRelay(relayHop(first), sealOf(3000), 4000L, now)
        // 5000 bytes fit nowhere, even after evicting everything on offer.
        val huge = TransferId(Uuid.random())
        val result = store.admitRelay(relayHop(huge), sealOf(5000), 4000L, now)
        assertFalse(result.admitted)
        assertTrue(result.evicted.isEmpty(), "a refused admission evicts nothing")
        assertNotNull(store.relayHop(first), "the held hop is untouched")
        assertNull(store.findTransfer(huge))
    }

    @Test
    fun relayStoreChunk_reportsCoverageAndWholeness() = runTest {
        val store = DefaultFileIncomingStore(db())
        val id = TransferId(Uuid.random())
        store.admitRelay(relayHop(id, TransferShape(1000, 0, 3000L)), sealOf(1), 100L * 1024 * 1024, now)

        assertEquals(RelayCoverage(0, false), store.storeRelayChunk(id, 0, byteArrayOf(1), now))
        assertEquals(RelayCoverage(0, false), store.storeRelayChunk(id, 2, byteArrayOf(3), now))
        // Duplicate chunk stores are no-ops (PK dedup), not double counts.
        assertEquals(RelayCoverage(0, false), store.storeRelayChunk(id, 0, byteArrayOf(1), now))
        assertEquals(RelayCoverage(2, true), store.storeRelayChunk(id, 1, byteArrayOf(2), now))
        assertEquals(true, store.relayHop(id)?.isWhole)
    }

    @Test
    fun pruneExpired_leavesRelayHopsToTheirForward() = runTest {
        val store = DefaultFileIncomingStore(db())
        val relayed = TransferId(Uuid.random())
        val ours = TransferId(Uuid.random())
        store.admitRelay(relayHop(relayed, expiresAt = now), sealOf(1), 100L * 1024 * 1024, now)
        store.insertTransfer(relayHop(ours, expiresAt = now).copy(finalTarget = device))

        store.pruneExpired(now)

        assertNull(store.findTransfer(ours), "the target's expired hop goes")
        assertNotNull(store.relayHop(relayed), "a relay hop ends with its forward, not the target sweep")
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
                    finalTarget = relay,
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

    @Test
    fun pullFindDue_severalDeclinedDevices_pagesByFileAndFoldsTheJoin() = runTest {
        val store = DefaultFilePullAttemptStore(db())
        val first = FileId("1".repeat(64))
        val second = FileId("2".repeat(64))
        val third = FileId("3".repeat(64))
        store.start(first, FilePullClass.SMALL, now - 30.seconds)
        store.start(second, FilePullClass.SMALL, now - 20.seconds)
        store.start(third, FilePullClass.SMALL, now - 10.seconds)
        store.addDeclined(first, device)
        store.addDeclined(first, relay)

        val due = store.findDue(now, limit = 2, FilePullClass.SMALL)

        assertEquals(listOf(first, second), due.map { it.fileId }, "the limit counts files, not joined rows")
        assertEquals(setOf(device, relay), due[0].declinedDevices)
        assertEquals(emptySet(), due[1].declinedDevices)
        assertEquals(setOf(device, relay), store.findById(first)?.declinedDevices)
    }

    @Test
    fun syncFindDue_severalDeclinedDevices_pagesBySyncAndFoldsTheJoin() = runTest {
        val store = DefaultSyncAttemptStore(db())
        val first = Uuid.random()
        val second = Uuid.random()
        val third = Uuid.random()
        store.start(first, now - 30.seconds)
        store.start(second, now - 20.seconds)
        store.start(third, now - 10.seconds)
        store.addDeclined(first, device)
        store.addDeclined(first, relay)

        val due = store.findDue(now, limit = 2)

        assertEquals(listOf(first, second), due.map { it.syncId }, "the limit counts syncs, not joined rows")
        assertEquals(setOf(device, relay), due[0].declinedDevices)
        assertEquals(emptySet(), due[1].declinedDevices)
    }
}
