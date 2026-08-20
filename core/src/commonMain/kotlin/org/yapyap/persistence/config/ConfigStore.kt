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

class ConfigStore(
    private val userSettingsFile: Path,
    private val stateFile: Path,
) {
    private val _runtime = MutableStateFlow<RuntimeConfig>(RuntimeConfig())   // temp, set in init
    val runtime: StateFlow<RuntimeConfig> = _runtime.asStateFlow()

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
        val next = readAndParse<UserPreferences>(userSettingsFile)
        if (next != null) {                                  // parse error → keep old prefs
            _userPrefs.value = next
            rederiveAndCommit()
        }
    }

    private suspend fun rederiveAndCommit() {
        val effective = derive(_userPrefs.value, _networkPolicy.value)
        writeFile(stateFile, toml.encodeToString(effective))
        _runtime.value = effective
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
            null //TODO: log
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
