package org.yapyap.orchestrator

import kotlinx.io.files.Path

public expect class OrchestratorFactory(
    dataDirectory: Path,
    mode: NodeMode
) {
    public fun create(): Orchestrator
}