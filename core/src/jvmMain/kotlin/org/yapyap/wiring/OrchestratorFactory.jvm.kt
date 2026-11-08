package org.yapyap.wiring

import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import org.yapyap.config.ConfigFileWatcher
import org.yapyap.config.JvmConfigFileWatcher
import org.yapyap.crypto.JavaKeyringSessionFactory
import org.yapyap.crypto.store.KeyringSessionFactory
import org.yapyap.logging.AppLogger
import org.yapyap.logging.JvmAppLogger
import org.yapyap.orchestrator.NodeMode
import org.yapyap.orchestrator.Orchestrator
import org.yapyap.orchestrator.boot.BootConfig
import org.yapyap.persistence.JvmEncryptedDriverFactory
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.protocol.DeviceType
import org.yapyap.transport.network.JvmNetworkMonitor
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.LoopbackTorBackend
import org.yapyap.transport.tor.backend.TorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig

public actual class OrchestratorFactory actual constructor(
    private val dataDirectory: Path,
    private val mode: NodeMode,
    private val loopback: LoopbackTransport,
    private val keyringNamespace: String?,
) {

    public actual fun create(): Orchestrator =
        createOrchestrator(
            dataDirectory = dataDirectory,
            bootConfig = BootConfig(
                mode = mode,
                localDeviceType = if (mode == NodeMode.HEADLESS_RELAY) DeviceType.HEADLESS else DeviceType.DESKTOP,
            ),
            platform = JvmPlatform(loopback, keyringNamespace),
        )
}

internal class JvmPlatform(
    private val loopback: LoopbackTransport,
    keyringNamespace: String?,
) : Platform {
    override val keyringSessionFactory: KeyringSessionFactory = JavaKeyringSessionFactory(keyringNamespace)

    override fun driverFactory(masterKey: ByteArray, databaseFile: Path): DriverFactory =
        JvmEncryptedDriverFactory(databaseFile, masterKey)

    // kmp-file boundary lives here
    override fun torBackend(config: StateFlow<TorBackendConfig>, torStateRoot: Path): TorBackend =
        when (loopback) {
            LoopbackTransport.NONE, LoopbackTransport.WEBRTC ->
                KmpTorBackend(torStateRoot, config = config, networkMonitor = JvmNetworkMonitor)

            LoopbackTransport.TOR, LoopbackTransport.ALL -> LoopbackTorBackend(config)
        }

    override fun webRtcBackend(config: StateFlow<WebRtcBackendConfig>): WebRtcBackend =
        when (loopback) {
            LoopbackTransport.NONE, LoopbackTransport.TOR -> JvmWebRtcBackend(config = config)
            LoopbackTransport.WEBRTC, LoopbackTransport.ALL ->
                error("Loopback WebRTC backend is not yet implemented")
        }

    override fun logger(logDirectory: Path): AppLogger = JvmAppLogger(logDirectory = logDirectory)

    override fun configFileWatcher(userSettingsFile: Path): ConfigFileWatcher = JvmConfigFileWatcher(userSettingsFile)
}
