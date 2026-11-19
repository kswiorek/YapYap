@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.*
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.*
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.FileEvent
import org.yapyap.routing.router.RouterConfig
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.NoopFileCarrier
import org.yapyap.testfixtures.NoopRouter
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Step 16, orchestrator side: the REQUEST trigger, the serve gate and candidate order,
 * re-requestable vs refused states, and accounting through [FileEvent]s.
 */
class FileRequestCoordinatorTest {

    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val senderAccount = AccountId("coordinator-sender")
    private val ownAccount = AccountId("coordinator-own")
    private val strangerAccount = AccountId("coordinator-stranger")
    private val roomId = RoomId(Uuid.random())
    private val senderDevice = PeerId("coordinator-sender-device")
    private val now = epochSeconds(10_000L)

    private class Harness(
        val pending: PendingFileRequestStore,
        val receives: FileReceiveStore,
        val transfers: FileTransferStore,
        val descriptors: FileDescriptorStore,
        val messages: FakeMessageRepository,
        val rooms: FakeRoomRepository,
        val router: RecordingFileRouter,
        val pipeline: FakePipeline,
        val coordinator: DefaultFileRequestCoordinator,
    )

    private fun harness(
        memberships: Map<Pair<RoomId, AccountId>, FakeRoomRepository.Member> = emptyMap(),
    ): Harness {
        val db = openMemoryDatabase().database
        val clock = FakeClock(now)
        val receives = DefaultFileReceiveStore(db)
        val transfers = DefaultFileTransferStore(db)
        val descriptors = DefaultFileDescriptorStore(db)
        val messages = FakeMessageRepository()
        val rooms = FakeRoomRepository(memberships)
        val router = RecordingFileRouter()
        val pipeline = FakePipeline()
        val files = org.yapyap.orchestrator.file.io.JvmFileAccess()
        val work = Path(SystemTemporaryDirectory.toString(), "yapyap-coordtest-${Uuid.random()}")
        files.createDirectories(work)
        val crypto = DefaultCryptoProvider()
        val config = FileServiceConfig()
        val store = DefaultFileStore(descriptors, crypto, files, Path(work.toString(), "store"), config, clock)
        val engine = DefaultFileReceiveEngine(
            receives = receives,
            descriptors = descriptors,
            messages = messages,
            crypto = crypto,
            files = files,
            storeDirectory = Path(work.toString(), "store"),
            incomingDirectory = Path(work.toString(), "incoming"),
            config = config,
            fileStore = store,
            carrier = router.fileCarrier,
            routerConfig = MutableStateFlow(RouterConfig()),
            clock = clock,
        )
        val coordinator = DefaultFileRequestCoordinator(
            pending = DefaultPendingFileRequestStore(db),
            receives = receives,
            transfers = transfers,
            messages = messages,
            rooms = rooms,
            descriptors = descriptors,
            localIdentity = FakeLocalIdentity(ownAccount),
            router = router,
            pipeline = pipeline,
            engine = engine,
            config = MutableStateFlow(OrchestratorConfig()),
            routerConfig = MutableStateFlow(RouterConfig()),
            clock = clock,
        )
        return Harness(
            DefaultPendingFileRequestStore(db), receives, transfers, descriptors,
            messages, rooms, router, pipeline, coordinator,
        )
    }

    private fun textMessage(
        messageId: Uuid,
        sender: AccountId,
        room: RoomId = roomId,
        attachments: List<MessagePayload.Attachment> = listOf(
            MessagePayload.Attachment(fileId, "img.jpg", "image/jpeg", 1_000L)
        ),
    ) = MessagePayload.Text(
        messageId = messageId,
        roomId = room,
        senderAccountId = sender,
        authorDeviceId = senderDevice,
        prevIds = emptyList(),
        createdAt = now,
        text = "look",
        attachments = attachments,
    )

    @Test
    fun messageIngest_withAttachment_opensPendingAndStartsRequest() = runTest {
        val h = harness()
        val mid = Uuid.random()
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.rooms.members[roomId to senderAccount] = FakeRoomRepository.Member(RoomMemberStatus.ACTIVE, null)

        h.coordinator.start(this)
        h.pipeline.emit(IngestResult.Inserted(textMessage(mid, senderAccount)))
        advanceTimeBy(1_000)
        runCurrent()

        val pending = h.pending.find(fileId)
        assertNotNull(pending, "trigger opens a pending row")
        assertEquals(PendingFileRequestStatus.PENDING, pending.status)
        assertEquals(1, h.router.startedFiles.size)
        assertEquals(fileId, h.router.startedFiles.single().first)
        assertEquals(now + 60.seconds, h.router.startedFiles.single().second)
    }

