package org.yapyap.routing.router

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider

/**
 * Tracks which peers are currently reachable.
 *
 * Until ping/pong (Sprint 4) lands, reachability is derived purely from inbound
 * traffic: receiving any envelope marks the source reachable at `now`, and a
 * peer stays online for [onlineThresholdSeconds].
 *
 * TODO Sprint 4: replace the in-memory TTL with heartbeat-driven updates and
 * persistence (last-seen db writes).
 */
internal class PeerAvailabilityRegistry(
    private val timeProvider: EpochProvider = SystemEpochProvider,
    private val routerConfig: StateFlow<RouterConfig>,
) {
    @OptIn(InternalCoroutinesApi::class)
    private val lock = SynchronizedObject()
    private val lastSeenEpoch = mutableMapOf<PeerId, Long>()

    private val onlineDevicesFlow = MutableStateFlow<Set<PeerId>>(emptySet())
    private val onlineEventsFlow = MutableSharedFlow<PeerId>(extraBufferCapacity = 64)

    val onlineDevices: Flow<Set<PeerId>> = onlineDevicesFlow.asStateFlow()
    val onlineEvents: Flow<PeerId> = onlineEventsFlow.asSharedFlow()

    @OptIn(InternalCoroutinesApi::class)
    fun markReachable(deviceId: PeerId, now: Long) {
        var transitioned = false
        synchronized(lock) {
            val wasOnline = isOnlineLocked(deviceId, now)
            lastSeenEpoch[deviceId] = now
            if (!wasOnline) {
                transitioned = true
                onlineDevicesFlow.value += deviceId
            }
        }
        // Emit outside the lock to avoid side-effects inside synchronized.
        if (transitioned) onlineEventsFlow.tryEmit(deviceId)
    }

    @OptIn(InternalCoroutinesApi::class)
    fun markOffline(deviceId: PeerId) {
        synchronized(lock) {
            onlineDevicesFlow.value -= deviceId
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    fun isOnline(deviceId: PeerId): Boolean =
        synchronized(lock) { isOnlineLocked(deviceId, timeProvider.nowEpochSeconds()) }

    /**
     * Epoch seconds of the last inbound traffic observed from [deviceId], or null if the device
     * has never been seen. Used by callers that need a more granular freshness check than
     * [isOnline] (e.g. proactive session opening).
     */
    @OptIn(InternalCoroutinesApi::class)
    fun lastSeenEpoch(deviceId: PeerId): Long? =
        synchronized(lock) { lastSeenEpoch[deviceId] }

    private fun isOnlineLocked(deviceId: PeerId, now: Long): Boolean {
        val last = lastSeenEpoch[deviceId] ?: return false
        return now - last < routerConfig.value.onlineThreshold.inWholeSeconds
    }
}