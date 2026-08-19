package org.yapyap.orchestrator

expect class OrchestratorFactory(
    dataDirectory: String,
    mode: NodeMode,) {
    fun create(): Orchestrator
}