    @Test
    fun readyFile_doesNotTrigger() = runTest {
        val h = harness()
        val mid = Uuid.random()
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.READY, totalBytes = 1_000L,
                contiguousBytes = 1_000L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                partPath = "p", finalPath = "f", originMessageId = mid,
                createdAt = now, updatedAt = now,
            )
        )
        h.coordinator.start(this)
        h.pipeline.emit(IngestResult.Inserted(textMessage(mid, senderAccount)))
        advanceUntilIdle()
        assertNull(h.pending.find(fileId))
        assertTrue(h.router.startedFiles.isEmpty())
    }

    @Test
    fun largeAttachment_isSkipped() = runTest {
        val h = harness()
        val mid = Uuid.random()
        val big = MessagePayload.Attachment(fileId, "big.bin", null, 100L * 1024 * 1024)
        val payload = textMessage(mid, senderAccount).copy(attachments = listOf(big))
        h.messages.rows[mid] = StoredMessage(payload, isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.coordinator.start(this)
        h.pipeline.emit(IngestResult.Inserted(payload))
        advanceUntilIdle()
        assertNull(h.pending.find(fileId))
        assertTrue(h.router.startedFiles.isEmpty())
    }

    @Test
    fun satisfiedAndReady_dropPending() = runTest {
        val h = harness()
        h.pending.upsertPending(fileId, now, now)
        h.coordinator.start(this)
        advanceUntilIdle()
        h.router.events.emit(FileEvent.RequestSatisfied(fileId))
        advanceUntilIdle()
        assertNull(h.pending.find(fileId))

        h.pending.upsertPending(fileId, now, now)
        h.router.events.emit(FileEvent.Ready(fileId))
        advanceUntilIdle()
        assertNull(h.pending.find(fileId))
    }

    @Test
    fun unavailableMarked_declinedTerminalUntilRearmed() = runTest {
        val h = harness()
        h.pending.upsertPending(fileId, now, now)
        h.coordinator.start(this)
        advanceUntilIdle()
        h.router.events.emit(FileEvent.RequestUnavailable(fileId))
        advanceUntilIdle()
        assertEquals(PendingFileRequestStatus.UNAVAILABLE, h.pending.find(fileId)?.status)

        // A new message referencing the file re-arms the trigger.
        val mid = Uuid.random()
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.pipeline.emit(IngestResult.Inserted(textMessage(mid, senderAccount)))
        advanceUntilIdle()
        assertEquals(PendingFileRequestStatus.PENDING, h.pending.find(fileId)?.status)
        assertTrue(h.router.startedFiles.isNotEmpty())
    }

    @Test
    fun stalledReceive_reopensRequestOnSweep() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.STALLED, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 1,
                partPath = "p", finalPath = "f", originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.coordinator.start(this)
        advanceUntilIdle()
        assertNull(h.pending.find(fileId), "no trigger before the sweep fires")
        advanceTimeBy(61.seconds)
        advanceUntilIdle()
        assertNotNull(h.pending.find(fileId), "stalled receive re-opens the request")
        assertTrue(h.router.startedFiles.isNotEmpty())
    }

    @Test
    fun serveGate_allowsActiveRefusesStranger() = runTest {
        val h = harness()
        upsertHeld(h, totalBytes = 1_000L)
        val mid = Uuid.random()
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.rooms.members[roomId to senderAccount] = FakeRoomRepository.Member(RoomMemberStatus.ACTIVE, null)

        assertTrue(h.coordinator.canServe(fileId, senderAccount))
        assertFalse(h.coordinator.canServe(fileId, strangerAccount), "never-member is refused")
    }

    @Test
    fun serveGate_removedMemberSeesOnlyEra() = runTest {
        val h = harness()
        upsertHeld(h, totalBytes = 1_000L)
        val removal = Uuid.random()
        val eraMessage = Uuid.random()
        val afterMessage = Uuid.random()
        h.messages.rows[eraMessage] = StoredMessage(textMessage(eraMessage, senderAccount), isOrphaned = false)
        h.messages.rows[afterMessage] = StoredMessage(textMessage(afterMessage, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(eraMessage, afterMessage)
        h.messages.parents[removal] = listOf(eraMessage)
        h.rooms.members[roomId to senderAccount] =
            FakeRoomRepository.Member(RoomMemberStatus.REMOVED, removal)

        // Era file (ancestor of the removal node): visible.
        h.messages.attachments[fileId] = listOf(eraMessage)
        assertTrue(h.coordinator.canServe(fileId, senderAccount))
        // Post-removal file: not visible.
        h.messages.attachments[fileId] = listOf(afterMessage)
        assertFalse(h.coordinator.canServe(fileId, senderAccount))
        // The removal node itself names a message: visible.
        h.messages.rows[removal] = StoredMessage(textMessage(removal, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(removal)
        assertTrue(h.coordinator.canServe(fileId, senderAccount))
    }

    @Test
    fun serveGate_rejectsLargeAndMissing() = runTest {
        val h = harness()
        upsertHeld(h, totalBytes = 100L * 1024 * 1024)
        val mid = Uuid.random()
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.rooms.members[roomId to senderAccount] = FakeRoomRepository.Member(RoomMemberStatus.ACTIVE, null)
        assertFalse(h.coordinator.canServe(fileId, senderAccount), "large files are pull-only")

        assertFalse(h.coordinator.canServe(FileId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), senderAccount))
    }

    @Test
    fun requestCandidates_sendersFirstOwnLast() = runTest {
        val h = harness()
        val other = AccountId("coordinator-other")
        val m1 = Uuid.random()
        val m2 = Uuid.random()
        h.messages.rows[m1] = StoredMessage(textMessage(m1, senderAccount), isOrphaned = false)
        h.messages.rows[m2] = StoredMessage(textMessage(m2, other), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(m1, m2)
        assertEquals(listOf(senderAccount, other, ownAccount), h.coordinator.requestCandidates(fileId))
    }

    @Test
    fun outgoingComplete_marksSendCompleted() = runTest {
        val h = harness()
        h.transfers.upsert(
            FileTransfer(
                fileId = fileId, peerAccountId = senderAccount,
                direction = FileTransferDirection.SEND, klass = FileTransferClass.SMALL,
                status = FileTransferStatus.IN_FLIGHT, createdAt = now, updatedAt = now,
                expiresAt = now + 2_000_000.seconds,
            )
        )
        h.coordinator.start(this)
        advanceUntilIdle()
        h.router.events.emit(FileEvent.OutgoingComplete(fileId, senderAccount))
        advanceUntilIdle()
        assertEquals(FileTransferStatus.COMPLETED, h.transfers.find(fileId, senderAccount)?.status)
    }

    @Test
    fun serveStarted_upsertsServeRow() = runTest {
        val h = harness()
        h.coordinator.start(this)
        advanceUntilIdle()
        h.router.events.emit(FileEvent.ServeStarted(fileId, senderAccount))
        advanceUntilIdle()
        val row = h.transfers.find(fileId, senderAccount)
        assertNotNull(row)
        assertEquals(FileTransferDirection.SERVE, row.direction)
        assertEquals(FileTransferStatus.IN_FLIGHT, row.status)
    }

    @Test
    fun heldCoverage_reportsContiguousAndMissing() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = null, hashMismatches = 0,
                partPath = "p", finalPath = "f", originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.receives.stageChunk(fileId, StagedRange(100L, 50L, ByteArray(50)))
        h.receives.stageChunk(fileId, StagedRange(200L, 50L, ByteArray(50)))
        val coverage = assertNotNull(h.coordinator.heldCoverage(fileId))
        assertEquals(100L, coverage.contiguousBytes)
        assertEquals(1, coverage.missingRanges.size)
        assertEquals(150L, coverage.missingRanges.single().startBytes)
        assertEquals(50L, coverage.missingRanges.single().lengthBytes)
    }

    // -- Helpers ---------------------------------------------------------------

    private suspend fun upsertHeld(h: Harness, totalBytes: Long) {
        h.descriptors.upsert(
            FileDescriptor(
                fileId = fileId, fileName = "img.jpg", mimeType = "image/jpeg",
                totalBytes = totalBytes, blockSize = 1024 * 1024, blockHashes = emptyList(),
                storage = FileStorage.COPY, path = "/tmp/img.jpg",
                status = FileDescriptorStatus.HELD, createdAt = now,
            )
        )
    }

    private class RecordingFileRouter(
        val startedFiles: MutableList<Pair<FileId, kotlin.time.Instant>> = mutableListOf(),
        val events: MutableSharedFlow<FileEvent> = MutableSharedFlow(extraBufferCapacity = 64),
    ) : NoopRouter() {
        override val fileCarrier: org.yapyap.routing.file.FileCarrier =
            object : org.yapyap.routing.file.FileCarrier by NoopFileCarrier() {
                override val fileEvents: Flow<FileEvent> = events
            }

        override suspend fun startFileRequest(fileId: FileId, firstAttemptAt: kotlin.time.Instant) {
            startedFiles += fileId to firstAttemptAt
        }
    }

    private class FakeLocalIdentity(private val account: AccountId) : LocalIdentity {
        override suspend fun getLocalDeviceIdentity(): org.yapyap.protocol.identity.DeviceIdentity =
            TODO("not used")

        override suspend fun getLocalAccountIdentity(): org.yapyap.protocol.identity.AccountIdentity =
            TODO("not used")

        override suspend fun getLocalDeviceId(): PeerId = TODO("not used")
        override suspend fun getLocalAccountId(): AccountId = account
    }

    private class FakePipeline : InboundMessagePipeline {
        private val flow = MutableSharedFlow<IngestResult>(extraBufferCapacity = 64)
        override val ingestResults: Flow<IngestResult> = flow
        suspend fun emit(result: IngestResult) {
            flow.emit(result)
        }

        override fun start(scope: CoroutineScope) = Unit
    }

    private class FakeMessageRepository : MessageRepository {
        val rows = mutableMapOf<Uuid, StoredMessage>()
        val attachments = mutableMapOf<FileId, List<Uuid>>()
        val parents = mutableMapOf<Uuid, List<Uuid>>()

        override suspend fun insert(payload: MessagePayload, isOrphaned: Boolean, ancestryComplete: Boolean, verificationState: org.yapyap.orchestrator.dag.VerificationState) = TODO("not used")
        override suspend fun insertAttachment(messageId: Uuid, fileId: FileId) { TODO("not used") }
        override suspend fun findMessageIdsByFileId(fileId: FileId): List<Uuid> = attachments[fileId].orEmpty()
        override suspend fun findFileIdsByMessageId(messageId: Uuid): List<FileId> = TODO("not used")
        override suspend fun findById(messageId: Uuid): StoredMessage? = rows[messageId]
        override suspend fun findRoomFrontier(roomId: RoomId): List<StoredMessage> = TODO("not used")
        override suspend fun findParents(messageId: Uuid): List<Uuid> = parents[messageId].orEmpty()
        override suspend fun insertParent(messageId: Uuid, parentId: Uuid) = TODO("not used")
        override suspend fun findChildrenInRoom(parentId: Uuid, roomId: RoomId): List<StoredMessage> = TODO("not used")
        override suspend fun findMessagesInRoomPageDesc(roomId: RoomId, limit: Int, cursor: MessageCursor?): List<StoredMessage> = TODO("not used")
        override suspend fun renderableMessagesInRoom(roomId: RoomId, limit: Int, cursor: MessageCursor?): List<StoredMessage> = TODO("not used")
        override suspend fun isRenderable(roomId: RoomId, messageId: Uuid): Boolean = TODO("not used")
        override suspend fun findAllInRoom(roomId: RoomId): List<StoredMessage> = TODO("not used")
        override suspend fun findFoldableInRoom(roomId: RoomId): List<StoredMessage> = TODO("not used")
        override suspend fun hasMessages(roomId: RoomId): Boolean = TODO("not used")
        override suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean) = TODO("not used")
        override suspend fun updateAncestryComplete(messageId: Uuid, complete: Boolean) = TODO("not used")
        override suspend fun updateVerificationState(messageId: Uuid, state: org.yapyap.orchestrator.dag.VerificationState) = TODO("not used")
        override suspend fun findPendingByAuthor(deviceId: PeerId): List<StoredMessage> = TODO("not used")
        override suspend fun findAllPending(): List<StoredMessage> = TODO("not used")
    }

    private class FakeRoomRepository(
        memberships: Map<Pair<RoomId, AccountId>, Member> = emptyMap(),
    ) : RoomRepository {
        val members: MutableMap<Pair<RoomId, AccountId>, Member> = memberships.toMutableMap()
        data class Member(val status: RoomMemberStatus, val removalNodeId: Uuid?)

        override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> = TODO("not used")
        override suspend fun memberStatusesOfRoom(roomId: RoomId): List<RoomMember> = TODO("not used")
        override suspend fun memberRowOf(roomId: RoomId, accountId: AccountId): RoomMember? =
            members[roomId to accountId]?.let {
                RoomMember(accountId, RoomMemberRole.MEMBER, it.status, it.removalNodeId)
            }

        override suspend fun roomsOfPeer(peerId: PeerId): List<RoomId> = TODO("not used")
        override suspend fun roomsOfAccount(accountId: AccountId): List<RoomId> = TODO("not used")
        override suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String) = TODO("not used")
        override suspend fun upsertMember(roomId: RoomId, accountId: AccountId, role: RoomMemberRole, status: RoomMemberStatus, removalNodeId: Uuid?) = TODO("not used")
        override suspend fun removeMember(roomId: RoomId, accountId: AccountId) = TODO("not used")
        override suspend fun allChatRoomIds(): List<RoomId> = TODO("not used")
        override suspend fun roomOf(roomId: RoomId): Room? = TODO("not used")
        override suspend fun mergeRoomFromGenesis(roomId: RoomId, name: String, type: RoomType, spaceId: String?) = TODO("not used")
        override suspend fun removeRoomMembersNotIn(roomId: RoomId, keep: Collection<AccountId>) = TODO("not used")
    }
}
