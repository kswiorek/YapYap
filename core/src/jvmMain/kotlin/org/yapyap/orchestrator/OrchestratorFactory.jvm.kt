package org.yapyap.orchestrator

import io.matthewnelson.kmp.file.File
import org.yapyap.crypto.JavaKeyringSessionFactory
import org.yapyap.logging.JvmAppLogger
import org.yapyap.persistence.JvmEncryptedDriverFactory
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend
import java.nio.file.Files
import java.nio.file.Path

actual class OrchestratorFactory actual constructor(
    private val config: OrchestratorConfig,
) {
    actual fun create(): Orchestrator {
        val dataDir = Path.of(config.dataDirectory)
        Files.createDirectories(dataDir)
        Files.createDirectories(Path.of(config.torStateRootPath))
        Files.createDirectories(Path.of(config.logDirectory))

        val logger = JvmAppLogger(logDirectory = Path.of(config.logDirectory))
        val databasePath = config.databasePath.replace('\\', '/')

        return DefaultOrchestrator(
            config = config,
            keyringSessionFactory = JavaKeyringSessionFactory,
            createDriverFactory = { masterKey ->
                JvmEncryptedDriverFactory(
                    databasePath = databasePath,
                    masterKey = masterKey,
                    logger = logger,
                )
            },
            torBackend = KmpTorBackend(
                torStateRootPath = File(config.torStateRootPath),
                config = config.torBackendConfig,
                logger = logger,
            ),
            webRtcBackend = JvmWebRtcBackend(logger = logger, config = config.webRtcBackendConfig),
            logger = logger,
        )
    }
}
