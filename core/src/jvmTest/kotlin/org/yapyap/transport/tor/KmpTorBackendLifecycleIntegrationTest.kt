package org.yapyap.transport.tor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.logging.AppLog
import org.yapyap.testfixtures.PrintingAppLogger
import org.yapyap.transport.network.NetworkChange
import org.yapyap.transport.network.NetworkMonitor
import org.yapyap.transport.network.NetworkState
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
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

    /**
     * A network switch (or wake-up) keeps the device online, so kmp-tor's own
     * observer does nothing; the backend must cycle Tor's network itself and
     * re-prove the onion is dialable. Offline→online goes through kmp-tor's
     * DisableNetwork and must end PUBLISHED again too.
     */
    @Test
    fun networkSwitchAndOfflineOnline_reverifyPublication() = runBlocking {
        val dir = Path(SystemTemporaryDirectory, "yapyap-tor-network-${Uuid.random()}")
        SystemFileSystem.createDirectories(dir)
        val network = MutableStateFlow(NetworkState(online = true, generation = 0, change = null))
        val monitor = object : NetworkMonitor {
            override val state: StateFlow<NetworkState> = network
        }
        val backend = KmpTorBackend(
            dir,
            config = MutableStateFlow(TorBackendConfig(startupTimeout = 60.seconds)),
            networkMonitor = monitor,
        )
        val publishStates = mutableListOf<TorPublishState>()
        try {
            backend.start()
            val recorder =
                launch { backend.publishState.collect { synchronized(publishStates) { publishStates += it } } }
            // Bootstrap time is the network's (a stuck relay can cost minutes);
            // what this test owns is everything after Tor is Ready.
            assertNotNull(
                withTimeoutOrNull(READY_BUDGET) { backend.health.first { it == TorHealth.Ready } },
                "never bootstrapped; last health=${backend.health.value}",
            )
            assertNotNull(
                withTimeoutOrNull(PUBLISH_BUDGET) { backend.publishState.first { it == TorPublishState.PUBLISHED } },
                "never published initially; health=${backend.health.value}",
            )

            suspend fun expectRepublished(label: String) {
                val republished = withTimeoutOrNull(PUBLISH_BUDGET) {
                    while (true) {
                        val seen = synchronized(publishStates) { publishStates.toList() }
                        val lastPublishing = seen.lastIndexOf(TorPublishState.PUBLISHING)
                        if (lastPublishing >= 0 && seen.drop(lastPublishing + 1)
                                .contains(TorPublishState.PUBLISHED)
                        ) break
                        delay(200.milliseconds)
                    }
                }
                assertNotNull(
                    republished,
                    "$label: not re-verified to PUBLISHED; states=$publishStates health=${backend.health.value}"
                )
                assertEquals(TorHealth.Ready, backend.health.value, "$label: health after re-verification")
            }

            synchronized(publishStates) { publishStates.clear() }
            network.value = NetworkState(online = true, generation = 1, change = NetworkChange.SWITCHED)
            expectRepublished("switch")

            synchronized(publishStates) { publishStates.clear() }
            network.value = NetworkState(online = false, generation = 2, change = NetworkChange.OFFLINE)
            delay(5.seconds)
            network.value = NetworkState(online = true, generation = 3, change = NetworkChange.ONLINE)
            expectRepublished("offline→online")
            recorder.cancel()
        } finally {
            backend.stop()
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
        val READY_BUDGET = 10.minutes
        val PUBLISH_BUDGET = 4.minutes
    }
}
