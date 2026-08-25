package org.yapyap.orchestrator

import kotlinx.io.files.Path
import org.yapyap.config.BootConfig
import org.yapyap.config.JvmConfigFileWatcher
import org.yapyap.crypto.JavaKeyringSessionFactory
import org.yapyap.logging.JvmAppLogger
import org.yapyap.persistence.JvmEncryptedDriverFactory
import org.yapyap.persistence.db.DeviceType
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend

actual class OrchestratorFactory actual constructor(
    private val dataDirectory: Path,
    private val mode: NodeMode,
) {

    actual fun create(): Orchestrator =
        DefaultOrchestrator(
            dataDirectory = dataDirectory,
            bootConfig = BootConfig(
                mode = mode,
                localDeviceType = if (mode == NodeMode.HEADLESS_RELAY) DeviceType.HEADLESS else DeviceType.DESKTOP,
            ),
            keyringSessionFactory = JavaKeyringSessionFactory,
            createDriverFactory = { masterKey, databaseFile -> JvmEncryptedDriverFactory(databaseFile, masterKey) },
            createTorBackend = { torConfig, torStateRoot -> KmpTorBackend(torStateRoot, config = torConfig) },   // kmp-file boundary lives here
            createWebRtcBackend = { webRtcConfig -> JvmWebRtcBackend(config = webRtcConfig) },
            createLogger = { logDirectory -> JvmAppLogger(logDirectory = logDirectory) },
            createConfigFileWatcher = { userSettingsFile -> JvmConfigFileWatcher(userSettingsFile) },
        )
}
