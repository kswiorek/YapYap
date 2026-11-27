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
