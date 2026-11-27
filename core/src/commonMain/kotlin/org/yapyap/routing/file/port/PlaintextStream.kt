package org.yapyap.routing.file.port

/**
 * A verified plaintext stream from [FilePlaintextSource.open]. Reads are bounded
 * ([maxBytes] caps memory to one buffer); blocks are verified once per serve,
 * not once per chunk. Close when done (a large-file channel close, a push finishing); closing never throws.
 */
internal interface PlaintextStream : AutoCloseable {
    /** Up to [maxBytes] verified bytes from the current position; [PlaintextRead.Eof] at the end. */
    suspend fun read(maxBytes: Int): PlaintextRead

    override fun close()
}
