package org.yapyap.orchestrator.file.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * JVM `FileAccess` (design §11): `FileChannel`-backed positioned reads,
 * append-only writes with an explicit fsync, and volume free-space.
 *
 * Every function switches to the IO dispatcher internally: no blocking fsync
 * ever runs on a router or node coroutine. Content URIs do not occur on JVM.
 */
internal class JvmFileAccess : FileAccess {
    override suspend fun exists(path: Path): Boolean = withContext(Dispatchers.IO) {
        Files.exists(nio(path))
    }

    override suspend fun listFileNames(dir: Path): List<String> = withContext(Dispatchers.IO) {
        val nioDir = nio(dir)
        if (!Files.isDirectory(nioDir)) return@withContext emptyList()
        Files.list(nioDir).use { entries ->
            entries.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
        }
    }

    override suspend fun createDirectories(path: Path): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(nio(path))
    }

    override suspend fun fileSize(path: Path): Long? = withContext(Dispatchers.IO) {
        val p = nio(path)
        if (!Files.exists(p)) return@withContext null
        Files.size(p)
    }

    override suspend fun freeSpaceBytes(directory: Path): Long = withContext(Dispatchers.IO) {
        File(directory.toString()).usableSpace
    }

    override suspend fun delete(path: Path, mustExist: Boolean): Unit = withContext(Dispatchers.IO) {
        if (mustExist) {
            Files.delete(nio(path))
        } else {
            Files.deleteIfExists(nio(path))
        }
    }

    override suspend fun move(source: Path, target: Path): Unit = withContext(Dispatchers.IO) {
        target.toString().let { t ->
            File(t).parentFile?.mkdirs()
        }
        // Atomic only: a non-atomic fallback would leave a torn final file on crash.
        Files.move(nio(source), nio(target), StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun syncDirectory(path: Path): Unit = withContext(Dispatchers.IO) {
        val dir = Paths.get(path.toString()).parent ?: return@withContext
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: AccessDeniedException) {
            // Windows cannot open a directory as a channel; its rename is durable without it.
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Directory fsync not supported here",
                fields = mapOf("dir" to dir.toString(), "error" to e.toString()),
            )
        } catch (e: UnsupportedOperationException) {
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Directory fsync not supported here",
                fields = mapOf("dir" to dir.toString(), "error" to e.toString()),
            )
        }
        // Any other failure (EIO) propagates: the rename may not be durable, so READY must wait.
    }

    override suspend fun openAppendWriter(path: Path, truncateToLength: Long?): AppendHandle =
        withContext(Dispatchers.IO) {
            val p = nio(path)
            p.parent?.let { Files.createDirectories(it) }
            val raf = RandomAccessFile(path.toString(), "rw")
            val channel = raf.channel
            if (truncateToLength != null && channel.size() != truncateToLength) {
                raf.setLength(truncateToLength)
            }
            channel.position(channel.size())
            JvmAppendHandle(channel, raf)
        }

    override suspend fun openPositionedReader(path: Path, startOffset: Long): PositionedHandle =
        withContext(Dispatchers.IO) {
            require(startOffset >= 0) { "startOffset must be >= 0 but was $startOffset" }
            val channel = FileChannel.open(nio(path), StandardOpenOption.READ)
            JvmPositionedHandle(channel, startOffset)
        }

    override suspend fun contentSize(uri: String): Long? = null

    override suspend fun openContentPositionedReader(uri: String, startOffset: Long): PositionedHandle? = null

    override suspend fun cleanTmpFiles(storeDir: Path, prefix: String): Unit = withContext(Dispatchers.IO) {
        val dir = Paths.get(storeDir.toString())
        if (!Files.isDirectory(dir)) return@withContext
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith(prefix) }.forEach { Files.deleteIfExists(it) }
        }
    }

    private fun nio(path: Path) = Paths.get(path.toString())

    private class JvmAppendHandle(
        private val channel: FileChannel,
        private val raf: RandomAccessFile,
    ) : AppendHandle {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int): Unit = withContext(Dispatchers.IO) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
                "offset ($offset) + length ($length) exceeds size ${bytes.size}"
            }
            if (length == 0) return@withContext
            val buf = ByteBuffer.wrap(bytes, offset, length)
            while (buf.hasRemaining()) {
                channel.write(buf)
            }
        }

        override suspend fun flushAndSync(): Unit = withContext(Dispatchers.IO) {
            channel.force(true)
        }

        override suspend fun truncate(length: Long): Unit = withContext(Dispatchers.IO) {
            require(length >= 0) { "length must be >= 0 but was $length" }
            raf.setLength(length)
            channel.position(channel.size())
        }

        override suspend fun size(): Long = withContext(Dispatchers.IO) { channel.size() }

        override fun close() {
            runCatching { channel.close() }
            runCatching { raf.close() }
        }
    }

    private class JvmPositionedHandle(
        private val channel: FileChannel,
        private val startOffset: Long,
    ) : PositionedHandle {
        override suspend fun size(): Long = withContext(Dispatchers.IO) { channel.size() }

        override suspend fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int =
            withContext(Dispatchers.IO) {
                require(offset >= 0) { "offset must be >= 0 but was $offset" }
                require(bufOffset >= 0 && length >= 0 && bufOffset + length <= buf.size) {
                    "bufOffset ($bufOffset) + length ($length) exceeds size ${buf.size}"
                }
                if (length == 0) return@withContext 0
                val byteBuf = ByteBuffer.wrap(buf, bufOffset, length)
                channel.read(byteBuf, startOffset + offset)
            }

        override fun close() {
            runCatching { channel.close() }
        }
    }
}
