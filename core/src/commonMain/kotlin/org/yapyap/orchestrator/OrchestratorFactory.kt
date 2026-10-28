package org.yapyap.orchestrator

import kotlinx.io.files.Path

public expect class OrchestratorFactory(
    dataDirectory: Path,
    mode: NodeMode,
    loopback: LoopbackTransport = LoopbackTransport.NONE,
) {
    public fun create(): Orchestrator
}