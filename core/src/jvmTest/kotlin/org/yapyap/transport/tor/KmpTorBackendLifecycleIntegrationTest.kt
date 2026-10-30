package org.yapyap.transport.tor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.logging.AppLog
import org.yapyap.testfixtures.PrintingAppLogger
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.backend.TorHealth
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Real-Tor lifecycle checks (opt-in, see `-PintegrationTests=true`).
 *
 * Covers what broke in practice: start() must not wait for bootstrap (a slow
 * directory mirror can stall a fresh bootstrap for a minute without any Tor
 * warning), and repeated in-process restarts on the same directory must keep
 * reporting health. kmp-tor hands back the same runtime per directory, so
 * observers bound to an earlier backend instance would silently swallow
 * the new instance's events.
 */
class KmpTorBackendLifecycleIntegrationTest {

    @BeforeTest
    fun installLogger() = AppLog.init(PrintingAppLogger())

    @AfterTest
    fun resetLogger() = AppLog.reset()

    @Test
    fun startReturnsBeforeBootstrap_andRestartsOnSameDirectoryReachReady() = runBlocking {
        val dir = Path(SystemTemporaryDirectory, "yapyap-tor-lifecycle-${Uuid.random()}")
        SystemFileSystem.createDirectories(dir)
        try {
            repeat(RESTARTS) { cycle ->
                val backend =
                    KmpTorBackend(dir, config = MutableStateFlow(TorBackendConfig(startupTimeout = 60.seconds)))
                try {
                    val mark = TimeSource.Monotonic.markNow()
                    backend.start()
                    val startTook = mark.elapsedNow()
                    assertTrue(
                        startTook < START_BUDGET,
                        "cycle $cycle: start() took $startTook; it must not wait for bootstrap",
                    )
                    val ready = withTimeoutOrNull(READY_BUDGET) { backend.health.first { it == TorHealth.Ready } }
                    assertNotNull(ready, "cycle $cycle: never reached Ready; last health=${backend.health.value}")
                } finally {
                    backend.stop()
                }
            }
        } finally {
            runCatching { deleteRecursively(dir) }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    private companion object {
        const val RESTARTS = 4
        val START_BUDGET = 20.seconds
        val READY_BUDGET = 5.minutes
    }
}
