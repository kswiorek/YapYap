package org.yapyap.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.yapyap.config.BootConfig
import org.yapyap.config.ConfigFileWatcher
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.maintenance.CryptoMaintenance
import org.yapyap.crypto.e2ee.manager.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.session.X3dhHandshake
import org.yapyap.crypto.identity.DefaultIdentityProvisioning
import org.yapyap.crypto.identity.DefaultIdentityResolver
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.AppLogger
import org.yapyap.orchestrator.dag.DefaultDagEngine
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.globalevent.DefaultGlobalEventProjector
import org.yapyap.orchestrator.globalevent.IdentityStateChange
import org.yapyap.orchestrator.maintenance.MaintenanceScheduler
import org.yapyap.orchestrator.onboarding.DefaultOnboardingProvider
import org.yapyap.orchestrator.onboarding.DefaultRecoveryResponder
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.pipeline.DefaultInboundMessagePipeline
import org.yapyap.orchestrator.runtime.DefaultOrchestratorRuntime
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.sync.DefaultSyncCoordinator
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.availability.DefaultPeerAvailabilityStore
import org.yapyap.persistence.config.ConfigStore
import org.yapyap.persistence.crypto.DefaultCryptoSessionStore
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomType
import org.yapyap.persistence.key.*
import org.yapyap.persistence.messaging.DefaultCausalHoldRepository
import org.yapyap.persistence.messaging.DefaultMessageRepository
import org.yapyap.persistence.messaging.DefaultRoomRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.persistence.packet.DefaultPacketDeduplicator
import org.yapyap.persistence.packet.DefaultPacketOutbox
import org.yapyap.persistence.sync.DefaultPendingSyncRepository
import org.yapyap.protection.envelope.*
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.protocol.envelopes.accountSignedDeviceBindingBytes
import org.yapyap.routing.maintenance.PacketStoreMaintenance
import org.yapyap.routing.ping.DefaultFrontierSnapshotProvider
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.sync.DefaultSyncPayloadProvider
import org.yapyap.transport.tor.backend.TorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.transport.DefaultTorTransport
import org.yapyap.transport.webrtc.backend.WebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import kotlin.time.Clock

