package org.yapyap.transport.network

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** Why [NetworkState.generation] last moved. */
internal enum class NetworkChange {
    /** Went from no usable interface to at least one. */
    ONLINE,

    /** Lost every usable interface. */
    OFFLINE,

    /** Still online, but on different interfaces/addresses (new Wi-Fi, VPN up/down, new IP). */
    SWITCHED,

    /** The process was suspended (laptop sleep) long enough that old connections are likely dead. */
    WOKE,
}

/**
 * Device network state as seen by transports. [generation] increments on every
 * [NetworkChange], so consumers can react to "something changed" even when
 * [online] stays true (a switch or a wake-up).
 */
internal data class NetworkState(
    val online: Boolean,
    val generation: Long,
    /** Null only for the initial state. */
    val change: NetworkChange?,
)

/**
 * Platform network observation for the transport layer. Process-wide: one
 * instance per process, shared by every transport and backend instance.
 */
internal interface NetworkMonitor {
    val state: StateFlow<NetworkState>
}

/**
 * Pure change detection behind a polling [NetworkMonitor]. Each poll passes the
 * current set of usable interface/address keys and the wall-clock time since the
 * previous poll.
 *
 * - A different key set must be seen on [settlePolls] consecutive polls before it
 *   counts: a network switch passes through intermediate states (address dropped,
 *   DHCP pending) that would otherwise each count as a change.
 * - A wall-clock gap above [wakeGap] means the process did not run (sleep); that
 *   counts as [NetworkChange.WOKE] at once, whatever the keys say.
 */
internal class NetworkStateTracker(
    private val settlePolls: Int,
    private val wakeGap: Duration,
) {
    init {
        require(settlePolls >= 1) { "settlePolls must be >= 1" }
    }

    var state: NetworkState? = null
        private set

    private var committed: Set<String> = emptySet()
    private var candidate: Set<String>? = null
    private var candidateSeen = 0

    /** Returns the new state when it changed, null otherwise. The first poll always returns the initial state. */
    fun onPoll(keys: Set<String>, sinceLastPoll: Duration?): NetworkState? {
        val current = state
        if (current == null) {
            committed = keys
            return NetworkState(online = keys.isNotEmpty(), generation = 0, change = null).also { state = it }
        }
        if (sinceLastPoll != null && sinceLastPoll > wakeGap) {
            return commit(current, keys, NetworkChange.WOKE)
        }
        if (keys == committed) {
            candidate = null
            candidateSeen = 0
            return null
        }
        if (keys == candidate) candidateSeen++ else {
            candidate = keys
            candidateSeen = 1
        }
        if (candidateSeen < settlePolls) return null
        val change = when {
            keys.isEmpty() -> NetworkChange.OFFLINE
            !current.online -> NetworkChange.ONLINE
            else -> NetworkChange.SWITCHED
        }
        return commit(current, keys, change)
    }

    private fun commit(current: NetworkState, keys: Set<String>, change: NetworkChange): NetworkState {
        committed = keys
        candidate = null
        candidateSeen = 0
        return NetworkState(online = keys.isNotEmpty(), generation = current.generation + 1, change = change)
            .also { state = it }
    }
}
