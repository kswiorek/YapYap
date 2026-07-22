package org.yapyap.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yapyap.crypto.e2ee.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.X3dhHandshake
import org.yapyap.crypto.identity.DefaultIdentityResolver
import org.yapyap.crypto.identity.IdentityKeyServiceConfig
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.logging.AppLogger
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.crypto.DefaultCryptoSessionStore
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.key.*
import org.yapyap.persistence.packet.DefaultPacketDeduplicator
import org.yapyap.persistence.packet.DefaultPacketIdAllocator
import org.yapyap.persistence.packet.DefaultPacketOutbox
import org.yapyap.protection.envelope.PlaintextFileProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.envelope.SignedAndEncryptedWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.transport.tor.backend.TorBackend
import org.yapyap.transport.tor.transport.DefaultTorTransport
import org.yapyap.transport.webrtc.backend.WebRtcBackend
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport

class DefaultOrchestrator(
    private val config: OrchestratorConfig,
    private val keyringSessionFactory: KeyringSessionFactory,
    private val createDriverFactory: (masterKey: ByteArray) -> DriverFactory,
    private val torBackend: TorBackend,
    private val webRtcBackend: WebRtcBackend,
    private val logger: AppLogger = NoopAppLogger,
) : Orchestrator {

    private val _state = MutableStateFlow(OrchestratorState.Created)
    private val _lastError = MutableStateFlow<Throwable?>(null)
    private val runtimeImpl = object : OrchestratorRuntime {}

    override val state: StateFlow<OrchestratorState> = _state.asStateFlow()
    override val lastError: StateFlow<Throwable?> = _lastError.asStateFlow()

    private lateinit var router: DefaultRouter
    private lateinit var torTransport: DefaultTorTransport
    private lateinit var webRtcTransport: DefaultWebRtcTransport
    private lateinit var identityResolver: DefaultIdentityResolver
    private lateinit var cryptoSessionManager: DefaultCryptoSessionManager
    private lateinit var database: YapYapDatabase

    override suspend fun start() {
        if (_state.value == OrchestratorState.Running) return
        _state.value = OrchestratorState.Starting
        _lastError.value = null
        try {
            val keyStore = DefaultKeyStore(config.keyringServiceName, keyringSessionFactory, logger = logger)
            val cryptoProvider = KmpCryptoProvider(logger = logger)
            val masterKey = DefaultMasterKeyProvider(keyStore, cryptoProvider).getOrCreate()
            val dbConnection = DatabaseFactory(createDriverFactory(masterKey), logger = logger).createConnection()
            val identityRepo = DefaultIdentityKeyRepository(dbConnection.database, logger = logger)
            database = dbConnection.database

            if (identityRepo.getLocalDeviceRecord() == null) {
                _state.value = OrchestratorState.SetupRequired
                firstInit()   // or pause for GUI + completeSetup later
            }
            _state.value = OrchestratorState.Starting
            init(cryptoProvider, identityRepo, keyStore)

            _state.value = OrchestratorState.Running
        } catch (e: Throwable) {
            _lastError.value = e
            _state.value = OrchestratorState.Failed
        }
    }

    private suspend fun firstInit() {
        //TODO first init logic
    }

    private suspend fun init(cryptoProvider: KmpCryptoProvider, identityRepo: DefaultIdentityKeyRepository, keyStore: DefaultKeyStore) {
        identityResolver = DefaultIdentityResolver(
            cryptoProvider = cryptoProvider,
            publicKeyRepository = identityRepo,        // DefaultIdentityKeyRepository
            privateKeyStore = keyStore,                 // DefaultKeyStore
            config = IdentityKeyServiceConfig(),        // use defaults or derive from config
            logger = logger,
        )

        torTransport = DefaultTorTransport(torBackend, logger)
        webRtcTransport = DefaultWebRtcTransport(webRtcBackend, logger)

        val packetIdAllocator = DefaultPacketIdAllocator(database, logger = logger)
        val packetDeduplicator = DefaultPacketDeduplicator(database, logger = logger)
        val packetOutbox = DefaultPacketOutbox(database, logger = logger)

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
            logger = logger,
        )

        val signatureProvider = DefaultSignatureProvider(identityResolver, cryptoProvider, logger)

        val envelopeProtectionService = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = SignedAndEncryptedWebRtcSignalProtection(
                signatureProvider,
                cryptoSessionManager,
                cryptoProvider,
                logger
            ),
            fileProtection = PlaintextFileProtection(cryptoProvider, logger), //TODO file protection
            messageProtection = SignedAndEncryptedMessageProtection(
                signatureProvider,
                cryptoSessionManager,
                cryptoProvider,
                logger
            ),
            systemProtection = SignedSystemProtection(signatureProvider, cryptoProvider, logger),
        )

        router = DefaultRouter(
            torTransport = torTransport,
            webRtcTransport = webRtcTransport,
            identityResolver = identityResolver,
            packetIdAllocator = packetIdAllocator,
            packetDeduplicator = packetDeduplicator,
            packetOutbox = packetOutbox,
            envelopeProtectionService = envelopeProtectionService,
            logger = logger,
            routerConfig = config.routerConfig,
        )

        router.start()
    }

    override suspend fun stop() {
        if (_state.value != OrchestratorState.Running) return
        _state.value = OrchestratorState.Stopping

        try {
            router.stop()
        } catch (e: Throwable) {
            _lastError.value = e
            _state.value = OrchestratorState.Failed
        }
        finally {
            _state.value = OrchestratorState.Stopped
        }
    }

    override fun runtime(): OrchestratorRuntime {
        check(_state.value == OrchestratorState.Running) { "Orchestrator must be Running" }
        return runtimeImpl
    }
}
