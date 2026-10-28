package org.yapyap.orchestrator

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.JavaKeyringSessionFactory
import org.yapyap.persistence.key.DefaultKeyStore
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Headless reproduction of the desktop GUI boot path: fresh data dir + loopback
 * transport + isolated keyring namespace, genesis → `Running`.
 *
 * Opt-in (`-PintegrationTests=true`): boots the full stack including the real
 * WebRTC backend, and writes real OS-keyring entries under a unique `boottest-*`
 * service (wiped in `finally`).
 */
class OrchestratorLoopbackBootTest {

    @Test
    fun loopbackBoot_genesis_reachesRunning(): Unit = runBlocking {
        val suffix = Uuid.random().toString().replace("-", "").take(12)
        val dir = Path(SystemTemporaryDirectory, "yapyap-orch-boot-$suffix")
        val ns = "boottest-$suffix"
        SystemFileSystem.createDirectories(dir)
        val mark = TimeSource.Monotonic.markNow()
        fun at(): String = "[+${mark.elapsedNow().inWholeMilliseconds}ms]"

        val orch = OrchestratorFactory(dir, NodeMode.FULL_CLIENT, LoopbackTransport.TOR, ns).create()
        val timeline = mutableListOf<String>()
        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                orch.state.collect { timeline += "${at()} $it" }
            }
        try {
            withTimeout(240.seconds) {
                println("${at()} start()")
                orch.start()
                println("${at()} start() returned, waiting for SetupRequired")
                orch.state.first { it is OrchestratorState.SetupRequired }
                println("${at()} SetupRequired, completing Genesis")
                orch.completeSetup(SetupIntent.Genesis("boot-test"))
                println("${at()} completeSetup returned, waiting for Running")
                orch.state.first { it is OrchestratorState.Running }
                println("${at()} Running")
            }
        } finally {
            println("STATE TIMELINE:\n" + timeline.joinToString("\n"))
            watcher.cancel()
            runCatching { orch.stop() }
            runCatching { DefaultKeyStore(JavaKeyringSessionFactory(ns)).deleteAll() }
            runCatching { deleteRecursively(dir) }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
