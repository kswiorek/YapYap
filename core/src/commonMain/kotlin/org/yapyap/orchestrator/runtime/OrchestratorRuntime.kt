package org.yapyap.orchestrator.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.IdentityProvisioning
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.fold.room.RoomEventProjector
import org.yapyap.orchestrator.identity.IdentityReader
import org.yapyap.orchestrator.onboarding.OnboardingProvider
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.runtime.account.AccountService
import org.yapyap.orchestrator.runtime.account.DefaultAccountService
import org.yapyap.orchestrator.runtime.admin.AdminService
import org.yapyap.orchestrator.runtime.admin.DefaultAdminService
import org.yapyap.orchestrator.runtime.config.ConfigService
import org.yapyap.orchestrator.runtime.config.DefaultConfigService
import org.yapyap.orchestrator.runtime.config.UserSettings
import org.yapyap.orchestrator.runtime.identity.DefaultIdentityService
import org.yapyap.orchestrator.runtime.identity.IdentityService
import org.yapyap.orchestrator.runtime.message.DefaultMessagingService
import org.yapyap.orchestrator.runtime.message.MessagingService
import org.yapyap.orchestrator.runtime.onboarding.DefaultOnboardingService
import org.yapyap.orchestrator.runtime.onboarding.OnboardingService
import org.yapyap.orchestrator.runtime.room.DefaultRoomService
import org.yapyap.orchestrator.runtime.room.RoomService
import org.yapyap.orchestrator.store.CausalHoldRepository
import org.yapyap.orchestrator.store.MessageDeliveryRepository
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.RoomRepository
import org.yapyap.protocol.DeviceType
import org.yapyap.routing.router.Router

public interface OrchestratorRuntime {
    public val messaging: MessagingService
    public val config: ConfigService

    /** Bootstrap / onboarding handshake (scaffolding; bodies land with sprint 4). */
    public val onboarding: OnboardingService

    /** Read-only roster: accounts, devices, per-account presence. */
    public val identity: IdentityService

    /** Chat list, headers, and room creation. */
    public val rooms: RoomService

    /** Admin-gated global-event mutations. */
    public val admin: AdminService

    /** Self-service: own devices and account. */
    public val account: AccountService
    // sync added in a later sprint
}

internal class DefaultOrchestratorRuntime(
    dagEngine: DagEngine,
    router: Router,
    pipeline: InboundMessagePipeline,
    messageRepository: MessageRepository,
    deliveryRepository: MessageDeliveryRepository,
    causalHoldRepository: CausalHoldRepository,
    localIdentity: LocalIdentity,
    maxTextMessageBytes: StateFlow<Int>,
    orchestratorConfig: StateFlow<OrchestratorConfig>,
    userSettings: UserSettings,
    onboardingProvider: OnboardingProvider,
    identityReader: IdentityReader,
    identityProvisioning: IdentityProvisioning,
    cryptoProvider: CryptoProvider,
    globalEventProjector: GlobalEventProjector,
    roomEventProjector: RoomEventProjector,
    roomRepository: RoomRepository,
    localDeviceType: DeviceType,
) : OrchestratorRuntime {

    override val messaging = DefaultMessagingService(
        dagEngine = dagEngine,
        router = router,
        pipeline = pipeline,
        roomRepository = roomRepository,
        messageRepository = messageRepository,
        deliveryRepository = deliveryRepository,
        causalHoldRepository = causalHoldRepository,
        localIdentity = localIdentity,
        maxTextBytes = maxTextMessageBytes,
        orchestratorConfig = orchestratorConfig,
    )

    override val onboarding = DefaultOnboardingService(
        provider = onboardingProvider,
        router = router,
        localIdentity = localIdentity,
        identityReader = identityReader,
        messageRepository = messageRepository,
        cryptoProvider = cryptoProvider,
        localDeviceType = localDeviceType,
        projector = globalEventProjector,
    )

    override val config = DefaultConfigService(userSettings)

    override val rooms = DefaultRoomService(
        projector = roomEventProjector,
        roomRepository = roomRepository,
        identityReader = identityReader,
        localIdentity = localIdentity,
    )

    // Stateless (no StateFlow of its own, nothing to collect): no start/stop.
    override val account = DefaultAccountService(
        projector = globalEventProjector,
        localIdentity = localIdentity,
        identityReader = identityReader,
        identityProvisioning = identityProvisioning,
        onboardingState = onboardingProvider.state,
    )

    // Stateful in one field (the live localIsAdmin flag off the projector's
    // stateChanges): needs start/stop like the room service.
    override val admin = DefaultAdminService(
        projector = globalEventProjector,
        localIdentity = localIdentity,
        identityReader = identityReader,
        onboardingState = onboardingProvider.state,
    )

    // Read-only roster: rows + router presence + fold events.
    override val identity = DefaultIdentityService(
        identityReader = identityReader,
        router = router,
        projector = globalEventProjector,
        orchestratorConfig = orchestratorConfig,
    )

    fun start(scope: CoroutineScope) {
        messaging.start(scope)
        rooms.start(scope)
        admin.start(scope)
        identity.start(scope)
    }

    suspend fun stop() {
        messaging.stop()
        rooms.stop()
        admin.stop()
        identity.stop()
    }
}
