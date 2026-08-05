package org.yapyap.orchestrator.dag

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.messaging.MessageCursor
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.testfixtures.*
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Pure-Kotlin contract tests for [DefaultDagEngine] backed by fake in-memory repositories.
 * Safe to move to commonTest.
 */
class DefaultDagEngineTest {

    private lateinit var dagEngine: DefaultDagEngine
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var causalHoldRepo: FakeCausalHoldRepository
    private lateinit var roomRepo: FakeRoomRepository
    private lateinit var identityResolver: FakeIdentityResolver
    private lateinit var signatureProvider: FakeSignatureProvider
    private lateinit var timeProvider: MutableEpochSecondsProvider

    private val testAccount = AccountId("dag-sender-account")
    private val remoteAccount = AccountId("dag-remote-account")
    private val testDeviceId = PeerId("test-device-id")
    private val remoteDeviceId = PeerId("remote-device-id")
    private val roomId = "dag-test-room"

    @BeforeTest
    fun setup() {
        messageRepo = FakeMessageRepository()
        causalHoldRepo = FakeCausalHoldRepository(messageRepo)
        roomRepo = FakeRoomRepository()
        identityResolver = FakeIdentityResolver(testAccount, testDeviceId)
        signatureProvider = FakeSignatureProvider()
        timeProvider = MutableEpochSecondsProvider(1_000_000L)
        dagEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomRepo,
            identityResolver = identityResolver,
            signatureProvider = signatureProvider,
            timeProvider = timeProvider,
        )
    }

    @Test
    fun append_emptyRoom_assignsLamportZeroAndNullPrevId() = runTest {
        val payload = dagEngine.append(roomId, MessageDraft.Text("first"))

        assertEquals(0L, payload.lamportClock)
        assertNull(payload.prevId)
        assertEquals(roomId, payload.roomId)
        assertEquals(testAccount, payload.senderAccountId)
        assertEquals("first", (payload as MessagePayload.Text).text)
        assertEquals(timeProvider.nowEpochSeconds(), payload.createdAtEpochSeconds)
        assertFalse(messageRepo.findById(payload.messageId)!!.isOrphaned)
    }

    @Test
    fun append_chainsOffRoomTail_incrementsLamport() = runTest {
        val first = dagEngine.append(roomId, MessageDraft.Text("first"))
        timeProvider.t += 1L
        val second = dagEngine.append(roomId, MessageDraft.Text("second"))

        assertEquals(1L, second.lamportClock)
        assertEquals(first.messageId, second.prevId)
    }

    @Test
    fun append_concurrentMessagesFromSameSender_haveMonotonicLamportAndChain() = runTest {
        val a = dagEngine.append(roomId, MessageDraft.Text("a"))
        val b = dagEngine.append(roomId, MessageDraft.Text("b"))
        val c = dagEngine.append(roomId, MessageDraft.Text("c"))

        assertEquals(0L, a.lamportClock)
        assertEquals(1L, b.lamportClock)
        assertEquals(2L, c.lamportClock)
        assertEquals(a.messageId, b.prevId)
        assertEquals(b.messageId, c.prevId)
    }

    @Test
    fun ingest_newMessageWithExistingPrev_returnsInserted_noGaps() = runTest {
        val first = dagEngine.append(roomId, MessageDraft.Text("first"))

        val remotePayload = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = first.messageId,
            lamportClock = 1L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "from remote",
        )

        val result = dagEngine.ingest(remotePayload)

        assertTrue(result is IngestResult.Inserted)
        assertEquals(remotePayload, result.payload)
        assertTrue(result.closedGapMissingPrevIds.isEmpty())
        assertFalse(messageRepo.findById(remotePayload.messageId)!!.isOrphaned)
    }

    @Test
    fun ingest_duplicateMessage_isDeduped_noNewRow() = runTest {
        val first = dagEngine.append(roomId, MessageDraft.Text("first"))
        assertEquals(1, messageRepo.byId.size)

        // Ingesting an appended message is a dedup case.
        val result = dagEngine.ingest(first)
        assertNull(result)
        // No new row inserted.
        assertEquals(1, messageRepo.byId.size)
    }

    @Test
    fun ingest_missingPrev_returnsBecameOrphan_andCreatesGap() = runTest {
        val prevUuid = Uuid.random()
        val msgUuid = Uuid.random()
        val remotePayload = MessagePayload.Text(
            messageId = msgUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = prevUuid,
            lamportClock = 5L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "i am orphaned",
        )

        val result = dagEngine.ingest(remotePayload)

        assertTrue(result is IngestResult.BecameOrphan)
        assertEquals(prevUuid, result.missingPrevId)
        assertEquals(emptyList(), result.closedGapMissingPrevIds)
        assertTrue(messageRepo.findById(remotePayload.messageId)!!.isOrphaned)

        val gaps = dagEngine.openGaps(roomId)
        assertEquals(1, gaps.size)
        assertEquals(prevUuid, gaps[0].missingPrevId)
        assertEquals(msgUuid, gaps[0].orphanedMessageId)
    }

    @Test
    fun ingest_closesGapWhenMissingMessageArrives() = runTest {
        val prevUuid = Uuid.random()
        val msgUuid = Uuid.random()
        // 1. Ingest an orphan that references a missing prev.
        val orphan = MessagePayload.Text(
            messageId = msgUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = prevUuid,
            lamportClock = 5L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "waiting for prev",
        )
        assertTrue(dagEngine.ingest(orphan) is IngestResult.BecameOrphan)
        assertEquals(1, dagEngine.openGaps(roomId).size)
        assertTrue(messageRepo.findById(orphan.messageId)!!.isOrphaned)

        // 2. Ingest the previously-missing message (prevId = null â†’ not orphaned).
        val missing = MessagePayload.Text(
            messageId = prevUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = null,
            lamportClock = 4L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "i am the prev",
        )
        val missingResult = dagEngine.ingest(missing)

        // 3. The arrived message is Inserted; it closed the gap pointing at it.
        assertTrue(missingResult is IngestResult.Inserted)
        assertEquals(1, missingResult.closedGapMissingPrevIds.size)
        assertEquals(prevUuid, missingResult.closedGapMissingPrevIds[0])

        // Gap closed; orphan no longer flagged.
        assertEquals(0, dagEngine.openGaps(roomId).size)
        assertFalse(messageRepo.findById(orphan.messageId)!!.isOrphaned)
    }

    @Test
    fun ingest_closesMultipleGapsWaitingOnSameMessage() = runTest {
        val prevUuid = Uuid.random()
        val msg1Uuid = Uuid.random()
        val msg2Uuid = Uuid.random()
        // Two orphans, both waiting for "missing-prev-id".
        val orphan1 = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = prevUuid,
            lamportClock = 5L,
            createdAtEpochSeconds = 10L,
            text = "a",
        )
        val orphan2 = MessagePayload.Text(
            messageId = msg2Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = prevUuid,
            lamportClock = 6L,
            createdAtEpochSeconds = 11L,
            text = "b",
        )
        dagEngine.ingest(orphan1)
        dagEngine.ingest(orphan2)
        assertEquals(2, dagEngine.openGaps(roomId).size)

        val missing = MessagePayload.Text(
            messageId = prevUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = null,
            lamportClock = 4L,
            createdAtEpochSeconds = 9L,
            text = "the prev",
        )
        val result = dagEngine.ingest(missing)

        assertTrue(result is IngestResult.Inserted)
        assertEquals(2, result.closedGapMissingPrevIds.size)
        assertTrue(result.closedGapMissingPrevIds.all { it == prevUuid })

        assertEquals(0, dagEngine.openGaps(roomId).size)
        assertFalse(messageRepo.findById(orphan1.messageId)!!.isOrphaned)
        assertFalse(messageRepo.findById(orphan2.messageId)!!.isOrphaned)
    }

    @Test
    fun getMessagesInRoom_paginated_withCursor() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        val m2 = dagEngine.append(roomId, MessageDraft.Text("b"))
        val m3 = dagEngine.append(roomId, MessageDraft.Text("c"))

        // First page of 2 (newest first).
        val page1 = dagEngine.getMessagesInRoom(roomId, limit = 2)
        assertEquals(2, page1.size)
        assertEquals(m3.messageId, page1[0].messageId)
        assertEquals(m2.messageId, page1[1].messageId)

        // Cursor = oldest row in page1.
        val cursor = MessageCursor(
            createdAtEpochSeconds = page1[1].createdAtEpochSeconds,
            lamportClock = page1[1].lamportClock,
            messageId = page1[1].messageId,
        )
        val page2 = dagEngine.getMessagesInRoom(roomId, limit = 2, before = cursor)
        assertEquals(1, page2.size)
        assertEquals(m1.messageId, page2[0].messageId)
    }

    @Test
    fun getMessagesInRoom_emptyPagination_returnsEmpty() = runTest {
        val result = dagEngine.getMessagesInRoom(roomId, limit = 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun ancestorsOf_walksPrevIdChain() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        val m2 = dagEngine.append(roomId, MessageDraft.Text("b"))
        val m3 = dagEngine.append(roomId, MessageDraft.Text("c"))

        val ancestors = dagEngine.ancestorsOf(roomId, m3.messageId, limit = 10)

        assertEquals(2, ancestors.size)
        assertEquals(m2.messageId, ancestors[0].messageId)
        assertEquals(m1.messageId, ancestors[1].messageId)
    }

    @Test
    fun ancestorsOf_returnsEmptyForRootMessage() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        val ancestors = dagEngine.ancestorsOf(roomId, m1.messageId, limit = 10)
        assertTrue(ancestors.isEmpty())
    }

    @Test
    fun ancestorsOf_stopsAtLimit() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        dagEngine.append(roomId, MessageDraft.Text("b"))
        val m3 = dagEngine.append(roomId, MessageDraft.Text("c"))

        val ancestors = dagEngine.ancestorsOf(roomId, m3.messageId, limit = 1)
        assertEquals(1, ancestors.size)
    }

    @Test
    fun openGaps_byRoom_filtersToRoom() = runTest {
        val prev1Uuid = Uuid.random()
        val prev2Uuid = Uuid.random()
        val msg1Uuid = Uuid.random()
        val msg2Uuid = Uuid.random()
        // Orphan in roomId.
        val orphan1 = MessagePayload.Text(
            messageId = msg1Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = prev1Uuid,
            lamportClock = 1L,
            createdAtEpochSeconds = 0L,
            text = "x",
        )
        dagEngine.ingest(orphan1)

        // Orphan in a second room.
        val otherRoom = "other-room"
        val orphan2 = MessagePayload.Text(
            messageId = msg2Uuid,
            roomId = otherRoom,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = prev2Uuid,
            lamportClock = 1L,
            createdAtEpochSeconds = 0L,
            text = "y",
        )
        dagEngine.ingest(orphan2)

        val gapsInRoom = dagEngine.openGaps(roomId)
        assertEquals(1, gapsInRoom.size)
        assertEquals(msg1Uuid, gapsInRoom[0].orphanedMessageId)

        val gapsInOther = dagEngine.openGaps(otherRoom)
        assertEquals(1, gapsInOther.size)
        assertEquals(msg2Uuid, gapsInOther[0].orphanedMessageId)

        val allGaps = dagEngine.openGaps()
        assertEquals(2, allGaps.size)
    }

    @Test
    fun append_globalEventDraft_buildsGlobalEventPayload() = runTest {
        val payload = dagEngine.append(roomId, MessageDraft.GlobalEvent(byteArrayOf(0x01, 0x02)))

        assertTrue(payload is MessagePayload.GlobalEvent)
        assertEquals(roomId, payload.roomId)
        assertContentEquals(byteArrayOf(0x01, 0x02), payload.eventBytes)
        assertEquals(0L, payload.lamportClock)
    }

    @Test
    fun ingest_invalidSignature_returnsNull_doesNotInsert() = runTest {
        val msgUuid = Uuid.random()
        val remotePayload = MessagePayload.Text(
            messageId = msgUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevId = null,
            lamportClock = 1L,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            text = "should be rejected",
        )

        // Replace with a rejecting signature provider
        val rejectingEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomRepo,
            identityResolver = identityResolver,
            signatureProvider = FakeRejectingSignatureProvider(),
            timeProvider = timeProvider,
        )

        val result = rejectingEngine.ingest(remotePayload)

        assertNull(result)
        assertNull(messageRepo.findById(msgUuid))
    }

    @Test
    fun append_setsAuthorDeviceIdAndSignature() = runTest {
        val payload = dagEngine.append(roomId, MessageDraft.Text("hello"))

        assertEquals(testDeviceId, payload.authorDeviceId)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), payload.authorSignature)
    }
}
