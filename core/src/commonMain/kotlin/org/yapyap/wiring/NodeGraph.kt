package org.yapyap.wiring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.yapyap.crypto.e2ee.maintenance.CryptoMaintenance
import org.yapyap.crypto.e2ee.manager.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.session.X3dhHandshake
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.crypto.store.DefaultCryptoSessionStore
import org.yapyap.crypto.store.DefaultOpkRepository
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.*
import org.yapyap.orchestrator.boot.BootConfig
import org.yapyap.orchestrator.boot.Node
import org.yapyap.orchestrator.boot.ResetRequest
import org.yapyap.orchestrator.dag.DefaultDagEngine
import org.yapyap.orchestrator.fold.global.DefaultGlobalEventProjector
import org.yapyap.orchestrator.fold.room.DefaultRoomEventProjector
import org.yapyap.orchestrator.maintenance.MaintenanceScheduler
import org.yapyap.orchestrator.onboarding.DefaultOnboardingProvider
import org.yapyap.orchestrator.onboarding.DefaultRecoveryResponder
import org.yapyap.orchestrator.onboarding.LocalSetup
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.pipeline.DefaultInboundMessagePipeline
import org.yapyap.orchestrator.runtime.DefaultOrchestratorRuntime
import org.yapyap.orchestrator.store.*
import org.yapyap.orchestrator.sync.DefaultFrontierSnapshotProvider
import org.yapyap.orchestrator.sync.DefaultSyncCoordinator
import org.yapyap.orchestrator.sync.DefaultSyncPayloadProvider
import org.yapyap.protection.envelope.*
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.routing.maintenance.PacketStoreMaintenance
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.store.DefaultDeviceRouteStore
import org.yapyap.routing.store.DefaultPacketDeduplicator
import org.yapyap.routing.store.DefaultPacketOutbox
import org.yapyap.routing.store.DefaultSyncAttemptStore
import org.yapyap.transport.tor.transport.DefaultTorTransport
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import kotlin.time.Clock

/** Where a node keeps its files inside the data directory. */
internal class DataLayout(val dataDirectory: Path) {
    val databaseFile = Path(dataDirectory, "vault.db")
    val torStateRoot = Path(dataDirectory, "tor")
    val logDirectory = Path(dataDirectory, "logs")
    val userSettingsFile = Path(dataDirectory, "userSettings.toml")
    val stateFile = Path(dataDirectory, "state.toml")
}

/**
 * Phase two of a node: every component that needs the local identity, built on a [VaultGraph].
 * Construction only wires; [start] starts components in dependency order. Built fresh per start.
 */
