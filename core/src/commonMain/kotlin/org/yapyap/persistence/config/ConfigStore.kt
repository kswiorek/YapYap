package org.yapyap.persistence.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.peanuuutz.tomlkt.Toml
import org.yapyap.config.*
import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.crypto.e2ee.session.CryptoLimits
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.sync.SyncConfig
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig

class ConfigStore(
    private val userSettingsFile: Path,
    private val stateFile: Path,
) {
    private val _runtime = MutableStateFlow(RuntimeConfig())
    val runtime: StateFlow<RuntimeConfig> = _runtime.asStateFlow()

    private val _tor = MutableStateFlow(RuntimeConfig().tor)
    val torConfig: StateFlow<TorBackendConfig> = _tor.asStateFlow()

    private val _webRtc = MutableStateFlow(RuntimeConfig().webRtc)
    val webRtcConfig: StateFlow<WebRtcBackendConfig> = _webRtc.asStateFlow()

    private val _router = MutableStateFlow(RuntimeConfig().router)
    val routerConfig: StateFlow<RouterConfig> = _router.asStateFlow()

    private val _sync = MutableStateFlow(RuntimeConfig().sync)
    val syncConfig: StateFlow<SyncConfig> = _sync.asStateFlow()

    private val _crypto = MutableStateFlow(RuntimeConfig().crypto)
    val cryptoConfig: StateFlow<CryptoSessionConfig> = _crypto.asStateFlow()

    private val _orchestrator = MutableStateFlow(RuntimeConfig().orchestrator)
    val orchestratorConfig: StateFlow<OrchestratorConfig> = _orchestrator.asStateFlow()

    private val _messageLimits = MutableStateFlow(MessageLimits.from(RuntimeConfig()))
    val messageLimits: StateFlow<MessageLimits> = _messageLimits.asStateFlow()

    private val _cryptoLimits = MutableStateFlow(MessageLimits.from(RuntimeConfig()).crypto)
    val cryptoLimits: StateFlow<CryptoLimits> = _cryptoLimits.asStateFlow()

    private val _transportLimits = MutableStateFlow(MessageLimits.from(RuntimeConfig()).transport)
    val transportLimits: StateFlow<TransportLimits> = _transportLimits.asStateFlow()

    private val _userOverrides = MutableStateFlow<Overrides>(emptyMap())
    val userOverrides: StateFlow<Overrides> = _userOverrides.asStateFlow()

    private val _networkOverrides = MutableStateFlow<Overrides>(emptyMap())
    val networkOverrides: StateFlow<Overrides> = _networkOverrides.asStateFlow()

    private val writeMutex = Mutex()
    private val toml = Toml { ignoreUnknownKeys = true }

    init {
        // 1. user overrides (absent/corrupt → empty)
        val userOverrides = readUserOverrides(userSettingsFile) ?: emptyMap()

        // 2. last effective config + network cache from state.toml
        val stateRuntime = readAndParse<RuntimeConfig>(stateFile)
        val networkOverrides = stateRuntime?.let { projectNetwork(it) } ?: emptyMap()

        // 3. derive effective
        val effective = derive(userOverrides, networkOverrides)

        // 4. refresh state.toml on boot (it's the persisted truth)
        writeFile(stateFile, toml.encodeToString(effective))

        _userOverrides.value = userOverrides
        _networkOverrides.value = networkOverrides
        commit(effective)
    }

    /**
     * Set (or clear, when [value] is null) a single user setting.
     * Returns null on success, otherwise an error message describing why it was rejected.
     */
    suspend fun updateUser(id: String, value: ConfigValue?): String? = writeMutex.withLock {
        val field = FIELDS.firstOrNull { it.id == id } ?: return@withLock "Unknown setting: $id"
        if (field.source != FieldSource.USER) return@withLock "Setting '$id' is not user-editable"

        val next = if (value == null) {
            _userOverrides.value - id
        } else {
            when (val result = field.write(RuntimeConfig(), value)) {
                is WriteResult.Invalid -> return@withLock result.reason
                is WriteResult.Ok -> _userOverrides.value + (id to value)
            }
        }

        writeFile(userSettingsFile, next.toTomlText())
        _userOverrides.value = next
        rederiveAndCommit()
        null
    }

    suspend fun applyNetwork(network: Overrides) = writeMutex.withLock {
        _networkOverrides.value = network
        rederiveAndCommit()                                  // does NOT touch userSettings.toml
    }

    suspend fun onUserSettingsFileChanged() = writeMutex.withLock {
        val next = readUserOverrides(userSettingsFile) ?: return@withLock  // parse error → keep old
        if (next == _userOverrides.value) return@withLock                                          // self-trigger or no-op save
        _userOverrides.value = next
        rederiveAndCommit()
    }

    private fun rederiveAndCommit() {
        val effective = derive(_userOverrides.value, _networkOverrides.value)
        writeFile(stateFile, toml.encodeToString(effective))
        commit(effective)
    }

    private fun commit(effective: RuntimeConfig) {
        _runtime.value = effective
        _tor.value = effective.tor
        _webRtc.value = effective.webRtc
        _router.value = effective.router
        _sync.value = effective.sync
        _crypto.value = effective.crypto
        _orchestrator.value = effective.orchestrator
        val limits = MessageLimits.from(effective)
        _messageLimits.value = limits
        _cryptoLimits.value = limits.crypto
        _transportLimits.value = limits.transport
    }

    private fun readText(file: Path): String? {
        if (!SystemFileSystem.exists(file)) return null
        return try {
            val buffer = Buffer()
            SystemFileSystem.source(file).use { source ->
                while (source.readAtMostTo(buffer, Long.MAX_VALUE) != -1L) {
                    // read until exhausted
                }
            }
            buffer.readString()
        } catch (e: Exception) {
            logReadError(file, e)
            null
        }
    }

    private inline fun <reified T> readAndParse(file: Path): T? =
        readText(file)?.let { text ->
            try {
                toml.decodeFromString<T>(text)
            } catch (e: Exception) {
                logReadError(file, e)
                null
            }
        }

    private fun readUserOverrides(file: Path): Overrides? =
        readText(file)?.let { text ->
            try {
                Toml.parseToTomlTable(text).toOverrides()
            } catch (e: Exception) {
                logReadError(file, e)
                null
            }
        }

    private fun logReadError(file: Path, e: Exception) {
        AppLog.error(
            component = LogComponent.CONFIG,
            event = LogEvent.CONFIG_READ_FAILED,
            message = "Failed to read config file:",
            throwable = e,
            fields = mapOf("file" to file),
        )
    }

    private fun writeFile(file: Path, text: String) {
        val buffer = Buffer()
        buffer.writeString(text)
        SystemFileSystem.sink(file).use { sink ->
            sink.write(buffer, buffer.size)
            sink.flush()
        }
    }
}
