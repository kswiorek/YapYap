package org.yapyap.routing.router

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import org.yapyap.persistence.availability.PeerAvailabilityStore
import org.yapyap.protocol.PeerId
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Owns everything about a peer's availability — and our own:
 *
 * - Instantaneous online/offline of peers: [onlineDevices], [isOnline], driven by inbound traffic
 *   via [markReachable], with pings guaranteeing there is always *some* traffic. A peer is offline
 *   once silent for two [pingInterval]s.
 * - Per-peer reliability: the **measured** score (advanced every [RouterConfig.sweepInterval] by the
 *   sweep, converging on the peer's availability fraction as judged from our vantage point) and the
 *   peer's **self-reported** score (carried on its pings). [reliabilityScore] blends the two with a
 *   geometric mean `sqrt(measured * reported)`.
 * - Our own **self-reported** score ([currentSelfScore]): the fraction of wall-clock time we were up
 *   and able to send pings, computed by the same complementary filter but applied to ourselves.
 *   Because the app cannot observe its own downtime, the downtime is **billed at the boundaries**:
 *   on every successful ping-send we stamp [PeerAvailabilityRegistry.lastSelfActiveAt] (memory +
 *   persisted every sweep and on [stop]); at each [start] the gap since the last persisted stamp is
 *   decayed as if we had been down for that entire gap. Sleep/suspension of a live process is billed
 *   the same way by the unclamped self-sweep on wake.
 *
 * [PeerAvailabilityStore] I/O runs on the persistence dispatcher and works in epoch seconds; the
 * instants held here are converted at that boundary. The local device's own row holds the self-score
 * (`reliability_score`) and the last-active stamp (`last_seen_timestamp`).
 */
internal class PeerAvailabilityRegistry(
    private val clock: Clock = Clock.System,
    private val routerConfig: StateFlow<RouterConfig>,
    private val store: PeerAvailabilityStore,
) {
    @OptIn(InternalCoroutinesApi::class)
    private val lock = SynchronizedObject()
    private val lastSeen = mutableMapOf<PeerId, Instant>()
    private val reliability = mutableMapOf<PeerId, Double>()
    private val reported = mutableMapOf<PeerId, Double>()
    private val lastPingAt = mutableMapOf<PeerId, Instant>()

    private val onlineDevicesFlow = MutableStateFlow<Set<PeerId>>(emptySet())
    private val onlineEventsFlow = MutableSharedFlow<PeerId>(extraBufferCapacity = 64)

    private var localDeviceId: PeerId? = null
    private var selfScore: Double = 0.5
    private var lastSelfActiveAt: Instant = Instant.DISTANT_PAST
    private var lastSelfSweepAt: Instant = Instant.DISTANT_PAST

    private var lastSweepAt: Instant = Instant.DISTANT_PAST
    private var sweepJob: Job? = null

    val onlineDevices: Flow<Set<PeerId>> = onlineDevicesFlow.asStateFlow()
    val onlineEvents: Flow<PeerId> = onlineEventsFlow.asSharedFlow()

    @OptIn(InternalCoroutinesApi::class)
    private fun now(): Instant = clock.now()

    /**
     * Starts the periodic sweep (peer reliability + self-score) and seeds from persistence.
     * [localDeviceId] identifies our own device so its row is treated as the self-score rather than
     * a peer, and so our own pings count as self-activity evidence.
     */
    suspend fun start(scope: CoroutineScope, localDeviceId: PeerId) {
        stop()
        this.localDeviceId = localDeviceId
        val startedAt = now()
        lastSweepAt = startedAt
        seedFromStore()
        billSelfDowntimeGap() // must run after seeding and before the sweep loop
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

    /** Stops the sweep and persists the current self-score so a graceful shutdown bills no downtime. */
    suspend fun stop() {
        sweepJob?.cancel()
        sweepJob = null
        val local = localDeviceId ?: return
        val selfScore = this.selfScore
        val lastActive = this.lastSelfActiveAt
        runCatching { store.updateReliability(local, selfScore, lastActive) }
    }

    /**
     * Loads persisted availability for all known devices into memory. Peers go into the peer maps;
     * our own device seeds the self-filter. Fresh peers are added to [onlineDevices] optimistically
     * (without emitting [onlineEvents], which are transition signals). Best-effort.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun seedFromStore() {
        runCatching {
            val rows = store.availabilityForAll()
            val now = now()
            val offlineAfter = offlineAfter()
            val local = localDeviceId
            val fresh = mutableSetOf<PeerId>()
            var seededSelfScore: Double? = null
            var seededSelfActiveAt: Instant? = null
            synchronized(lock) {
                for (row in rows) {
                    val seen = row.lastSeen
                    if (row.deviceId == local) {
                        seededSelfScore = row.reliabilityScore
                        seededSelfActiveAt = row.lastSeen
                        continue
                    }
                    lastSeen[row.deviceId] = seen
                    reliability[row.deviceId] = row.reliabilityScore
                    if (now - seen < offlineAfter) fresh.add(row.deviceId)
                }
                if (fresh.isNotEmpty()) onlineDevicesFlow.value += fresh
            }
            seededSelfScore?.let {
                synchronized(lock) { selfScore = it.coerceIn(0.0, 1.0) }
            }
            seededSelfActiveAt?.let {
                // last_seen == NEVER means "never seen" (fresh provision); treat as no history.
                if (it != Instant.DISTANT_PAST) synchronized(lock) { lastSelfActiveAt = it }
            }
        }
    }

    /**
     * Applies the downtime bill at boot: the time since our last persisted activity stamp is decayed
     * as if we were down for all of it. If the gap is a jump or nonsense (clock skew), it is clamped
     * to at most [RouterConfig.maxBillableGap] — a device genuinely off for months is ~0 anyway.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun billSelfDowntimeGap() {
        val now = now()
        val local = localDeviceId ?: return
        // No history (fresh install, or a local row that was never seen-active) means no downtime
        // to bill; treat the boot moment as the start of our availability.
        val freshStart = synchronized(lock) { lastSelfActiveAt == Instant.DISTANT_PAST }
        if (freshStart) {
            synchronized(lock) {
                lastSelfActiveAt = now
                lastSelfSweepAt = now
            }
            return
        }

        val maxBillable = routerConfig.value.maxBillableGap.coerceAtLeast(1.seconds)
        val gap = synchronized(lock) { (now - lastSelfActiveAt).coerceIn(Duration.ZERO, maxBillable) }
        if (gap == Duration.ZERO) {
            synchronized(lock) { lastSelfSweepAt = now }
            return
        }
        val halfLife = routerConfig.value.reliabilityHalfLife.coerceAtLeast(1.seconds)
        val factor = 2.0.pow(-(gap / halfLife))
        val newScore = synchronized(lock) {
            selfScore = (selfScore * factor).coerceIn(0.0, 1.0)
            lastSelfSweepAt = now
            selfScore
        }
        val lastActive = synchronized(lock) { lastSelfActiveAt }
        runCatching { store.updateReliability(local, newScore, lastActive) }
    }

    /** Marks [deviceId] as reachable now, persisting its last-seen. */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun markReachable(deviceId: PeerId, at: Instant) {
        var transitioned = false
        synchronized(lock) {
            val wasOnline = isOnlineLocked(deviceId, at)
            lastSeen[deviceId] = at
            if (!wasOnline) {
                transitioned = true
                onlineDevicesFlow.value += deviceId
            }
        }
        // Emit outside the lock to avoid side-effects inside synchronized.
        if (transitioned) onlineEventsFlow.tryEmit(deviceId)
        store.markSeen(deviceId, at)
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
     * The last inbound traffic seen from [deviceId], or null if the device has never been seen.
     * Used by callers that need a more granular freshness check than [isOnline] (e.g. proactive
     * session opening).
     */
    @OptIn(InternalCoroutinesApi::class)
    fun lastSeen(deviceId: PeerId): Instant? =
        synchronized(lock) { lastSeen[deviceId] }

    /**
     * Effective reliability of [deviceId] in `[0, 1]`: the geometric mean of our measured score and
     * the peer's self-reported score (`sqrt(measured * reported)`). Falls back to the measured score
     * alone until the peer's first report arrives; returns null for a peer we know nothing about.
     */
    @OptIn(InternalCoroutinesApi::class)
    fun reliabilityScore(deviceId: PeerId): Double? =
        synchronized(lock) {
            val measured = reliability[deviceId] ?: return@synchronized null
            val reported = reported[deviceId] ?: measured
            sqrt(measured * reported)
        }

    /** Our own self-reported availability score in `[0, 1]` (attached to pings we originate). */
    @OptIn(InternalCoroutinesApi::class)
    fun currentSelfScore(): Double =
        synchronized(lock) { selfScore }

    /** Records a self-reported availability score received on a ping from [deviceId]. */
    @OptIn(InternalCoroutinesApi::class)
    fun noteSelfReported(deviceId: PeerId, score: Double) {
        if (deviceId == localDeviceId) return
        synchronized(lock) { reported[deviceId] = score.coerceIn(0.0, 1.0) }
    }

    /**
     * Records that we successfully transmitted a probe to [deviceId]. For peers this gates their
     * decay (silence is only counted against them once our probe actually went out). It is also the
     * self-activity stamp: being able to send a ping proves we are up, and every such success keeps
     * the self-score from decaying for this window.
     */
    @OptIn(InternalCoroutinesApi::class)
    fun notePingSent(deviceId: PeerId) {
        synchronized(lock) {
            lastPingAt[deviceId] = now()
            lastSelfActiveAt = now()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun isOnlineLocked(deviceId: PeerId, at: Instant): Boolean {
        val last = lastSeen[deviceId] ?: return false
        return at - last < offlineAfter()
    }

    /** A peer is considered offline once it has missed two consecutive pings. */
    @OptIn(InternalCoroutinesApi::class)
    private fun offlineAfter(): Duration =
        (routerConfig.value.pingInterval * 2).coerceAtLeast(1.seconds)

    @OptIn(InternalCoroutinesApi::class)
    internal suspend fun sweep() {
        val now = now()
        val config = routerConfig.value
        val window = config.sweepInterval.coerceAtLeast(1.seconds)
        val halfLife = config.reliabilityHalfLife.coerceAtLeast(1.seconds)
        val offlineAfter = offlineAfter()

        selfSweep(now, halfLife)

        // Apply at most one window of gain per sweep: after a suspension the first sweep only
        // counts one window of fresh evidence rather than blindly extrapolating stale state.
        val elapsed = (now - lastSweepAt).coerceIn(Duration.ZERO, window)
        val distanceToOneFactor = 2.0.pow(-(elapsed / halfLife))
        val boostFraction = 1.0 - distanceToOneFactor

        val peers = synchronized(lock) {
            (lastSeen.keys + lastPingAt.keys).distinct().toList()
        }

        for (peer in peers) {
            val lastSeen = synchronized(lock) { lastSeen[peer] }
            val pingedAt = synchronized(lock) { lastPingAt[peer] } ?: Instant.DISTANT_PAST

            val score = synchronized(lock) { reliability[peer] } ?: 0.5
            val hadScore = synchronized(lock) { reliability.containsKey(peer) }

            val sawTraffic = lastSeen != null && lastSeen >= now - window
            val probedButSilent = pingedAt >= now - window
            var newScore = score
            if (sawTraffic) {
                newScore = score + boostFraction * (1 - score)
            } else if (probedButSilent) {
                newScore = score * distanceToOneFactor
            }

            // A peer with fresh evidence this window is no longer "unknown": record its (possibly
            // still-default) measured score even when no gain/decay applied yet, e.g. two sweeps at the
            // same instant leave boostFraction == 0.
            if (newScore != score || (!hadScore && (sawTraffic || probedButSilent))) {
                synchronized(lock) { reliability[peer] = newScore }
            }

            if (lastSeen != null && now - lastSeen >= offlineAfter) {
                synchronized(lock) {
                    if (peer in onlineDevicesFlow.value) onlineDevicesFlow.value -= peer
                }
            }

            if (sawTraffic || probedButSilent) {
                runCatching { store.updateReliability(peer, newScore, lastSeen) }
            }
        }

        lastSweepAt = now
    }

    /**
     * Advances our own self-score for one (unclamped) elapsed period: if at least one ping went out
     * since the last self-sweep we were reachable and the score rises towards 1; otherwise we decay,
     * billing the full elapsed time — this is what accounts for suspension/sleep and delayed sweeps,
     * where no separate "downtime" event exists. Unlike the peer filter there is no abstain case:
     * while our process is alive we always have ground truth about our own ping-sending.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun selfSweep(now: Instant, halfLife: Duration) {
        val (pinged, scoreBefore) = synchronized(lock) {
            (lastSelfActiveAt >= lastSelfSweepAt) to selfScore
        }
        val maxBillable = routerConfig.value.maxBillableGap.coerceAtLeast(1.seconds)
        val elapsed = synchronized(lock) { (now - lastSelfSweepAt) }.coerceIn(Duration.ZERO, maxBillable)
        if (elapsed <= Duration.ZERO) return
        val factor = 2.0.pow(-(elapsed / halfLife))
        val newScore = synchronized(lock) {
            if (pinged) {
                val value = selfScore + (1 - factor) * (1 - selfScore)
                selfScore = value.coerceIn(0.0, 1.0)
            } else {
                val value = selfScore * factor
                selfScore = value.coerceIn(0.0, 1.0)
            }
            selfScore
        }
        if (newScore == scoreBefore) return
        val local = localDeviceId ?: return
        val lastActive = synchronized(lock) { lastSelfActiveAt }
        runCatching { store.updateReliability(local, newScore, lastActive) }
    }
}