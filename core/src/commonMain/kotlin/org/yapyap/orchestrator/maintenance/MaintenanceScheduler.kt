package org.yapyap.orchestrator.maintenance

import kotlinx.coroutines.*
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import kotlin.time.Duration.Companion.seconds

class MaintenanceScheduler(
    private val tasks: List<suspend () -> Unit>,
    private val intervalSeconds: Long,
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
        runOnce()
        while (isActive) {
            delay(intervalSeconds.seconds)
            runOnce()
        }
    }
}