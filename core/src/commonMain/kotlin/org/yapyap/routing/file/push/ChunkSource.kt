package org.yapyap.routing.file.push

import org.yapyap.protocol.envelopes.BinaryEnvelope

/**
 * How one chunk envelope is made. The push engine owns the window and the rows; the source
 * owns the bytes. Pushes read plaintext through [PlaintextChunkSource]; relay forwards replay
 * stored envelopes through a dedicated forward actor.
 */
internal interface ChunkSource : AutoCloseable {
    suspend fun offer(): BinaryEnvelope
    suspend fun chunk(index: Int): ChunkResult

    override fun close()
}

internal sealed interface ChunkResult {
    data class Envelope(val envelope: BinaryEnvelope) : ChunkResult

    /** Seal not possible now (ratchet session not ready): re-arm on the actor's own tick. */
    data object Deferred : ChunkResult

    /** The plaintext source is gone: the transfer ends. */
    data object SourceLost : ChunkResult
}
