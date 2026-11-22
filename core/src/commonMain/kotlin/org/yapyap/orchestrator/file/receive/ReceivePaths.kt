package org.yapyap.orchestrator.file.receive

import kotlinx.io.files.Path
import org.yapyap.protocol.FileId

/**
 * Where a receive's bytes live, derived from the [FileId] and the store layout (design §13).
 * Paths are never stored: the `.part` and the final file are functions of the id.
 */
/** Suffix of a receive's in-progress file in `incoming/`. */
internal const val PART_SUFFIX = ".part"

internal data class ReceivePaths(
    val incomingDir: Path,
    val storeDir: Path,
) {
    fun part(fileId: FileId): Path = Path(incomingDir.toString(), "${fileId.value}$PART_SUFFIX")

    fun final(fileId: FileId): Path = Path(storeDir.toString(), fileId.value)
}
