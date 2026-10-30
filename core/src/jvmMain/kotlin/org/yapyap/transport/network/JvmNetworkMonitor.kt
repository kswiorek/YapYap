package org.yapyap.transport.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.transport.network.JvmNetworkMonitor.POLL_INTERVAL
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop [NetworkMonitor]: polls the OS interface list (the JDK has no
 * connectivity callback) and detects sleep from wall-clock gaps between polls.
 * Process-wide singleton, started on first use; its poll loop runs on daemon
 * threads and never blocks exit.
 */
internal object JvmNetworkMonitor : NetworkMonitor {

    private val POLL_INTERVAL = 2.seconds

    /** Well above [POLL_INTERVAL]: only a suspended process misses this many polls. */
    private val WAKE_GAP = 30.seconds

    private val tracker = NetworkStateTracker(settlePolls = 2, wakeGap = WAKE_GAP)

    private val _state = MutableStateFlow(checkNotNull(tracker.onPoll(usableInterfaceKeys() ?: emptySet(), null)))
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("yapyap-network-monitor")).launch {
            var lastPollAt = System.currentTimeMillis()
            while (true) {
                delay(POLL_INTERVAL)
                val now = System.currentTimeMillis()
                val sinceLastPoll = (now - lastPollAt).milliseconds
                lastPollAt = now
                // A failed enumeration says nothing about the network: skip it
                // rather than reporting "offline".
                val keys = usableInterfaceKeys() ?: continue
                val next = tracker.onPoll(keys, sinceLastPoll) ?: continue
                _state.value = next
                AppLog.info(
                    component = LogComponent.NETWORK,
                    event = LogEvent.NETWORK_CHANGED,
                    message = "Device network changed",
                    fields = mapOf(
                        "change" to next.change,
                        "online" to next.online,
                        "generation" to next.generation,
                        "interfaces" to keys.size,
                    ),
                )
            }
        }
    }

    /**
     * One key per usable address: `interface/address`, null when the OS refused
     * to enumerate. Loopback and link-local addresses are excluded (present
     * without any real network). IPv6 addresses are reduced to their /64
     * prefix: privacy (temporary) addresses rotate the host part on their own
     * schedule, which is not a network change.
     */
    private fun usableInterfaceKeys(): Set<String>? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { "${nif.name}/${it.networkKey()}" }
            }
            .toSet()
    }.getOrNull()

    private fun InetAddress.networkKey(): String =
        if (this is Inet6Address) address.take(8).joinToString("") { "%02x".format(it) } + "::/64"
        else hostAddress
}
