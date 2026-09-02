package org.yapyap.routing.router

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import org.yapyap.persistence.availability.PeerAvailabilityStore
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider
import kotlin.math.pow

/**
 * Owns everything about a peer's availability: the current online/offline view, its persisted
 * last-seen, and a reliability score in `[0, 1]` used to gauge how likely a peer is to pass a
 * message on later (input to relay/swarm selection).
 *
 * Two clock domains, deliberately decoupled:
 * - Instantaneous online/offline ([lastSeenEpoch], [onlineDevices], [isOnline]): driven by inbound
 *   traffic via [markReachable], with pings guaranteeing there is always *some* traffic. A peer is
 *   offline once silent for two [pingInterval]s (this is the one place ping cadence matters). On
 *   [start] the view is seeded from [PeerAvailabilityStore] so a recently-seen peer is still online
 *   right after a restart.
 * - Reliability score: advanced by [start]'s periodic sweep (every [RouterConfig.sweepInterval])
 *   using a complementary filter with a single wall-clock time constant
 *   ([RouterConfig.reliabilityHalfLife]). Because the gain is derived from elapsed time rather than
 *   a fixed per-sweep amount, the score's long-run dynamics are independent of [RouterConfig.sweepInterval]
 *   and of how long the app stays awake each session.
 *
 * Per sweep, per peer, using the recent [RouterConfig.sweepInterval] as the evidence window:
 * - saw any traffic from the peer within the window → pull the score toward 1;
 * - otherwise, if one of our pings actually went out in the window (reported via [notePingSent])
 *   and drew no reply → pull the score toward 0 (the silence is the peer's, not ours);
 * - otherwise → leave the score alone (we were not in a position to judge the peer this window).
 *
 * Because the applied gain is clamped to a single window and judged on *recent* traffic rather than
 * "since the last sweep", a phone that comes online for a few pings and then sleeps neither
 * over-credits stale traffic nor over-penalises peers it never actually probed while away.
 *
 * [PeerAvailabilityStore] I/O runs on the persistence dispatcher; [markReachable] therefore only
 * blocks its caller (the inbound envelope path) for the same single-row write that packet dedup
 * already performs per envelope.
 */
