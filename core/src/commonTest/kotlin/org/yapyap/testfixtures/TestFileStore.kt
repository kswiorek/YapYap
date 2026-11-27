package org.yapyap.testfixtures

import org.yapyap.orchestrator.file.FileDescriptor
import org.yapyap.orchestrator.file.FileImportSource
import org.yapyap.orchestrator.file.FileStore
import org.yapyap.orchestrator.file.ImportResult

/** Imports and returns the descriptor, failing the test on a refusal. */
internal suspend fun FileStore.importHeld(
    source: FileImportSource,
    fileName: String,
    mimeType: String?,
    keepCopy: Boolean,
): FileDescriptor = when (val result = import(source, fileName, mimeType, keepCopy)) {
    is ImportResult.Imported -> result.descriptor
    else -> error("import refused: $result")
}
