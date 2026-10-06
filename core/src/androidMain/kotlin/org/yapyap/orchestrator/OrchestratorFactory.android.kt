package org.yapyap.orchestrator

import kotlinx.io.files.Path

actual class OrchestratorFactory actual constructor(
    dataDirectory: Path,
    mode: NodeMode
) {
    actual fun create(): Orchestrator {
        TODO("[Multiplatform] Not yet implemented")
    }
}