class DefaultOrchestrator(
    private val dataDirectory: Path,
    private val bootConfig: BootConfig,
    private val keyringSessionFactory: KeyringSessionFactory,
    private val createDriverFactory: (masterKey: ByteArray, databaseFile: Path) -> DriverFactory,
    private val createTorBackend: (StateFlow<TorBackendConfig>, torStateRoot: Path) -> TorBackend,
    private val createWebRtcBackend: (StateFlow<WebRtcBackendConfig>) -> WebRtcBackend,
    private val createLogger: (logDirectory: Path) -> AppLogger,
    private val createConfigFileWatcher: (userSettingsFile: Path) -> ConfigFileWatcher,
) : Orchestrator {

    private val _state = MutableStateFlow(OrchestratorState.Created)
    private val _lastError = MutableStateFlow<Throwable?>(null)

    override val state: StateFlow<OrchestratorState> = _state.asStateFlow()
    override val lastError: StateFlow<Throwable?> = _lastError.asStateFlow()

    private val _onboardingState = MutableStateFlow(OnboardingState.IDLE)
    override val onboardingState: StateFlow<OnboardingState> = _onboardingState.asStateFlow()

    private lateinit var configStore: ConfigStore
    private lateinit var router: DefaultRouter
    private lateinit var torBackend: TorBackend
    private lateinit var torTransport: DefaultTorTransport
    private lateinit var webRtcBackend: WebRtcBackend
    private lateinit var webRtcTransport: DefaultWebRtcTransport
    private lateinit var identityResolver: DefaultIdentityResolver
    private lateinit var cryptoSessionManager: DefaultCryptoSessionManager
    private lateinit var bootstrapSessionStore: BootstrapSessionStore
    private lateinit var database: YapYapDatabase
    private lateinit var keyStore: DefaultKeyStore
    private lateinit var cryptoProvider: DefaultCryptoProvider
    private lateinit var identityRepo: DefaultIdentityKeyRepository
    private lateinit var identityProvisioning: DefaultIdentityProvisioning
    private lateinit var dagEngine: DefaultDagEngine
    private lateinit var pipeline: DefaultInboundMessagePipeline
    private lateinit var syncCoordinator: DefaultSyncCoordinator
    private lateinit var roomRepository: RoomRepository
    private lateinit var orchestratorScope: CoroutineScope

    private lateinit var orchestratorRuntime: DefaultOrchestratorRuntime

    private lateinit var onboardingProvider: DefaultOnboardingProvider

    private lateinit var recoveryResponder: DefaultRecoveryResponder

    private lateinit var projector: DefaultGlobalEventProjector


    override suspend fun start() {
        if (_state.value == OrchestratorState.Running) return
        orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        _state.value = OrchestratorState.Starting
        _lastError.value = null
        try {
            // 1. paths (kotlinx.io.files.Path, resolve via Path(parent, child))
            val databaseFile = Path(dataDirectory, "vault.db")
            val torStateRoot = Path(dataDirectory, "tor")
            val logDirectory = Path(dataDirectory, "logs")
            val userSettingsFile = Path(dataDirectory, "userSettings.toml")
            val stateFile = Path(dataDirectory, "state.toml")

            // 2. create dirs (sync, as today)
            SystemFileSystem.createDirectories(dataDirectory)
            SystemFileSystem.createDirectories(torStateRoot)
            SystemFileSystem.createDirectories(logDirectory)

            // 3. logging first
            AppLog.init(createLogger(logDirectory))

            // 4. config store (loads userSettings.toml + state.toml cache → derive)
            configStore = ConfigStore(userSettingsFile, stateFile)

            val watcher = createConfigFileWatcher(userSettingsFile)

            orchestratorScope.launch {
                watcher.changes().collect { configStore.onUserSettingsFileChanged() }
            }

            // TODO(sprint 7): fetch NetworkPolicy from clearnet API and call
            //   configStore.applyNetwork(fetched) before backends read the derived config.


            torBackend    = createTorBackend(configStore.torConfig, torStateRoot)
            webRtcBackend = createWebRtcBackend(configStore.webRtcConfig)

            keyStore = DefaultKeyStore(keyringSessionFactory)
            cryptoProvider = DefaultCryptoProvider()
            val masterKey = DefaultMasterKeyProvider(keyStore, cryptoProvider).getOrCreate()
            val dbConnection = DatabaseFactory(createDriverFactory(masterKey, databaseFile)).createConnection()
            identityRepo = DefaultIdentityKeyRepository(dbConnection.database, bootConfig.localDeviceType)
            database = dbConnection.database
            identityResolver = DefaultIdentityResolver(
                cryptoProvider = cryptoProvider,
                publicKeyRepository = identityRepo,        // DefaultIdentityKeyRepository
                privateKeyStore = keyStore,                 // DefaultKeyStore
            )
            bootstrapSessionStore = BootstrapSessionStore(keyStore)
            identityProvisioning = DefaultIdentityProvisioning(
                cryptoProvider, identityRepo, keyStore,
                identityResolver,
                Clock.System,
            )
            try {
                identityResolver.getLocalDeviceIdentityRecord()
                _state.value = OrchestratorState.Starting
                init()
                _state.value = OrchestratorState.Running
            }
            catch (_: CryptoException) {
                _state.value = OrchestratorState.SetupRequired
            }
        } catch (e: Throwable) {
            _lastError.value = e
            _state.value = OrchestratorState.Failed
        }
    }

    override suspend fun completeSetup(intent: SetupIntent): SetupResult {
        require(state.value == OrchestratorState.SetupRequired) { "Orchestrator must be in SetupRequired state" }
        // The provider owns the session slot: stale secrets (e.g. wiped data dir, live keyring)
        // are cleared through it after init(), before a new session begins.
        when (intent) {
            is SetupIntent.Genesis -> {
                val account = identityProvisioning.createNewAccountIdentity(intent.accountName, admin = true)
                val device = identityProvisioning.createNewDeviceIdentity()
                val recoveryKey = identityProvisioning.exportLocalAccountRecoveryKey()
                _state.value = OrchestratorState.Starting
                init()
                onboardingProvider.cancelOnboarding()
                roomRepository.addMember(RoomId.GLOBAL, account.accountId, RoomMemberRole.MEMBER)
                _state.value = OrchestratorState.Running

                // Genesis: append AddAccount (DAG root, prevId == null — admin by definition, §3)
                // + AddDevice self-introduction, then fold immediately. The account key is online
                // here (fresh provisioning), so the binding signature is computed locally.
                // is_admin is seeded true in the local accounts row already so the GUI can rely
                // on it; the projector's fold owns this column once it lands.
                val tor = identityResolver.resolveTorEndpointForDevice(device.deviceId)
                val genesisKeySignature = cryptoProvider.signDetached(
                    identityResolver.getLocalAccountPrivateKey(IdentityKeyPurpose.SIGNING),
                    accountSignedDeviceBindingBytes(
                        accountId = account.accountId,
                        deviceId = device.deviceId,
                        signingPublicKey = device.signing.publicKey,
                        encryptionPublicKey = device.encryption.publicKey,
                        torEndpoint = tor,
                        deviceType = bootConfig.localDeviceType,
                    ),
                )
                projector.publishGenesisAccount(
                    account = account,
                    device = device,
                    deviceType = bootConfig.localDeviceType,
                    torEndpoint = tor,
                    accountKeySignature = genesisKeySignature,
                )
                return SetupResult(
                    invite = null, // no sponsor invite — the network waits in limbo for its first newcomer
                    recoveryKey = recoveryKey,
                )
            }
            is SetupIntent.NewAccountFirstDevice -> {
                val account = identityProvisioning.createNewAccountIdentity(intent.accountName)
                val device = identityProvisioning.createNewDeviceIdentity()
                val recoveryKey = identityProvisioning.exportLocalAccountRecoveryKey()
                _state.value = OrchestratorState.Starting
                init()
                onboardingProvider.cancelOnboarding()
                roomRepository.addMember(RoomId.GLOBAL, account.accountId, RoomMemberRole.MEMBER)
                val tor = identityResolver.resolveTorEndpointForDevice(device.deviceId)
                _state.value = OrchestratorState.Running

                // Join-existing-network path: always produces a sponsor invite (is_admin seeded
                // false, projector-corrected). The provider persists the secret, enters
                // AWAITING_INTRO, and arms the timer. The newcomer's account key signs the device
                // binding — the sponsor relays it as the AddDevice key_signature (branch 2).
                val secret = cryptoProvider.randomBytes(32)
                onboardingProvider.beginSession(secret)
                val newcomerDevice = identityResolver.getLocalDeviceIdentityRecord()
                val accountKeySignature = cryptoProvider.signDetached(
                    identityResolver.getLocalAccountPrivateKey(IdentityKeyPurpose.SIGNING),
                    accountSignedDeviceBindingBytes(
                        accountId = account.accountId,
                        deviceId = newcomerDevice.deviceId,
                        signingPublicKey = newcomerDevice.signing.publicKey,
                        encryptionPublicKey = newcomerDevice.encryption.publicKey,
                        torEndpoint = tor,
                        deviceType = bootConfig.localDeviceType,
                    ),
                )

                return SetupResult(
                    invite = Invite(
                        account = account,
                        device = newcomerDevice,
                        deviceType = bootConfig.localDeviceType,
                        torEndpoint = tor,
                        sharedSecret = secret,
                        accountKeySignature = accountKeySignature,
                    ),
                    recoveryKey = recoveryKey,
                )
            }
            is SetupIntent.ImportAccountRecoveryKey -> {
                val account = identityProvisioning.importLocalAccountFromRecovery(intent.recoveryKey)
                val device = identityProvisioning.createNewDeviceIdentity()
                _state.value = OrchestratorState.Starting
                init()
                onboardingProvider.cancelOnboarding()
                roomRepository.addMember(RoomId.GLOBAL, account.accountId, RoomMemberRole.MEMBER)
                val tor = identityResolver.resolveTorEndpointForDevice(device.deviceId)
                _state.value = OrchestratorState.Running

                // Recovery request (§8.2 phase 1): no known peers, so the request rides the outbox
                // with the user-supplied endpoint override. The account key signs the device
                // binding (possession proof, later the AddDevice key_signature); the secret
                // session-binds the responder's AEAD reply.
                val secret = cryptoProvider.randomBytes(32)
                onboardingProvider.beginSession(secret)
                val unsigned = RecoveryRequest(
                    account = account,
                    device = device,
                    deviceType = bootConfig.localDeviceType,
                    torEndpoint = tor,
                    sharedSecret = secret,
                    // Placeholder: the binding being signed does not cover the signature itself,
                    // so sign-then-copy below is sound.
                    accountSignature = byteArrayOf(0),
                )
                val accountSignature = cryptoProvider.signDetached(
                    identityResolver.getLocalAccountPrivateKey(IdentityKeyPurpose.SIGNING),
                    unsigned.accountSignedDeviceBindingBytes(),
                )
                router.sendBootstrap(
                    unsigned.copy(accountSignature = accountSignature),
                    target = intent.bootstrapEndpoint.peerId,
                    targetEndpoint = intent.bootstrapEndpoint.torEndpoint,
                )

                return SetupResult(
                    invite = null, // no sponsor QR — direct to the supplied bootstrap endpoint
                    recoveryKey = null,
                )
            }
            is SetupIntent.AddDeviceToExistingAccount -> {
                //TODO: if device is headless and belongs to an account, exclude from message fanount but not global room?
                val account = identityProvisioning.createPlaceholderAccountIdentity()
                val device = identityProvisioning.createNewDeviceIdentity()
                _state.value = OrchestratorState.Starting
                init()
                onboardingProvider.cancelOnboarding()
                roomRepository.addMember(RoomId.GLOBAL, account.accountId, RoomMemberRole.MEMBER)
                val tor = identityResolver.resolveTorEndpointForDevice(device.deviceId)
                _state.value = OrchestratorState.Running

                val secret = cryptoProvider.randomBytes(32)
                onboardingProvider.beginSession(secret)

                return SetupResult(
                    invite = Invite(
                        account = null, // existing account; the sponsor adds the device to its own
                        device = identityResolver.getLocalDeviceIdentityRecord(),
                        deviceType = bootConfig.localDeviceType,
                        torEndpoint = tor,
                        sharedSecret = secret,
                        accountKeySignature = null, // branch 1: the sponsor's own authorship authorizes
                    ),
                    recoveryKey = null,
                )
            }
        }
    }

    private suspend fun init() {
        torTransport = DefaultTorTransport(torBackend)
        webRtcTransport = DefaultWebRtcTransport(webRtcBackend)

        val packetDeduplicator = DefaultPacketDeduplicator(database)
        val packetOutbox = DefaultPacketOutbox(database)

        val cryptoSessionStore = DefaultCryptoSessionStore(database)
        val x3dhHandshake = X3dhHandshake(cryptoProvider)

        // Need the local device identity to initialize OPK repository
        val localDeviceId = identityResolver.getLocalDeviceId()

        val opkRepository = DefaultOpkRepository(
            database = database,
            keyStore = keyStore,
            crypto = cryptoProvider,
            localDeviceId = localDeviceId,
        )

        cryptoSessionManager = DefaultCryptoSessionManager(
            crypto = cryptoProvider,
            x3dh = x3dhHandshake,
            sessionStore = cryptoSessionStore,
            identityResolver = identityResolver,
            opkRepository = opkRepository,
            cryptoLimits = configStore.cryptoLimits,
            sessionConfig = configStore.cryptoConfig,
        )

        val signatureProvider = DefaultSignatureProvider(identityResolver, cryptoProvider)

        val envelopeProtectionService = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = SignedAndEncryptedWebRtcSignalProtection(
                signatureProvider,
                cryptoSessionManager,
                cryptoProvider,
            ),
            fileProtection = PlaintextFileProtection(cryptoProvider), //TODO file protection
            messageProtection = SignedAndEncryptedMessageProtection(
                signatureProvider,
                cryptoSessionManager,
                cryptoProvider,
            ),
            systemProtection = SignedSystemProtection(signatureProvider, cryptoProvider),
            bootstrapProtection = BootstrapProtection(cryptoProvider, bootstrapSessionStore),
        )

        val messageRepo = DefaultMessageRepository(database)
        val syncRepo = DefaultPendingSyncRepository(database)
        roomRepository = DefaultRoomRepository(database)

        // The GLOBAL control room must exist as a real room before the router starts:
        // messages.room_id FKs to rooms, and frontier sync / ping / broadcast all
        // consult rooms + room_members. Seed idempotently.
        roomRepository.ensureRoomExists(RoomId.GLOBAL, RoomType.GLOBAL_CONTROL, "global")

        val syncPayloadProvider = DefaultSyncPayloadProvider(messageRepo, configStore.routerConfig)

        val frontierSnapshotProvider = DefaultFrontierSnapshotProvider(roomRepository, messageRepo)

        val peerAvailabilityStore = DefaultPeerAvailabilityStore(database)

        val maintenance = MaintenanceScheduler(
            tasks = listOf(
                PacketStoreMaintenance(packetOutbox, packetDeduplicator, configStore.routerConfig)::run,
                CryptoMaintenance(cryptoSessionStore, opkRepository, configStore.cryptoConfig)::run
            ),
            config = configStore.orchestratorConfig
        )
        maintenance.start(orchestratorScope)

        router = DefaultRouter(
            torTransport = torTransport,
            webRtcTransport = webRtcTransport,
            identityResolver = identityResolver,
            packetDeduplicator = packetDeduplicator,
            packetOutbox = packetOutbox,
            envelopeProtectionService = envelopeProtectionService,
            syncPayloadProvider = syncPayloadProvider,
            syncRepository = syncRepo,
            routerConfig = configStore.routerConfig,
            transportLimits = configStore.transportLimits,
            frontierSnapshotProvider = frontierSnapshotProvider,
            peerAvailabilityStore = peerAvailabilityStore,
            bootstrapSessionStore = bootstrapSessionStore,
            identityKeyRepository = identityRepo,
        )

        router.start()

        val causalHoldRepo = DefaultCausalHoldRepository(database)
        dagEngine = DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = causalHoldRepo,
            roomRepository = roomRepository,
            identityResolver = identityResolver,
            signatureProvider = signatureProvider,
            clock = Clock.System,
        )
        pipeline = DefaultInboundMessagePipeline(router, dagEngine)
        pipeline.start(orchestratorScope)

        syncCoordinator = DefaultSyncCoordinator(
            pipeline = pipeline,
            roomRepository = roomRepository,
            messageRepository = messageRepo,
            identityResolver = identityResolver,
            pendingSyncRepository = syncRepo,
            orchestratorConfig = configStore.orchestratorConfig,
        )
        syncCoordinator.start(orchestratorScope)

        // Global control plane: sole writer of chain-derived identity columns. Owns the GLOBAL
        // room fold (boot + every ingest trigger) and the publish path used by the sponsor
        // service, the recovery responder, and the genesis setup above.
        projector = DefaultGlobalEventProjector(
            dagEngine = dagEngine,
            pipeline = pipeline,
            messageRepository = messageRepo,
            identityResolver = identityResolver,
            roomRepository = roomRepository,
            router = router,
            cryptoProvider = cryptoProvider,
        )
        projector.start(orchestratorScope)

        // A newly committed device may resolve previously PENDING chat authors (the implemented
        // reverify path, §10); the boot sweep runs once after the first fold.
        orchestratorScope.launch {
            projector.stateChanges.collect { change ->
                if (change is IdentityStateChange.DeviceAdded) {
                    dagEngine.reverifyPendingFor(change.deviceId)
                }
            }
        }
        orchestratorScope.launch { dagEngine.reverifyAllPending() }

        // Newcomer onboarding runs in every mode: headless relays have no runtime yet must still be
        // onboarded as newcomers. It consumes the sponsor's bootstrap intro, seeds provisional rows,
        // and triggers the global-room range sync.
        onboardingProvider = DefaultOnboardingProvider(
            router = router,
            sessionStore = bootstrapSessionStore,
            syncCoordinator = syncCoordinator,
            identityKeyRepository = identityRepo,
            roomRepository = roomRepository,
            projector = projector,
            clock = Clock.System,
            routerConfig = configStore.routerConfig,
        )
        onboardingProvider.start(orchestratorScope)
        // Mirror the provider's flow: the provider is recreated per start cycle, the exposed
        // flow is stable. Collectors see the current value on subscribe (IDLE before first
        // start, then whatever the provider drives).
        orchestratorScope.launch {
            onboardingProvider.state.collect { _onboardingState.value = it }
        }

        // Standing recovery service: serves OTHER nodes' account-recovery requests, in every mode —
        // an always-on relay is exactly the bootstrap endpoint a recovering device points at.
        recoveryResponder = DefaultRecoveryResponder(
            router = router,
            identityKeyRepository = identityRepo,
            messageRepository = messageRepo,
            localDeviceType = bootConfig.localDeviceType,
            projector = projector,
        )
        recoveryResponder.start(orchestratorScope)

        orchestratorScope.launch {
            router.pingPayloads.collect { roomFrontiers ->
                roomFrontiers.forEach { (roomId, tips) ->
                    syncCoordinator.requestFrontierSync(roomId, tips)
                }
            }
        }

        if (bootConfig.mode == NodeMode.FULL_CLIENT) {
            orchestratorRuntime = DefaultOrchestratorRuntime(
                dagEngine = dagEngine,
                router = router,
                pipeline = pipeline,
                database = database,
                identityResolver = identityResolver,
                messageLimits = configStore.messageLimits,
                configStore = configStore,
                onboardingProvider = onboardingProvider,
                identityKeyRepository = identityRepo,
                cryptoProvider = cryptoProvider,
                globalEventProjector = projector,
                localDeviceType = bootConfig.localDeviceType,
            )
            orchestratorRuntime.start(orchestratorScope)
        }

        // Announce presence + exchange lamport snapshots now that the subsystems consuming
        // pingPayloads (sync coordinator) are up and subscribed.
        router.announceOnline()
    }

    override suspend fun stop() {
        if (_state.value != OrchestratorState.Running) return
        _state.value = OrchestratorState.Stopping

        try {
            if (::orchestratorRuntime.isInitialized) {
                orchestratorRuntime.stop()
            }
            router.stop()
            orchestratorScope.cancel()
        } catch (e: Throwable) {
            _lastError.value = e
            _state.value = OrchestratorState.Failed
        }
        finally {
            _state.value = OrchestratorState.Stopped
        }
    }

    override fun runtime(): OrchestratorRuntime {
        check(bootConfig.mode == NodeMode.FULL_CLIENT) { "runtime() requires FULL_CLIENT mode"}
        check(_state.value == OrchestratorState.Running) { "Orchestrator must be Running" }
        return orchestratorRuntime
    }
}
