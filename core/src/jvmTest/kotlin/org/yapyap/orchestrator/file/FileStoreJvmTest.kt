package org.yapyap.orchestrator.file

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
import org.yapyap.orchestrator.file.receive.FileRetention
import org.yapyap.orchestrator.file.receive.ReceiveEngine
import org.yapyap.orchestrator.file.receive.ReceivePaths
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.routing.file.port.FileReadResult
import org.yapyap.testfixtures.importHeld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private class TickClock : Clock {
    private var t = Instant.fromEpochSeconds(1_700_000_000)
    override fun now(): Instant {
        t += 1.seconds
        return t
    }
}

class FileStoreJvmTest {
    private val crypto = DefaultCryptoProvider()
    private val files = JvmFileAccess()

    private suspend fun tempDir(prefix: String): Path {
        val dir = Path(SystemTemporaryDirectory.toString(), "$prefix-${Uuid.random()}")
        files.createDirectories(dir)
        return dir
    }

    private fun storeFileNames(storeDir: Path): List<String> =
        java.io.File(storeDir.toString()).list()?.sorted().orEmpty()

    private suspend fun quotaFor(
        db: YapYapDatabase,
        store: FileStore,
        work: Path,
        storeDir: Path,
        config: MutableStateFlow<OrchestratorConfig>,
    ): FileRetention {
        val receives = DefaultFileReceiveStore(db)
        val descriptors = DefaultFileDescriptorStore(db)
        val paths = ReceivePaths(Path(work.toString(), "incoming"), storeDir)
        val engine = ReceiveEngine(receives, descriptors, crypto, files, paths, config, store)
        return FileRetention(
            receives, descriptors, DefaultMessageRepository(db), engine, store, files, paths,
            emptyFlow(), config,
        )
    }

    private suspend fun writeSource(dir: Path, name: String, bytes: ByteArray): Path {
        val p = Path(dir.toString(), name)
        files.openAppendWriter(p, null).use {
            it.write(bytes)
            it.flushAndSync()
        }
        return p
    }

