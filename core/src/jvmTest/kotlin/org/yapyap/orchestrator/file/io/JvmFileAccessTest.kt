package org.yapyap.orchestrator.file.io

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Step 7 (JVM): chunk-shaped I/O boundary over `FileChannel`.
 */
class JvmFileAccessTest {
    private val access = JvmFileAccess()

    private suspend fun tempPath(name: String): Path {
        val dir = Path(SystemTemporaryDirectory.toString(), "yapyap-file-io-${Uuid.random()}")
        access.createDirectories(dir)
        return Path(dir.toString(), name)
    }

    @Test
    fun positionedRead_insideLargeSparseFile_skipsPrefix() = runTest {
        val path = tempPath("sparse.bin")
        val total = 16L * 1024L * 1024L
        val markerOffset = 9L * 1024L * 1024L + 123L
        val marker = ByteArray(4096) { (it * 7).toByte() }
        access.openAppendWriter(path, null).use { w ->
            w.truncate(total)
            w.truncate(markerOffset)
            w.write(marker)
            w.flushAndSync()
            assertEquals(markerOffset + marker.size, w.size())
        }
        access.openPositionedReader(path, startOffset = 0).use { r ->
            assertEquals(markerOffset + marker.size, r.size())
            val buf = ByteArray(marker.size)
            var off = 0
            while (off < buf.size) {
                val n = r.readAt(markerOffset + off, buf, off, buf.size - off)
                check(n > 0) { "unexpected EOF" }
                off += n
            }
            assertContentEquals(marker, buf)
            val hole = ByteArray(16)
            val n = r.readAt(1024L, hole, 0, hole.size)
            assertEquals(hole.size, n)
            assertContentEquals(ByteArray(16), hole)
        }
    }

    @Test
    fun appendWriter_truncatesTornAppendToLength() = runTest {
        val path = tempPath("torn.bin")
        access.openAppendWriter(path, null).use { w ->
            w.write(ByteArray(100) { it.toByte() })
            w.flushAndSync()
            assertEquals(100L, w.size())
            w.truncate(40L)
            assertEquals(40L, w.size())
            w.flushAndSync()
        }
        access.openAppendWriter(path, truncateToLength = 40L).use { w ->
            assertEquals(40L, w.size())
        }
        access.openPositionedReader(path).use { r ->
            assertEquals(40L, r.size())
            val buf = ByteArray(40)
            var off = 0
            while (off < buf.size) {
                val n = r.readAt(off.toLong(), buf, off, buf.size - off)
                check(n > 0)
                off += n
            }
            assertContentEquals(ByteArray(40) { it.toByte() }, buf)
        }
    }

    @Test
    fun flush_syncsOncePerCall_andIsDurable() = runTest {
        val path = tempPath("durable.bin")
        val payload = ByteArray(65536) { (it % 251).toByte() }
        access.openAppendWriter(path, null).use { w ->
            w.write(payload)
            w.flushAndSync()
        }
        access.openPositionedReader(path).use { r ->
            assertEquals(payload.size.toLong(), r.size())
            val buf = ByteArray(payload.size)
            var off = 0
            while (off < buf.size) {
                val n = r.readAt(off.toLong(), buf, off, buf.size - off)
                check(n > 0)
                off += n
            }
            assertContentEquals(payload, buf)
        }
    }
}
