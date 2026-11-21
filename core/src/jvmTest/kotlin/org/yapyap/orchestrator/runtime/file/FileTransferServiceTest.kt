package org.yapyap.orchestrator.runtime.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.VerificationState
import org.yapyap.orchestrator.file.DefaultFileDescriptorStore
import org.yapyap.orchestrator.file.DefaultFileReceiveStore
import org.yapyap.orchestrator.file.DefaultFileRequestCoordinator
import org.yapyap.orchestrator.file.DefaultFileStore
import org.yapyap.orchestrator.file.DefaultPendingFileRequestStore
import org.yapyap.orchestrator.file.FileReceiveRow
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.PendingFileRequestStatus
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.receive.ReceiveEngine
import org.yapyap.orchestrator.file.receive.ReceivePaths
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.orchestrator.store.DefaultRoomRepository
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileFetchAbortReason
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.NoopFileCarrier
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The runtime facade: the large-file accept/decline decision is local (DECLINED, re-openable)
 * and never touches the wire by itself. Accept and decline run through the real coordinator and
 * receive engine, the only writers of the tables they change.
 */
class FileTransferServiceTest {

    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val senderAccount = AccountId("facade-sender")
    private val roomId = RoomId(Uuid.random())
    private val now = epochSeconds(10_000L)

    private class Harness(
        val service: DefaultFileTransferService,
        val receives: DefaultFileReceiveStore,
        val pending: DefaultPendingFileRequestStore,
        val messages: DefaultMessageRepository,
        val rooms: DefaultRoomRepository,
        val router: RecordingFacadeRouter,
        val messageId: Uuid,
    )

    private class RecordingFacadeRouter : NoopFileCarrier() {
        val files: FileCarrier get() = this
        val started = mutableListOf<Pair<FileId, kotlin.time.Instant>>()
        val aborted = mutableListOf<FileId>()

        override suspend fun startFileRequest(fileId: FileId, firstAttemptAt: kotlin.time.Instant) {
            started += fileId to firstAttemptAt
        }

        override suspend fun abortFileFetch(
            fileId: FileId,
            reason: FileFetchAbortReason
        ) {
            aborted += fileId
        }
    }

    private suspend fun harness(): Harness {
        val db = openMemoryDatabase().database
        val clock = FakeClock(now)
        val receives = DefaultFileReceiveStore(db)
        val descriptors = DefaultFileDescriptorStore(db)
        val pending = DefaultPendingFileRequestStore(db)
        val messages = DefaultMessageRepository(db)
        val rooms = DefaultRoomRepository(db)
        val router = RecordingFacadeRouter()
        val files = JvmFileAccess()
        val work = Path(SystemTemporaryDirectory.toString(), "yapyap-facade-${Uuid.random()}")
        val config = MutableStateFlow(OrchestratorConfig())
        val crypto = DefaultCryptoProvider()
        val storeDir = Path(work.toString(), "store")
        val fileStore = DefaultFileStore(descriptors, crypto, files, storeDir, config, clock)
        val engine = ReceiveEngine(
            receives = receives,
            descriptors = descriptors,
            crypto = crypto,
            files = files,
            paths = ReceivePaths(Path(work.toString(), "incoming"), storeDir),
            config = config,
            fileStore = fileStore,
            clock = clock,
        )
        val coordinator = DefaultFileRequestCoordinator(
            pending = pending,
            receives = receives,
            descriptors = descriptors,
            control = engine,
            messages = messages,
            fileCarrier = router.files,
            pipeline = NoIngest,
            receiveEvents = emptyFlow(),
            config = config,
            clock = clock,
        )
        val service = DefaultFileTransferService(
            receives = receives,
            descriptors = descriptors,
            files = files,
            requests = coordinator,
            store = fileStore,
        )
        val mid = Uuid.random()
        return Harness(service, receives, pending, messages, rooms, router, mid)
    }

