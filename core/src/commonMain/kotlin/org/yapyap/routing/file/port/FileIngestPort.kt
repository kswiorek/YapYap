package org.yapyap.routing.file.port

import org.yapyap.protocol.FileId

/**
 * Synchronous ingest port into the orchestrator's receive engine (design §8), declared by routing
 * and implemented by the engine. The handoff is synchronous so "ACK = decrypted and durably
 * recorded" holds: nothing is ACKed before it is handled. A transient failure (the store or disk
 * unavailable) answers `Defer`, never a refusal: the sender retries.
 */
internal interface FileIngestPort {
    suspend fun checkOffer(offer: FileManifest): FileOfferVerdict
    suspend fun ingest(
        fileId: FileId,
        byteOffset: Long,
        plaintext: ByteArray,
    ): FileIngestResult

    /**
     * Append large-file bytes at the uncommitted end (design §6.6, §8): straight into the `.part` at
     * [offset], without staging. [offset] must equal the uncommitted end, or the checkpointed
     * prefix when resuming after a drop (the leftover suffix is truncated). Checkpoints move P by
     * bytes (here) and by time (in the sweep).
     */
    suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult
}
