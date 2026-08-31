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
import kotlin.uuid.Uuid

class SettingsTest {

    @Test
    fun overrides_roundTripThroughGroupedToml() {
        val overrides: Overrides = mapOf(
            "outboxMaxSizeBytes" to ConfigValue.Number(4096L),
            "gracePeriodSeconds" to ConfigValue.Number(60L),
        )
        val text = overrides.toTomlText()

        assertTrue("[Router]" in text, "expected [Router] table, got:\n$text")
        assertTrue("[Sync]" in text, "expected [Sync] table, got:\n$text")
        assertTrue("[numbers]" !in text, "unexpected type-partition, got:\n$text")

        assertEquals(overrides, Toml.parseToTomlTable(text).toOverrides())
    }

    @Test
    fun derive_appliesUserOverride() {
        val runtime = derive(mapOf("outboxMaxSizeBytes" to ConfigValue.Number(2048L)))
        assertEquals(2048L, runtime.router.outboxMaxSizeBytes)
    }

    @Test
    fun write_rejectsInvalidValue() {
        val field = FIELDS.first { it.id == "outboxMaxSizeBytes" }
        assertIs<WriteResult.Invalid>(field.write(RuntimeConfig(), ConfigValue.Number(-1L)))
    }

    @Test
    fun configStore_persistsAndReloadsOverrides() = runBlocking {
        val dir = Path(SystemTemporaryDirectory, "yapyap-config-${Uuid.random()}")
        SystemFileSystem.createDirectories(dir)
        try {
            val store = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))

            assertEquals(UpdateResult.Success, store.updateUser("outboxMaxSizeBytes", ConfigValue.Number(4096L)))
            assertEquals(4096L, store.routerConfig.value.outboxMaxSizeBytes)

            store.applyNetwork(mapOf("messageLifetimeSeconds" to ConfigValue.Number(999L)))
            assertEquals(999L, store.routerConfig.value.binaryEnvelopeLifetime)

            // Reject invalid + non-editable updates.
            assertTrue(store.updateUser("outboxMaxSizeBytes", ConfigValue.Number(-1L)) is UpdateResult.Failure)
            assertTrue(store.updateUser("messageLifetimeSeconds", ConfigValue.Number(1L)) is UpdateResult.Failure)

            // A fresh store reloads the same effective values from the files.
            val reloaded = ConfigStore(Path(dir, "userSettings.toml"), Path(dir, "state.toml"))
            assertEquals(4096L, reloaded.routerConfig.value.outboxMaxSizeBytes)
            assertEquals(999L, reloaded.routerConfig.value.binaryEnvelopeLifetime)
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

