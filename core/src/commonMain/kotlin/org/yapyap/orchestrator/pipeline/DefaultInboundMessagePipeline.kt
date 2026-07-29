package org.yapyap.orchestrator.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LoggingTypes
import org.yapyap.logging.NoopAppLogger
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.routing.router.Router
import kotlin.coroutines.cancellation.CancellationException

class DefaultInboundMessagePipeline(
    private val router: Router,
    private val dagEngine: DagEngine,
    private val logger: AppLogger = NoopAppLogger,
) : InboundMessagePipeline {

    private val _ingestResults = MutableSharedFlow<IngestResult>(extraBufferCapacity = 64)
    override val ingestResults: Flow<IngestResult> = _ingestResults.asSharedFlow()

    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        check(job == null) { "Inbound pipeline already started" }
        job = scope.launch {
            router.incomingMessages.collect { payload ->
                runCatching {
                    val result = dagEngine.ingest(payload)
                    if (result != null) {
                        _ingestResults.emit(result)
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    logger.error(
                        component = LogComponent.MESSAGING,
                        event = LoggingTypes.ENVELOPE_HANDLE_FAILED,
                        message = "Failed to ingest inbound message",
                        fields = mapOf(
                            "messageId" to payload.messageId,
                            "roomId" to payload.roomId,
                        ),
                        throwable = e,
                    )
                }
            }
        }
    }
}
