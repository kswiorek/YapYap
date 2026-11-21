package org.yapyap.orchestrator.file

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.io.PositionedHandle
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.blockSizeFor
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.testfixtures.importHeld
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Counts positioned reads: total bytes served, call count, largest single read, lowest offset. */
internal class CountingAccess(private val delegate: FileAccess = JvmFileAccess()) : FileAccess by delegate {
    var readCalls: Long = 0
    var bytesRead: Long = 0
    var maxLength: Int = 0
    var minAbsoluteOffset: Long = Long.MAX_VALUE

    fun reset() {
        readCalls = 0
        bytesRead = 0
        maxLength = 0
        minAbsoluteOffset = Long.MAX_VALUE
    }

    override suspend fun openPositionedReader(path: Path, startOffset: Long): PositionedHandle {
        val inner = delegate.openPositionedReader(path, startOffset)
        return object : PositionedHandle {
            override suspend fun size(): Long = inner.size()
            override suspend fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int {
                readCalls++
                if (length > maxLength) maxLength = length
                val absolute = startOffset + offset
                if (absolute < minAbsoluteOffset) minAbsoluteOffset = absolute
                val n = inner.readAt(offset, buf, bufOffset, length)
                if (n > 0) bytesRead += n
                return n
            }

            override fun close() = inner.close()
        }
    }
}

/**
 * Serves [targetStart, targetEnd) intact until [blockSize] bytes of it were read (the first
 * full-block hashing pass), then flips subsequent bytes (the re-read/streaming pass). Lets a
 * test emulate a referenced original edited between the two passes of one block.
 */
internal class FlipBetweenPassesAccess(
    private val delegate: FileAccess = JvmFileAccess(),
    private val targetStart: Long,
    private val targetEnd: Long,
    private val blockSize: Long,
) : FileAccess by delegate {
    override suspend fun openPositionedReader(path: Path, startOffset: Long): PositionedHandle {
        val inner = delegate.openPositionedReader(path, startOffset)
        var servedInTarget = 0L
        return object : PositionedHandle {
            override suspend fun size(): Long = inner.size()
            override suspend fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int {
                val n = inner.readAt(offset, buf, bufOffset, length)
                if (n <= 0) return n
                val absolute = startOffset + offset
                val overlapStart = maxOf(absolute, targetStart)
                val overlapEnd = minOf(absolute + n, targetEnd)
                if (overlapEnd <= overlapStart) return n
                val alreadyServed = servedInTarget
                servedInTarget += overlapEnd - overlapStart
                if (alreadyServed >= blockSize) {
                    val from = (overlapStart - absolute).toInt() + bufOffset
                    val until = (overlapEnd - absolute).toInt() + bufOffset
                    for (i in from until until) buf[i] = (buf[i].toInt() xor 0xFF).toByte()
                }
                return n
            }

            override fun close() = inner.close()
        }
    }
}

class FilePlaintextSourceJvmTest {
    private val crypto = DefaultCryptoProvider()

    private suspend fun tempDir(prefix: String): Path {
        val access = JvmFileAccess()
        val dir = Path(SystemTemporaryDirectory.toString(), "$prefix-${Uuid.random()}")
        access.createDirectories(dir)
        return dir
    }

    private data class Fixture(
        val descriptors: DefaultFileDescriptorStore,
        val store: DefaultFileStore,
        val work: Path,
        val storeDir: Path,
    )

    private suspend fun fixture(prefix: String, files: FileAccess): Fixture {
        val db = openMemoryDatabase().database
        val work = tempDir(prefix)
        val storeDir = Path(work.toString(), "store")
        val descriptors = DefaultFileDescriptorStore(db)
        val store = DefaultFileStore(
            descriptors,
            crypto,
            files,
            storeDir,
            MutableStateFlow(OrchestratorConfig()),
            Clock.System,
        )
        return Fixture(descriptors, store, work, storeDir)
    }

    /** Reads to EOF (or SourceLost), returning the collected bytes and the terminal read. */
    private suspend fun drain(stream: PlaintextStream, maxBytes: Int): Pair<ByteArray, PlaintextRead> {
        val parts = mutableListOf<ByteArray>()
        var total = 0
        while (true) {
            when (val r = stream.read(maxBytes)) {
                is PlaintextRead.Bytes -> {
                    parts += r.bytes
                    total += r.bytes.size
                }

                else -> {
                    val all = ByteArray(total)
                    var p = 0
                    for (part in parts) {
                        part.copyInto(all, p)
                        p += part.size
                    }
                    return all to r
                }
            }
        }
    }

