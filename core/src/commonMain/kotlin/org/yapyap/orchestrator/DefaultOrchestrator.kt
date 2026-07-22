package org.yapyap.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.logging.AppLogger
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.key.DefaultIdentityKeyRepository
import org.yapyap.persistence.key.DefaultKeyStore
import org.yapyap.persistence.key.DefaultMasterKeyProvider
import org.yapyap.persistence.key.KeyringSessionFactory
import org.yapyap.transport.tor.backend.TorBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackend

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

    override suspend fun start() {
        if (_state.value == OrchestratorState.Running) return
        // Unlock DB, wire Default* stack, start router — next slice.
        _state.value = OrchestratorState.Starting
        _lastError.value = null
        try {
            val keyStore = DefaultKeyStore(config.keyringServiceName, keyringSessionFactory, logger = logger)
            val cryptoProvider = KmpCryptoProvider(logger = logger)
            val masterKey = DefaultMasterKeyProvider(keyStore, cryptoProvider).getOrCreate()
            val dbConnection = DatabaseFactory(createDriverFactory(masterKey), logger = logger).createConnection()
            val identityRepo = DefaultIdentityKeyRepository(dbConnection.database, logger = logger)

            if (identityRepo.getLocalDeviceRecord() == null) {
                _state.value = OrchestratorState.SetupRequired
                firstInit()   // or pause for GUI + completeSetup later
            }
            _state.value = OrchestratorState.Starting
            init()

            _state.value = OrchestratorState.Running
        } catch (e: Throwable) {
            _lastError.value = e
            _state.value = OrchestratorState.Failed
        }
    }

    private suspend fun firstInit() {
        //TODO first init logic
    }

    private suspend fun init() {
        //TODO init logic
    }

    override suspend fun stop() {
        if (_state.value != OrchestratorState.Running) return
        _state.value = OrchestratorState.Stopping

        try {
            // Stop router / close resources — next slice.
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
