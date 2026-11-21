package org.yapyap.orchestrator.file

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.io.PositionedHandle
import org.yapyap.orchestrator.file.receive.FileQuota
import org.yapyap.orchestrator.runtime.message.PickedFile
import org.yapyap.orchestrator.runtime.message.PickedFileSource
import org.yapyap.orchestrator.runtime.message.SendRefusal
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileSendState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

private class NoopCarrier : FileCarrier {
    override val fileEvents: Flow<FileEvent> = emptyFlow()
    override suspend fun pushFile(fileId: FileId, target: AccountId) = Unit
    override suspend fun cancelFile(fileId: FileId, target: AccountId) = Unit
    override suspend fun sendState(fileId: FileId) = emptyMap<AccountId, FileSendState>()
    override suspend fun startFileRequest(fileId: FileId, firstAttemptAt: Instant) = Unit
    override suspend fun abortFileFetch(fileId: FileId, reason: org.yapyap.protocol.envelopes.FileFetchAbortReason) =
        Unit
}

private class RecordingQuota : FileQuota {
    val keeps = mutableListOf<Set<FileId>>()
    override suspend fun enforceQuota(keep: Set<FileId>) {
        keeps += keep
    }
}

class FileSendServiceTest {
    private val crypto = DefaultCryptoProvider()
    private val files = JvmFileAccess()

    private class Rig(
        val db: YapYapDatabase,
        val storeDir: Path,
        val work: Path,
        val descriptors: DefaultFileDescriptorStore,
        val quota: RecordingQuota,
        val svc: DefaultFileSendService,
    )

    private suspend fun rig(access: FileAccess = files): Rig {
        val db = openMemoryDatabase().database
        val work = Path(SystemTemporaryDirectory.toString(), "yapyap-send-${Uuid.random()}")
        files.createDirectories(work)
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store = DefaultFileStore(descriptors, crypto, access, storeDir, MutableStateFlow(OrchestratorConfig()))
        val quota = RecordingQuota()
        val svc = DefaultFileSendService(store, quota, access, storeDir, NoopCarrier(), DefaultMessageRepository(db))
        return Rig(db, storeDir, work, descriptors, quota, svc)
    }

    private suspend fun writeSource(dir: Path, name: String, size: Int): Path {
        val p = Path(dir.toString(), name)
        files.openAppendWriter(p, null).use {
            it.write(ByteArray(size) { i -> (i % 251).toByte() })
            it.flushAndSync()
        }
        return p
    }

    private fun picked(path: Path, keepCopy: Boolean? = true) =
        PickedFile(path.name, null, PickedFileSource.FilePath(path.toString()), keepCopy)

    private fun storeFileNames(storeDir: Path): List<String> =
        java.io.File(storeDir.toString()).list()?.sorted().orEmpty()

    @Test
    fun unreadableSource_isFileUnreadableNotException() = runTest {
        val r = rig()
        val result = r.svc.prepare(
            listOf(PickedFile("nope.bin", null, PickedFileSource.FilePath("/no/such/file.bin"))),
            onProgress = { _, _ -> },
        )
        assertIs<SendRefusal.FileUnreadable>(assertIs<PrepareResult.Refused>(result).refusal)
    }

    @Test
    fun refusalPartWay_releasesTheCopiesAlreadyMade() = runTest {
        var vanishes: Path? = null
        // Sized at the start, deleted while the first file hashes: the second import finds it unreadable.
        val vanishing = object : FileAccess by files {
            override suspend fun openPositionedReader(path: Path, startOffset: Long): PositionedHandle {
                if (path.name == "first.bin") vanishes?.let { files.delete(it) }
                return files.openPositionedReader(path, startOffset)
            }
        }
        val r = rig(vanishing)
        val first = writeSource(r.work, "first.bin", 2_000_000)
        val gone = writeSource(r.work, "gone.bin", 1_000)
        vanishes = gone
        val result = r.svc.prepare(listOf(picked(first), picked(gone)), onProgress = { _, _ -> })
        assertIs<SendRefusal.FileUnreadable>(assertIs<PrepareResult.Refused>(result).refusal)
        assertEquals(emptyList(), storeFileNames(r.storeDir), "the first file's copy must be released")
        assertTrue(r.descriptors.copyDescriptorsLeastRecentlyUsed().isEmpty())
    }

