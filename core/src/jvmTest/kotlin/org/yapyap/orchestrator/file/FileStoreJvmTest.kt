package org.yapyap.orchestrator.file

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.persistence.openMemoryDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private class TickClock : Clock {
    private var t = Instant.fromEpochSeconds(1_700_000_000)
    override fun now(): Instant {
        t += kotlin.time.Duration.parse("1s")
        return t
    }
}

class FileStoreJvmTest {
    private val crypto = DefaultCryptoProvider()
    private val files = JvmFileAccess()

    private fun tempDir(prefix: String): Path {
        val dir = Path(SystemTemporaryDirectory.toString(), "$prefix-${Uuid.random()}")
        files.createDirectories(dir)
        return dir
    }

    private fun writeSource(dir: Path, name: String, bytes: ByteArray): Path {
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
        val store = DefaultFileStore(
            DefaultFileDescriptorStore(db), crypto, files, storeDir,
            MutableStateFlow(OrchestratorConfig()), TickClock(),
        )
        val original = writeSource(work, "photo.bin", ByteArray(100_000) { (it % 251).toByte() })
        val descriptor = store.import(FileImportSource.PathSource(original), "photo.bin", null, keepCopy = true)
        assertEquals(FileStorage.COPY, descriptor.storage)
        files.delete(original)
        val resolved = assertNotNull(store.resolve(descriptor.fileId))
        assertTrue(files.exists(Path(resolved.path)))
        val source = DefaultFilePlaintextSource(DefaultFileDescriptorStore(db), store, files, crypto)
        val got = source.read(descriptor.fileId, 0, 4096)
        check(got is org.yapyap.routing.file.port.FileReadResult.Ok)
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
        val descriptor = store.import(FileImportSource.PathSource(original), "orig.bin", null, keepCopy = false)
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
        check(res is org.yapyap.routing.file.port.FileReadResult.SourceLost) {
            "expected SourceLost but was $res"
        }
        assertEquals(FileDescriptorStatus.SOURCE_LOST, assertNotNull(store.resolve(descriptor.fileId)).status)
    }

    @Test
    fun quotaEviction_removesOldestCopy_neverReferences() = runTest {
        val db = openMemoryDatabase().database
        val work = tempDir("yapyap-store-quota")
        val storeDir = Path(work.toString(), "store")
        val config = MutableStateFlow(OrchestratorConfig(storeQuotaBytes = 2_500_000L))
        val descriptors = DefaultFileDescriptorStore(db)
        val store = DefaultFileStore(descriptors, crypto, files, storeDir, config, TickClock())
        // Reference file: must survive quota pressure.
        val refBytes = ByteArray(500_000) { 9 }
        val refPath = writeSource(work, "ref.bin", refBytes)
        val ref = store.import(FileImportSource.PathSource(refPath), "ref.bin", null, keepCopy = false)
        // Three 1 MiB copies under a 2.5 MB quota: the first must be evicted.
        val copies = (0..2).map { i ->
            val p = writeSource(work, "c$i.bin", ByteArray(1_000_000) { (it + i).toByte() })
            store.import(FileImportSource.PathSource(p), "c$i.bin", null, keepCopy = true)
        }
        assertNull(store.resolve(copies[0].fileId), "oldest copy must be evicted")
        assertNotNull(store.resolve(copies[1].fileId))
        assertNotNull(store.resolve(copies[2].fileId))
        assertNotNull(store.resolve(ref.fileId), "references are never evicted")
    }
}
