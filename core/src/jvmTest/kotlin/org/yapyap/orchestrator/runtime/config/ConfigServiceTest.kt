package org.yapyap.orchestrator.runtime.config

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import org.yapyap.config.*
import org.yapyap.persistence.config.ConfigStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ConfigServiceTest {

    private fun newDir(): Path {
        val dir = Path(SystemTemporaryDirectory, "yapyap-configsvc-${Uuid.random()}")
        SystemFileSystem.createDirectories(dir)
        return dir
    }

    // Select fields dynamically from FIELDS so the tests exercise the generic
    // structure/logic rather than pinning specific settings.
    private fun userNumberField(): NumberField =
        FIELDS.filterIsInstance<NumberField>().first { it.source == FieldSource.USER }

    private fun networkPeriodField(): PeriodField =
        FIELDS.filterIsInstance<PeriodField>().first { it.source == FieldSource.NETWORK }

    private fun nonEditableField(): Field = FIELDS.first { it.source != FieldSource.USER }

    private fun defaultNumber(field: NumberField): Long =
        (field.read(RuntimeConfig()) as ConfigValue.Number).value

    private fun defaultPeriod(field: PeriodField): Duration =
        (field.read(RuntimeConfig()) as ConfigValue.Period).value

    private fun number(service: ConfigService, id: String): Long =
        (service.settings.value.first { it.id == id } as NumberSetting).value

    private suspend fun awaitNumber(service: ConfigService, id: String, expected: Long) {
        withTimeout(10_000) {
            service.settings.first { settings ->
                (settings.first { it.id == id } as NumberSetting).value == expected
            }
        }
    }

    private suspend fun awaitPeriod(service: ConfigService, id: String, expected: Duration) {
        withTimeout(10_000) {
            service.settings.first { settings ->
                (settings.first { it.id == id } as PeriodSetting).value == expected
            }
        }
    }

    private fun writeFile(path: Path, text: String) {
        val buffer = Buffer()
        buffer.writeString(text)
        SystemFileSystem.sink(path).use { sink ->
            sink.write(buffer, buffer.size)
            sink.flush()
        }
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    @Test
    fun settings_exposeAllFieldsWithEditableFlags() = runBlocking {
        val dir = newDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))
            val service = DefaultConfigService(store)
            service.start(scope)

            val byId = service.settings.value.associateBy { it.id }
            assertEquals(FIELDS.size, byId.size)
            for (field in FIELDS) {
                val setting = byId[field.id] ?: fail("missing setting for ${field.id}")
                assertEquals(field.editable, setting.editable, "editable mismatch for ${field.id}")
            }
        } finally {
            scope.cancel()
            deleteRecursively(dir)
        }
    }

    @Test
    fun update_changesSettingReactively() = runBlocking {
        val dir = newDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))
            val service = DefaultConfigService(store)
            service.start(scope)

            val field = userNumberField()
            val newValue = defaultNumber(field) + 1
            assertEquals(UpdateResult.Success, service.update(field.id, ConfigValue.Number(newValue)))
            awaitNumber(service, field.id, newValue)
        } finally {
            scope.cancel()
            deleteRecursively(dir)
        }
    }

    @Test
    fun updateNull_restoresDefault() = runBlocking {
        val dir = newDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))
            val service = DefaultConfigService(store)
            service.start(scope)

            val field = userNumberField()
            val defaultValue = defaultNumber(field)
            val newValue = defaultValue + 1

            service.update(field.id, ConfigValue.Number(newValue))
            awaitNumber(service, field.id, newValue)

            service.update(field.id, null)
            awaitNumber(service, field.id, defaultValue) // default restored
        } finally {
            scope.cancel()
            deleteRecursively(dir)
        }
    }

    @Test
    fun update_rejectsNonEditableAndInvalid() = runBlocking {
        val dir = newDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))
            val service = DefaultConfigService(store)
            service.start(scope)

            assertTrue(service.update(nonEditableField().id, ConfigValue.Number(1L)) is UpdateResult.Failure)

            val userNumber = userNumberField()
            // Type mismatch → rejected by Field.write()'s type check.
            assertTrue(service.update(userNumber.id, ConfigValue.Period(1.seconds)) is UpdateResult.Failure)
            // Below declared min, for every number field that declares one.
            for (withMin in FIELDS.filterIsInstance<NumberField>().filter { it.min != null }) {
                assertTrue(service.update(withMin.id, ConfigValue.Number(withMin.min!! - 1)) is UpdateResult.Failure)
            }
        } finally {
            scope.cancel()
            deleteRecursively(dir)
        }
    }

    @Test
    fun applyNetwork_ignoresNonNetworkIds() = runBlocking {
        val dir = newDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))
            val service = DefaultConfigService(store)
            service.start(scope)

            val networkField = networkPeriodField()
            val userField = userNumberField()
            val newNet = defaultPeriod(networkField) + 999.seconds
            val userDefault = defaultNumber(userField)

            store.applyNetwork(
                mapOf(
                    networkField.id to ConfigValue.Period(newNet),
                    userField.id to ConfigValue.Number(userDefault + 1), // USER id → ignored
                )
            )

            awaitPeriod(service, networkField.id, newNet)
            assertEquals(userDefault, number(service, userField.id))
        } finally {
            scope.cancel()
            deleteRecursively(dir)
        }
    }

    @Test
    fun onUserSettingsFileChanged_reloads() = runBlocking {
        val dir = newDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val userFile = Path(dir, "userSettings.toml")
            val store = ConfigStore(userFile, Path(dir, "state.toml"))
            val service = DefaultConfigService(store)
            service.start(scope)

            val field = userNumberField()
            val newValue = defaultNumber(field) + 1
            val overrides: Overrides = mapOf(field.id to ConfigValue.Number(newValue))
            writeFile(userFile, overrides.toTomlText())

            store.onUserSettingsFileChanged()
            awaitNumber(service, field.id, newValue)
        } finally {
            scope.cancel()
            deleteRecursively(dir)
        }
    }
}