internal class NodeGraph(
    vault: VaultGraph,
    bootConfig: BootConfig,
    platform: Platform,
    layout: DataLayout,
    localDeviceId: PeerId,
    private val scope: CoroutineScope,
) : Node {
    private val config = vault.configStore
    private val database = vault.database
    private val crypto = vault.cryptoProvider
    private val localIdentity = vault.localIdentity
    private val localKeys = vault.localKeys
    private val peerKeys = vault.peerKeys

    // The whole identity store stays here; each component gets the narrow view it needs.
    private val identityStore = vault.identityKeyRepository
    private val clock = Clock.System

    // Transports
    private val torTransport =
        DefaultTorTransport(platform.torBackend(config.torConfig, layout.torStateRoot), config.torConfig)
    private val webRtcTransport = DefaultWebRtcTransport(platform.webRtcBackend(config.webRtcConfig))

    // Stores
    private val packetDeduplicator = DefaultPacketDeduplicator(database)
    private val packetOutbox = DefaultPacketOutbox(database)
    private val cryptoSessionStore = DefaultCryptoSessionStore(database)
    private val opkRepository = DefaultOpkRepository(database, vault.keyStore, crypto, localDeviceId)
    private val messageRepository = DefaultMessageRepository(database)
    private val deliveryRepository = DefaultMessageDeliveryRepository(database)
    private val causalHoldRepository = DefaultCausalHoldRepository(database)
    private val roomRepository = DefaultRoomRepository(database)
    private val pendingSyncRepository = DefaultPendingSyncRepository(database)
    private val deviceRoutes = DefaultDeviceRouteStore(database, identityStore)

    // Crypto and envelope protection
    private val cryptoSessionManager = DefaultCryptoSessionManager(
        crypto = crypto,
        x3dh = X3dhHandshake(crypto),
        sessionStore = cryptoSessionStore,
        localIdentity = localIdentity,
        localKeys = localKeys,
        peerKeys = peerKeys,
        opkRepository = opkRepository,
        cryptoLimits = config.cryptoLimits,
        sessionConfig = config.cryptoConfig,
    )
    private val signatureProvider = DefaultSignatureProvider(localKeys, peerKeys, crypto)
    private val envelopeProtectionService = DefaultEnvelopeProtectionService(
        webRtcSignalProtection = SignedAndEncryptedWebRtcSignalProtection(
            signatureProvider,
            cryptoSessionManager,
            crypto
        ),
        fileProtection = PlaintextFileProtection(crypto), //TODO: [Sprint 5] file protection
        messageProtection = SignedAndEncryptedMessageProtection(signatureProvider, cryptoSessionManager, crypto),
        systemProtection = SignedSystemProtection(signatureProvider, crypto),
        bootstrapProtection = BootstrapProtection(crypto, vault.bootstrapSessionStore),
    )

    private val maintenance = MaintenanceScheduler(
        tasks = listOf(
            PacketStoreMaintenance(packetOutbox, packetDeduplicator, config.routerConfig)::run,
            CryptoMaintenance(cryptoSessionStore, opkRepository, config.cryptoConfig)::run,
        ),
        config = config.orchestratorConfig,
    )

    // Router, with the orchestrator-side content it advertises and serves
    private val router = DefaultRouter(
        torTransport = torTransport,
        webRtcTransport = webRtcTransport,
        localIdentity = localIdentity,
        devices = identityStore,
        routes = deviceRoutes,
        packetDeduplicator = packetDeduplicator,
        packetOutbox = packetOutbox,
        envelopeProtectionService = envelopeProtectionService,
        syncPayloadProvider = DefaultSyncPayloadProvider(
            messageRepository = messageRepository,
            pendingSyncRepository = pendingSyncRepository,
            orchestratorConfig = config.orchestratorConfig,
            roomRepository = roomRepository,
        ),
        syncAttemptStore = DefaultSyncAttemptStore(database),
        routerConfig = config.routerConfig,
        transportLimits = config.transportLimits,
        frontierSnapshotProvider = DefaultFrontierSnapshotProvider(roomRepository, messageRepository, localDeviceId),
        peerAvailabilityStore = deviceRoutes,
    )

    // History, folds and sync
    private val dagEngine = DefaultDagEngine(
        messageRepository = messageRepository,
        causalHoldRepository = causalHoldRepository,
        roomRepository = roomRepository,
        localIdentity = localIdentity,
        signatureProvider = signatureProvider,
        cryptoProvider = crypto,
        clock = clock,
    )
    private val pipeline = DefaultInboundMessagePipeline(router, dagEngine)

    // Global control plane: sole writer of chain-derived identity columns. Owns the GLOBAL
    // room fold (boot + every ingest trigger) and the publish path used by the sponsor
    // service, the recovery responder, and setup.
    private val globalEventProjector = DefaultGlobalEventProjector(
        dagEngine = dagEngine,
        pipeline = pipeline,
        messageRepository = messageRepository,
        identityReader = identityStore,
        identityWriter = identityStore,
        localIdentity = localIdentity,
        roomRepository = roomRepository,
        router = router,
        cryptoProvider = crypto,
    )

    // Room membership plane: sole writer of chain-derived chat-room membership.
    private val roomEventProjector = DefaultRoomEventProjector(
        pipeline = pipeline,
        dagEngine = dagEngine,
        globalEventProjector = globalEventProjector,
        messageRepository = messageRepository,
        roomRepository = roomRepository,
        identityReader = identityStore,
        router = router,
    )

    private val syncCoordinator = DefaultSyncCoordinator(
        pipeline = pipeline,
        roomRepository = roomRepository,
        messageRepository = messageRepository,
        pendingSyncRepository = pendingSyncRepository,
        router = router,
        roomChanges = roomEventProjector.stateChanges,
        identityChanges = globalEventProjector.stateChanges,
        orchestratorConfig = config.orchestratorConfig,
    )

    // Onboarding. Newcomer onboarding runs in every mode: headless relays have no runtime yet must
    // still be onboarded as newcomers. The recovery responder serves OTHER nodes' account-recovery
    // requests, also in every mode — an always-on relay is exactly the bootstrap endpoint a
    // recovering device points at.
    private val onboardingProvider = DefaultOnboardingProvider(
        router = router,
        sessionStore = vault.bootstrapSessionStore,
        syncCoordinator = syncCoordinator,
        identityReader = identityStore,
        provisionalSeeder = identityStore,
        projector = globalEventProjector,
        clock = clock,
        routerConfig = config.routerConfig,
    )
    private val recoveryResponder = DefaultRecoveryResponder(
        router = router,
        identityReader = identityStore,
        messageRepository = messageRepository,
        localDeviceType = bootConfig.localDeviceType,
        projector = globalEventProjector,
    )

    private val transportStatusMonitor = TransportStatusMonitor(router)
    private val identityWatch = LocalIdentityWatch(
        identityChanges = globalEventProjector.stateChanges,
        onboardingState = onboardingProvider.state,
        identityReader = identityStore,
    )

    override val setup = LocalSetup(
        localIdentity = localIdentity,
        localKeys = localKeys,
        cryptoProvider = crypto,
        onboardingProvider = onboardingProvider,
        globalEventProjector = globalEventProjector,
        router = router,
        localDeviceType = bootConfig.localDeviceType,
    )

    override val runtime: DefaultOrchestratorRuntime? =
        if (bootConfig.mode != NodeMode.FULL_CLIENT) null else DefaultOrchestratorRuntime(
            dagEngine = dagEngine,
            router = router,
            pipeline = pipeline,
            messageRepository = messageRepository,
            deliveryRepository = deliveryRepository,
            causalHoldRepository = causalHoldRepository,
            localIdentity = localIdentity,
            maxTextMessageBytes = config.maxTextMessageBytes,
            orchestratorConfig = config.orchestratorConfig,
            userSettings = config,
            onboardingProvider = onboardingProvider,
            identityReader = identityStore,
            identityProvisioning = vault.identityProvisioning,
            cryptoProvider = crypto,
            globalEventProjector = globalEventProjector,
            roomEventProjector = roomEventProjector,
            roomRepository = roomRepository,
            localDeviceType = bootConfig.localDeviceType,
        )

    override val onboardingState: StateFlow<OnboardingState> get() = onboardingProvider.state
    override val transportStatus: StateFlow<LocalTransportStatus> get() = transportStatusMonitor.status
    override val resetRequests: Flow<ResetRequest> get() = identityWatch.resetRequests

    override suspend fun start(onProgress: (BootProgress) -> Unit) {
        onProgress(BootProgress(BootPhase.ROUTER, 0, 0))
        maintenance.start(scope)
        router.start()
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.STARTED,
            message = "Router started",
        )
        pipeline.start(scope)
        syncCoordinator.start(scope)
        globalEventProjector.start(scope)
        // Started after the global projector so the room boot sweep sees committed identity;
        // deferred rows land on the next GLOBAL commit anyway.
        onProgress(BootProgress(BootPhase.FOLDS, 0, 0))
        roomEventProjector.start(scope) { done, total ->
            onProgress(BootProgress(BootPhase.FOLDS, done, total))
        }
        onboardingProvider.start(scope)
        transportStatusMonitor.start(scope)
        recoveryResponder.start(scope)
        runtime?.start(scope)
        // Announce presence + exchange room frontiers now that the ping consumers are subscribed.
        // Fire-and-forget: a send to a not-yet-ready peer can take minutes on real Tor and must
        // never gate boot completion (the periodic ping loop re-announces anyway).
        scope.launch { runCatching { router.announceOnline() } }
        onProgress(BootProgress(BootPhase.READY, 0, 0))
    }

    override suspend fun stop() {
        runtime?.stop()
        router.stop()
    }
}
