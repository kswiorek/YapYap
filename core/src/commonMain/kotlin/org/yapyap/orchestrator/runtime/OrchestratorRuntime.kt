package org.yapyap.orchestrator.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.config.MessageLimits
import org.yapyap.crypto.identity.IdentityProvisioning
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.fold.room.RoomEventProjector
import org.yapyap.orchestrator.onboarding.OnboardingProvider
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.runtime.account.AccountService
import org.yapyap.orchestrator.runtime.account.DefaultAccountService
import org.yapyap.orchestrator.runtime.admin.AdminService
import org.yapyap.orchestrator.runtime.admin.DefaultAdminService
import org.yapyap.orchestrator.runtime.config.ConfigService
import org.yapyap.orchestrator.runtime.config.DefaultConfigService
import org.yapyap.orchestrator.runtime.identity.DefaultIdentityService
import org.yapyap.orchestrator.runtime.identity.IdentityService
import org.yapyap.orchestrator.runtime.message.DefaultMessagingService
import org.yapyap.orchestrator.runtime.message.MessagingService
import org.yapyap.orchestrator.runtime.onboarding.DefaultOnboardingService
import org.yapyap.orchestrator.runtime.onboarding.OnboardingService
import org.yapyap.orchestrator.runtime.room.DefaultRoomService
import org.yapyap.orchestrator.runtime.room.RoomService
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.config.ConfigStore
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.DefaultCausalHoldRepository
import org.yapyap.persistence.messaging.DefaultMessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.DeviceType
import org.yapyap.routing.router.Router

interface OrchestratorRuntime {
    val messaging: MessagingService
    val config: ConfigService

    /** Bootstrap / onboarding handshake (scaffolding; bodies land with sprint 4). */
    val onboarding: OnboardingService

    /** Read-only roster: accounts, devices, per-account presence. */
    val identity: IdentityService

    /** Chat list, headers, and room creation. */
    val rooms: RoomService

    /** Admin-gated global-event mutations. */
    val admin: AdminService

    /** Self-service: own devices and account. */
    val account: AccountService
    // sync added in a later sprint
}

internal class DefaultOrchestratorRuntime(
    private val dagEngine: DagEngine,
    private val router: Router,
    private val pipeline: InboundMessagePipeline,
    private val database: YapYapDatabase,
    private val identityResolver: IdentityResolver,
    private val messageLimits: StateFlow<MessageLimits>,
    private val configStore: ConfigStore,
    private val onboardingProvider: OnboardingProvider,
    private val identityKeyRepository: IdentityKeyRepository,
    private val identityProvisioning: IdentityProvisioning,
    private val cryptoProvider: CryptoProvider,
    private val globalEventProjector: GlobalEventProjector,
    private val roomEventProjector: RoomEventProjector,
    private val roomRepository: RoomRepository,
    private val localDeviceType: DeviceType,
) : OrchestratorRuntime {

    private lateinit var _messaging: DefaultMessagingService
    override val messaging: MessagingService get() = _messaging

    private lateinit var _config: DefaultConfigService
    override val config: ConfigService get() = _config

    private lateinit var _onboarding: DefaultOnboardingService
    override val onboarding: OnboardingService get() = _onboarding

    private lateinit var _rooms: DefaultRoomService
    override val rooms: RoomService get() = _rooms

    private lateinit var _identity: DefaultIdentityService
    override val identity: IdentityService get() = _identity

    private lateinit var _admin: DefaultAdminService
    override val admin: AdminService get() = _admin

    private lateinit var _account: DefaultAccountService
    override val account: AccountService get() = _account

    fun start(scope: CoroutineScope) {
        _messaging = DefaultMessagingService(
            dagEngine = dagEngine,
            router = router,
            pipeline = pipeline,
            roomRepository = roomRepository,
            messageRepository = DefaultMessageRepository(database),
            causalHoldRepository = DefaultCausalHoldRepository(database),
            identityResolver = identityResolver,
            messageLimits = messageLimits,
            orchestratorConfig = configStore.orchestratorConfig,
        )
        _messaging.start(scope)

        _onboarding = DefaultOnboardingService(
            provider = onboardingProvider,
            router = router,
            identityResolver = identityResolver,
            messageRepository = DefaultMessageRepository(database),
            cryptoProvider = cryptoProvider,
            localDeviceType = localDeviceType,
            projector = globalEventProjector,
        )

        _config = DefaultConfigService(configStore)
        _config.start(scope)

        _rooms = DefaultRoomService(
            projector = roomEventProjector,
            roomRepository = roomRepository,
            identityKeyRepository = identityKeyRepository,
            identityResolver = identityResolver,
        )
        _rooms.start(scope)

        // Stateless (no StateFlow of its own, nothing to collect): constructed only,
        // no start/stop.
        _account = DefaultAccountService(
            projector = globalEventProjector,
            identityResolver = identityResolver,
            identityKeyRepository = identityKeyRepository,
            identityProvisioning = identityProvisioning,
            onboardingState = onboardingProvider.state,
        )

        // Stateful in one field (the live localIsAdmin flag off the projector's
        // stateChanges): needs start/stop like the room service.
        _admin = DefaultAdminService(
            projector = globalEventProjector,
            identityResolver = identityResolver,
            identityKeyRepository = identityKeyRepository,
            onboardingState = onboardingProvider.state,
        )
        _admin.start(scope)

        // Read-only roster: rows + router presence + fold events. All deps are
        // already constructor params — no new wiring.
        _identity = DefaultIdentityService(
            identityKeyRepository = identityKeyRepository,
            router = router,
            projector = globalEventProjector,
            orchestratorConfig = configStore.orchestratorConfig,
        )
        _identity.start(scope)
    }

    suspend fun stop() {
        _messaging.stop()
        _rooms.stop()
        _admin.stop()
        _identity.stop()
    }
}