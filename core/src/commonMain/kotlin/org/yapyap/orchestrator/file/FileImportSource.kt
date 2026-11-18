package org.yapyap.orchestrator.file

import kotlinx.io.files.Path

/**
 * What the GUI hands to [FileStore.import]: a platform file or an Android
 * content URI. The GUI owns only the picker and the keep-a-copy checkbox
 * (design §10); the store owns everything after this boundary.
 */
internal sealed interface FileImportSource {
    data class PathSource(val path: Path) : FileImportSource
    data class ContentUriSource(val uri: String) : FileImportSource
}
