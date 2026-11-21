package org.yapyap.orchestrator.file.receive

import kotlinx.io.files.Path
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.file.io.FileAccess

/** Result of checking one block against its expected hash. */
internal sealed interface BlockCheck {
    data object Verified : BlockCheck
    data class Mismatch(val blockIndex: Int) : BlockCheck
}

/**
 * The `.part` file (design §8): append at P with one fsync, truncate, and verify a block by
 * re-reading it from disk. No hash state is kept between drains, so a crash between the
 * append and the DB commit leaves nothing to rebuild: the next drain re-appends at P.
 */
internal class PartWriter(
    private val files: FileAccess,
    private val crypto: CryptoProvider,
) {
    /** Append [bytes] at [at], creating the parent directories. Caller fsyncs via the handle. */
    suspend fun appendAt(path: Path, at: Long, bytes: ByteArray) {
        files.openAppendWriter(path, null).use { writer ->
            val size = writer.size()
            if (size != at) {
                if (size > at) writer.truncate(at)
                else throw IllegalStateException("torn .part shorter than P: size=$size at=$at")
            }
            writer.write(bytes)
            writer.flushAndSync()
        }
    }

    /** Truncate [path] to [length] and fsync. */
    suspend fun truncateTo(path: Path, length: Long) {
        files.openAppendWriter(path, null).use { writer ->
            writer.truncate(length)
            writer.flushAndSync()
        }
    }

    /** Read and hash the block at [blockIndex], comparing it to [expected]. */
    suspend fun verifyBlock(
        path: Path,
        blockIndex: Int,
        blockSize: Int,
        totalBytes: Long,
        expected: ByteArray,
    ): BlockCheck {
        val blockStart = blockIndex.toLong() * blockSize
        val blockEnd = minOf(blockStart + blockSize, totalBytes)
        val hasher = crypto.sha256Hasher()
        files.openPositionedReader(path).use { reader ->
            var pos = blockStart
            val buf = ByteArray(128 * 1024)
            while (pos < blockEnd) {
                val want = minOf(buf.size.toLong(), blockEnd - pos).toInt()
                var got = 0
                while (got < want) {
                    val n = reader.readAt(pos + got, buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) return BlockCheck.Mismatch(blockIndex)
                hasher.update(buf, 0, got)
                pos += got
            }
        }
        return if (hasher.digest().contentEquals(expected)) BlockCheck.Verified
        else BlockCheck.Mismatch(blockIndex)
    }
}
