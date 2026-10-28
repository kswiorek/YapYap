package org.yapyap.orchestrator

import kotlinx.io.files.Path
import org.yapyap.config.BootConfig
import org.yapyap.config.JvmConfigFileWatcher
import org.yapyap.crypto.JavaKeyringSessionFactory
import org.yapyap.logging.JvmAppLogger
import org.yapyap.persistence.JvmEncryptedDriverFactory
import org.yapyap.protocol.DeviceType
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.LoopbackTorBackend
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend

public actual class OrchestratorFactory actual constructor(
    private val dataDirectory: Path,
    private val mode: NodeMode,
    private val loopback: LoopbackTransport,
    private val keyringNamespace: String?,
) {

    public actual fun create(): Orchestrator =
        DefaultOrchestrator(
            dataDirectory = dataDirectory,
            bootConfig = BootConfig(
                mode = mode,
                localDeviceType = if (mode == NodeMode.HEADLESS_RELAY) DeviceType.HEADLESS else DeviceType.DESKTOP,
            ),
            keyringSessionFactory = JavaKeyringSessionFactory(keyringNamespace),
            createDriverFactory = { masterKey, databaseFile -> JvmEncryptedDriverFactory(databaseFile, masterKey) },
            createTorBackend = { torConfig, torStateRoot ->
                when (loopback) {
                    LoopbackTransport.NONE, LoopbackTransport.WEBRTC ->
                        KmpTorBackend(
                            torStateRoot,
                            config = torConfig
                        )
                    LoopbackTransport.TOR, LoopbackTransport.ALL ->
                        LoopbackTorBackend(torConfig)
                }
            },   // kmp-file boundary lives here
            createWebRtcBackend = { webRtcConfig ->
                when (loopback) {
                    LoopbackTransport.NONE, LoopbackTransport.TOR -> JvmWebRtcBackend(config = webRtcConfig)
                    LoopbackTransport.WEBRTC, LoopbackTransport.ALL ->
                        error("Loopback WebRTC backend is not yet implemented")
                }
            },
            createLogger = { logDirectory -> JvmAppLogger(logDirectory = logDirectory) },
            createConfigFileWatcher = { userSettingsFile -> JvmConfigFileWatcher(userSettingsFile) },
        )
}
