package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.port.FileCoverage

/**
 * The receive engine's held coverage of a file, read by the request coordinator to build REQUESTs.
 * The engine owns staging, so only it can say what is held beyond the prefix.
 */
internal interface FileCoverageReader {
    /**
     * The contiguous prefix plus the capped missing ranges beyond it (from zero when no receive
     * exists yet); null when the file needs no request: held, READY, or ended (DECLINED, FAILED,
     * CANCELLED, EVICTED stay ended until the user re-opens them).
     */
    suspend fun heldCoverage(fileId: FileId): FileCoverage?

    /**
     * After a block mismatch, the device that fed the bad block: the next REQUEST asks it first
     * (design §8/§9). Null when there was no recent mismatch or no recorded device fed it.
     * In memory only: after a restart the candidate order applies.
     */
    suspend fun preferDevice(fileId: FileId): PeerId?
}
