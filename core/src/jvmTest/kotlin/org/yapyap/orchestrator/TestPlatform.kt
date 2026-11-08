package org.yapyap.orchestrator

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.files.Path
import org.yapyap.config.ConfigFileWatcher
import org.yapyap.crypto.store.KeyringSessionFactory
import org.yapyap.crypto.store.MapBackedKeyringSessionFactory
import org.yapyap.logging.AppLogger
import org.yapyap.logging.NoopAppLogger
import org.yapyap.orchestrator.boot.BootConfig
import org.yapyap.persistence.DriverFactory
import org.yapyap.protocol.DeviceType
import org.yapyap.transport.tor.RecordingTorBackend
import org.yapyap.transport.tor.backend.TorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.webrtc.RecordingWebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig
import org.yapyap.wiring.Platform
import org.yapyap.wiring.createOrchestrator

/** A JVM platform with plain SQLite, an in-memory keyring and recording transports. */
internal class TestPlatform(
    override val keyringSessionFactory: KeyringSessionFactory = MapBackedKeyringSessionFactory(),
) : Platform {
    override fun driverFactory(masterKey: ByteArray, databaseFile: Path): DriverFactory =
        object : DriverFactory {
            override fun createDriver(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$databaseFile")
        }

    override fun torBackend(config: StateFlow<TorBackendConfig>, torStateRoot: Path): TorBackend = RecordingTorBackend()

    override fun webRtcBackend(config: StateFlow<WebRtcBackendConfig>): WebRtcBackend = RecordingWebRtcBackend()

    override fun logger(logDirectory: Path): AppLogger = NoopAppLogger

    override fun configFileWatcher(userSettingsFile: Path): ConfigFileWatcher = object : ConfigFileWatcher {
        override fun changes(): Flow<Unit> = emptyFlow()
    }
}

/** A full-client desktop orchestrator on [dir], wired like production but on [platform]. */
internal fun testOrchestrator(dir: Path, platform: Platform = TestPlatform()): DefaultOrchestrator =
    createOrchestrator(
        dataDirectory = dir,
        bootConfig = BootConfig(mode = NodeMode.FULL_CLIENT, localDeviceType = DeviceType.DESKTOP),
        platform = platform,
    )
