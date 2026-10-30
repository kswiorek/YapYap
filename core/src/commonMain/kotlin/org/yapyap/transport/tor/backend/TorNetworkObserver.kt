package org.yapyap.transport.tor.backend

import io.matthewnelson.kmp.tor.runtime.NetworkObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import org.yapyap.transport.network.NetworkMonitor
import kotlin.concurrent.Volatile

/**
 * Bridges a [NetworkMonitor] to kmp-tor's [NetworkObserver], which sets Tor's
 * `DisableNetwork` while the device is offline.
 *
 * Online/offline only. kmp-tor debounces notifications (a newer one cancels a
 * pending one within ~300 ms), so a quick Disconnected→Connected pair cannot
 * signal a network switch; [KmpTorBackend] handles switches and wake-ups itself.
 *
 * One instance per monitor for the life of the process ([of]): kmp-tor fixes a
 * runtime's observer when the runtime is first built and keeps that runtime per
 * data directory, so this must never reference a backend instance.
 */
internal class TorNetworkObserver private constructor(
    private val monitor: NetworkMonitor,
) : NetworkObserver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var forwarding: Job? = null

    override fun isNetworkConnected(): Boolean = monitor.state.value.online

    override fun onObserversNotEmpty() {
        forwarding?.cancel()
        forwarding = scope.launch {
            monitor.state
                .map { it.online }
                .distinctUntilChanged()
                .collect { online -> notify(if (online) Connectivity.Connected else Connectivity.Disconnected) }
        }
    }

    override fun onObserversEmpty() {
        forwarding?.cancel()
        forwarding = null
    }

    companion object {
        @OptIn(InternalCoroutinesApi::class)
        private val lock = SynchronizedObject()
        private val instances = HashMap<NetworkMonitor, TorNetworkObserver>()

        @OptIn(InternalCoroutinesApi::class)
        fun of(monitor: NetworkMonitor): TorNetworkObserver = synchronized(lock) {
            instances.getOrPut(monitor) { TorNetworkObserver(monitor) }
        }
    }
}