    private object NoIngest : InboundMessagePipeline {
        override val ingestResults: Flow<IngestResult> = emptyFlow()
        override fun start(scope: CoroutineScope) = Unit
    }

    private suspend fun Harness.storeMessage(payload: MessagePayload.Text) {
        rooms.ensureRoomExists(roomId, RoomType.TEXT_CHANNEL, "t")
        messages.insert(
            payload,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.VERIFIED
        )
    }

    @Test
    fun decline_withoutRow_recordsDeclinedFromAttachment() = runTest {
        val h = harness()
        val payload = MessagePayload.Text(
            messageId = h.messageId, roomId = roomId, senderAccountId = senderAccount,
            authorDeviceId = PeerId("facade-author-device"), prevIds = emptyList(),
            createdAt = now, text = "big",
            attachments = listOf(
                MessagePayload.Attachment(
                    fileId,
                    "huge.bin",
                    "application/octet-stream",
                    300L * 1024 * 1024
                )
            ),
        )
        h.storeMessage(payload)

        h.service.declineFile(fileId)

        val row = assertNotNull(h.receives.find(fileId))
        assertEquals(FileReceiveStatus.DECLINED, row.status)
        assertEquals(300L * 1024 * 1024, row.totalBytes)
        assertNull(h.pending.find(fileId))
        // Abort is best-effort even with no live fetch.
        assertEquals(listOf(fileId), h.router.aborted)
        assertTrue(h.router.started.isEmpty())
    }

    @Test
    fun accept_afterDecline_dropsRowAndStartsRequest() = runTest {
        val h = harness()
        val payload = MessagePayload.Text(
            messageId = h.messageId, roomId = roomId, senderAccountId = senderAccount,
            authorDeviceId = PeerId("facade-author-device"), prevIds = emptyList(),
            createdAt = now, text = "big",
            attachments = listOf(MessagePayload.Attachment(fileId, "huge.bin", null, 300L * 1024 * 1024)),
        )
        h.storeMessage(payload)
        h.service.declineFile(fileId)
        assertNotNull(h.receives.find(fileId))

        h.service.acceptFile(fileId)

        assertNull(h.receives.find(fileId), "DECLINED removed so FETCH_OK can create the row")
        assertEquals(PendingFileRequestStatus.PENDING, h.pending.find(fileId)?.status)
        assertEquals(listOf(fileId to now), h.router.started)
    }

    @Test
    fun accept_whenReady_isNoop() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.READY, totalBytes = 1_000L,
                contiguousBytes = 1_000L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "a.bin", mimeType = null, hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )

        h.service.acceptFile(fileId)

        assertTrue(h.router.started.isEmpty(), "READY needs no request")
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
    }

    @Test
    fun decline_whenReady_keepsReady() = runTest {
        val h = harness()
        h.receives.upsert(
            FileReceiveRow(
                fileId = fileId, status = FileReceiveStatus.READY, totalBytes = 1_000L,
                contiguousBytes = 1_000L, blockSize = 1024 * 1024, blockHashes = emptyList(),
                fileName = "a.bin", mimeType = null, hashMismatches = 0,
                originMessageId = null,
                createdAt = now, updatedAt = now,
            )
        )

        h.service.declineFile(fileId)

        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
    }

    @Test
    fun receiveState_reflectsDeclined() = runTest {
        val h = harness()
        val payload = MessagePayload.Text(
            messageId = h.messageId, roomId = roomId, senderAccountId = senderAccount,
            authorDeviceId = PeerId("facade-author-device"), prevIds = emptyList(),
            createdAt = now, text = "big",
            attachments = listOf(MessagePayload.Attachment(fileId, "huge.bin", null, 300L * 1024 * 1024)),
        )
        h.storeMessage(payload)
        h.service.declineFile(fileId)

        val snapshot = assertNotNull(h.service.receiveState(fileId))
        assertEquals(FileReceiveStatus.DECLINED, snapshot.status)
        assertNull(h.service.localFilePath(fileId))
    }
}
