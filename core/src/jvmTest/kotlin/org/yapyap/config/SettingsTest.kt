package org.yapyap.config

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import net.peanuuutz.tomlkt.Toml
import org.yapyap.persistence.config.ConfigStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class SettingsTest {

    private fun userNumberField(): NumberField =
        FIELDS.filterIsInstance<NumberField>().first { it.source == FieldSource.USER }

    private fun networkPeriodField(): PeriodField =
        FIELDS.filterIsInstance<PeriodField>().first { it.source == FieldSource.NETWORK }

    private fun defaultNumber(field: NumberField): Long =
        (field.read(RuntimeConfig()) as ConfigValue.Number).value

    @Test
    fun overrides_roundTripThroughGroupedToml() {
        val numberField = userNumberField()
        val periodField = FIELDS.filterIsInstance<PeriodField>().first { it.source == FieldSource.USER }
        val overrides: Overrides = mapOf(
            numberField.id to ConfigValue.Number(4096L),
            periodField.id to ConfigValue.Period(60.seconds),
        )
        val text = overrides.toTomlText()

        val tables = listOf(numberField.group, periodField.group)
        assertTrue(tables.all { "[$it]" in text }, "expected tables $tables, got:\n$text")
        assertTrue("[numbers]" !in text, "unexpected type-partition, got:\n$text")

        assertEquals(overrides, Toml.parseToTomlTable(text).toOverrides())
    }

    @Test
    fun derive_appliesUserOverride() {
        val field = userNumberField()
        val newValue = defaultNumber(field) + 1
        val runtime = derive(mapOf(field.id to ConfigValue.Number(newValue)))
        assertEquals(newValue, (field.read(runtime) as ConfigValue.Number).value)
    }

    @Test
    fun write_rejectsInvalidValue() {
        val field = userNumberField()
        assertIs<WriteResult.Invalid>(field.write(RuntimeConfig(), ConfigValue.Period(1.seconds)))
    }

    @Test
    fun configStore_persistsAndReloadsOverrides() = runBlocking {
        val dir = Path(SystemTemporaryDirectory, "yapyap-config-${Uuid.random()}")
        SystemFileSystem.createDirectories(dir)
        try {
            val store = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))

            val userField = userNumberField()
            val networkField = networkPeriodField()

            assertEquals(UpdateResult.Success, store.updateUser(userField.id, ConfigValue.Number(4096L)))
            assertEquals(ConfigValue.Number(4096L), store.overrides.value[userField.id])

            store.applyNetwork(mapOf(networkField.id to ConfigValue.Period(999.seconds)))
            assertEquals(ConfigValue.Period(999.seconds), store.overrides.value[networkField.id])

            // Reject invalid (type mismatch) + non-editable updates.
            assertTrue(store.updateUser(userField.id, ConfigValue.Period(1.seconds)) is UpdateResult.Failure)
            assertTrue(store.updateUser(networkField.id, ConfigValue.Number(1L)) is UpdateResult.Failure)

            // A fresh store reloads the same effective overrides from the files.
            val reloaded = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))
            assertEquals(ConfigValue.Number(4096L), reloaded.overrides.value[userField.id])
            assertEquals(ConfigValue.Period(999.seconds), reloaded.overrides.value[networkField.id])
        } finally {
            deleteRecursively(dir)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}