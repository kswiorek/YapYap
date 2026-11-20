package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId
import org.yapyap.routing.file.port.FileCoverage

/**
 * The receive engine's held coverage of a file, read by the request coordinator to build REQUESTs.
 * The engine owns staging, so only it can say what is held beyond the prefix.
 */
internal interface FileCoverageReader {
    /**
     * The contiguous prefix plus the capped missing ranges beyond it; null when the file is READY
     * and needs no request.
     */
    suspend fun heldCoverage(fileId: FileId): FileCoverage?
}
