package org.yapyap.orchestrator.runtime.config

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.config.ConfigValue
import org.yapyap.config.Setting
import org.yapyap.config.UpdateResult

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
    /** All settings with their effective values, live. */
    val settings: StateFlow<List<Setting>>

    /** Validates and persists a user override; `null` clears it. */
    suspend fun updateUser(id: String, value: ConfigValue?): UpdateResult
}

internal class DefaultConfigService(
    private val configStore: UserSettings,
) : ConfigService {

    override val settings: StateFlow<List<Setting>> = configStore.settings

    override suspend fun update(id: String, value: ConfigValue?): UpdateResult =
        configStore.updateUser(id, value)
}
