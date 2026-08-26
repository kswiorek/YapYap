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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ConfigServiceTest {

    private fun newDir(): Path {
        val dir = Path(SystemTemporaryDirectory, "yapyap-configsvc-${Uuid.random()}")
        SystemFileSystem.createDirectories(dir)
        return dir
    }

    private fun number(service: ConfigService, id: String): Long =
        (service.settings.value.first { it.id == id } as NumberSetting).value

    private suspend fun awaitNumber(service: ConfigService, id: String, expected: Long) {
        withTimeout(10_000) {
            service.settings.first { settings ->
                (settings.first { it.id == id } as NumberSetting).value == expected
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

            val settings = service.settings.value
            assertEquals(FIELDS.size, settings.size)
            assertTrue(settings.first { it.id == "outboxMaxSizeBytes" }.editable)
            assertFalse(settings.first { it.id == "messageLifetimeSeconds" }.editable)
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

            assertEquals(UpdateResult.Success, service.update("outboxMaxSizeBytes", ConfigValue.Number(4096L)))
            awaitNumber(service, "outboxMaxSizeBytes", 4096L)
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

            service.update("outboxMaxSizeBytes", ConfigValue.Number(4096L))
            awaitNumber(service, "outboxMaxSizeBytes", 4096L)

            service.update("outboxMaxSizeBytes", null)
            awaitNumber(service, "outboxMaxSizeBytes", 10485760L) // default restored
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

            assertTrue(service.update("messageLifetimeSeconds", ConfigValue.Number(1L)) is UpdateResult.Failure)
            assertTrue(service.update("outboxMaxSizeBytes", ConfigValue.Number(-1L)) is UpdateResult.Failure)
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

            store.applyNetwork(
                mapOf(
                    "messageLifetimeSeconds" to ConfigValue.Number(999L),
                    "outboxMaxSizeBytes" to ConfigValue.Number(1L), // USER id → ignored
                )
            )

            awaitNumber(service, "messageLifetimeSeconds", 999L)
            assertEquals(10485760L, number(service, "outboxMaxSizeBytes"))
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

            val overrides: Overrides = mapOf("outboxMaxSizeBytes" to ConfigValue.Number(777L))
            writeFile(userFile, overrides.toTomlText())

            store.onUserSettingsFileChanged()
            awaitNumber(service, "outboxMaxSizeBytes", 777L)
        } finally {
            scope.cancel()
            deleteRecursively(dir)
        }
    }
}
