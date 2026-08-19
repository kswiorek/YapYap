package org.yapyap.orchestrator

import kotlinx.coroutines.CoroutineScope
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.message.DefaultMessagingService
import org.yapyap.orchestrator.message.MessagingService
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.messaging.DefaultRoomRepository
import org.yapyap.routing.router.Router
import org.yapyap.time.SystemEpochProvider

interface OrchestratorRuntime {
    val messaging: MessagingService
    // identity / rooms / sync / roster added in later sprints
}

internal class DefaultOrchestratorRuntime(
    private val dagEngine: DagEngine,
    private val router: Router,
    private val pipeline: InboundMessagePipeline,
    private val database: YapYapDatabase,
    private val identityResolver: IdentityResolver,
) : OrchestratorRuntime {

    private lateinit var _messaging: DefaultMessagingService
    override val messaging: MessagingService get() = _messaging

    fun start(scope: CoroutineScope) {
        _messaging = DefaultMessagingService(
            dagEngine = dagEngine,
            router = router,
            pipeline = pipeline,
            roomRepository = DefaultRoomRepository(database),
            identityResolver = identityResolver,
            timeProvider = SystemEpochProvider,
        )
        _messaging.start(scope)
    }

    suspend fun stop() {
        _messaging.stop()
    }
}