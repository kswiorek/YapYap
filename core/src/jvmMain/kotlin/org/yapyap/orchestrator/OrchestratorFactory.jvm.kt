package org.yapyap.orchestrator

import io.matthewnelson.kmp.file.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.yapyap.crypto.JavaKeyringSessionFactory
import org.yapyap.logging.AppLog
import org.yapyap.logging.JvmAppLogger
import org.yapyap.orchestrator.config.AppConfig
import org.yapyap.orchestrator.config.BootConfig
import org.yapyap.persistence.JvmEncryptedDriverFactory
import org.yapyap.persistence.db.DeviceType
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend
import java.nio.file.Files
import java.nio.file.Path

actual class OrchestratorFactory actual constructor(
    dataDirectory: String,
    private val mode: NodeMode,
) {
    private val root: Path = Path.of(dataDirectory)

    actual fun create(): Orchestrator {
        val databaseFile = root.resolve("vault.db")
        val torStateRoot = root.resolve("tor")
        val logDirectory = root.resolve("logs")

        Files.createDirectories(root)
        Files.createDirectories(torStateRoot)
        Files.createDirectories(logDirectory)

        AppLog.init(JvmAppLogger(logDirectory = logDirectory))   // already takes Path

        val loaded = ConfigLoader.load(root)                      // JVM: Typesafe Config + java.nio

        val deviceType = if (mode == NodeMode.HEADLESS_RELAY) DeviceType.HEADLESS else DeviceType.DESKTOP
        val appConfig = AppConfig(
            boot = BootConfig(
                mode = mode,
                localDeviceType = deviceType,
            ),
            runtime = MutableStateFlow(loaded.runtime),
            userPreferences = MutableStateFlow(loaded.userPreferences),
            networkPolicy = MutableStateFlow(loaded.networkPolicy),
        )

        ConfigWatcher.start(root, appConfig)                      // JVM: WatchService

        return DefaultOrchestrator(
            config = appConfig,
            keyringSessionFactory = JavaKeyringSessionFactory,
            createDriverFactory = { masterKey ->
                JvmEncryptedDriverFactory(databaseFile, masterKey) // takes Path
            },
            torBackend = KmpTorBackend(
                torStateRootPath = torStateRoot.toKmpFile(),       // ← the ONE boundary
                config = loaded.runtime.tor,
            ),
            webRtcBackend = JvmWebRtcBackend(config = loaded.runtime.webRtc),
        )
    }
    private fun Path.toKmpFile(): File = File(toString())
}
