package org.yapyap.wiring

import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import org.yapyap.config.ConfigFileWatcher
import org.yapyap.crypto.store.KeyringSessionFactory
import org.yapyap.logging.AppLogger
import org.yapyap.persistence.DriverFactory
import org.yapyap.transport.tor.backend.TorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.webrtc.backend.WebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig

/** The platform-specific pieces of a node; the platform factories supply them, the graphs use them. */
internal interface Platform {
    val keyringSessionFactory: KeyringSessionFactory

    fun driverFactory(masterKey: ByteArray, databaseFile: Path): DriverFactory

    fun torBackend(config: StateFlow<TorBackendConfig>, torStateRoot: Path): TorBackend

    fun webRtcBackend(config: StateFlow<WebRtcBackendConfig>): WebRtcBackend

    fun logger(logDirectory: Path): AppLogger

    fun configFileWatcher(userSettingsFile: Path): ConfigFileWatcher
}
