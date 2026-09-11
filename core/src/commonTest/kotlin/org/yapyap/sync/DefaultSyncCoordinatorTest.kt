package org.yapyap.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.sync.DefaultSyncCoordinator
import org.yapyap.persistence.db.VerificationState
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.testfixtures.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DefaultSyncCoordinatorTest {

    private val localAccount = AccountId("local-account")
    private val remoteAccount = AccountId("remote-account")
    private val localDevice = PeerId("local-device")
    private val remoteDevice = PeerId("remote-device")
    private val roomId = RoomId(Uuid.random())

    private val pipeline = FakeInboundMessagePipeline()
    private lateinit var roomRepo: FakeRoomRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var pendingRepo: FakePendingSyncRepository
    private lateinit var clock: FakeClock

    private fun buildCoordinator(
        roomMembers: List<AccountId> = listOf(localAccount, remoteAccount),
    ): DefaultSyncCoordinator {
        roomRepo = FakeRoomRepository(mapOf(roomId to roomMembers))
        messageRepo = FakeMessageRepository()
        pendingRepo = FakePendingSyncRepository()
        clock = FakeClock(epochSeconds(1_000L))
        return DefaultSyncCoordinator(
            pipeline = pipeline,
            roomRepository = roomRepo,
            messageRepository = messageRepo,
            identityResolver = FakeIdentityResolver(localAccount, localDevice),
            pendingSyncRepository = pendingRepo,
            clock = clock,
            orchestratorConfig = MutableStateFlow(OrchestratorConfig()),
        )
    }

    private fun textMsg(
        roomId: RoomId,
        prevIds: List<Uuid>,
        sender: AccountId = remoteAccount,
    ): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = sender,
            authorDeviceId = remoteDevice,
            authorSignature = byteArrayOf(1),
            prevIds = prevIds,
            createdAt = epochSeconds(0L),
            text = "m",
        )

    // ------------------------------------------------------------------
    // requestFrontierSync
    // ------------------------------------------------------------------

    @Test
    fun requestFrontierSync_unknownTips_createsOneSyncPerTip() = runTest {
        val coordinator = buildCoordinator()
        val tip1 = Uuid.random()
        val tip2 = Uuid.random()

        coordinator.requestFrontierSync(roomId, listOf(tip1, tip2))

        val rows = pendingRepo.all()
        assertEquals(2, rows.size)
        assertEquals(setOf(tip1, tip2), rows.map { it.targetMessageId }.toSet())
        for (sync in rows) {
            assertEquals(listOf(remoteAccount), sync.candidateAccounts)
            assertEquals(epochSeconds(1_000L + 60L), pendingRepo.nextAttemptAtOf(sync.syncId))
        }
    }

    @Test
    fun requestFrontierSync_knownTip_createsNothing() = runTest {
        val coordinator = buildCoordinator()
        val known = textMsg(roomId, prevIds = emptyList())
        messageRepo.insert(
            known,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.VERIFIED
        )

        coordinator.requestFrontierSync(roomId, listOf(known.messageId))

        assertTrue(pendingRepo.all().isEmpty())
    }

    @Test
    fun requestFrontierSync_knownOrphanTip_createsNothing() = runTest {
        // A known tip is either chainable (nothing to do) or a local orphan whose
        // parents our causal holds already chase — no new sync in either case.
        val coordinator = buildCoordinator()
        val orphan = textMsg(roomId, prevIds = listOf(Uuid.random()))
        messageRepo.insert(
            orphan,
            isOrphaned = true,
            ancestryComplete = false,
            verificationState = VerificationState.VERIFIED
        )

        coordinator.requestFrontierSync(roomId, listOf(orphan.messageId))

        assertTrue(pendingRepo.all().isEmpty())
    }

    @Test
    fun requestFrontierSync_duplicateTip_doesNotDuplicate() = runTest {
        val coordinator = buildCoordinator()
        val tip = Uuid.random()
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId,
            targetMessageId = tip,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = epochSeconds(1_000L),
        )

        coordinator.requestFrontierSync(roomId, listOf(tip))

        assertEquals(1, pendingRepo.all().size)
    }

    @Test
    fun requestFrontierSync_multipleMembers_allNonLocalAreCandidates() = runTest {
        val thirdAccount = AccountId("third-account")
        val coordinator = buildCoordinator(roomMembers = listOf(localAccount, remoteAccount, thirdAccount))

        coordinator.requestFrontierSync(roomId, listOf(Uuid.random()))

        val sync = pendingRepo.all().single()
        assertEquals(listOf(remoteAccount, thirdAccount), sync.candidateAccounts)
    }

    // ------------------------------------------------------------------
    // processBecameOrphan (driven via pipeline)
    // ------------------------------------------------------------------

    @Test
    fun becameOrphan_eachMissingParentGetsSyncRow() = runTest {
        val coordinator = buildCoordinator()
        coordinator.start(this)
        testScheduler.advanceUntilIdle()
        val missing1 = Uuid.random()
        val missing2 = Uuid.random()
        val orphan = textMsg(roomId, prevIds = listOf(missing1, missing2))

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = orphan,
                closedGapMissingPrevIds = emptyList(),
                missingPrevIds = listOf(missing1, missing2),
            )
        )
        testScheduler.advanceUntilIdle()

        assertEquals(
            setOf(missing1, missing2),
            pendingRepo.all().map { it.targetMessageId }.toSet(),
        )
        coordinator.stop()
    }

    @Test
    fun becameOrphan_sharedMissingParent_collapsesToOneRow() = runTest {
        val coordinator = buildCoordinator()
        coordinator.start(this)
        testScheduler.advanceUntilIdle()
        val sharedMissing = Uuid.random()
        val orphan1 = textMsg(roomId, prevIds = listOf(sharedMissing))
        val orphan2 = textMsg(roomId, prevIds = listOf(sharedMissing, Uuid.random()))

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = orphan1,
                closedGapMissingPrevIds = emptyList(),
                missingPrevIds = listOf(sharedMissing),
            )
        )
        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = orphan2,
                closedGapMissingPrevIds = emptyList(),
                missingPrevIds = orphan2.prevIds,
            )
        )
        testScheduler.advanceUntilIdle()

        val rows = pendingRepo.all()
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.targetMessageId == sharedMissing })
        coordinator.stop()
    }

    @Test
    fun becameOrphan_existingSyncForTarget_noDuplicate() = runTest {
        val coordinator = buildCoordinator()
        val missing = Uuid.random()
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId,
            targetMessageId = missing,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = epochSeconds(1_000L),
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()

        pipeline.emit(
            IngestResult.BecameOrphan(
                payload = textMsg(roomId, prevIds = listOf(missing)),
                closedGapMissingPrevIds = emptyList(),
                missingPrevIds = listOf(missing),
            )
        )
        testScheduler.advanceUntilIdle()

        assertEquals(1, pendingRepo.all().size)
        coordinator.stop()
    }

    // ------------------------------------------------------------------
    // processInserted (driven via pipeline)
    // ------------------------------------------------------------------

    @Test
    fun inserted_targetArrives_deletesSync() = runTest {
        val coordinator = buildCoordinator()
        val target = textMsg(roomId, prevIds = emptyList())
        val other = textMsg(roomId, prevIds = emptyList())
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId,
            targetMessageId = target.messageId,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = epochSeconds(1_000L),
        )
        pendingRepo.insertSync(
            syncId = Uuid.random(), roomId = roomId,
            targetMessageId = other.messageId,
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = epochSeconds(1_000L),
        )
        coordinator.start(this)
        testScheduler.advanceUntilIdle()

        pipeline.emit(IngestResult.Inserted(payload = target))
        testScheduler.advanceUntilIdle()

        val rows = pendingRepo.all()
        assertEquals(1, rows.size)
        assertEquals(other.messageId, rows.single().targetMessageId)
        coordinator.stop()
    }

    @Test
    fun inserted_noSyncForTarget_isNoOp() = runTest {
        val coordinator = buildCoordinator()
        coordinator.start(this)
        testScheduler.advanceUntilIdle()

        pipeline.emit(IngestResult.Inserted(payload = textMsg(roomId, prevIds = emptyList())))
        testScheduler.advanceUntilIdle()

        assertTrue(pendingRepo.all().isEmpty())
        coordinator.stop()
    }
}