    @Test
    fun sequentialServe_of1GiB_readsEachByteAtMostTwice() = runTest {
        val counting = CountingAccess()
        val f = fixture("yapyap-plaintext-gib", counting)
        val total = 1024L * 1024L * 1024L
        assertEquals(1024 * 1024, blockSizeFor(total))
        // Fully sparse: holes read back as zeros without allocating 1 GiB.
        val src = Path(f.work.toString(), "big.bin")
        counting.openAppendWriter(src, null).use { w ->
            w.truncate(total)
            w.flushAndSync()
        }
        val descriptor = f.store.importHeld(
            FileImportSource.PathSource(src), "big.bin", "application/octet-stream", keepCopy = false,
        )
        assertEquals(total, descriptor.totalBytes)
        counting.reset()
        val source = DefaultFilePlaintextSource(f.descriptors, f.store, counting, crypto)
        val stream = assertNotNull(source.open(descriptor.fileId, 0))
        var served = 0L
        var maxChunk = 0
        try {
            while (true) {
                when (val r = stream.read(1024 * 1024)) {
                    is PlaintextRead.Bytes -> {
                        served += r.bytes.size
                        if (r.bytes.size > maxChunk) maxChunk = r.bytes.size
                        assertTrue(r.bytes.all { it == 0.toByte() }, "sparse holes must read as zeros")
                    }

                    is PlaintextRead.Eof -> break
                    is PlaintextRead.SourceLost -> fail("unexpected SourceLost at block ${r.blockIndex}")
                }
            }
        } finally {
            stream.close()
        }
        assertEquals(total, served)
        // Two passes per block (hash, then re-read and stream): at most twice the bytes...
        assertTrue(counting.readCalls > 0)
        assertTrue(counting.bytesRead <= 2 * total, "read ${counting.bytesRead} bytes for $total")
        // ...in bounded internal reads no matter how large the caller's maxBytes is.
        assertTrue(
            counting.maxLength <= DefaultFilePlaintextSource.BUFFER_BYTES,
            "internal read too large: ${counting.maxLength}",
        )
        assertTrue(maxChunk <= 1024 * 1024)
    }

    @Test
    fun serve_startingMidBlock_returnsExactSlice() = runTest {
        val access = JvmFileAccess()
        val f = fixture("yapyap-plaintext-mid", access)
        val bytes = ByteArray(3_000_000) { (it % 251).toByte() }
        val blockSize = blockSizeFor(bytes.size.toLong())
        assertEquals(1024 * 1024, blockSize)
        val src = Path(f.work.toString(), "mid.bin")
        access.openAppendWriter(src, null).use {
            it.write(bytes)
            it.flushAndSync()
        }
        val descriptor = f.store.importHeld(FileImportSource.PathSource(src), "mid.bin", null, keepCopy = true)
        val source = DefaultFilePlaintextSource(f.descriptors, f.store, access, crypto)
        // Start mid-block: the re-hash covers the whole block, the serve hands out the suffix.
        val offset = blockSize / 2L
        val stream = assertNotNull(source.open(descriptor.fileId, offset))
        try {
            val (collected, terminal) = drain(stream, 128 * 1024)
            assertTrue(terminal is PlaintextRead.Eof, "expected EOF but was $terminal")
            assertEquals(bytes.size - offset.toInt(), collected.size)
            assertContentEquals(bytes.copyOfRange(offset.toInt(), bytes.size), collected)
            assertContentEquals(
                bytes.copyOfRange(offset.toInt(), offset.toInt() + 1024),
                collected.copyOfRange(0, 1024)
            )
        } finally {
            stream.close()
        }
    }

    @Test
    fun rottenBlock_stopsAtBlock_andMarksDescriptor() = runTest {
        val access = JvmFileAccess()
        val f = fixture("yapyap-plaintext-rot", access)
        val bytes = ByteArray(3_000_000) { (it * 3).toByte() }
        val blockSize = blockSizeFor(bytes.size.toLong())
        assertEquals(1024 * 1024, blockSize)
        val src = Path(f.work.toString(), "rot.bin")
        access.openAppendWriter(src, null).use {
            it.write(bytes)
            it.flushAndSync()
        }
        val descriptor = f.store.importHeld(FileImportSource.PathSource(src), "rot.bin", null, keepCopy = true)
        // Rot the stored copy inside block 1.
        java.io.RandomAccessFile(Path(descriptor.path).toString(), "rw").use { raf ->
            raf.seek(1_200_000L)
            raf.write(0x00)
            raf.write(0x01)
        }
        val source = DefaultFilePlaintextSource(f.descriptors, f.store, access, crypto)
        val stream = assertNotNull(source.open(descriptor.fileId, 0))
        try {
            val (collected, terminal) = drain(stream, 128 * 1024)
            check(terminal is PlaintextRead.SourceLost) { "expected SourceLost but was $terminal" }
            assertEquals(1, terminal.blockIndex)
            // Only the verified block 0 was handed out; nothing past it.
            assertEquals(blockSize, collected.size)
            assertContentEquals(bytes.copyOfRange(0, blockSize), collected)
        } finally {
            stream.close()
        }
        assertEquals(
            FileDescriptorStatus.SOURCE_LOST,
            f.descriptors.findById(descriptor.fileId)?.status,
        )
    }

