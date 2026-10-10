package org.yapyap.orchestrator.runtime.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import org.yapyap.config.ConfigValue
import org.yapyap.config.Setting
import org.yapyap.config.UpdateResult
import org.yapyap.config.buildSettings
import org.yapyap.persistence.config.ConfigStore

public interface ConfigService {
    /** All user-facing settings, live, in a stable order (grouped via [Setting.group]). */
    public val settings: StateFlow<List<Setting>>

    /**
     * Update a user setting by id. Pass `null` to clear the override and
     * restore the default. Non-user-editable ids and invalid values are rejected.
     */
    public suspend fun update(id: String, value: ConfigValue?): UpdateResult
}

internal class DefaultConfigService(
    private val configStore: ConfigStore,
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
