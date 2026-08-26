package org.yapyap.orchestrator.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.config.MessageLimits
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.runtime.config.ConfigService
import org.yapyap.orchestrator.runtime.config.DefaultConfigService
import org.yapyap.orchestrator.runtime.message.DefaultMessagingService
import org.yapyap.orchestrator.runtime.message.MessagingService
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.config.ConfigStore
import org.yapyap.persistence.messaging.DefaultRoomRepository
import org.yapyap.routing.router.Router
import org.yapyap.time.SystemEpochProvider

interface OrchestratorRuntime {
    val messaging: MessagingService
    val config: ConfigService
    // identity / rooms / sync / roster added in later sprints
}

internal class DefaultOrchestratorRuntime(
    private val dagEngine: DagEngine,
    private val router: Router,
    private val pipeline: InboundMessagePipeline,
    private val database: YapYapDatabase,
    private val identityResolver: IdentityResolver,
    private val messageLimits: StateFlow<MessageLimits>,
    private val configStore: ConfigStore,
) : OrchestratorRuntime {

    private lateinit var _messaging: DefaultMessagingService
    override val messaging: MessagingService get() = _messaging

    private lateinit var _config: DefaultConfigService
    override val config: ConfigService get() = _config

    fun start(scope: CoroutineScope) {
        _messaging = DefaultMessagingService(
            dagEngine = dagEngine,
            router = router,
            pipeline = pipeline,
            roomRepository = DefaultRoomRepository(database),
            identityResolver = identityResolver,
            timeProvider = SystemEpochProvider,
            messageLimits = messageLimits,
        )
        _messaging.start(scope)

        _config = DefaultConfigService(configStore)
        _config.start(scope)
    }

    suspend fun stop() {
        _messaging.stop()
    }
}