    @Test
    fun blockChangedBetweenPasses_stopsAtBlock() = runTest {
        val access = JvmFileAccess()
        val f = fixture("yapyap-plaintext-flip", access)
        val bytes = ByteArray(3_000_000) { (it * 5 + 1).toByte() }
        val blockSize = blockSizeFor(bytes.size.toLong())
        assertEquals(1024 * 1024, blockSize)
        val src = Path(f.work.toString(), "flip.bin")
        access.openAppendWriter(src, null).use {
            it.write(bytes)
            it.flushAndSync()
        }
        val descriptor = f.store.importHeld(FileImportSource.PathSource(src), "flip.bin", null, keepCopy = true)
        // Block 1 reads intact on the first (hashing) pass, corrupted on the re-read pass.
        val flip = FlipBetweenPassesAccess(
            delegate = JvmFileAccess(),
            targetStart = blockSize.toLong(),
            targetEnd = 2L * blockSize,
            blockSize = blockSize.toLong(),
        )
        val source = DefaultFilePlaintextSource(f.descriptors, f.store, flip, crypto)
        val stream = assertNotNull(source.open(descriptor.fileId, 0))
        try {
            val (collected, terminal) = drain(stream, 128 * 1024)
            check(terminal is PlaintextRead.SourceLost) { "expected SourceLost but was $terminal" }
            assertEquals(1, terminal.blockIndex)
            // Block 0 was verified before the flip; its bytes match the original.
            assertTrue(collected.size >= blockSize)
            assertContentEquals(bytes.copyOfRange(0, blockSize), collected.copyOfRange(0, blockSize))
        } finally {
            stream.close()
        }
        assertEquals(
            FileDescriptorStatus.SOURCE_LOST,
            f.descriptors.findById(descriptor.fileId)?.status,
        )
    }

    @Test
    fun serve_withLargeMaxBytes_staysBounded() = runTest {
        val counting = CountingAccess()
        val f = fixture("yapyap-plaintext-bounded", counting)
        val bytes = ByteArray(1_200_000) { (it % 31).toByte() }
        val src = Path(f.work.toString(), "bounded.bin")
        counting.openAppendWriter(src, null).use {
            it.write(bytes)
            it.flushAndSync()
        }
        val descriptor = f.store.importHeld(FileImportSource.PathSource(src), "bounded.bin", null, keepCopy = true)
        counting.reset()
        val source = DefaultFilePlaintextSource(f.descriptors, f.store, counting, crypto)
        // 1 MiB exceeds the 256 KiB internal buffer: reads still work, nothing unbounded happens.
        val stream = assertNotNull(source.open(descriptor.fileId, 0))
        val parts = mutableListOf<ByteArray>()
        var total = 0
        var maxChunk = 0
        try {
            while (true) {
                when (val r = stream.read(1024 * 1024)) {
                    is PlaintextRead.Bytes -> {
                        assertTrue(r.bytes.size <= 1024 * 1024, "read returned ${r.bytes.size}")
                        if (r.bytes.size > maxChunk) maxChunk = r.bytes.size
                        parts += r.bytes
                        total += r.bytes.size
                    }

                    is PlaintextRead.Eof -> break
                    is PlaintextRead.SourceLost -> fail("unexpected SourceLost at block ${r.blockIndex}")
                }
            }
        } finally {
            stream.close()
        }
        val collected = ByteArray(total)
        var p = 0
        for (part in parts) {
            part.copyInto(collected, p)
            p += part.size
        }
        assertContentEquals(bytes, collected)
        assertTrue(maxChunk <= 1024 * 1024)
        assertTrue(maxChunk > 0)
        assertTrue(counting.readCalls > 0)
        assertTrue(
            counting.maxLength <= DefaultFilePlaintextSource.BUFFER_BYTES,
            "internal read too large: ${counting.maxLength}",
        )
    }
}
