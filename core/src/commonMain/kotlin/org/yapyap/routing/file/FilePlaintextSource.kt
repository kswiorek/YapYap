package org.yapyap.routing.file

import org.yapyap.protocol.FileId

/**
 * Plaintext source for file serves (design §6). Declared by routing, implemented
 * by the orchestrator (`orchestrator/file`): plaintext from any byte offset,
 * nothing about keys or chunks.
 *
 * The implementation reads from the start of the block containing [offset],
 * hashes each touched block and compares it to the block list before handing
 * out its bytes, and stops on a mismatch (design §9).
 */
internal interface FilePlaintextSource {
    suspend fun read(fileId: FileId, offset: Long, maxBytes: Int): FileReadResult

    /**
     * What the pusher needs to offer and serve a file, or null when this device does
     * not hold it (missing descriptor or SOURCE_LOST — both decline later REQUESTs).
     */
    suspend fun describe(fileId: FileId): FileDescriptorInfo?
}

/**
 * One bounded read. `Ok` bytes always correspond to exactly
 * `[offset, offset + bytes.size)` — verified prefix, never unverified data.
 */
internal sealed interface FileReadResult {
    data class Ok(val bytes: ByteArray, val eof: Boolean) : FileReadResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Ok
            if (!bytes.contentEquals(other.bytes)) return false
            if (eof != other.eof) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + eof.hashCode()
            return result
        }
    }

    data object NotHeld : FileReadResult

    data class SourceLost(val blockIndex: Int) : FileReadResult
}
