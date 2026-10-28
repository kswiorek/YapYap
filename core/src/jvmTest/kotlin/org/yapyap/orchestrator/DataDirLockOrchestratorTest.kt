package org.yapyap.orchestrator

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.yapyap.config.BootConfig
import org.yapyap.config.ConfigFileWatcher
import org.yapyap.logging.NoopAppLogger
import org.yapyap.orchestrator.boot.DataDirectoryInUseException
import org.yapyap.orchestrator.boot.currentProcessPid
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.key.MapBackedKeyringSessionFactory
import org.yapyap.protocol.DeviceType
import org.yapyap.transport.tor.RecordingTorBackend
import org.yapyap.transport.webrtc.RecordingWebRtcBackend
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Single-instance guard at orchestrator level: a second [DefaultOrchestrator]
 * on the same data directory fails fast into `Failed(DataDirectoryInUse)` and
 * never touches the live instance's database; `resetApp` refuses while held
 * elsewhere and releases on wipe.
 */
class DataDirLockOrchestratorTest {

    private fun orchestrator(dir: Path): DefaultOrchestrator {
        val dbUrl = "jdbc:sqlite:${dir}/vault.db"
        val driverFactory = object : DriverFactory {
            override fun createDriver(): SqlDriver = JdbcSqliteDriver(dbUrl)
        }
        val watcher = object : ConfigFileWatcher {
            override fun changes(): Flow<Unit> = emptyFlow()
        }
        return DefaultOrchestrator(
            dataDirectory = dir,
            bootConfig = BootConfig(mode = NodeMode.FULL_CLIENT, localDeviceType = DeviceType.DESKTOP),
            keyringSessionFactory = MapBackedKeyringSessionFactory(),
            createDriverFactory = { _, _ -> driverFactory },
            createTorBackend = { _, _ -> RecordingTorBackend() },
            createWebRtcBackend = { _ -> RecordingWebRtcBackend() },
            createLogger = { _ -> NoopAppLogger },
            createConfigFileWatcher = { _ -> watcher },
        )
    }

    @Test
    fun second_start_on_same_dir_fails_fast_and_reset_releases() = runBlocking {
        val dir = Path(Files.createTempDirectory("yapyap-lock-orch").toFile().absolutePath)
        try {
            val first = orchestrator(dir)
            first.start()
            assertTrue(
                first.state.value is OrchestratorState.SetupRequired,
                "first start must boot, was ${first.state.value}",
            )

            val second = orchestrator(dir)
            second.start()
            val failed = second.state.value as? OrchestratorState.Failed
                ?: fail("second start must land in Failed, was ${second.state.value}")
            val cause = failed.cause as? DataDirectoryInUseException
                ?: fail("cause must name the lock, was ${failed.cause}")
            // Same-process holder: the message names our own PID so the report is
            // actionable (close it / it is mid-shutdown) instead of mysterious.
            assertTrue(
                cause.message!!.contains(currentProcessPid().toString()),
                "message must name the holder PID, was: ${cause.message}",
            )

            // The lock survives stop: the data is still ours while Stopped.
            first.stop()
            val third = orchestrator(dir)
            third.start()
            assertTrue(
                third.state.value is OrchestratorState.Failed,
                "start while first instance holds the dir must fail, was ${third.state.value}",
            )

            // resetApp wipes and releases; the next start proceeds.
            first.resetApp()
            assertTrue(first.state.value is OrchestratorState.Stopped)
            val fourth = orchestrator(dir)
            fourth.start()
            assertTrue(
                fourth.state.value is OrchestratorState.SetupRequired,
                "start after reset must boot, was ${fourth.state.value}",
            )
            fourth.resetApp()
        } finally {
            deleteRecursively(dir)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (kotlinx.io.files.SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            kotlinx.io.files.SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        kotlinx.io.files.SystemFileSystem.delete(path, mustExist = false)
    }
}
