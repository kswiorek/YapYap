package org.yapyap.orchestrator

import kotlinx.io.files.Path

public expect class OrchestratorFactory(
    dataDirectory: Path,
    mode: NodeMode,
    loopback: LoopbackTransport = LoopbackTransport.NONE,
    keyringNamespace: String? = null,
) {
    public fun create(): Orchestrator
}