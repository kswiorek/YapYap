package org.yapyap.orchestrator

import kotlinx.io.files.Path

public actual class OrchestratorFactory actual constructor(
    dataDirectory: Path,
    mode: NodeMode,
    loopback: LoopbackTransport,
) {
    public actual fun create(): Orchestrator {
        TODO("[Multiplatform] Not yet implemented")
    }
}