internal class PeerAvailabilityRegistry(
    private val timeProvider: EpochProvider = SystemEpochProvider,
    private val routerConfig: StateFlow<RouterConfig>,
    private val store: PeerAvailabilityStore,
) {
    @OptIn(InternalCoroutinesApi::class)
    private val lock = SynchronizedObject()
    private val lastSeenEpoch = mutableMapOf<PeerId, Long>()
    private val reliability = mutableMapOf<PeerId, Double>()
    private val lastPingAt = mutableMapOf<PeerId, Long>()
    private val lastSeenPersistedAt = mutableMapOf<PeerId, Long>()

    private val onlineDevicesFlow = MutableStateFlow<Set<PeerId>>(emptySet())
    private val onlineEventsFlow = MutableSharedFlow<PeerId>(extraBufferCapacity = 64)

    private var lastSweepAt: Long = 0L
    private var sweepJob: Job? = null

    val onlineDevices: Flow<Set<PeerId>> = onlineDevicesFlow.asStateFlow()
    val onlineEvents: Flow<PeerId> = onlineEventsFlow.asSharedFlow()

    @OptIn(InternalCoroutinesApi::class)
    private fun now(): Long = timeProvider.nowEpochSeconds()

    /** Starts the periodic sweep that advances reliability scores and evicts stale online peers. */
    suspend fun start(scope: CoroutineScope) {
        stop()
        lastSweepAt = now()
        // Seed from persistence so last-seen and reliability are correct immediately after a restart
        // (a peer seen shortly before the app closed stays online instead of waiting for its first
        // ping to arrive), and so the sweep is aware of every known peer from the start.
        seedFromStore()
        sweepJob = scope.launch {
            routerConfig.map { it.sweepInterval }
                .distinctUntilChanged()
                .collectLatest { interval ->
                    while (isActive) {
                        delay(interval)
                        // Best-effort: a DB hiccup must not kill the sweep permanently.
                        runCatching { sweep() }
                    }
                }
        }
    }

    fun stop() {
        sweepJob?.cancel()
        sweepJob = null
    }

    /**
     * Loads persisted availability for all known peers into memory. Fresh peers are added to
     * [onlineDevices] optimistically (without emitting [onlineEvents], which are transition
     * signals); the sweep corrects the set within [RouterConfig.pingInterval] * 2 if the peer has
     * actually gone silent. Best-effort: on a store failure we simply start empty.
     */
    private suspend fun seedFromStore() {
        runCatching {
            val rows = store.availabilityForAll()
            val now = now()
            val offlineAfter = offlineAfterSeconds()
            val fresh = mutableSetOf<PeerId>()
            @OptIn(InternalCoroutinesApi::class) synchronized(lock) {
                for (row in rows) {
                    lastSeenEpoch[row.deviceId] = row.lastSeenEpoch
                    reliability[row.deviceId] = row.reliabilityScore
                    if (now - row.lastSeenEpoch < offlineAfter) fresh.add(row.deviceId)
                }
                if (fresh.isNotEmpty()) onlineDevicesFlow.value += fresh
            }
        }
    }

    /** Marks [deviceId] as reachable now, persisting its last-seen (throttled). */
    suspend fun markReachable(deviceId: PeerId, atEpochSeconds: Long) {
        var transitioned = false
        @OptIn(InternalCoroutinesApi::class) synchronized(lock) {
            val wasOnline = isOnlineLocked(deviceId, atEpochSeconds)
            lastSeenEpoch[deviceId] = atEpochSeconds
            if (!wasOnline) {
                transitioned = true
                onlineDevicesFlow.value += deviceId
            }
        }
        // Emit outside the lock to avoid side-effects inside synchronized.
        if (transitioned) onlineEventsFlow.tryEmit(deviceId)
        persistSeenIfDue(deviceId, atEpochSeconds)
    }

    @OptIn(InternalCoroutinesApi::class)
    fun markOffline(deviceId: PeerId) {
        synchronized(lock) {
            onlineDevicesFlow.value -= deviceId
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    fun isOnline(deviceId: PeerId): Boolean =
        synchronized(lock) { isOnlineLocked(deviceId, now()) }

    /**
     * Epoch seconds of the last inbound traffic observed from [deviceId], or null if the device
     * has never been seen. Used by callers that need a more granular freshness check than
     * [isOnline] (e.g. proactive session opening).
     */
    @OptIn(InternalCoroutinesApi::class)
    fun lastSeenEpoch(deviceId: PeerId): Long? =
        synchronized(lock) { lastSeenEpoch[deviceId] }

    /** Current in-memory reliability score in [0, 1] for [deviceId], or null if not yet tracked. */
    //TODO: self reported score
    @OptIn(InternalCoroutinesApi::class)
    fun reliabilityScore(deviceId: PeerId): Double? =
        synchronized(lock) { reliability[deviceId] }

    /**
     * Records that we successfully transmitted a probe to [deviceId]. The sweep only decays a
     * peer's score when a probe of ours actually went out and drew no traffic back, so a peer is
     * never penalised just because we were unable to reach it.
     */
    @OptIn(InternalCoroutinesApi::class)
    fun notePingSent(deviceId: PeerId) {
        synchronized(lock) { lastPingAt[deviceId] = now() }
    }

    private suspend fun persistSeenIfDue(deviceId: PeerId, atEpochSeconds: Long) {
        var due = false
        @OptIn(InternalCoroutinesApi::class) synchronized(lock) {
            val last = lastSeenPersistedAt[deviceId]
            if (last == null || atEpochSeconds - last >= LAST_SEEN_PERSIST_INTERVAL_SECONDS) {
                lastSeenPersistedAt[deviceId] = atEpochSeconds
                due = true
            }
        }
        if (due) runCatching { store.markSeen(deviceId, atEpochSeconds) }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun isOnlineLocked(deviceId: PeerId, atEpochSeconds: Long): Boolean {
        val last = lastSeenEpoch[deviceId] ?: return false
        return atEpochSeconds - last < offlineAfterSeconds()
    }

    /** A peer is considered offline once it has missed two consecutive pings. */
    @OptIn(InternalCoroutinesApi::class)
    private fun offlineAfterSeconds(): Long =
        (routerConfig.value.pingInterval.inWholeSeconds * 2).coerceAtLeast(1)

    internal suspend fun sweep() {
        val now = now()
        val config = routerConfig.value
        val windowSeconds = config.sweepInterval.inWholeSeconds.coerceAtLeast(1)
        val halfLifeSeconds = config.reliabilityHalfLife.inWholeSeconds.coerceAtLeast(1)
        val offlineAfter = offlineAfterSeconds()

        // Apply at most one window of gain per sweep: after a suspension the first sweep only
        // counts one window of fresh evidence rather than blindly extrapolating stale state.
        val elapsed = (now - lastSweepAt).coerceIn(1, windowSeconds)
        val distanceToOneFactor = 2.0.pow(-elapsed.toDouble() / halfLifeSeconds)
        val boostFraction = 1.0 - distanceToOneFactor

        val peers = @OptIn(InternalCoroutinesApi::class) synchronized(lock) {
            (lastSeenEpoch.keys + lastPingAt.keys).distinct().toList()
        }

        for (peer in peers) {
            val lastSeen = @OptIn(InternalCoroutinesApi::class) synchronized(lock) { lastSeenEpoch[peer] }
            val pingedAt = @OptIn(InternalCoroutinesApi::class) synchronized(lock) { lastPingAt[peer] } ?: 0L

            val score = @OptIn(InternalCoroutinesApi::class) synchronized(lock) { reliability[peer] }
                ?: 0.5

            val sawTraffic = lastSeen != null && lastSeen >= now - windowSeconds
            val probedButSilent = pingedAt >= now - windowSeconds
            var newScore = score
            if (sawTraffic) {
                newScore = score + boostFraction * (1 - score)
            } else if (probedButSilent) {
                newScore = score * distanceToOneFactor
            }
            // Else: we neither saw the peer nor got a probe of ours out this window; leave it alone.

            if (newScore != score) {
                @OptIn(InternalCoroutinesApi::class) synchronized(lock) { reliability[peer] = newScore }
            }

            if (lastSeen != null && now - lastSeen >= offlineAfter) {
                @OptIn(InternalCoroutinesApi::class) synchronized(lock) {
                    if (peer in onlineDevicesFlow.value) onlineDevicesFlow.value -= peer
                }
            }

            if (sawTraffic || probedButSilent) {
                runCatching { store.updateReliability(peer, newScore, lastSeen) }
            }
        }

        lastSweepAt = now
    }

    private companion object {
        const val LAST_SEEN_PERSIST_INTERVAL_SECONDS = 60L
    }
}
