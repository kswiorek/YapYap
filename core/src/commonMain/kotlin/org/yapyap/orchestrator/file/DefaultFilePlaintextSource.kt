package org.yapyap.orchestrator.file

import kotlinx.io.IOException
import kotlinx.io.files.Path
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.file.io.PositionedHandle
import org.yapyap.protocol.FileId
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.file.port.PlaintextStream

/**
 * Block-checking plaintext source (design §9, §11): both the pusher and the fetch read through this
 * port.
 *
 * Each block is read twice with one bounded buffer (256 KiB, never a whole block, which reaches
 * 128 MiB):
 * - pass 1 hashes the block and compares it with the block list; nothing is handed out yet;
 * - pass 2 re-reads the block with positioned reads and streams it in bounded buffers, from the
 *   requested offset when the serve starts mid-block, hashing again and comparing at the block's
 *   end. A referenced original edited between the two passes ends the serve at that block.
 *
 * A mismatch or a missing underlying file ends the serve: the descriptor goes SOURCE_LOST and the
 * stream reports [PlaintextRead.SourceLost]. Only an [IOException] opening the file maps to
 * SOURCE_LOST; other errors propagate.
 */
internal class DefaultFilePlaintextSource(
    private val descriptors: FileDescriptorReader,
    /** Where a bad or missing block is reported: the descriptor goes SOURCE_LOST. */
    private val store: FileStore,
    private val files: FileAccess,
    private val crypto: CryptoProvider,
) : FilePlaintextSource {
    override suspend fun describe(fileId: FileId): FileManifest? {
        val descriptor = descriptors.findById(fileId) ?: return null
        if (descriptor.status != FileDescriptorStatus.HELD) return null
        return FileManifest(
            fileId = descriptor.fileId,
            totalBytes = descriptor.totalBytes,
            blockSize = descriptor.blockSize,
            blockHashes = descriptor.blockHashes,
            fileName = descriptor.fileName,
            mimeType = descriptor.mimeType,
        )
    }

    override suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream? {
        require(fromOffset >= 0) { "fromOffset must be >= 0 but was $fromOffset" }
        val descriptor = descriptors.findById(fileId) ?: return null
        if (descriptor.status != FileDescriptorStatus.HELD) return null
        if (fromOffset >= descriptor.totalBytes) {
            // Empty suffix: a stream already at EOF; a serve of the whole (empty) rest touches LRU.
            return EofStream(fileId, store, touchOnRead = fromOffset == descriptor.totalBytes)
        }
        val reader = try {
            when (descriptor.pathKind) {
                FilePathKind.CONTENT_URI -> files.openContentPositionedReader(descriptor.path)
                FilePathKind.PATH -> files.openPositionedReader(Path(descriptor.path))
            }
        } catch (e: IOException) {
            store.markSourceLost(fileId)
            return LostStream(blockIndexFor(descriptor.blockSize, fromOffset, descriptor.totalBytes))
        }
        if (reader == null) {
            store.markSourceLost(fileId)
            return LostStream(blockIndexFor(descriptor.blockSize, fromOffset, descriptor.totalBytes))
        }
        return StreamingPlaintext(
            fileId = fileId,
            totalBytes = descriptor.totalBytes,
            blockSize = descriptor.blockSize,
            blockHashes = descriptor.blockHashes,
            reader = reader,
            pos = fromOffset,
            crypto = crypto,
            store = store,
        )
    }

    private fun blockIndexFor(blockSize: Int, offset: Long, totalBytes: Long): Int {
        if (totalBytes == 0L) return 0
        return (minOf(offset, totalBytes - 1).coerceAtLeast(0) / blockSize).toInt()
    }

    companion object {
        /** One read buffer: memory stays flat no matter how large a block is. */
        const val BUFFER_BYTES: Int = 256 * 1024
    }

    private class EofStream(
        private val fileId: FileId,
        private val store: FileStore,
        private var touchOnRead: Boolean,
    ) : PlaintextStream {
        override suspend fun read(maxBytes: Int): PlaintextRead {
            require(maxBytes > 0) { "maxBytes must be > 0 but was $maxBytes" }
            if (touchOnRead) {
                touchOnRead = false
                store.touch(fileId)
            }
            return PlaintextRead.Eof
        }

        override fun close() = Unit
    }

    private class LostStream(blockIndex: Int) : PlaintextStream {
        private val read = PlaintextRead.SourceLost(blockIndex)
        private var done = false
        override suspend fun read(maxBytes: Int): PlaintextRead {
            require(maxBytes > 0) { "maxBytes must be > 0 but was $maxBytes" }
            if (done) return PlaintextRead.Eof
            done = true
            return read
        }

        override fun close() = Unit
    }

    private class StreamingPlaintext(
        private val fileId: FileId,
        private val totalBytes: Long,
        private val blockSize: Int,
        private val blockHashes: List<ByteArray>,
        private val reader: PositionedHandle,
        private var pos: Long,
        private val crypto: CryptoProvider,
        private val store: FileStore,
    ) : PlaintextStream {
        private var blockIndex = (pos / blockSize).toInt()
        private var pass1Done = false
        private var pass2Hasher = crypto.sha256Hasher()
        private var closed = false
        private val buf = ByteArray(BUFFER_BYTES)

        override suspend fun read(maxBytes: Int): PlaintextRead {
            require(maxBytes > 0) { "maxBytes must be > 0 but was $maxBytes" }
            check(!closed) { "stream is closed" }
            // The read that handed out the last bytes already touched LRU.
            if (pos >= totalBytes) return PlaintextRead.Eof
            val blockStart = blockIndex.toLong() * blockSize
            val blockEnd = minOf(blockStart + blockSize, totalBytes)
            // Pass 1: hash the whole block, compare, hand out nothing yet.
            if (!pass1Done) {
                if (!verifyBlock(blockStart, blockEnd, blockIndex)) {
                    store.markSourceLost(fileId)
                    return PlaintextRead.SourceLost(blockIndex)
                }
                pass1Done = true
                pass2Hasher = crypto.sha256Hasher()
                // When starting mid-block, the re-hash covers the whole block: hash the prefix
                // before the serve offset so pass 2 compares the full block at its end.
                if (pos > blockStart) {
                    hashRange(blockStart, pos)
                }
            }
            // Clamp to the internal buffer: callers may pass megabytes, but positioned reads
            // into buf must stay within it however large maxBytes is.
            // Never zero: a finished block always advances to the next one below.
            val want = minOf(maxBytes.toLong(), BUFFER_BYTES.toLong(), blockEnd - pos).toInt()
            val got = readFully(pos, want)
            if (got <= 0) {
                store.markSourceLost(fileId)
                return PlaintextRead.SourceLost(blockIndex)
            }
            val out = buf.copyOf(got)
            pass2Hasher.update(out, 0, got)
            pos += got
            if (pos >= blockEnd) {
                // The block is done: compare the re-hash. An edit between the two passes ends
                // the serve here (the receiver's own block check would also catch it).
                val digest = pass2Hasher.digest()
                val expected = blockHashes.getOrNull(blockIndex)
                if (expected == null || !digest.contentEquals(expected)) {
                    store.markSourceLost(fileId)
                    // The last bytes were already handed out in prior reads except when the
                    // whole block fit in this read; report loss either way so the serve stops.
                    advanceBlock()
                    return PlaintextRead.SourceLost(blockIndex - 1)
                }
                advanceBlock()
                if (pos >= totalBytes) store.touch(fileId)
                // Hand out this read's bytes; the next read continues or hits EOF.
                return PlaintextRead.Bytes(out)
            }
            return PlaintextRead.Bytes(out)
        }

        private fun advanceBlock() {
            blockIndex++
            pass1Done = false
        }

        private suspend fun verifyBlock(blockStart: Long, blockEnd: Long, index: Int): Boolean {
            val expected = blockHashes.getOrNull(index) ?: return false
            val hasher = crypto.sha256Hasher()
            var p = blockStart
            while (p < blockEnd) {
                val want = minOf(buf.size.toLong(), blockEnd - p).toInt()
                var got = 0
                while (got < want) {
                    val n = reader.readAt(p + got, buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) return false
                hasher.update(buf, 0, got)
                p += got
            }
            return hasher.digest().contentEquals(expected)
        }

        private suspend fun hashRange(from: Long, until: Long) {
            var p = from
            while (p < until) {
                val want = minOf(buf.size.toLong(), until - p).toInt()
                var got = 0
                while (got < want) {
                    val n = reader.readAt(p + got, buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) break
                pass2Hasher.update(buf, 0, got)
                p += got
            }
        }

        private suspend fun readFully(offset: Long, want: Int): Int {
            var got = 0
            while (got < want) {
                val n = reader.readAt(offset + got, buf, got, want - got)
                if (n <= 0) break
                got += n
            }
            return got
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                reader.close()
            } catch (e: IOException) {
                // Nothing was written through it: a failed close loses nothing.
                AppLog.debug(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Closing a serve's reader failed",
                    fields = mapOf("fileId" to fileId, "error" to e.message),
                )
            }
        }
    }
}
