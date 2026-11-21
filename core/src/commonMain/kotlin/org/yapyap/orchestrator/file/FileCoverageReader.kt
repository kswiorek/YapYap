package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId
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

}
