package org.yapyap.orchestrator.file

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.io.AppendHandle
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.io.PositionedHandle
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.blockSizeFor
import org.yapyap.routing.file.port.FileReadResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Records the lowest absolute offset ever read: proves prefix-skipping. */
internal class RecordingAccess(private val delegate: FileAccess = JvmFileAccess()) : FileAccess by delegate {
    var minReadOffset: Long = Long.MAX_VALUE
    var readCalls: Long = 0

    fun resetCounters() {
        minReadOffset = Long.MAX_VALUE
        readCalls = 0
    }

    override fun openPositionedReader(path: Path, startOffset: Long): PositionedHandle {
        val inner = delegate.openPositionedReader(path, startOffset)
        return object : PositionedHandle {
            override fun size(): Long = inner.size()
            override fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int {
                readCalls++
                val absolute = startOffset + offset
                if (absolute < minReadOffset) minReadOffset = absolute
                return inner.readAt(offset, buf, bufOffset, length)
            }

            override fun close() = inner.close()
        }
    }

    override fun openAppendWriter(path: Path, truncateToLength: Long?): AppendHandle =
        delegate.openAppendWriter(path, truncateToLength)
}

class FilePlaintextSourceJvmTest {
    private val crypto = DefaultCryptoProvider()

    private fun tempDir(prefix: String): Path {
        val access = JvmFileAccess()
        val dir = Path(SystemTemporaryDirectory.toString(), "$prefix-${Uuid.random()}")
        access.createDirectories(dir)
        return dir
    }

    @Test
    fun serve_startingMidBlock_returnsExactSlice() = runTest {
        val db = openMemoryDatabase().database
        val access = JvmFileAccess()
        val work = tempDir("yapyap-plaintext-mid")
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store = DefaultFileStore(
            descriptors,
            crypto,
            access,
            storeDir,
            MutableStateFlow(OrchestratorConfig()),
            Clock.System
        )
        val bytes = ByteArray(3_000_000) { (it % 251).toByte() }
        val src = Path(work.toString(), "mid.bin")
        access.openAppendWriter(src, null).use {
            it.write(bytes)
            it.flushAndSync()
        }
        val descriptor = store.import(FileImportSource.PathSource(src), "mid.bin", null, keepCopy = true)
        val source = DefaultFilePlaintextSource(descriptors, store, access, crypto)
        // 1 MiB blocks here; start inside block 1 and span into block 2.
        val offset = 1_000_000L
        val res = source.read(descriptor.fileId, offset, 600_000)
        check(res is FileReadResult.Ok)
        assertEquals(600_000, res.bytes.size)
        assertContentEquals(bytes.copyOfRange(offset.toInt(), offset.toInt() + 600_000), res.bytes)
    }

    @Test
    fun rottenBlock_stopsAtBlock_andMarksDescriptor() = runTest {
        val db = openMemoryDatabase().database
        val access = JvmFileAccess()
        val work = tempDir("yapyap-plaintext-rot")
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store = DefaultFileStore(
            descriptors,
            crypto,
            access,
            storeDir,
            MutableStateFlow(OrchestratorConfig()),
            Clock.System
        )
        val bytes = ByteArray(3_000_000) { (it * 3).toByte() }
        val src = Path(work.toString(), "rot.bin")
        access.openAppendWriter(src, null).use {
            it.write(bytes)
            it.flushAndSync()
        }
        val descriptor = store.import(FileImportSource.PathSource(src), "rot.bin", null, keepCopy = true)
        // Rot the stored copy inside block 1.
        val stored = Path(descriptor.path.ifEmpty { storeDir.toString() })
        val actualStored = if (descriptor.storage == FileStorage.COPY) Path(descriptor.path) else src
        java.io.RandomAccessFile(actualStored.toString(), "rw").use { raf ->
            raf.seek(1_200_000L)
            raf.write(0x00)
            raf.write(0x01)
        }
        void(stored)
        val source = DefaultFilePlaintextSource(descriptors, store, access, crypto)
        val res = source.read(descriptor.fileId, 0, 2_500_000)
        check(res is FileReadResult.SourceLost) { "expected SourceLost but was $res" }
        assertEquals(1, res.blockIndex)
        assertEquals(
            FileDescriptorStatus.SOURCE_LOST,
            descriptors.findById(descriptor.fileId)?.status,
        )
    }

    @Test
    fun largeFile_servedFromHighOffset_withoutReadingPrefix() = runTest {
        val db = openMemoryDatabase().database
        val recording = RecordingAccess()
        val work = tempDir("yapyap-plaintext-gib")
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        // 128 MiB sparse file (1 MiB blocks, like the 1 GiB case in the plan:
        // both are <= 1024 blocks). Only the tail page is written; import still
        // hashes the logical bytes through positioned reads. A full 1 GiB file
        // would allocate 1 GiB on filesystems without sparse-file support.
        val total = 128L * 1024L * 1024L
        val src = Path(work.toString(), "big.bin")
        val tailAt = total - 4096
        val tail = ByteArray(4096) { (it * 13).toByte() }
        recording.openAppendWriter(src, null).use { w ->
            w.truncate(total)
            java.io.RandomAccessFile(src.toString(), "rw").use { raf ->
                raf.seek(tailAt)
                raf.write(tail)
            }
            w.flushAndSync()
        }
        val blockSize = blockSizeFor(total)
        assertEquals(1024 * 1024, blockSize)
        val store = DefaultFileStore(
            descriptors,
            crypto,
            recording,
            storeDir,
            MutableStateFlow(OrchestratorConfig()),
            Clock.System
        )
        val descriptor = store.import(
            FileImportSource.PathSource(src), "big.bin", "application/octet-stream", keepCopy = false,
        )
        recording.resetCounters()
        val source = DefaultFilePlaintextSource(descriptors, store, recording, crypto)
        val from = 100L * 1024L * 1024L
        val res = source.read(descriptor.fileId, from, 220 * 1024)
        check(res is FileReadResult.Ok)
        assertEquals(220 * 1024, res.bytes.size)
        assertTrue(res.bytes.all { it == 0.toByte() }, "100 MiB region of the sparse file must be zeros")
        assertTrue(recording.readCalls > 0)
        // Nothing below the containing block's start may have been touched.
        val blockStart = from / blockSize * blockSize
        assertTrue(
            recording.minReadOffset >= blockStart,
            "read prefix below block start: min=${recording.minReadOffset} blockStart=$blockStart",
        )
    }

    private fun void(@Suppress("UNUSED_PARAMETER") vararg ignored: Any?) = Unit
}
