package org.yapyap.orchestrator

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.DefaultKeyStore
import org.yapyap.crypto.store.MapBackedKeyringSessionFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The data directory is deleted while the OS keyring keeps the identity keys (a reinstall, or
 * a manual cleanup): the next boot creates a new database and must offer setup, not a reset.
 */
class LeftoverKeyringBootTest {

    @Test
    fun deletedDataDirectory_withKeysLeftInTheKeyring_bootsIntoSetup() = runBlocking {
        val dir = Files.createTempDirectory("yapyap-leftover").toFile()
        val keyring = MapBackedKeyringSessionFactory()
        val crypto = DefaultCryptoProvider()
        DefaultKeyStore(keyring).apply {
            putKey(KeySlot.DeviceSigningPrivate, crypto.generateSigningKeyPair().privateKey.bytes)
            putKey(KeySlot.DeviceEncryptionPrivate, crypto.generateEncryptionKeyPair().privateKey.bytes)
            val account = crypto.generateSigningKeyPair()
            putKey(KeySlot.AccountSigningPrivate, account.privateKey.bytes)
            putKey(KeySlot.AccountSigningPublic, account.publicKey.bytes)
        }
        val orchestrator = orchestrator(dir, keyring)
        try {
            orchestrator.start()
            assertEquals(OrchestratorState.SetupRequired, orchestrator.state.value)
        } finally {
            runCatching { orchestrator.resetApp() }
            dir.deleteRecursively()
        }
    }

    private fun orchestrator(dir: File, keyring: MapBackedKeyringSessionFactory): DefaultOrchestrator =
        testOrchestrator(Path(dir.absolutePath), TestPlatform(keyring))
}
