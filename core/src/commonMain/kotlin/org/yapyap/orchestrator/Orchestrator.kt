package org.yapyap.orchestrator

import kotlinx.coroutines.flow.StateFlow


interface Orchestrator {
    val state: StateFlow<OrchestratorState>
    val lastError: StateFlow<Throwable?>

    /** Boot recovery → start router → start domain loops. */
    suspend fun start()

    suspend fun stop()

    suspend fun completeSetup(intent: SetupIntent): SetupResult

    /**
     * Domain APIs. Prefer throwing/checking state over nullable returns
     * so misuse fails fast in tests.
     */
    fun runtime(): OrchestratorRuntime
}