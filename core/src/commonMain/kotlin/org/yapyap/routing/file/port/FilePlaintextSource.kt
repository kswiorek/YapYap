package org.yapyap.routing.file.port

import org.yapyap.protocol.FileId

/**
 * Plaintext source for file serves (design §6, §9, §11). Declared by routing, implemented
 * by the orchestrator (`orchestrator/file`): plaintext from any byte offset,
 * nothing about keys or chunks.
 *
 * A serve opens a [PlaintextStream] at its start offset and reads verified bytes
 * in bounded buffers until EOF. Each block is verified against the block list before
 * its bytes are handed out (two passes per block: hash, then re-read and stream with
 * a re-hash), so a referenced original edited mid-serve stops at that block.
 */
internal interface FilePlaintextSource {
    /**
     * The manifest the pusher offers and serves, or null when this device does not hold the file
     * (missing descriptor or SOURCE_LOST; both decline later REQUESTs).
     */
    suspend fun describe(fileId: FileId): FileManifest?

    /** Verified plaintext from [fromOffset], or null when not held. The caller closes it. */
    suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream?
}

/** One bounded, verified read from a [PlaintextStream]. */
internal sealed interface PlaintextRead {
    /** Verified bytes (never empty; EOF is separate). */
    data class Bytes(val bytes: ByteArray) : PlaintextRead {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Bytes
            if (!bytes.contentEquals(other.bytes)) return false
            return true
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** End of file: every byte was verified. */
    data object Eof : PlaintextRead

    /** The block at [blockIndex] failed verification: the serve ends here. */
    data class SourceLost(val blockIndex: Int) : PlaintextRead
}

/**
 * A verified plaintext stream from [FilePlaintextSource.open]. Reads are bounded
 * ([maxBytes] caps memory to one buffer); blocks are verified once per serve,
 * not once per chunk. Close when done (a fetch channel close, a push finishing); closing never throws.
 */
internal interface PlaintextStream : AutoCloseable {
    /** Up to [maxBytes] verified bytes from the current position; [PlaintextRead.Eof] at the end. */
    suspend fun read(maxBytes: Int): PlaintextRead

    override fun close()
}
