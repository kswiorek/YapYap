package org.yapyap.orchestrator

import kotlinx.io.files.Path

expect class OrchestratorFactory(
    dataDirectory: Path,
    mode: NodeMode
) {
    fun create(): Orchestrator
}