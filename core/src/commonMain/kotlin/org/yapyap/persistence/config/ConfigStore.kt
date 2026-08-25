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

    private val _userPrefs = MutableStateFlow(UserPreferences())
    private val _networkPolicy = MutableStateFlow(NetworkPolicy())

    private val writeMutex = Mutex()
    private val toml = Toml { ignoreUnknownKeys = true }

    init {
        // 1. user wishes (absent/corrupt → defaults)
        val userPrefs = readAndParse<UserPreferences>(userSettingsFile) ?: UserPreferences()

        // 2. last effective config + network cache from state.toml
        val stateRuntime = readAndParse<RuntimeConfig>(stateFile)
        val netPolicy = stateRuntime?.let { fromRuntime(it) } ?: NetworkPolicy()

        // 3. derive effective
        val effective = derive(userPrefs, netPolicy)

        // 4. refresh state.toml on boot (it's the persisted truth)
        writeFile(stateFile, toml.encodeToString(effective))

        _userPrefs.value = userPrefs
        _networkPolicy.value = netPolicy
        _runtime.value = effective
    }

    suspend fun updateUser(patch: UserPreferences) = writeMutex.withLock {
        val next = _userPrefs.value.mergePatch(patch)       // null = unchanged
        writeFile(userSettingsFile, toml.encodeToString(next))
        _userPrefs.value = next
        rederiveAndCommit()
    }

    suspend fun applyNetwork(policy: NetworkPolicy) = writeMutex.withLock {
        _networkPolicy.value = policy                        // full replacement
        rederiveAndCommit()                                  // does NOT touch userSettings.toml
    }

    suspend fun onUserSettingsFileChanged() = writeMutex.withLock {
        val next = readAndParse<UserPreferences>(userSettingsFile) ?: return@withLock  // parse error → keep old prefs
        if (next == _userPrefs.value) return@withLock                                  // self-trigger or no-op save
        _userPrefs.value = next
        rederiveAndCommit()
    }

    private fun rederiveAndCommit() {
        val effective = derive(_userPrefs.value, _networkPolicy.value)
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

    private inline fun <reified T> readAndParse(file: Path): T? {
        if (!SystemFileSystem.exists(file)) return null
        return try {
            val buffer = Buffer()
            SystemFileSystem.source(file).use { source ->
                while (source.readAtMostTo(buffer, Long.MAX_VALUE) != -1L) {
                    // read until exhausted
                }
            }
            toml.decodeFromString<T>(buffer.readString())
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.CONFIG,
                event = LogEvent.CONFIG_READ_FAILED,
                message = "Failed to read config file:",
                throwable = e,
                fields = mapOf("file" to file),
            )
            null
        }
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
