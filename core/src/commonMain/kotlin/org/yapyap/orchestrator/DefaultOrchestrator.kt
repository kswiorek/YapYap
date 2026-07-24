package org.yapyap.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.X3dhHandshake
import org.yapyap.crypto.identity.DefaultIdentityProvisioning
import org.yapyap.crypto.identity.DefaultIdentityResolver
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
import org.yapyap.time.SystemEpochSecondsProvider
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
    private lateinit var keyStore: DefaultKeyStore
    private lateinit var cryptoProvider: KmpCryptoProvider
    private lateinit var identityRepo: DefaultIdentityKeyRepository
    private lateinit var identityProvisioning: DefaultIdentityProvisioning

    override suspend fun start() {
        if (_state.value == OrchestratorState.Running) return
        _state.value = OrchestratorState.Starting
        _lastError.value = null
        try {
            keyStore = DefaultKeyStore(config.keyringServiceName, keyringSessionFactory, logger = logger)
            cryptoProvider = KmpCryptoProvider(logger = logger)
            val masterKey = DefaultMasterKeyProvider(keyStore, cryptoProvider).getOrCreate()
            val dbConnection = DatabaseFactory(createDriverFactory(masterKey), logger = logger).createConnection()
            identityRepo = DefaultIdentityKeyRepository(dbConnection.database, logger = logger)
            database = dbConnection.database
            identityResolver = DefaultIdentityResolver(
                cryptoProvider = cryptoProvider,
                publicKeyRepository = identityRepo,        // DefaultIdentityKeyRepository
                privateKeyStore = keyStore,                 // DefaultKeyStore
                config = config.identityKeyServiceConfig,        // use defaults or derive from config
                logger = logger,
            )
            identityProvisioning = DefaultIdentityProvisioning(
                cryptoProvider, identityRepo, keyStore,
                config.identityKeyServiceConfig, identityResolver,
                SystemEpochSecondsProvider,
                logger,
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
        when (intent) {
            is SetupIntent.NewAccountFirstDevice -> {
                val account = identityProvisioning.createNewAccountIdentity(intent.accountName)
                val device = identityProvisioning.createNewDeviceIdentity()
                val recoveryKey = identityProvisioning.exportLocalAccountRecoveryKey()
                _state.value = OrchestratorState.Starting
                init()
                val tor = identityResolver.resolveTorEndpointForDevice(device.deviceId)
                _state.value = OrchestratorState.Running

                return SetupResult(
                    identityPayload = IdentityPayload(
                        account = account,
                        device = identityResolver.getLocalDeviceIdentityRecord(),
                        torEndpoint = tor,
                    ),
                    recoveryKey = recoveryKey,
                )
            }
            is SetupIntent.ImportAccountRecoveryKey -> {
                val account = identityProvisioning.importLocalAccountFromRecovery(intent.recoveryKey)
                val device = identityProvisioning.createNewDeviceIdentity()
                _state.value = OrchestratorState.Starting
                init()
                val tor = identityResolver.resolveTorEndpointForDevice(device.deviceId)
                _state.value = OrchestratorState.Running

                return SetupResult(
                    identityPayload = IdentityPayload(
                        account = account,
                        device = identityResolver.getLocalDeviceIdentityRecord(),
                        torEndpoint = tor,
                    ),
                    recoveryKey = null,
                )
                //TODO trigger sync
            }
            is SetupIntent.AddDeviceToExistingAccount -> {
                identityProvisioning.createPlaceholderAccountIdentity()
                val device = identityProvisioning.createNewDeviceIdentity()
                _state.value = OrchestratorState.Starting
                init()
                val tor = identityResolver.resolveTorEndpointForDevice(device.deviceId)
                _state.value = OrchestratorState.Running
                return SetupResult(
                    identityPayload = IdentityPayload(
                        account = null,
                        device = identityResolver.getLocalDeviceIdentityRecord(),
                        torEndpoint = tor,
                    ),
                    recoveryKey = null,
                )
            }
        }
    }

    private suspend fun init() {
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
        // TODO ping, request sync etc
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
