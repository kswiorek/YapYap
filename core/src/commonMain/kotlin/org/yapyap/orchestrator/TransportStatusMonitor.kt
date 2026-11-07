package org.yapyap.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.routing.router.Router

/**
 * The node's [LocalTransportStatus], lifted from the router's [Router.localLinkStatus].
 * Transitions are logged for headless operators (this — plus the log — is their status
 * UX); bootstrap percentages alone don't log, only a new kind or problem.
 */
internal class TransportStatusMonitor(
    private val router: Router,
) {
    private val _status = MutableStateFlow(INITIAL_TRANSPORT_STATUS)
    val status: StateFlow<LocalTransportStatus> = _status.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            router.localLinkStatus.map { it.toTransportStatus() }.collect { status ->
                val previous = _status.value
                if (status == previous) return@collect
                _status.value = status
                val sameKind = status::class == previous::class &&
                        status.problemOrNull() == previous.problemOrNull() &&
                        (status as? LocalTransportStatus.Recovering)?.action ==
                        (previous as? LocalTransportStatus.Recovering)?.action
                if (!sameKind) {
                    AppLog.info(
                        component = LogComponent.ORCHESTRATOR,
                        event = LogEvent.SESSION_STATE_CHANGED,
                        message = "Local transport status changed",
                        fields = mapOf("transportStatus" to status),
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