    @Test
    fun cancelMidHash_leavesNoCopy_ofEitherFile() = runTest {
        val secondRead = CompletableDeferred<Unit>()
        val stalling = object : FileAccess by files {
            override suspend fun openPositionedReader(path: Path, startOffset: Long): PositionedHandle {
                val real = files.openPositionedReader(path, startOffset)
                if (path.name != "second.bin") return real
                return object : PositionedHandle by real {
                    var reads = 0
                    override suspend fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int {
                        if (reads++ > 0) {
                            secondRead.complete(Unit)
                            awaitCancellation()
                        }
                        return real.readAt(offset, buf, bufOffset, length)
                    }
                }
            }
        }
        val r = rig(stalling)
        val first = writeSource(r.work, "first.bin", 1_500_000)
        val second = writeSource(r.work, "second.bin", 3_000_000)
        val job = launch(Dispatchers.Default) {
            r.svc.prepare(listOf(picked(first), picked(second)), onProgress = { _, _ -> })
        }
        secondRead.await()
        assertEquals(2, storeFileNames(r.storeDir).size, "first copy and second tmp exist mid-hash")
        job.cancelAndJoin()
        assertEquals(emptyList(), storeFileNames(r.storeDir), "cancel must leave no copy")
        assertTrue(r.descriptors.copyDescriptorsLeastRecentlyUsed().isEmpty())
    }

    @Test
    fun progress_isPerByte_andQuotaKeepsTheSendsCopies() = runTest {
        val r = rig()
        val a = writeSource(r.work, "a.bin", 3_000_000)
        val b = writeSource(r.work, "b.bin", 1_000_000)
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = r.svc.prepare(listOf(picked(a), picked(b))) { hashed, total -> progress += hashed to total }
        val attachments = assertIs<PrepareResult.Ready>(result).attachments
        assertTrue(progress.size > 20, "progress must move while a file hashes, got ${progress.size} updates")
        assertEquals(progress, progress.sortedBy { it.first })
        assertEquals(4_000_000L to 4_000_000L, progress.last())
        assertEquals(attachments.map { it.fileId }.toSet(), r.quota.keeps.single())
    }

    @Test
    fun release_afterFailedAppend_deletesOnlyWhatThisSendCreated() = runTest {
        val r = rig()
        val older = writeSource(r.work, "older.bin", 1_000_000)
        val olderSend = assertIs<PrepareResult.Ready>(r.svc.prepare(listOf(picked(older))) { _, _ -> })
        r.svc.confirm(olderSend.attachments)
        val fresh = writeSource(r.work, "fresh.bin", 1_200_000)
        val send = assertIs<PrepareResult.Ready>(r.svc.prepare(listOf(picked(older), picked(fresh))) { _, _ -> })
        r.svc.release(send.attachments)
        val (olderId, freshId) = send.attachments.map { it.fileId }
        assertTrue(r.descriptors.findById(olderId) != null, "a copy an earlier send made stays")
        assertNull(r.descriptors.findById(freshId))
        assertEquals(listOf(olderId.value), storeFileNames(r.storeDir))
    }

    @Test
    fun cleanTmp_deletesPartialCopies() = runTest {
        val r = rig()
        files.createDirectories(r.storeDir)
        val tmp = Path(r.storeDir.toString(), "tmp-123-abc")
        files.openAppendWriter(tmp, null).use { it.write(ByteArray(10)); it.flushAndSync() }
        assertTrue(files.exists(tmp))
        r.svc.cleanTmp()
        assertTrue(!files.exists(tmp), "killed send partial cleaned on start")
    }
}
