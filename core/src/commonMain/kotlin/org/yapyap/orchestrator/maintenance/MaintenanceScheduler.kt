package org.yapyap.orchestrator.maintenance

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig

class MaintenanceScheduler(
    private val tasks: List<suspend () -> Unit>,
    private val config: StateFlow<OrchestratorConfig>,
) {
    suspend fun runOnce() {
        tasks.forEach { task ->
            runCatching { task() }  // log-and-continue, never let one task kill the loop
                .onFailure { e -> AppLog.error(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.MAINTENANCE_FAILED,
                    message = "Maintenance task failed:",
                    throwable = e,
                ) }
        }
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        config.map { it.maintenanceInterval }
            .distinctUntilChanged()
            .collectLatest { interval ->
                while (isActive) { delay(interval); runOnce() }
            }
    }
}