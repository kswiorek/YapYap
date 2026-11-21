package org.yapyap.orchestrator.file.receive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.DefaultFileDescriptorStore
import org.yapyap.orchestrator.file.DefaultFileReceiveStore
import org.yapyap.orchestrator.file.DefaultFileStore
import org.yapyap.orchestrator.file.FileReceiveRow
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.FileId
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

class FileRetentionTest {
    private val crypto = DefaultCryptoProvider()
    private val files = JvmFileAccess()

    @Test
    fun unreferencedTtl_coversEveryStatus() = runTest {
        val clock = FakeClock(epochSeconds(10_000L))
        val db = openMemoryDatabase().database
        val receives = DefaultFileReceiveStore(db)
        val descriptors = DefaultFileDescriptorStore(db)
        val work = Path(SystemTemporaryDirectory.toString(), "yapyap-ret-${Uuid.random()}")
        files.createDirectories(work)
        val paths = ReceivePaths(Path(work.toString(), "incoming"), Path(work.toString(), "store"))
        files.createDirectories(paths.incomingDir)
        files.createDirectories(paths.storeDir)
        val config = MutableStateFlow(OrchestratorConfig())
        val store = DefaultFileStore(descriptors, crypto, files, paths.storeDir, config, clock)
        val engine = ReceiveEngine(receives, descriptors, crypto, files, paths, config, store, clock)
        val retention = FileRetention(
            receives,
            descriptors,
            DefaultMessageRepository(db),
            engine,
            store,
            files,
            paths,
            emptyFlow(),
            config,
            clock,
        )
        val statuses = listOf(
            FileReceiveStatus.IN_FLIGHT,
            FileReceiveStatus.STALLED,
            FileReceiveStatus.FAILED,
            FileReceiveStatus.DECLINED,
        )
        for ((i, status) in statuses.withIndex()) {
            val fileId = FileId("a".repeat(63) + i.toString())
            receives.upsert(
                FileReceiveRow(
                    fileId, status, 1000, 100, 1024 * 1024, emptyList(),
                    "f", null, 0, null, clock.now(), clock.now(),
                )
            )
        }
        for (i in statuses.indices) {
            files.openAppendWriter(paths.part(FileId("a".repeat(63) + i.toString())), null).use { it.flushAndSync() }
        }
        clock.advanceTo(epochSeconds(10_000L) + 3.days)
        retention.sweep(clock.now())
        for (i in statuses.indices) {
            val fileId = FileId("a".repeat(63) + i.toString())
            assertNull(receives.find(fileId), "unreferenced $fileId must go, whatever its status")
            assertNull(files.fileSize(paths.part(fileId)), "its .part goes with it")
        }
    }

    @Test
    fun referencedRow_keptAndOriginLinked() = runTest {
        val clock = FakeClock(epochSeconds(10_000L))
        val db = openMemoryDatabase().database
        val receives = DefaultFileReceiveStore(db)
        val descriptors = DefaultFileDescriptorStore(db)
        val messages = DefaultMessageRepository(db)
        val work = Path(SystemTemporaryDirectory.toString(), "yapyap-ret-${Uuid.random()}")
        files.createDirectories(work)
        val paths = ReceivePaths(Path(work.toString(), "incoming"), Path(work.toString(), "store"))
        files.createDirectories(paths.incomingDir)
        files.createDirectories(paths.storeDir)
        val config = MutableStateFlow(OrchestratorConfig())
        val store = DefaultFileStore(descriptors, crypto, files, paths.storeDir, config, clock)
        val engine = ReceiveEngine(receives, descriptors, crypto, files, paths, config, store, clock)
        val retention =
            FileRetention(receives, descriptors, messages, engine, store, files, paths, emptyFlow(), config, clock)
        // Seed a message referencing the file first so the sweep links the origin instead of deleting.
        val fileId = FileId("b".repeat(64))
        val roomId = org.yapyap.protocol.RoomId(Uuid.random())
        val msgId = Uuid.random()
        val rooms = org.yapyap.orchestrator.store.DefaultRoomRepository(db)
        rooms.ensureRoomExists(roomId, org.yapyap.protocol.RoomType.TEXT_CHANNEL, "t")
        messages.insert(
            org.yapyap.protocol.envelopes.MessagePayload.Text(
                messageId = msgId, roomId = roomId, senderAccountId = org.yapyap.protocol.AccountId("a"),
                authorDeviceId = org.yapyap.protocol.PeerId("d"), prevIds = emptyList(),
                createdAt = clock.now(), text = "x",
                attachments = listOf(
                    org.yapyap.protocol.envelopes.MessagePayload.Attachment(fileId, "f", null, 1000)
                ),
            ),
            isOrphaned = false, ancestryComplete = true,
            verificationState = org.yapyap.orchestrator.dag.VerificationState.VERIFIED,
        )
        receives.upsert(
            FileReceiveRow(
                fileId,
                FileReceiveStatus.IN_FLIGHT,
                1000,
                0,
                1024 * 1024,
                emptyList(),
                "f",
                null,
                0,
                null,
                clock.now(),
                clock.now()
            )
        )
        clock.advanceTo(epochSeconds(10_000L) + 3.days)
        retention.sweep(clock.now())
        assertNotNull(receives.find(fileId), "referenced row must stay")
    }
}
