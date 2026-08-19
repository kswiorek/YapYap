package org.yapyap.sync

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.sync.DefaultSyncCoordinator
import org.yapyap.orchestrator.sync.SyncConfig
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.testfixtures.FakeIdentityResolver
import org.yapyap.testfixtures.FakeMessageRepository
import org.yapyap.testfixtures.FakeRoomRepository
import org.yapyap.time.FixedEpochProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DefaultSyncCoordinatorTest {

    private val localAccount = AccountId("local-account")
    private val remoteAccount = AccountId("remote-account")
    private val localDevice = PeerId("local-device")
    private val remoteDevice = PeerId("remote-device")
    private val roomId = "sync-room"

    private val pipeline = FakeInboundMessagePipeline()
    private lateinit var roomRepo: FakeRoomRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var pendingRepo: FakePendingSyncRepository
    private lateinit var time: FixedEpochProvider

    private fun buildCoordinator(
        roomMembers: List<AccountId> = listOf(localAccount, remoteAccount),
    ): DefaultSyncCoordinator {
        roomRepo = FakeRoomRepository(mapOf(roomId to roomMembers))
        messageRepo = FakeMessageRepository()
        pendingRepo = FakePendingSyncRepository()
        time = FixedEpochProvider(1_000L)
        return DefaultSyncCoordinator(
            pipeline = pipeline,
            roomRepository = roomRepo,
            messageRepository = messageRepo,
            identityResolver = FakeIdentityResolver(localAccount, localDevice),
            pendingSyncRepository = pendingRepo,
            timeProvider = time,
            syncConfig = SyncConfig(
                gracePeriodSeconds = 60,
                syncMaxMessages = 20,
                deviceOfflineRetryDelaySeconds = 60,
            ),
        )
    }

    private fun textMsg(
        roomId: String,
        lamport: Long,
        prevId: Uuid?,
        sender: AccountId = remoteAccount,
    ): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = sender,
            authorDeviceId = remoteDevice,
            authorSignature = byteArrayOf(1),
            prevId = prevId,
            lamportClock = lamport,
            createdAtEpochSeconds = 0L,
            text = "m$lamport",
        )

    // ------------------------------------------------------------------
    // requestRangeSync
    // ------------------------------------------------------------------

    @Test
    fun requestRangeSync_noExistingSync_createsGapSync() = runTest {
        val coordinator = buildCoordinator()
        roomRepo.updateLocalSeq(roomId, 5L)

        coordinator.requestRangeSync(roomId, pingLamport = 10L)

        val rows = pendingRepo.all()
        assertEquals(1, rows.size)
        val sync = rows.single()
        assertEquals(5L, sync.anchorLamport)
        assertEquals(10L, sync.orphanLamport)
        assertEquals(listOf(remoteAccount), sync.candidateAccounts)
        assertEquals(1_000L + 60L, pendingRepo.nextAttemptAtOf(sync.syncId))
    }

    @Test
    fun requestRangeSync_existingSync_raisesOrphanLamport() = runTest {
        val coordinator = buildCoordinator()
        roomRepo.updateLocalSeq(roomId, 5L)
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 5L, orphanLamport = 8L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )

        coordinator.requestRangeSync(roomId, pingLamport = 10L)

        val sync = pendingRepo.findGapSyncByAnchor(roomId, 5L)!!
        assertEquals(10L, sync.orphanLamport)
        assertEquals(1, pendingRepo.all().size)
    }

    @Test
    fun requestRangeSync_alreadyCaughtUp_ignores() = runTest {
        val coordinator = buildCoordinator()
        roomRepo.updateLocalSeq(roomId, 10L)

        coordinator.requestRangeSync(roomId, pingLamport = 5L)

        assertTrue(pendingRepo.all().isEmpty())
    }

    @Test
    fun requestRangeSync_existingSyncAlreadyCoversPing_ignores() = runTest {
        val coordinator = buildCoordinator()
        roomRepo.updateLocalSeq(roomId, 5L)
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 5L, orphanLamport = 12L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )

        coordinator.requestRangeSync(roomId, pingLamport = 10L)

        assertEquals(12L, pendingRepo.findGapSyncByAnchor(roomId, 5L)!!.orphanLamport)
    }

    // ------------------------------------------------------------------
    // processBecameOrphan (driven via pipeline)
    // ------------------------------------------------------------------

    @Test
    fun becameOrphan_noExistingSync_createsGapSync() = runTest {
        val coordinator = buildCoordinator()
        coordinator.start(this)
        testScheduler.advanceUntilIdle()
        val orphan = textMsg(roomId, lamport = 8L, prevId = Uuid.random())

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = orphan,
                closedGapMissingPrevIds = emptyList(),
                missingPrevId = orphan.prevId!!,
                anchorLamport = 4L,
            )
        )
        testScheduler.advanceUntilIdle()

        val sync = pendingRepo.findGapSyncByAnchor(roomId, 4L)!!
        assertEquals(8L, sync.orphanLamport)
        coordinator.stop()    }

    @Test
    fun becameOrphan_higherLamport_raisesOrphan() = runTest {
        val coordinator = buildCoordinator()
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 4L, orphanLamport = 6L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()
        val orphan = textMsg(roomId, lamport = 9L, prevId = Uuid.random())

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = orphan,
                closedGapMissingPrevIds = emptyList(),
                missingPrevId = orphan.prevId!!,
                anchorLamport = 4L,
            )
        )
        testScheduler.advanceUntilIdle()

        assertEquals(9L, pendingRepo.findGapSyncByAnchor(roomId, 4L)!!.orphanLamport)
        coordinator.stop()
    }

    // ------------------------------------------------------------------
    // processInserted (driven via pipeline)
    // ------------------------------------------------------------------

    @Test
    fun inserted_satisfiesAnchor_deletesSyncWithoutRecreate() = runTest {
        val coordinator = buildCoordinator()
        val anchorMsg = textMsg(roomId, lamport = 4L, prevId = null)
        messageRepo.insert(anchorMsg, isOrphaned = false)
        messageRepo.insert(textMsg(roomId, lamport = 9L, prevId = null), isOrphaned = false)
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 4L, orphanLamport = 9L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()

        val inserted = textMsg(roomId, lamport = 8L, prevId = anchorMsg.messageId)
        pipeline.emit(IngestResult.Inserted(payload = inserted))
        testScheduler.advanceUntilIdle()

        assertTrue(pendingRepo.all().isEmpty())
        coordinator.stop()
    }

    @Test
    fun inserted_orphanStillOpen_recreatesContinuationSync() = runTest {
        val coordinator = buildCoordinator()
        val anchorMsg = textMsg(roomId, lamport = 4L, prevId = null)
        messageRepo.insert(anchorMsg, isOrphaned = false)
        // No message at orphan lamport 9 -> gap is still open, a continuation sync must be created.
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 4L, orphanLamport = 9L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()

        val inserted = textMsg(roomId, lamport = 8L, prevId = anchorMsg.messageId)
        pipeline.emit(IngestResult.Inserted(payload = inserted))
        testScheduler.advanceUntilIdle()

        val rows = pendingRepo.all()
        assertEquals(1, rows.size)
        assertEquals(8L, rows.single().anchorLamport)
        assertEquals(9L, rows.single().orphanLamport)
        coordinator.stop()
    }

    // ------------------------------------------------------------------
    // requestRangeSync — boundary & multi-member edge cases
    // ------------------------------------------------------------------

    @Test
    fun requestRangeSync_pingEqualsLocalSeq_ignores() = runTest {
        val coordinator = buildCoordinator()
        roomRepo.updateLocalSeq(roomId, 5L)

        coordinator.requestRangeSync(roomId, pingLamport = 5L)

        assertTrue(pendingRepo.all().isEmpty())
    }

    @Test
    fun requestRangeSync_pingEqualsExistingOrphan_noChange() = runTest {
        val coordinator = buildCoordinator()
        roomRepo.updateLocalSeq(roomId, 5L)
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 5L, orphanLamport = 10L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )

        coordinator.requestRangeSync(roomId, pingLamport = 10L)

        assertEquals(10L, pendingRepo.findGapSyncByAnchor(roomId, 5L)!!.orphanLamport)
        assertEquals(1, pendingRepo.all().size)
    }

    @Test
    fun requestRangeSync_multipleMembers_allNonLocalAreCandidates() = runTest {
        val thirdAccount = AccountId("third-account")
        val coordinator = buildCoordinator(roomMembers = listOf(localAccount, remoteAccount, thirdAccount))
        roomRepo.updateLocalSeq(roomId, 5L)

        coordinator.requestRangeSync(roomId, pingLamport = 10L)

        val sync = pendingRepo.all().single()
        assertEquals(listOf(remoteAccount, thirdAccount), sync.candidateAccounts)
    }

    // ------------------------------------------------------------------
    // processBecameOrphan — branching edge cases
    // ------------------------------------------------------------------

    @Test
    fun becameOrphan_sameLamportAsOrphan_isNoOp() = runTest {
        val coordinator = buildCoordinator()
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 4L, orphanLamport = 8L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()
        val twin = textMsg(roomId, lamport = 8L, prevId = Uuid.random())

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = twin,
                closedGapMissingPrevIds = emptyList(),
                missingPrevId = twin.prevId!!,
                anchorLamport = 4L,
            )
        )
        testScheduler.advanceUntilIdle()

        val sync = pendingRepo.findGapSyncByAnchor(roomId, 4L)!!
        assertEquals(8L, sync.orphanLamport)
        assertEquals(1, pendingRepo.all().size)
        coordinator.stop()
    }

    @Test
    fun becameOrphan_lowerLamport_orphanStillOpen_splitsIntoTwoSyncs() = runTest {
        val coordinator = buildCoordinator()
        messageRepo.insert(textMsg(roomId, lamport = 9L, prevId = Uuid.random()), isOrphaned = true)
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 4L, orphanLamport = 9L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()
        val newOrphan = textMsg(roomId, lamport = 7L, prevId = Uuid.random())

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = newOrphan,
                closedGapMissingPrevIds = emptyList(),
                missingPrevId = newOrphan.prevId!!,
                anchorLamport = 4L,
            )
        )
        testScheduler.advanceUntilIdle()

        val rows = pendingRepo.all().sortedBy { it.anchorLamport }
        assertEquals(2, rows.size)
        assertEquals(4L, rows[0].anchorLamport)
        assertEquals(7L, rows[0].orphanLamport)
        assertEquals(7L, rows[1].anchorLamport)
        assertEquals(9L, rows[1].orphanLamport)
        coordinator.stop()
    }

    @Test
    fun becameOrphan_lowerLamport_noMessageAtOrphan_splitsIntoTwoSyncs() = runTest {
        val coordinator = buildCoordinator()
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 4L, orphanLamport = 9L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()
        val newOrphan = textMsg(roomId, lamport = 6L, prevId = Uuid.random())

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = newOrphan,
                closedGapMissingPrevIds = emptyList(),
                missingPrevId = newOrphan.prevId!!,
                anchorLamport = 4L,
            )
        )
        testScheduler.advanceUntilIdle()

        val rows = pendingRepo.all().sortedBy { it.anchorLamport }
        assertEquals(2, rows.size)
        assertEquals(4L, rows[0].anchorLamport)
        assertEquals(6L, rows[0].orphanLamport)
        assertEquals(6L, rows[1].anchorLamport)
        assertEquals(9L, rows[1].orphanLamport)
        coordinator.stop()
    }

    @Test
    fun becameOrphan_lowerLamport_orphanAlreadyClosed_onlyShortens() = runTest {
        val coordinator = buildCoordinator()
        messageRepo.insert(textMsg(roomId, lamport = 9L, prevId = null), isOrphaned = false)
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 4L, orphanLamport = 9L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()
        val newOrphan = textMsg(roomId, lamport = 6L, prevId = Uuid.random())

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = newOrphan,
                closedGapMissingPrevIds = emptyList(),
                missingPrevId = newOrphan.prevId!!,
                anchorLamport = 4L,
            )
        )
        testScheduler.advanceUntilIdle()

        val rows = pendingRepo.all()
        assertEquals(1, rows.size)
        assertEquals(4L, rows.single().anchorLamport)
        assertEquals(6L, rows.single().orphanLamport)
        coordinator.stop()
    }

    // ------------------------------------------------------------------
    // processInserted — additional edge cases
    // ------------------------------------------------------------------

    @Test
    fun inserted_noExistingSync_isNoOp() = runTest {
        val coordinator = buildCoordinator()
        val anchorMsg = textMsg(roomId, lamport = 4L, prevId = null)
        messageRepo.insert(anchorMsg, isOrphaned = false)
        coordinator.start(this)
        testScheduler.advanceUntilIdle()

        val inserted = textMsg(roomId, lamport = 5L, prevId = anchorMsg.messageId)
        pipeline.emit(IngestResult.Inserted(payload = inserted))
        testScheduler.advanceUntilIdle()

        assertTrue(pendingRepo.all().isEmpty())
        coordinator.stop()
    }

    @Test
    fun inserted_orphanAtOrphanLamportStillFlagged_recreatesContinuationSync() = runTest {
        val coordinator = buildCoordinator()
        val anchorMsg = textMsg(roomId, lamport = 4L, prevId = null)
        messageRepo.insert(anchorMsg, isOrphaned = false)
        messageRepo.insert(textMsg(roomId, lamport = 9L, prevId = Uuid.random()), isOrphaned = true)
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId, maxMessages = 20,
            anchorLamport = 4L, orphanLamport = 9L,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = 1_000L,
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()

        val inserted = textMsg(roomId, lamport = 8L, prevId = anchorMsg.messageId)
        pipeline.emit(IngestResult.Inserted(payload = inserted))
        testScheduler.advanceUntilIdle()

        val rows = pendingRepo.all()
        assertEquals(1, rows.size)
        assertEquals(8L, rows.single().anchorLamport)
        assertEquals(9L, rows.single().orphanLamport)
        coordinator.stop()
    }

    @Test
    fun inserted_noMessagesBelowL_isNoOp() = runTest {
        val coordinator = buildCoordinator()
        coordinator.start(this)
        testScheduler.advanceUntilIdle()

        val inserted = textMsg(roomId, lamport = 0L, prevId = null)
        pipeline.emit(IngestResult.Inserted(payload = inserted))
        testScheduler.advanceUntilIdle()

        assertTrue(pendingRepo.all().isEmpty())
        coordinator.stop()
    }
}
