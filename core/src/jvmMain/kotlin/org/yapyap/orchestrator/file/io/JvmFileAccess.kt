package org.yapyap.orchestrator.file.io

import kotlinx.io.files.Path
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * JVM `FileAccess` (design §11): `FileChannel`-backed positioned reads,
 * append-only writes with an explicit fsync, and volume free-space.
 *
 * Content URIs do not occur on JVM: both content methods return null.
 */
internal class JvmFileAccess : FileAccess {
    override fun exists(path: Path): Boolean = Files.exists(nio(path))

    override fun createDirectories(path: Path) {
        Files.createDirectories(nio(path))
    }

    override fun fileSize(path: Path): Long? {
        val p = nio(path)
        if (!Files.exists(p)) return null
        return Files.size(p)
    }

    override fun freeSpaceBytes(directory: Path): Long = File(directory.toString()).usableSpace

    override fun delete(path: Path, mustExist: Boolean) {
        if (mustExist) {
            Files.delete(nio(path))
        } else {
            Files.deleteIfExists(nio(path))
        }
    }

    override fun move(source: Path, target: Path) {
        target.toString().let { t ->
            java.io.File(t).parentFile?.mkdirs()
        }
        try {
            Files.move(nio(source), nio(target), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(
                nio(source),
                nio(target),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    override fun openAppendWriter(path: Path, truncateToLength: Long?): AppendHandle {
        val p = nio(path)
        p.parent?.let { Files.createDirectories(it) }
        val raf = RandomAccessFile(path.toString(), "rw")
        val channel = raf.channel
        if (truncateToLength != null && channel.size() != truncateToLength) {
            raf.setLength(truncateToLength)
        }
        // Torn appends are cut at open to the persisted length; the writer then appends only.
        channel.position(channel.size())
        return JvmAppendHandle(channel, raf)
    }

    override fun openPositionedReader(path: Path, startOffset: Long): PositionedHandle {
        require(startOffset >= 0) { "startOffset must be >= 0 but was $startOffset" }
        val channel = FileChannel.open(nio(path), StandardOpenOption.READ)
        return JvmPositionedHandle(channel, startOffset)
    }

    override suspend fun contentSize(uri: String): Long? = null

    override fun openContentSource(uri: String): kotlinx.io.Source? = null

    override fun openContentPositionedReader(uri: String, startOffset: Long): PositionedHandle? = null

    private fun nio(path: Path) = Paths.get(path.toString())

    private class JvmAppendHandle(
        private val channel: FileChannel,
        private val raf: RandomAccessFile,
    ) : AppendHandle {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
                "offset ($offset) + length ($length) exceeds size ${bytes.size}"
            }
            if (length == 0) return
            val buf = java.nio.ByteBuffer.wrap(bytes, offset, length)
            while (buf.hasRemaining()) {
                channel.write(buf)
            }
        }

        override fun flushAndSync() {
            channel.force(true)
        }

        override fun truncate(length: Long) {
            require(length >= 0) { "length must be >= 0 but was $length" }
            raf.setLength(length)
            channel.position(channel.size())
        }

        override fun size(): Long = channel.size()

        override fun close() {
            runCatching { channel.close() }
            runCatching { raf.close() }
        }
    }

    private class JvmPositionedHandle(
        private val channel: FileChannel,
        private val startOffset: Long,
    ) : PositionedHandle {
        override fun size(): Long = channel.size()

        override fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int {
            require(offset >= 0) { "offset must be >= 0 but was $offset" }
            require(bufOffset >= 0 && length >= 0 && bufOffset + length <= buf.size) {
                "bufOffset ($bufOffset) + length ($length) exceeds size ${buf.size}"
            }
            if (length == 0) return 0
            val byteBuf = java.nio.ByteBuffer.wrap(buf, bufOffset, length)
            return channel.read(byteBuf, startOffset + offset)
        }

        override fun close() {
            runCatching { channel.close() }
        }
    }
}
