package org.yapyap.orchestrator.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.yapyap.orchestrator.dag.IngestResult

/**
 * Core pipeline that collects messages from the Router, ingests them into the
 * DagEngine, and emits [IngestResult] events for downstream subscribers.
 *
 * Runs on ALL nodes (GUI + headless relay). Not GUI-facing.
 */
interface InboundMessagePipeline {
    /** Hot stream of ingest results. Collect to react to new messages. */
    val ingestResults: Flow<IngestResult>

    /** Start collecting from the router. Must be called exactly once. */
    fun start(scope: CoroutineScope)
}
