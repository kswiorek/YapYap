package org.yapyap.orchestrator.runtime.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import org.yapyap.config.*

public interface ConfigService {
    /** All user-facing settings, live, in a stable order (grouped via [Setting.group]). */
    public val settings: StateFlow<List<Setting>>

    /**
     * Update a user setting by id. Pass `null` to clear the override and
     * restore the default. Non-user-editable ids and invalid values are rejected.
     */
    public suspend fun update(id: String, value: ConfigValue?): UpdateResult
}

/** The user-settings side of the config store, as the config service sees it. */
internal interface UserSettings {
    /** Current setting overrides, live. */
    val overrides: StateFlow<Overrides>

    /** Validates and persists a user override; `null` clears it. */
    suspend fun updateUser(id: String, value: ConfigValue?): UpdateResult
}

internal class DefaultConfigService(
    private val configStore: UserSettings,
) : ConfigService {

    private val _settings = MutableStateFlow(buildSettings(configStore.overrides.value))
    override val settings: StateFlow<List<Setting>> = _settings.asStateFlow()

    fun start(scope: CoroutineScope) {
        configStore.overrides
            .map { buildSettings(it) }
            .onEach { _settings.value = it }
            .launchIn(scope)
    }

    override suspend fun update(id: String, value: ConfigValue?): UpdateResult =
        configStore.updateUser(id, value)
}
