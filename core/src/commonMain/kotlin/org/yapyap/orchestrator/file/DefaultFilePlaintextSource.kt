package org.yapyap.orchestrator.file

import kotlinx.io.files.Path
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.protocol.FileId
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReadResult

/**
 * Block-checking plaintext source (design §9): both the pusher and the fetch read through this
 * port.
 *
 * Every touched block is hashed as it is read and compared to the descriptor's
 * block list before any of its bytes are handed out. A mismatch or a missing
 * underlying file ends the serve: the descriptor goes SOURCE_LOST and the
 * read reports [FileReadResult.SourceLost].
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

    override suspend fun read(fileId: FileId, offset: Long, maxBytes: Int): FileReadResult {
        require(offset >= 0) { "offset must be >= 0 but was $offset" }
        require(maxBytes > 0) { "maxBytes must be > 0 but was $maxBytes" }
        val descriptor = descriptors.findById(fileId) ?: return FileReadResult.NotHeld
        if (descriptor.status != FileDescriptorStatus.HELD) {
            return FileReadResult.SourceLost(blockIndexFor(descriptor.blockSize, offset, descriptor.totalBytes))
        }
        if (offset >= descriptor.totalBytes) return FileReadResult.Ok(byteArrayOf(), eof = true)
        val want = minOf(maxBytes.toLong(), descriptor.totalBytes - offset).toInt()
        if (want == 0) return FileReadResult.Ok(byteArrayOf(), eof = true)

        val reader = openReader(descriptor) ?: run {
            store.markSourceLost(fileId)
            return FileReadResult.SourceLost(blockIndexFor(descriptor.blockSize, offset, descriptor.totalBytes))
        }
        try {
            val out = ByteArray(want)
            var outPos = 0
            val firstBlock = (offset / descriptor.blockSize).toInt()
            val lastByte = offset + want - 1
            val lastBlock = (lastByte / descriptor.blockSize).toInt()
            val buf = ByteArray(128 * 1024)
            for (blockIndex in firstBlock..lastBlock) {
                val blockStart = blockIndex.toLong() * descriptor.blockSize
                val blockEnd = minOf(blockStart + descriptor.blockSize, descriptor.totalBytes)
                val hasher = crypto.sha256Hasher()
                var pos = blockStart
                while (pos < blockEnd) {
                    val chunk = minOf(buf.size.toLong(), blockEnd - pos).toInt()
                    var got = 0
                    while (got < chunk) {
                        val n = reader.readAt(pos + got, buf, got, chunk - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got <= 0) {
                        store.markSourceLost(fileId)
                        return FileReadResult.SourceLost(blockIndex)
                    }
                    hasher.update(buf, 0, got)
                    // Copy only the requested slice of this block.
                    val sliceStart = maxOf(offset, blockStart)
                    val sliceEnd = minOf(offset + want, blockEnd)
                    if (sliceEnd > sliceStart) {
                        val copyFrom = maxOf(pos, sliceStart)
                        val copyTo = minOf(pos + got, sliceEnd)
                        if (copyTo > copyFrom) {
                            val srcOff = (copyFrom - pos).toInt()
                            val dstOff = (copyFrom - offset).toInt()
                            val len = (copyTo - copyFrom).toInt()
                            buf.copyInto(out, dstOff, srcOff, srcOff + len)
                            outPos += len
                        }
                    }
                    pos += got
                }
                val digest = hasher.digest()
                if (!digest.contentEquals(descriptor.blockHashes.getOrNull(blockIndex))) {
                    store.markSourceLost(fileId)
                    return FileReadResult.SourceLost(blockIndex)
                }
            }
            check(outPos == want) { "short read: $outPos < $want" }
            return FileReadResult.Ok(out, eof = offset + want >= descriptor.totalBytes)
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun openReader(descriptor: FileDescriptor): org.yapyap.orchestrator.file.io.PositionedHandle? {
        // REFERENCE descriptors store either a plain path or a content URI.
        if (descriptor.storage == FileStorage.REFERENCE && descriptor.path.startsWith("content://")) {
            runCatching { files.openContentPositionedReader(descriptor.path) }.getOrNull()?.let { return it }
            return null
        }
        val path = runCatching { Path(descriptor.path) }.getOrNull() ?: return null
        return runCatching { files.openPositionedReader(path) }.getOrNull()
    }

    private fun blockIndexFor(blockSize: Int, offset: Long, totalBytes: Long): Int {
        if (totalBytes == 0L) return 0
        return (minOf(offset, totalBytes - 1).coerceAtLeast(0) / blockSize).toInt()
    }
}
