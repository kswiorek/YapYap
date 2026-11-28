@file:OptIn(ExperimentalCoroutinesApi::class)
package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.VerificationState
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.receive.ReceiveEngine
import org.yapyap.orchestrator.file.receive.ReceivePaths
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.MessageCursor
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.Room
import org.yapyap.orchestrator.store.RoomMember
import org.yapyap.orchestrator.store.RoomRepository
import org.yapyap.orchestrator.store.StoredMessage
import org.yapyap.orchestrator.sync.DefaultRoomVisibility
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomMemberRole
import org.yapyap.protocol.RoomMemberStatus
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.FakeLocalIdentity
import org.yapyap.testfixtures.NoopFileCarrier
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

/**
 * Orchestrator side: the REQUEST trigger, the serve gate and candidate order,
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
        val descriptors: FileDescriptorStore,
        val messages: FakeMessageRepository,
        val rooms: FakeRoomRepository,
        val router: RecordingFileRouter,
        val pipeline: FakePipeline,
        val coordinator: DefaultFileRequestCoordinator,
        val receiveEvents: MutableSharedFlow<FileReceiveEvent>,
        val source: DefaultFileRequestSource,
        val timeSource: TestTimeSource,
    )

    private suspend fun harness(
        timeSource: TestTimeSource = TestTimeSource(),
        memberships: Map<Pair<RoomId, AccountId>, FakeRoomRepository.Member> = emptyMap(),
        orchestratorConfig: OrchestratorConfig = OrchestratorConfig(),
    ): Harness {
        val db = openMemoryDatabase().database
        val clock = FakeClock(now)
        // Unconfined: SQLDelight's blocking driver work runs eagerly instead of racing
        // the test scheduler (the production default is a single-threaded IO dispatcher).
        val io = UnconfinedTestDispatcher()
        val receives = DefaultFileReceiveStore(db, io)
        val descriptors = DefaultFileDescriptorStore(db, io)
        val messages = FakeMessageRepository()
        val rooms = FakeRoomRepository(memberships)
        val router = RecordingFileRouter()
        val pipeline = FakePipeline()
        val files = JvmFileAccess()
        val work = Path(SystemTemporaryDirectory.toString(), "yapyap-coordtest-${MessageId(Uuid.random())}")
        files.createDirectories(work)
        val crypto = DefaultCryptoProvider()
        val config = MutableStateFlow(orchestratorConfig)
        val store = DefaultFileStore(descriptors, crypto, files, Path(work.toString(), "store"), clock)
        val engine = ReceiveEngine(
            receives = receives,
            descriptors = descriptors,
            crypto = crypto,
            files = files,
            paths = ReceivePaths(Path(work.toString(), "incoming"), Path(work.toString(), "store")),
            config = config,
            fileStore = store,
            clock = clock,
        )
        val source = DefaultFileRequestSource(
            messages = messages,
            visibility = DefaultRoomVisibility(messages, rooms),
            receives = receives,
            descriptors = descriptors,
            localIdentity = FakeLocalIdentity(ownAccount),
            coverage = engine,
        )
        val receiveEvents = MutableSharedFlow<FileReceiveEvent>(extraBufferCapacity = 16)
        // timeSource passed in
        val coordinator = DefaultFileRequestCoordinator(
            pending = DefaultPendingFileRequestStore(db, io),
            receives = receives,
            descriptors = descriptors,
            control = engine,
            messages = messages,
            fileCarrier = router.files,
            pipeline = pipeline,
            receiveEvents = receiveEvents,
            config = config,
            clock = clock,
            timeSource = timeSource,
        )
        return Harness(
            DefaultPendingFileRequestStore(db, io), receives, descriptors,
            messages, rooms, router, pipeline, coordinator, receiveEvents, source, timeSource,
        )
    }

    private fun textMessage(
        messageId: MessageId,
        sender: AccountId,
        room: RoomId = roomId,
        attachments: List<DagPayload.Attachment> = listOf(
            DagPayload.Attachment(fileId, "img.jpg", "image/jpeg", 1_000L)
        ),
    ) = DagPayload.Text(
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
        val mid = MessageId(Uuid.random())
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.rooms.members[roomId to senderAccount] = FakeRoomRepository.Member(RoomMemberStatus.ACTIVE, null)

        h.coordinator.start(this)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.pipeline.emit(IngestResult.Inserted(textMessage(mid, senderAccount)))
        advanceTimeBy(1.seconds)
        runCurrent()

        val pending = h.pending.find(fileId)
        assertNotNull(pending, "trigger opens a pending row")
        assertEquals(PendingFileRequestStatus.PENDING, pending.status)
        assertEquals(1, h.router.startedFiles.size)
        assertEquals(fileId, h.router.startedFiles.single().first)
        assertEquals(false, h.router.startedFiles.single().second, "automatic trigger: routing waits the push window")
        h.coordinator.stop()
    }

    @Test
    fun readyFile_doesNotTrigger() = runTest {
        val h = harness()
        val mid = MessageId(Uuid.random())
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.READY, totalBytes = 1_000L,
                contiguousBytes = 1_000L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                originMessageId = mid,
                createdAt = now, updatedAt = now,
            )
        )
        h.coordinator.start(this)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.pipeline.emit(IngestResult.Inserted(textMessage(mid, senderAccount)))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(h.pending.find(fileId))
        assertTrue(h.router.startedFiles.isEmpty())
        h.coordinator.stop()
    }

    @Test
    fun largeAttachment_belowAutoDownload_triggersRequest() = runTest {
        val h = harness()
        val mid = MessageId(Uuid.random())
        val big = DagPayload.Attachment(fileId, "big.bin", null, 100L * 1024 * 1024)
        val payload = textMessage(mid, senderAccount).copy(attachments = listOf(big))
        h.messages.rows[mid] = StoredMessage(payload, isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.coordinator.start(this)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.pipeline.emit(IngestResult.Inserted(payload))
        advanceTimeBy(1.seconds)
        runCurrent()
        // The orchestrator only decides to ask; routing pins the class and starts a large file
        // at once without waiting the small path's push window.
        assertNotNull(h.pending.find(fileId))
        assertEquals(1, h.router.startedFiles.size)
        assertEquals(fileId, h.router.startedFiles.single().first)
        assertEquals(false, h.router.startedFiles.single().second, "automatic trigger: routing decides by class")
        h.coordinator.stop()
    }

    @Test
    fun largeAttachment_aboveAutoDownload_landsDeclinedWithoutRequest() = runTest {
        val h = harness()
        val mid = MessageId(Uuid.random())
        val huge = DagPayload.Attachment(fileId, "huge.bin", "application/octet-stream", 300L * 1024 * 1024)
        val payload = textMessage(mid, senderAccount).copy(attachments = listOf(huge))
        h.messages.rows[mid] = StoredMessage(payload, isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.coordinator.start(this)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.pipeline.emit(IngestResult.Inserted(payload))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(h.pending.find(fileId), "no request until the user accepts")
        assertTrue(h.router.startedFiles.isEmpty())
        assertEquals(FileReceiveStatus.DECLINED, h.receives.find(fileId)?.status)
        assertEquals(300L * 1024 * 1024, h.receives.find(fileId)?.totalBytes)
        h.coordinator.stop()
    }

    @Test
    fun smallAttachment_aboveAutoDownload_waitsForUserAccept() = runTest {
        val h = harness(orchestratorConfig = OrchestratorConfig(autoDownloadMaxBytes = 0L))
        val mid = MessageId(Uuid.random())
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.coordinator.start(this)
        runCurrent()
        h.pipeline.emit(IngestResult.Inserted(textMessage(mid, senderAccount)))
        runCurrent()
        // The gate is about the user's attention, not the router's small/large boundary:
        // even a small file above it waits for the user's accept.
        assertNull(h.pending.find(fileId), "no request until the user accepts")
        assertTrue(h.router.startedFiles.isEmpty())
        assertEquals(FileReceiveStatus.DECLINED, h.receives.find(fileId)?.status)
        h.coordinator.stop()
    }

    @Test
    fun requestTotalBytes_prefersAttachmentFallsBackToRow() = runTest {
        val h = harness()
        assertNull(h.source.requestTotalBytes(fileId))
        val mid = MessageId(Uuid.random())
        val big = DagPayload.Attachment(fileId, "big.bin", null, 100L * 1024 * 1024)
        val payload = textMessage(mid, senderAccount).copy(attachments = listOf(big))
        h.messages.rows[mid] = StoredMessage(payload, isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        assertEquals(100L * 1024 * 1024, h.source.requestTotalBytes(fileId))
    }

    @Test
    fun satisfiedAndReady_dropPending() = runTest {
        val h = harness()
        h.pending.upsertPending(fileId, now)
        h.coordinator.start(this)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.router.events.emit(FileEvent.RequestSatisfied(fileId))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(h.pending.find(fileId))

        h.pending.upsertPending(fileId, now)
        h.receiveEvents.emit(FileReceiveEvent.Ready(fileId))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(h.pending.find(fileId))
        h.coordinator.stop()
    }

    @Test
    fun unavailableMarked_declinedTerminalUntilRearmed() = runTest {
        val h = harness()
        h.pending.upsertPending(fileId, now)
        h.coordinator.start(this)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.router.events.emit(FileEvent.RequestUnavailable(fileId))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(PendingFileRequestStatus.UNAVAILABLE, h.pending.find(fileId)?.status)

        // A new message referencing the file re-arms the trigger.
        val mid = MessageId(Uuid.random())
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.pipeline.emit(IngestResult.Inserted(textMessage(mid, senderAccount)))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(PendingFileRequestStatus.PENDING, h.pending.find(fileId)?.status)
        assertTrue(h.router.startedFiles.isNotEmpty())
        h.coordinator.stop()
    }

    @Test
    fun noDirectConnection_keepsPending() = runTest {
        val h = harness()
        h.pending.upsertPending(fileId, now)
        h.coordinator.start(this)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.router.events.emit(FileEvent.NoDirectConnection(fileId))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNotNull(h.pending.find(fileId), "online-but-unconnectable stays pending for a later candidate")
        h.coordinator.stop()
    }

    @Test
    fun largeGapTrigger_abortsFetchAndStalls() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 100L * 1024 * 1024,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "big.bin", mimeType = null, hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now - 601.seconds,
            )
        )
        h.coordinator.start(this)
        runCurrent()
        h.timeSource += Duration.parse("11m")
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(FileReceiveStatus.STALLED, h.receives.find(fileId)?.status)
        assertEquals(listOf(fileId), h.router.abortedFiles)
        assertNotNull(h.pending.find(fileId), "stalled large receive re-opens the request")
        h.coordinator.stop()
    }

    @Test
    fun gapTrigger_stalledPrefixReopensRequestWithoutCancelling() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                originMessageId = null,
                // Silent for longer than fileStallTimeout (10 min): the gap trigger fires.
                createdAt = now - 11.minutes, updatedAt = now - 11.minutes,
            )
        )
        h.coordinator.start(this)
        runCurrent()
        h.timeSource += Duration.parse("11m")
        advanceTimeBy(61.seconds)
        runCurrent()
        val row = assertNotNull(h.pending.find(fileId), "stalled prefix re-opens the request")
        assertEquals(PendingFileRequestStatus.PENDING, row.status)
        assertTrue(h.router.startedFiles.any { it.first == fileId })
        val receive = assertNotNull(h.receives.find(fileId))
        assertEquals(FileReceiveStatus.STALLED, receive.status)
        assertEquals(100L, receive.contiguousBytes, "P is kept: the gap trigger cancels nothing")
        h.coordinator.stop()
    }

    @Test
    fun gapTrigger_progressingPrefixDoesNotStall() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.coordinator.start(this)
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(FileReceiveStatus.IN_FLIGHT, h.receives.find(fileId)?.status)
        assertNull(h.pending.find(fileId), "a progressing receive is left alone")
        h.coordinator.stop()
    }

    @Test
    fun stalledReceive_reopensRequestOnSweep() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.STALLED, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 1,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.coordinator.start(this)
        runCurrent()
        // The startup sweep runs before the periodic delay, so the stalled request re-opens at once.
        assertNotNull(h.pending.find(fileId), "the startup sweep re-opens the stalled request")
        assertTrue(h.router.startedFiles.isNotEmpty())
        advanceTimeBy(61.seconds)
        runCurrent()
        assertNotNull(h.pending.find(fileId), "the periodic sweep keeps the request open")
        h.coordinator.stop()
    }

    @Test
    fun stalledPendingReceive_laterSweeps_reArmWithoutResettingTheSchedule() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.STALLED, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 1,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.pending.upsertPending(fileId, now)
        h.coordinator.start(this)
        repeat(3) {
            advanceTimeBy(61.seconds)
            runCurrent()
        }
        assertTrue(h.router.startedFiles.isNotEmpty())
        assertTrue(
            h.router.startedFiles.none { (_, immediate) -> immediate },
            "an already-pending stalled file never moves routing's schedule to now",
        )
        h.coordinator.stop()
    }

    @Test
    fun stalledReceive_whoseRequestRanOutOfHolders_isNotReArmedBySweeps() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.STALLED, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 1,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        // Every holder declined: UNAVAILABLE is terminal until a new message or the user re-opens.
        h.pending.upsertPending(fileId, now)
        h.pending.setUnavailable(fileId, now)
        h.coordinator.start(this)
        repeat(3) {
            advanceTimeBy(61.seconds)
            runCurrent()
        }
        assertEquals(PendingFileRequestStatus.UNAVAILABLE, h.pending.find(fileId)?.status)
        assertTrue(h.router.startedFiles.isEmpty(), "no request re-armed every sweep")
        h.coordinator.stop()
    }

    @Test
    fun freshStall_reArmsARequestThatHadRunOutOfHolders() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.pending.upsertPending(fileId, now)
        h.pending.setUnavailable(fileId, now)
        h.coordinator.start(this)
        runCurrent()
        // The transfer that was delivering goes silent past the stall timeout.
        h.timeSource += 11.minutes
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(FileReceiveStatus.STALLED, h.receives.find(fileId)?.status)
        assertEquals(PendingFileRequestStatus.PENDING, h.pending.find(fileId)?.status, "a fresh stall asks again")
        assertTrue(h.router.startedFiles.isNotEmpty())
        h.coordinator.stop()
    }

    @Test
    fun offerAcceptedAfterStart_thatNeverSendsAByte_stalls() = runTest {
        val h = harness()
        h.coordinator.start(this)
        runCurrent()
        // An OFFER accepted while running: IN_FLIGHT, and no chunk (so no Progress) ever arrives.
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 1_000L,
                contiguousBytes = 0L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(FileReceiveStatus.IN_FLIGHT, h.receives.find(fileId)?.status, "its gap window starts now")
        h.timeSource += 11.minutes
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(FileReceiveStatus.STALLED, h.receives.find(fileId)?.status)
        assertNotNull(h.pending.find(fileId), "the stalled file is requested again")
        h.coordinator.stop()
    }

    @Test
    fun restartAfterLongSleep_doesNotFireGapTriggerAtOnce() = runTest {
        // A receive idle for longer than the stall timeout, then a restart: the fresh
        // marks on start mean the gap trigger waits a full timeout instead of firing at once.
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                originMessageId = null,
                // Last written long past the stall timeout: a check keyed on updatedAt would fire.
                createdAt = now - 11.minutes, updatedAt = now - 11.minutes,
            )
        )
        h.coordinator.start(this)
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(FileReceiveStatus.IN_FLIGHT, h.receives.find(fileId)?.status)
        assertNull(h.pending.find(fileId))
        // Even advancing the monotonic clock by only half the timeout still leaves it alone.
        h.timeSource += 5.minutes
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(FileReceiveStatus.IN_FLIGHT, h.receives.find(fileId)?.status)
        h.coordinator.stop()
    }

    @Test
    fun slowPushPastGrace_getsNoRequest() = runTest {
        // A push progressing past the small-file grace period (60s) emits Progress, which
        // drops the pending row: no REQUEST goes out while bytes arrive.
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 10_000L,
                contiguousBytes = 0L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.pending.upsertPending(fileId, now)
        h.coordinator.start(this)
        runCurrent()
        // Progress arrives before the grace period ends.
        h.receiveEvents.emit(FileReceiveEvent.Progress(fileId, 100L, 10_000L))
        advanceTimeBy(61.seconds)
        runCurrent()
        assertNull(h.pending.find(fileId), "progressing push covers the grace period")
        assertTrue(h.router.startedFiles.isEmpty(), "no REQUEST while a push progresses")
        h.coordinator.stop()
    }

    @Test
    fun noSpace_isDeclinedAndNotReRequested() = runTest {
        val h = harness()
        val mid = MessageId(Uuid.random())
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.rooms.members[roomId to senderAccount] = FakeRoomRepository.Member(RoomMemberStatus.ACTIVE, null)
        // Simulate a no-space DECLINED row (as the engine records on a full volume).
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.DECLINED, totalBytes = 1_000L,
                contiguousBytes = 0L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = "image/jpeg", hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.coordinator.start(this)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.pipeline.emit(IngestResult.Inserted(textMessage(mid, senderAccount)))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(h.pending.find(fileId), "DECLINED is refused, never auto-resurrected")
        assertTrue(h.router.startedFiles.isEmpty())
        h.coordinator.stop()
    }

    @Test
    fun serveGate_allowsActiveRefusesStranger() = runTest {
        val h = harness()
        upsertHeld(h, totalBytes = 1_000L)
        val mid = MessageId(Uuid.random())
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.rooms.members[roomId to senderAccount] = FakeRoomRepository.Member(RoomMemberStatus.ACTIVE, null)

        assertTrue(h.source.canServe(fileId, senderAccount))
        assertFalse(h.source.canServe(fileId, strangerAccount), "never-member is refused")
    }

    @Test
    fun serveGate_removedMemberSeesOnlyEra() = runTest {
        val h = harness()
        upsertHeld(h, totalBytes = 1_000L)
        val removal = MessageId(Uuid.random())
        val eraMessage = MessageId(Uuid.random())
        val afterMessage = MessageId(Uuid.random())
        h.messages.rows[eraMessage] = StoredMessage(textMessage(eraMessage, senderAccount), isOrphaned = false)
        h.messages.rows[afterMessage] = StoredMessage(textMessage(afterMessage, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(eraMessage, afterMessage)
        h.messages.parents[removal] = listOf(eraMessage)
        h.rooms.members[roomId to senderAccount] =
            FakeRoomRepository.Member(RoomMemberStatus.REMOVED, removal)

        // Era file (ancestor of the removal node): visible.
        h.messages.attachments[fileId] = listOf(eraMessage)
        assertTrue(h.source.canServe(fileId, senderAccount))
        // Post-removal file: not visible.
        h.messages.attachments[fileId] = listOf(afterMessage)
        assertFalse(h.source.canServe(fileId, senderAccount))
        // The removal node itself names a message: visible.
        h.messages.rows[removal] = StoredMessage(textMessage(removal, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(removal)
        assertTrue(h.source.canServe(fileId, senderAccount))
    }

    @Test
    fun serveGate_allowsLargeFetch_refusesMissing() = runTest {
        val h = harness()
        upsertHeld(h, totalBytes = 100L * 1024 * 1024)
        val mid = MessageId(Uuid.random())
        h.messages.rows[mid] = StoredMessage(textMessage(mid, senderAccount), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(mid)
        h.rooms.members[roomId to senderAccount] = FakeRoomRepository.Member(RoomMemberStatus.ACTIVE, null)
        // Fetch serves any size through the same per-account gate.
        assertTrue(h.source.canServe(fileId, senderAccount))

        assertFalse(
            h.source.canServe(
                FileId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                senderAccount
            )
        )
    }

    @Test
    fun requestCandidates_sendersFirstOwnLast() = runTest {
        val h = harness()
        val other = AccountId("coordinator-other")
        val m1 = MessageId(Uuid.random())
        val m2 = MessageId(Uuid.random())
        h.messages.rows[m1] = StoredMessage(textMessage(m1, senderAccount), isOrphaned = false)
        h.messages.rows[m2] = StoredMessage(textMessage(m2, other), isOrphaned = false)
        h.messages.attachments[fileId] = listOf(m1, m2)
        assertEquals(listOf(senderAccount, other, ownAccount), h.source.requestCandidates(fileId))
    }

    @Test
    fun heldCoverage_reportsContiguousAndMissing() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.IN_FLIGHT, totalBytes = 1_000L,
                contiguousBytes = 100L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "img.jpg", mimeType = null, hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )
        h.receives.stageChunks(
            fileId,
            listOf(StagedRange(100L, 50L, ByteArray(50)), StagedRange(200L, 50L, ByteArray(50))),
            FileReceiveStatus.IN_FLIGHT,
            now,
        )
        val coverage = assertNotNull(h.source.heldCoverage(fileId))
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
                pathKind = FilePathKind.PATH,
                status = FileDescriptorStatus.HELD, createdAt = now,
                lastAccessAt = now,
            )
        )
    }

    private class RecordingFileRouter(
        val startedFiles: MutableList<Pair<FileId, Boolean>> = mutableListOf(),
        val abortedFiles: MutableList<FileId> = mutableListOf(),
        val events: MutableSharedFlow<FileEvent> = MutableSharedFlow(extraBufferCapacity = 64),
    ) : NoopFileCarrier() {
        val files: FileCarrier get() = this
        override val fileEvents: Flow<FileEvent> = events

        override suspend fun startFilePull(fileId: FileId, immediate: Boolean) {
            startedFiles += fileId to immediate
        }

        override suspend fun abortLargeFile(
            fileId: FileId,
            reason: LargeFileAbortReason
        ) {
            abortedFiles += fileId
        }
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
        val rows = mutableMapOf<MessageId, StoredMessage>()
        val attachments = mutableMapOf<FileId, List<MessageId>>()
        val parents = mutableMapOf<MessageId, List<MessageId>>()

        override suspend fun insert(
            payload: DagPayload,
            isOrphaned: Boolean,
            ancestryComplete: Boolean,
            verificationState: VerificationState
        ): Boolean = error("not used")
        override suspend fun findMessageIdsByFileId(fileId: FileId): List<MessageId> = attachments[fileId].orEmpty()
        override suspend fun findById(messageId: MessageId): StoredMessage? = rows[messageId]
        override suspend fun findRoomFrontier(roomId: RoomId): List<StoredMessage> = error("not used")
        override suspend fun findParents(messageId: MessageId): List<MessageId> = parents[messageId].orEmpty()
        override suspend fun isAncestorOrSelf(messageId: MessageId, of: MessageId): Boolean {
            if (messageId == of) return true
            val visited = HashSet<MessageId>()
            val queue = ArrayDeque<MessageId>()
            visited.add(of)
            queue.add(of)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                val storedParents = parents[id].orEmpty()
                val payloadParents = rows[id]?.payload?.prevIds.orEmpty()
                for (parent in (storedParents + payloadParents).toSet()) {
                    if (parent == messageId) return true
                    if (visited.add(parent)) queue.add(parent)
                }
            }
            return false
        }

        override suspend fun insertParent(messageId: MessageId, parentId: MessageId) = error("not used")
        override suspend fun findChildrenInRoom(parentId: MessageId, roomId: RoomId): List<StoredMessage> =
            error("not used")
        override suspend fun findMessagesInRoomPageDesc(
            roomId: RoomId,
            limit: Int,
            cursor: MessageCursor?
        ): List<StoredMessage> = error("not used")

        override suspend fun renderableMessagesInRoom(
            roomId: RoomId,
            limit: Int,
            cursor: MessageCursor?
        ): List<StoredMessage> = error("not used")

        override suspend fun isRenderable(roomId: RoomId, messageId: MessageId): Boolean = error("not used")
        override suspend fun findAllInRoom(roomId: RoomId): List<StoredMessage> = error("not used")
        override suspend fun findFoldableInRoom(roomId: RoomId): List<StoredMessage> = error("not used")
        override suspend fun hasMessages(roomId: RoomId): Boolean = error("not used")
        override suspend fun updateOrphanedFlag(messageId: MessageId, isOrphaned: Boolean) = error("not used")
        override suspend fun updateAncestryComplete(messageId: MessageId, complete: Boolean) = error("not used")
        override suspend fun updateVerificationState(
            messageId: MessageId,
            state: VerificationState
        ) = error("not used")

        override suspend fun findPendingByAuthor(deviceId: PeerId): List<StoredMessage> = error("not used")
        override suspend fun findAllPending(): List<StoredMessage> = error("not used")
    }

    private class FakeRoomRepository(
        memberships: Map<Pair<RoomId, AccountId>, Member> = emptyMap(),
    ) : RoomRepository {
        val members: MutableMap<Pair<RoomId, AccountId>, Member> = memberships.toMutableMap()

        data class Member(val status: RoomMemberStatus, val removalNodeId: MessageId?)

        override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> = error("not used")
        override suspend fun memberStatusesOfRoom(roomId: RoomId): List<RoomMember> = error("not used")
        override suspend fun memberRowOf(roomId: RoomId, accountId: AccountId): RoomMember? =
            members[roomId to accountId]?.let {
                RoomMember(accountId, RoomMemberRole.MEMBER, it.status, it.removalNodeId)
            }

        override suspend fun roomsOfPeer(peerId: PeerId): List<RoomId> = error("not used")
        override suspend fun roomsOfAccount(accountId: AccountId): List<RoomId> = error("not used")
        override suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String) = error("not used")
        override suspend fun upsertMember(
            roomId: RoomId,
            accountId: AccountId,
            role: RoomMemberRole,
            status: RoomMemberStatus,
            removalNodeId: MessageId?
        ) = error("not used")

        override suspend fun removeMember(roomId: RoomId, accountId: AccountId) = error("not used")
        override suspend fun allChatRoomIds(): List<RoomId> = error("not used")
        override suspend fun roomOf(roomId: RoomId): Room? = error("not used")
        override suspend fun mergeRoomFromGenesis(roomId: RoomId, name: String, type: RoomType, spaceId: String?) =
            error("not used")

        override suspend fun removeRoomMembersNotIn(roomId: RoomId, keep: Collection<AccountId>) = error("not used")
    }
}
