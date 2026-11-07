package org.yapyap.wiring

import kotlinx.io.files.Path
import org.yapyap.orchestrator.LoopbackTransport
import org.yapyap.orchestrator.NodeMode
import org.yapyap.orchestrator.Orchestrator

public expect class OrchestratorFactory(
    dataDirectory: Path,
    mode: NodeMode,
    loopback: LoopbackTransport = LoopbackTransport.NONE,
    keyringNamespace: String? = null,
) {
    public fun create(): Orchestrator
}