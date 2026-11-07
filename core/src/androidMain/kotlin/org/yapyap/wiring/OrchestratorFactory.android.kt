package org.yapyap.wiring

import kotlinx.io.files.Path
import org.yapyap.orchestrator.LoopbackTransport
import org.yapyap.orchestrator.NodeMode
import org.yapyap.orchestrator.Orchestrator

public actual class OrchestratorFactory actual constructor(
    dataDirectory: Path,
    mode: NodeMode,
    loopback: LoopbackTransport,
    keyringNamespace: String?,
) {
    public actual fun create(): Orchestrator {
        TODO("[Multiplatform] Not yet implemented")
    }
}