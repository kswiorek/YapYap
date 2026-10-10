package org.yapyap.orchestrator.dag

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.VerificationState
import org.yapyap.persistence.messaging.MessageCursor
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.envelopes.GlobalEventPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RoomEventPayload
import org.yapyap.testfixtures.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
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
    private lateinit var clock: FakeClock

    private val testAccount = AccountId("dag-sender-account")
    private val remoteAccount = AccountId("dag-remote-account")
    private val testDeviceId = PeerId("test-device-id")
    private val remoteDeviceId = PeerId("remote-device-id")
    private val roomId = RoomId(Uuid.random())

    @BeforeTest
    fun setup() {
        messageRepo = FakeMessageRepository()
        causalHoldRepo = FakeCausalHoldRepository(messageRepo)
        roomRepo = FakeRoomRepository()
        identityResolver = FakeIdentityResolver(testAccount, testDeviceId)
        signatureProvider = FakeSignatureProvider()
        clock = FakeClock(epochSeconds(1_000_000L))
        dagEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomRepo,
            identityResolver = identityResolver,
            signatureProvider = signatureProvider,
            cryptoProvider = DefaultCryptoProvider(),
            clock = clock,
        )
    }

    @Test
    fun append_emptyRoom_assignsEmptyPrevIds() = runTest {
        val payload = dagEngine.append(roomId, MessageDraft.Text("first"))

        assertEquals(emptyList(), payload.prevIds)
        assertEquals(roomId, payload.roomId)
        assertEquals(testAccount, payload.senderAccountId)
        assertEquals("first", (payload as MessagePayload.Text).text)
        assertEquals(clock.now(), payload.createdAt)
        assertFalse(messageRepo.findById(payload.messageId)!!.isOrphaned)
    }

    @Test
    fun append_chainsOffRoomFrontier() = runTest {
        val first = dagEngine.append(roomId, MessageDraft.Text("first"))
        clock.advanceBy(1L.seconds)
        val second = dagEngine.append(roomId, MessageDraft.Text("second"))

        assertEquals(listOf(first.messageId), second.prevIds)
    }

    @Test
    fun append_concurrentMessagesFromSameSender_chainLinearly() = runTest {
        val a = dagEngine.append(roomId, MessageDraft.Text("a"))
        val b = dagEngine.append(roomId, MessageDraft.Text("b"))
        val c = dagEngine.append(roomId, MessageDraft.Text("c"))

        assertEquals(listOf(a.messageId), b.prevIds)
        assertEquals(listOf(b.messageId), c.prevIds)
    }

    @Test
    fun append_parkedFrontier_throwsFrontierUnavailable_andWritesNothing() = runTest {
        val orphan = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = listOf(Uuid.random()),
            createdAt = clock.now(),
            text = "waiting for prev",
        )
        assertTrue(dagEngine.ingest(orphan) is IngestResult.BecameOrphan)
        assertEquals(1, messageRepo.byId.size)

        val failure = assertFailsWith<DagException.FrontierUnavailable> {
            dagEngine.append(roomId, MessageDraft.Text("blocked"))
        }
        assertEquals(roomId, failure.roomId)
        assertEquals(1, messageRepo.byId.size)
    }

    @Test
    fun append_rejectedOnlyRoom_throwsFrontierUnavailable() = runTest {
        messageRepo.insert(
            payload = MessagePayload.Text(
                messageId = Uuid.random(),
                roomId = roomId,
                senderAccountId = remoteAccount,
                authorDeviceId = remoteDeviceId,
                authorSignature = byteArrayOf(0x01, 0x02, 0x03),
                prevIds = emptyList(),
                createdAt = clock.now(),
                text = "forged",
            ),
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.REJECTED,
        )

        assertFailsWith<DagException.FrontierUnavailable> {
            dagEngine.append(roomId, MessageDraft.Text("blocked"))
        }
        assertEquals(1, messageRepo.byId.size)
    }

    @Test
    fun append_afterGapCloses_chainsOffRecoveredFrontier() = runTest {
        // Bootstrap from nothing: the missing parent must be a valid genesis —
        // a remote Text root is REJECTED and quarantines its children.
        val crypto = DefaultCryptoProvider()
        val genesisId = Uuid.random()
        // createGenesis returns unsigned; the fake provider accepts any signature.
        val genesis = MessagePayload.RoomEvent.createGenesis(
            crypto = crypto,
            genesisMessageId = genesisId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            createdAt = clock.now(),
            event = RoomEventPayload.RoomCreated(
                initialMemberIds = listOf(remoteAccount),
                roomName = "test-room",
                roomType = RoomType.TEXT_CHANNEL,
                spaceId = null,
            ),
        ).withSignature(byteArrayOf(0x01, 0x02, 0x03))
        val genesisRoom = genesis.roomId
        val orphan = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = genesisRoom,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = listOf(genesisId),
            createdAt = clock.now(),
            text = "waiting for prev",
        )
        assertTrue(dagEngine.ingest(orphan) is IngestResult.BecameOrphan)
        assertFailsWith<DagException.FrontierUnavailable> {
            dagEngine.append(genesisRoom, MessageDraft.Text("blocked"))
        }

        assertTrue(dagEngine.ingest(genesis) is IngestResult.Inserted)

        val appended = dagEngine.append(genesisRoom, MessageDraft.Text("unblocked"))
        assertEquals(listOf(orphan.messageId), appended.prevIds)
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
            prevIds = listOf(first.messageId),
            createdAt = clock.now(),
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
            prevIds = listOf(prevUuid),
            createdAt = clock.now(),
            text = "i am orphaned",
        )

        val result = dagEngine.ingest(remotePayload)

        assertTrue(result is IngestResult.BecameOrphan)
        assertEquals(listOf(prevUuid), result.missingPrevIds)
        assertEquals(emptyList(), result.closedGapMissingPrevIds)
        assertTrue(messageRepo.findById(remotePayload.messageId)!!.isOrphaned)

        val gaps = causalHoldRepo.findByRoom(roomId)
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
            prevIds = listOf(prevUuid),
            createdAt = clock.now(),
            text = "waiting for prev",
        )
        assertTrue(dagEngine.ingest(orphan) is IngestResult.BecameOrphan)
        assertEquals(1, causalHoldRepo.findByRoom(roomId).size)
        assertTrue(messageRepo.findById(orphan.messageId)!!.isOrphaned)

        // 2. Ingest the previously-missing message (prevId = null â†’ not orphaned).
        val missing = MessagePayload.Text(
            messageId = prevUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = emptyList(),
            createdAt = clock.now(),
            text = "i am the prev",
        )
        val missingResult = dagEngine.ingest(missing)

        // 3. The arrived message is Inserted; it closed the gap pointing at it.
        assertTrue(missingResult is IngestResult.Inserted)
        assertEquals(1, missingResult.closedGapMissingPrevIds.size)
        assertEquals(prevUuid, missingResult.closedGapMissingPrevIds[0])

        // Gap closed; orphan no longer flagged.
        assertEquals(0, causalHoldRepo.findByRoom(roomId).size)
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
            prevIds = listOf(prevUuid),
            createdAt = epochSeconds(10L),
            text = "a",
        )
        val orphan2 = MessagePayload.Text(
            messageId = msg2Uuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = listOf(prevUuid),
            createdAt = epochSeconds(11L),
            text = "b",
        )
        dagEngine.ingest(orphan1)
        dagEngine.ingest(orphan2)
        assertEquals(2, causalHoldRepo.findByRoom(roomId).size)

        val missing = MessagePayload.Text(
            messageId = prevUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = emptyList(),
            createdAt = epochSeconds(9L),
            text = "the prev",
        )
        val result = dagEngine.ingest(missing)

        assertTrue(result is IngestResult.Inserted)
        assertEquals(2, result.closedGapMissingPrevIds.size)
        assertTrue(result.closedGapMissingPrevIds.all { it == prevUuid })

        assertEquals(0, causalHoldRepo.findByRoom(roomId).size)
        assertFalse(messageRepo.findById(orphan1.messageId)!!.isOrphaned)
        assertFalse(messageRepo.findById(orphan2.messageId)!!.isOrphaned)
    }

    @Test
    fun getMessagesInRoom_paginated_withCursor() = runTest {
        val m1 = dagEngine.append(roomId, MessageDraft.Text("a"))
        clock.advanceBy(1L.seconds)
        val m2 = dagEngine.append(roomId, MessageDraft.Text("b"))
        clock.advanceBy(1L.seconds)
        val m3 = dagEngine.append(roomId, MessageDraft.Text("c"))

        // First page of 2 (newest first).
        val page1 = messageRepo.findMessagesInRoomPageDesc(roomId, limit = 2, cursor = null)
            .map { it.payload }
        assertEquals(2, page1.size)
        assertEquals(m3.messageId, page1[0].messageId)
        assertEquals(m2.messageId, page1[1].messageId)

        // Cursor = oldest row in page1.
        val cursor = MessageCursor(
            createdAt = page1[1].createdAt,
            messageId = page1[1].messageId,
        )
        val page2 = messageRepo.findMessagesInRoomPageDesc(roomId, limit = 2, cursor = cursor)
            .map { it.payload }
        assertEquals(1, page2.size)
        assertEquals(m1.messageId, page2[0].messageId)
    }

    @Test
    fun getMessagesInRoom_emptyPagination_returnsEmpty() = runTest {
        val result = messageRepo.findMessagesInRoomPageDesc(roomId, limit = 10, cursor = null)
        assertTrue(result.isEmpty())
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
            prevIds = listOf(prev1Uuid),
            createdAt = epochSeconds(0L),
            text = "x",
        )
        dagEngine.ingest(orphan1)

        // Orphan in a second room.
        val otherRoom = RoomId(Uuid.random())
        val orphan2 = MessagePayload.Text(
            messageId = msg2Uuid,
            roomId = otherRoom,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = listOf(prev2Uuid),
            createdAt = epochSeconds(0L),
            text = "y",
        )
        dagEngine.ingest(orphan2)

        val gapsInRoom = causalHoldRepo.findByRoom(roomId)
        assertEquals(1, gapsInRoom.size)
        assertEquals(msg1Uuid, gapsInRoom[0].orphanedMessageId)

        val gapsInOther = causalHoldRepo.findByRoom(otherRoom)
        assertEquals(1, gapsInOther.size)
        assertEquals(msg2Uuid, gapsInOther[0].orphanedMessageId)

        val allGaps = causalHoldRepo.findAll()
        assertEquals(2, allGaps.size)
    }

    @Test
    fun append_globalEventDraft_buildsGlobalEventPayload() = runTest {
        val event = GlobalEventPayload.RemoveDevice(targetDeviceId = remoteDeviceId)
        val payload = dagEngine.append(roomId, MessageDraft.GlobalEvent(event))

        assertTrue(payload is MessagePayload.GlobalEvent)
        assertEquals(RoomId.GLOBAL, payload.roomId)
        assertContentEquals(event.encode(), payload.eventBytes)
        assertEquals(event, payload.decodeEvent())
    }

    @Test
    fun ingest_invalidSignature_storesMessageAsRejected() = runTest {
        val msgUuid = Uuid.random()
        val remotePayload = MessagePayload.Text(
            messageId = msgUuid,
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = listOf(Uuid.random()),
            createdAt = clock.now(),
            text = "should be rejected",
        )

        // Replace with a rejecting signature provider
        val rejectingEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomRepo,
            identityResolver = identityResolver,
            signatureProvider = FakeRejectingSignatureProvider(),
            cryptoProvider = DefaultCryptoProvider(),
            clock = clock,
        )

        val result = rejectingEngine.ingest(remotePayload)

        // A message that fails authorship verification is still stored (so the DAG structure is
        // preserved and sync loops are avoided) but is marked REJECTED.
        val ingested = result as IngestResult.BecameOrphan
        assertEquals(VerificationState.REJECTED, ingested.verificationState)

        val stored = messageRepo.findById(msgUuid)
        assertNotNull(stored)
        assertEquals(VerificationState.REJECTED, stored.verificationState)
    }

    @Test
    fun append_setsAuthorDeviceIdAndSignature() = runTest {
        val payload = dagEngine.append(roomId, MessageDraft.Text("hello"))

        assertEquals(testDeviceId, payload.authorDeviceId)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), payload.authorSignature)
    }

    private fun textPayload(text: String = "pending") = MessagePayload.Text(
        messageId = Uuid.random(),
        roomId = roomId,
        senderAccountId = remoteAccount,
        authorDeviceId = remoteDeviceId,
        authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        prevIds = listOf(Uuid.random()),
        createdAt = clock.now(),
        text = text,
    )

    @Test
    fun reverifyPendingFor_knownAuthor_transitionsPendingToVerified() = runTest {
        val payload = textPayload()
        messageRepo.insert(
            payload,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.PENDING
        )

        val results = dagEngine.reverifyPendingFor(remoteDeviceId)

        val resolved = results.single()
        assertEquals(payload.messageId, resolved.messageId)
        assertEquals(VerificationState.PENDING, resolved.fromState)
        assertEquals(VerificationState.VERIFIED, resolved.toState)
        assertEquals(VerificationState.VERIFIED, messageRepo.findById(payload.messageId)!!.verificationState)
    }

    @Test
    fun reverifyPendingFor_unknownAuthor_keepsPending_emitsNothing() = runTest {
        val unknownEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomRepo,
            identityResolver = identityResolver,
            signatureProvider = FakeUnknownAuthorSignatureProvider(),
            cryptoProvider = DefaultCryptoProvider(),
            clock = clock,
        )
        val payload = textPayload()
        messageRepo.insert(
            payload,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.PENDING
        )

        val results = unknownEngine.reverifyPendingFor(remoteDeviceId)

        assertTrue(results.isEmpty())
        assertEquals(VerificationState.PENDING, messageRepo.findById(payload.messageId)!!.verificationState)
    }

    @Test
    fun reverifyPendingFor_roomEvent_transitionsPendingToVerified() = runTest {
        // Regression (docs/room events.md §9): the reverify hooks cover
        // RoomEvent uniformly with Text — unchanged behavior, and the room
        // projector writes no verdicts (no reverify ping-pong).
        val payload = MessagePayload.RoomEvent(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            prevIds = listOf(Uuid.random()),
            createdAt = clock.now(),
            eventBytes = RoomEventPayload.MemberAdd(remoteAccount).encode(),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )
        messageRepo.insert(
            payload,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.PENDING
        )

        val results = dagEngine.reverifyPendingFor(remoteDeviceId)

        val resolved = results.single()
        assertEquals(payload.messageId, resolved.messageId)
        assertEquals(VerificationState.PENDING, resolved.fromState)
        assertEquals(VerificationState.VERIFIED, resolved.toState)
        assertEquals(VerificationState.VERIFIED, messageRepo.findById(payload.messageId)!!.verificationState)
    }

    @Test
    fun reverifyAllPending_noPending_returnsEmpty() = runTest {
        assertTrue(dagEngine.reverifyAllPending().isEmpty())
    }

    @Test
    fun ingest_textWithEmptyPrevIdsInChatRoom_storesAsRejected() = runTest {
        val payload = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = emptyList(),
            createdAt = clock.now(),
            text = "forged genesis",
        )

        val result = dagEngine.ingest(payload)

        val ingested = result as IngestResult.Inserted
        assertEquals(VerificationState.REJECTED, ingested.verificationState)
        assertEquals(VerificationState.REJECTED, messageRepo.findById(payload.messageId)!!.verificationState)
    }

    @Test
    fun ingest_roomEventNonGenesisWithEmptyPrevIds_storesAsRejected() = runTest {
        val payload = MessagePayload.RoomEvent(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            prevIds = emptyList(),
            createdAt = clock.now(),
            eventBytes = RoomEventPayload.MemberAdd(remoteAccount).encode(),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )

        val result = dagEngine.ingest(payload)

        val ingested = result as IngestResult.Inserted
        assertEquals(VerificationState.REJECTED, ingested.verificationState)
        assertEquals(VerificationState.REJECTED, messageRepo.findById(payload.messageId)!!.verificationState)
    }

    @Test
    fun ingest_roomEventUndecodableBytes_storesAsRejected() = runTest {
        val payload = MessagePayload.RoomEvent(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            prevIds = emptyList(),
            createdAt = clock.now(),
            eventBytes = byteArrayOf(0x09),
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
        )

        val result = dagEngine.ingest(payload)

        val ingested = result as IngestResult.Inserted
        assertEquals(VerificationState.REJECTED, ingested.verificationState)
        assertEquals(VerificationState.REJECTED, messageRepo.findById(payload.messageId)!!.verificationState)
    }

    @Test
    fun ingest_honestRoomCreatedGenesisFromUnknownAuthor_staysPending() = runTest {
        val crypto = DefaultCryptoProvider()
        val unknownEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomRepo,
            identityResolver = identityResolver,
            signatureProvider = FakeUnknownAuthorSignatureProvider(),
            cryptoProvider = crypto,
            clock = clock,
        )
        val genesis = MessagePayload.RoomEvent.createGenesis(
            crypto = crypto,
            genesisMessageId = Uuid.random(),
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            createdAt = clock.now(),
            event = RoomEventPayload.RoomCreated(
                initialMemberIds = listOf(remoteAccount),
                roomName = "test-room",
                roomType = RoomType.TEXT_CHANNEL,
                spaceId = null,
            ),
        )

        val result = unknownEngine.ingest(genesis)

        val ingested = result as IngestResult.Inserted
        assertEquals(VerificationState.PENDING, ingested.verificationState)
        assertEquals(VerificationState.PENDING, messageRepo.findById(genesis.messageId)!!.verificationState)
    }

    @Test
    fun ingest_genesisWithMismatchedRoomId_storesAsRejected() = runTest {
        val crypto = DefaultCryptoProvider()
        val genesis = MessagePayload.RoomEvent.createGenesis(
            crypto = crypto,
            genesisMessageId = Uuid.random(),
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            createdAt = clock.now(),
            event = RoomEventPayload.RoomCreated(
                initialMemberIds = listOf(remoteAccount),
                roomName = "test-room",
                roomType = RoomType.TEXT_CHANNEL,
                spaceId = null,
            ),
        ).copy(roomId = RoomId(Uuid.random()))

        val result = dagEngine.ingest(genesis)

        val ingested = result as IngestResult.Inserted
        assertEquals(VerificationState.REJECTED, ingested.verificationState)
        assertEquals(VerificationState.REJECTED, messageRepo.findById(genesis.messageId)!!.verificationState)
    }

    @Test
    fun ingest_roomCreatedWithNonEmptyPrevIds_storesAsRejected() = runTest {
        val crypto = DefaultCryptoProvider()
        val genesis = MessagePayload.RoomEvent.createGenesis(
            crypto = crypto,
            genesisMessageId = Uuid.random(),
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            createdAt = clock.now(),
            event = RoomEventPayload.RoomCreated(
                initialMemberIds = listOf(remoteAccount),
                roomName = "test-room",
                roomType = RoomType.TEXT_CHANNEL,
                spaceId = null,
            ),
        ).copy(prevIds = listOf(Uuid.random()))

        val result = dagEngine.ingest(genesis)

        val ingested = result as IngestResult.BecameOrphan
        assertEquals(VerificationState.REJECTED, ingested.verificationState)
        assertEquals(VerificationState.REJECTED, messageRepo.findById(genesis.messageId)!!.verificationState)
    }

    @Test
    fun ingest_unknownRoom_seedsRoomRow() = runTest {
        val freshRoom = RoomId(Uuid.random())
        val orphan = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = freshRoom,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = listOf(Uuid.random()),
            createdAt = clock.now(),
            text = "pre-genesis",
        )

        assertTrue(dagEngine.ingest(orphan) is IngestResult.BecameOrphan)
        assertTrue(freshRoom in roomRepo.roomsOfPeer(testDeviceId))
    }

    @Test
    fun ingest_globalRoom_doesNotSeedRoomRow() = runTest {
        val payload = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = RoomId.GLOBAL,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDeviceId,
            authorSignature = byteArrayOf(0x01, 0x02, 0x03),
            prevIds = listOf(Uuid.random()),
            createdAt = clock.now(),
            text = "global",
        )

        dagEngine.ingest(payload)

        assertTrue(RoomId.GLOBAL !in roomRepo.roomsOfPeer(testDeviceId))
    }
}
