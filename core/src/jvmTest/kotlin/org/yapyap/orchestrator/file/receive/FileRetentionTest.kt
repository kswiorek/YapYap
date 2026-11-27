package org.yapyap.orchestrator.file.receive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.VerificationState
import org.yapyap.orchestrator.file.DefaultFileDescriptorStore
import org.yapyap.orchestrator.file.DefaultFileReceiveStore
import org.yapyap.orchestrator.file.DefaultFileStore
import org.yapyap.orchestrator.file.FileDescriptor
import org.yapyap.orchestrator.file.FileDescriptorStatus
import org.yapyap.orchestrator.file.FilePathKind
import org.yapyap.orchestrator.file.FilePins
import org.yapyap.orchestrator.file.FileReceiveRow
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.FileStorage
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.orchestrator.store.DefaultRoomRepository
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.envelopes.DagPayload
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
        val store = DefaultFileStore(descriptors, crypto, files, paths.storeDir, clock)
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
        val store = DefaultFileStore(descriptors, crypto, files, paths.storeDir, clock)
        val engine = ReceiveEngine(receives, descriptors, crypto, files, paths, config, store, clock)
        val retention =
            FileRetention(receives, descriptors, messages, engine, store, files, paths, emptyFlow(), config, clock)
        // Seed a message referencing the file first so the sweep links the origin instead of deleting.
        val fileId = FileId("b".repeat(64))
        val roomId = RoomId(Uuid.random())
        val msgId = Uuid.random()
        val rooms = DefaultRoomRepository(db)
        rooms.ensureRoomExists(roomId, RoomType.TEXT_CHANNEL, "t")
        messages.insert(
            DagPayload.Text(
                messageId = msgId, roomId = roomId, senderAccountId = AccountId("a"),
                authorDeviceId = PeerId("d"), prevIds = emptyList(),
                createdAt = clock.now(), text = "x",
                attachments = listOf(
                    DagPayload.Attachment(fileId, "f", null, 1000)
                ),
            ),
            isOrphaned = false, ancestryComplete = true,
            verificationState = VerificationState.VERIFIED,
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

    private class Rig(
        val clock: FakeClock,
        val receives: DefaultFileReceiveStore,
        val descriptors: DefaultFileDescriptorStore,
        val paths: ReceivePaths,
        val pins: FilePins,
        val retention: FileRetention,
    )

    private suspend fun rig(quota: Long): Rig {
        val clock = FakeClock(epochSeconds(10_000L))
        val db = openMemoryDatabase().database
        val receives = DefaultFileReceiveStore(db)
        val descriptors = DefaultFileDescriptorStore(db)
        val work = Path(SystemTemporaryDirectory.toString(), "yapyap-ret-${Uuid.random()}")
        files.createDirectories(work)
        val paths = ReceivePaths(Path(work.toString(), "incoming"), Path(work.toString(), "store"))
        files.createDirectories(paths.incomingDir)
        files.createDirectories(paths.storeDir)
        val config = MutableStateFlow(
            OrchestratorConfig(storeQuotaBytes = quota, autoDownloadMaxBytes = 0L),
        )
        val store = DefaultFileStore(descriptors, crypto, files, paths.storeDir, clock)
        val engine = ReceiveEngine(receives, descriptors, crypto, files, paths, config, store, clock)
        val pins = FilePins()
        val retention = FileRetention(
            receives, descriptors, DefaultMessageRepository(db), engine, store, files, paths, emptyFlow(), config,
            clock, pins,
        )
        return Rig(clock, receives, descriptors, paths, pins, retention)
    }

    /** A sent copy of [size] bytes in the store, last accessed at [accessedAt] seconds. */
    private suspend fun Rig.storedCopy(name: Char, size: Int, accessedAt: Long): FileId {
        val fileId = FileId(name.toString().repeat(64))
        val path = paths.final(fileId)
        files.openAppendWriter(path, null).use { it.write(ByteArray(size)); it.flushAndSync() }
        descriptors.upsert(
            FileDescriptor(
                fileId, "$name.bin", null, size.toLong(), 1024 * 1024, emptyList(), FileStorage.COPY,
                path.toString(), FilePathKind.PATH, FileDescriptorStatus.HELD,
                epochSeconds(accessedAt), epochSeconds(accessedAt),
            )
        )
        return fileId
    }

    @Test
    fun quota_sparesTheArrivedFileAndPinnedOnes_evictsTheRest() = runTest {
        // 3 MB of copies under a 1 MB quota.
        val r = rig(quota = 1_000_000L)
        val oldest = r.storedCopy('a', 1_000_000, 100)
        val pinned = r.storedCopy('b', 1_000_000, 200)
        val arrived = r.storedCopy('c', 1_000_000, 300)
        r.pins.pin(pinned)
        r.retention.enforceQuota(setOf(arrived))
        assertNull(r.descriptors.findById(oldest), "the unprotected copy goes")
        assertNotNull(r.descriptors.findById(pinned), "a send still preparing keeps its copy")
        assertNotNull(r.descriptors.findById(arrived), "the file that just arrived is never evicted on arrival")
        assertNotNull(files.fileSize(r.paths.final(arrived)))
    }

    @Test
    fun sweep_keepsAPinnedUnreferencedReceive() = runTest {
        val r = rig(quota = 1_000_000_000L)
        val fileId = FileId("d".repeat(64))
        r.receives.upsert(
            FileReceiveRow(
                fileId, FileReceiveStatus.IN_FLIGHT, 1000, 100, 1024 * 1024, emptyList(),
                "f", null, 0, null, r.clock.now(), r.clock.now(),
            )
        )
        r.pins.pin(fileId)
        r.clock.advanceTo(epochSeconds(10_000L) + 3.days)
        r.retention.sweep(r.clock.now())
        assertNotNull(r.receives.find(fileId), "a pinned file is not unreferenced: its message is on the way")
        r.pins.unpin(fileId)
        r.retention.sweep(r.clock.now())
        assertNull(r.receives.find(fileId))
    }
}
