package org.yapyap.orchestrator

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.yapyap.config.BootConfig
import org.yapyap.config.ConfigFileWatcher
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.key.*
import org.yapyap.protocol.DeviceType
import org.yapyap.transport.tor.RecordingTorBackend
import org.yapyap.transport.webrtc.RecordingWebRtcBackend
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
            putKey(deviceSigningPrivateRef(), crypto.generateSigningKeyPair().privateKey)
            putKey(deviceEncryptionPrivateRef(), crypto.generateEncryptionKeyPair().privateKey)
            val account = crypto.generateSigningKeyPair()
            putKey(accountSigningPrivateRef(), account.privateKey)
            putKey(accountSigningPublicRef(), account.publicKey)
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

    private fun orchestrator(dir: File, keyring: MapBackedKeyringSessionFactory): DefaultOrchestrator {
        val driverFactory = object : DriverFactory {
            override fun createDriver(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${dir.absolutePath}/vault.db")
        }
        val watcher = object : ConfigFileWatcher {
            override fun changes(): Flow<Unit> = emptyFlow()
        }
        return DefaultOrchestrator(
            dataDirectory = Path(dir.absolutePath),
            bootConfig = BootConfig(mode = NodeMode.FULL_CLIENT, localDeviceType = DeviceType.DESKTOP),
            keyringSessionFactory = keyring,
            createDriverFactory = { _, _ -> driverFactory },
            createTorBackend = { _, _ -> RecordingTorBackend() },
            createWebRtcBackend = { _ -> RecordingWebRtcBackend() },
            createLogger = { _ -> NoopAppLogger },
            createConfigFileWatcher = { _ -> watcher },
        )
    }
}