    @Test
    fun copy_thenDeleteOriginal_stillServed() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-copy")
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store = DefaultFileStore(
            descriptors, crypto, files, storeDir,
            MutableStateFlow(OrchestratorConfig()), TickClock(),
        )
        val original = writeSource(work, "photo.bin", ByteArray(100_000) { (it % 251).toByte() })
        val descriptor = store.importHeld(FileImportSource.PathSource(original), "photo.bin", null, keepCopy = true)
        assertEquals(FileStorage.COPY, descriptor.storage)
        files.delete(original)
        val resolved = assertNotNull(descriptors.findById(descriptor.fileId))
        assertTrue(files.exists(Path(resolved.path)))
        val source = DefaultFilePlaintextSource(descriptors, store, files, crypto)
        val got = source.read(descriptor.fileId, 0, 4096)
        check(got is FileReadResult.Ok)
        assertEquals(4096, got.bytes.size)
    }

    @Test
    fun reference_thenEditOriginal_marksSourceLost() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-ref")
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store =
            DefaultFileStore(descriptors, crypto, files, storeDir, MutableStateFlow(OrchestratorConfig()), TickClock())
        val bytes = ByteArray(2_000_000) { (it * 7).toByte() }
        val original = writeSource(work, "orig.bin", bytes)
        val descriptor = store.importHeld(FileImportSource.PathSource(original), "orig.bin", null, keepCopy = false)
        assertEquals(FileStorage.REFERENCE, descriptor.storage)
        // Edit one byte in the second block (block size is 1 MiB here).
        val editAt = 1_500_000L
        files.openAppendWriter(original, null).use { _ -> }
        java.io.RandomAccessFile(original.toString(), "rw").use { raf ->
            raf.seek(editAt)
            raf.write(0xFF)
        }
        val source = DefaultFilePlaintextSource(descriptors, store, files, crypto)
        val res = source.read(descriptor.fileId, editAt - 100, 4096)
        check(res is FileReadResult.SourceLost) {
            "expected SourceLost but was $res"
        }
        assertEquals(FileDescriptorStatus.SOURCE_LOST, assertNotNull(descriptors.findById(descriptor.fileId)).status)
    }

    @Test
    fun quotaEviction_removesOldestCopy_neverReferences() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-quota")
        val storeDir = Path(work.toString(), "store")
        val config = MutableStateFlow(OrchestratorConfig(storeQuotaBytes = 2_500_000L))
        val descriptors = DefaultFileDescriptorStore(db)
        val store = DefaultFileStore(descriptors, crypto, files, storeDir, config, TickClock())
        val quota = quotaFor(db, store, work, storeDir, config)
        // Reference file: must survive quota pressure.
        val refPath = writeSource(work, "ref.bin", ByteArray(500_000) { 9 })
        val ref = store.importHeld(FileImportSource.PathSource(refPath), "ref.bin", null, keepCopy = false)
        // Three 1 MB copies under a 2.5 MB quota: the first must be evicted.
        val copies = (0..2).map { i ->
            val p = writeSource(work, "c$i.bin", ByteArray(1_000_000) { (it + i).toByte() })
            store.importHeld(FileImportSource.PathSource(p), "c$i.bin", null, keepCopy = true)
        }
        quota.enforceQuota()
        assertNull(descriptors.findById(copies[0].fileId), "oldest copy must be evicted")
        assertFalse(files.exists(Path(copies[0].path)), "its bytes must be deleted")
        assertNotNull(descriptors.findById(copies[1].fileId))
        assertNotNull(descriptors.findById(copies[2].fileId))
        assertNotNull(descriptors.findById(ref.fileId), "references are never evicted")
        assertTrue(files.exists(refPath))
    }

    @Test
    fun lostCopy_reimportedWithoutCopy_becomesReference_andQuotaNeverDeletesTheOriginal() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-lostcopy")
        val storeDir = Path(work.toString(), "store")
        val config = MutableStateFlow(OrchestratorConfig(storeQuotaBytes = 1L))
        val descriptors = DefaultFileDescriptorStore(db)
        val store = DefaultFileStore(descriptors, crypto, files, storeDir, config, TickClock())
        val bytes = ByteArray(2_000_000) { (it * 13).toByte() }
        val original = writeSource(work, "pic.bin", bytes)
        val copy = store.importHeld(FileImportSource.PathSource(original), "pic.bin", null, keepCopy = true)
        // The store copy is lost: serving finds it gone.
        files.delete(Path(copy.path))
        val source = DefaultFilePlaintextSource(descriptors, store, files, crypto)
        check(source.read(copy.fileId, 0, 4096) is FileReadResult.SourceLost)
        // Re-sent without keeping a copy: the user's file becomes the reference.
        val again = store.importHeld(FileImportSource.PathSource(original), "pic.bin", null, keepCopy = false)
        assertEquals(FileStorage.REFERENCE, again.storage)
        assertEquals(original.toString(), again.path)
        assertEquals(FileStorage.REFERENCE, descriptors.findById(copy.fileId)?.storage)
        quotaFor(db, store, work, storeDir, config).enforceQuota()
        assertTrue(files.exists(original), "quota must never delete the user's own file")
        val served = source.read(copy.fileId, 0, 4096)
        check(served is FileReadResult.Ok) { "expected Ok but was $served" }
    }

    @Test
    fun lostCopy_reimportedWithCopy_restoresTheBytes() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-restore")
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store =
            DefaultFileStore(descriptors, crypto, files, storeDir, MutableStateFlow(OrchestratorConfig()), TickClock())
        val original = writeSource(work, "doc.bin", ByteArray(1_500_000) { (it % 97).toByte() })
        val copy = store.importHeld(FileImportSource.PathSource(original), "doc.bin", null, keepCopy = true)
        files.delete(Path(copy.path))
        val source = DefaultFilePlaintextSource(descriptors, store, files, crypto)
        check(source.read(copy.fileId, 0, 4096) is FileReadResult.SourceLost)
        val again = store.importHeld(FileImportSource.PathSource(original), "doc.bin", null, keepCopy = true)
        assertEquals(FileDescriptorStatus.HELD, again.status)
        assertTrue(files.exists(Path(again.path)))
        check(source.read(copy.fileId, 0, 4096) is FileReadResult.Ok)
        assertEquals(listOf(copy.fileId.value), storeFileNames(storeDir))
    }

    @Test
    fun reimportHeldCopy_leavesNoTmp() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-dup")
        val storeDir = Path(work.toString(), "store")
        val store = DefaultFileStore(
            DefaultFileDescriptorStore(db),
            crypto,
            files,
            storeDir,
            MutableStateFlow(OrchestratorConfig()),
            TickClock(),
        )
        val original = writeSource(work, "a.bin", ByteArray(1_200_000) { (it % 31).toByte() })
        val first = store.importHeld(FileImportSource.PathSource(original), "a.bin", null, keepCopy = true)
        val second = store.import(FileImportSource.PathSource(original), "a.bin", null, keepCopy = true)
        check(second is ImportResult.Imported && !second.created)
        assertEquals(listOf(first.fileId.value), storeFileNames(storeDir))
    }

    @Test
    fun cancelledImport_deletesItsTmp() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-cancel")
        val storeDir = Path(work.toString(), "store")
        val firstRead = CompletableDeferred<Unit>()
        // The second read of the source never returns, like a slow disk mid-hash.
        val stalling = object : FileAccess by files {
            override suspend fun openPositionedReader(path: Path, startOffset: Long): PositionedHandle {
                val real = files.openPositionedReader(path, startOffset)
                return object : PositionedHandle by real {
                    var reads = 0
                    override suspend fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int {
                        if (reads++ > 0) {
                            firstRead.complete(Unit)
                            awaitCancellation()
                        }
                        return real.readAt(offset, buf, bufOffset, length)
                    }
                }
            }
        }
        val store = DefaultFileStore(
            DefaultFileDescriptorStore(db),
            crypto,
            stalling,
            storeDir,
            MutableStateFlow(OrchestratorConfig()),
            TickClock(),
        )
        val original = writeSource(work, "big.bin", ByteArray(3_000_000) { (it % 211).toByte() })
        val job = launch(Dispatchers.Default) {
            store.import(FileImportSource.PathSource(original), "big.bin", null, keepCopy = true)
        }
        firstRead.await()
        assertEquals(1, storeFileNames(storeDir).count { it.startsWith("tmp-") }, "the tmp exists mid-hash")
        job.cancelAndJoin()
        assertEquals(emptyList(), storeFileNames(storeDir), "cancel must delete the partial copy")
    }

    @Test
    fun missingSource_isUnreadable_andFullDisk_isNoSpace() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-refuse")
        val storeDir = Path(work.toString(), "store")
        val config = MutableStateFlow(OrchestratorConfig())
        val store = DefaultFileStore(DefaultFileDescriptorStore(db), crypto, files, storeDir, config, TickClock())
        val missing = Path(work.toString(), "gone.bin")
        assertEquals(
            ImportResult.SourceUnreadable,
            store.import(FileImportSource.PathSource(missing), "gone.bin", null, keepCopy = true),
        )
        val full = object : FileAccess by files {
            override suspend fun freeSpaceBytes(directory: Path): Long = 1_000L
        }
        val fullStore = DefaultFileStore(DefaultFileDescriptorStore(db), crypto, full, storeDir, config, TickClock())
        val original = writeSource(work, "x.bin", ByteArray(1_000_000) { 1 })
        assertEquals(
            ImportResult.NoSpace,
            fullStore.import(FileImportSource.PathSource(original), "x.bin", null, keepCopy = true),
        )
        assertEquals(emptyList(), storeFileNames(storeDir))
    }

    @Test
    fun reimportSourceLost_servesAgain() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-relost")
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store =
            DefaultFileStore(descriptors, crypto, files, storeDir, MutableStateFlow(OrchestratorConfig()), TickClock())
        val bytes = ByteArray(2_000_000) { (it * 7).toByte() }
        val original = writeSource(work, "orig.bin", bytes)
        val descriptor = store.importHeld(FileImportSource.PathSource(original), "orig.bin", null, keepCopy = false)
        // Rot the reference to mark SOURCE_LOST.
        java.io.RandomAccessFile(original.toString(), "rw").use { raf ->
            raf.seek(1_500_000L)
            raf.write(0xFF)
        }
        val source = DefaultFilePlaintextSource(descriptors, store, files, crypto)
        val lost = source.read(descriptor.fileId, 1_500_000L - 100, 4096)
        check(lost is FileReadResult.SourceLost)
        assertEquals(FileDescriptorStatus.SOURCE_LOST, descriptors.findById(descriptor.fileId)?.status)
        // Restore the original bytes and re-import: a matching hash goes back to HELD.
        files.delete(original)
        writeSource(work, "orig.bin", bytes)
        val again = store.importHeld(FileImportSource.PathSource(original), "orig.bin", null, keepCopy = false)
        assertEquals(FileDescriptorStatus.HELD, again.status)
        assertEquals(descriptor.fileId, again.fileId)
        val served = source.read(descriptor.fileId, 1_500_000L - 100, 4096)
        check(served is FileReadResult.Ok) { "expected Ok after re-import but was $served" }
        assertEquals(bytes.copyOfRange(1_500_000 - 100, 1_500_000 - 100 + 4096).toList(), served.bytes.toList())
    }

    @Test
    fun importExistingReference_withKeepCopy_upgradesToCopy() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-upgrade")
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store =
            DefaultFileStore(descriptors, crypto, files, storeDir, MutableStateFlow(OrchestratorConfig()), TickClock())
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val original = writeSource(work, "up.bin", bytes)
        val ref = store.importHeld(FileImportSource.PathSource(original), "up.bin", null, keepCopy = false)
        assertEquals(FileStorage.REFERENCE, ref.storage)
        val upgraded = store.importHeld(FileImportSource.PathSource(original), "up.bin", null, keepCopy = true)
        assertEquals(ref.fileId, upgraded.fileId)
        assertEquals(FileStorage.COPY, upgraded.storage)
        assertTrue(files.exists(Path(upgraded.path)), "upgraded copy must exist in the store")
    }
}
