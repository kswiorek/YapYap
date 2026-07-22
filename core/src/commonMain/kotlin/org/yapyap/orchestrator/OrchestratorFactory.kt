package org.yapyap.orchestrator

expect class OrchestratorFactory(config: OrchestratorConfig) {
    fun create(): Orchestrator
}