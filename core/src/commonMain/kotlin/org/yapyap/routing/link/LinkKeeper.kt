package org.yapyap.routing.link

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.transport.session.*
import kotlin.concurrent.Volatile
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal data class LinkKeeperConfig(
    /** Wait after the first failed open; doubles per consecutive failure. */
    val retryDelay: Duration,
    val maxRetryDelay: Duration,
    /** Opens in progress at once (each may hold a transport dial for its whole budget). */
    val maxConcurrentOpens: Int,
    /** How long one open may take before it counts as failed. */
    val openTimeout: Duration,
    /** Re-check cadence when nothing else wakes the keeper (new targets, timers). */
    val recheckInterval: Duration,
)

/**
 * Keeps a link open to every peer in [targets] over one session transport, in the
 * background and independently of any message: opening a link is the slow part (a Tor dial,
 * a WebRTC negotiation), so it must not sit in the path of the packets that need it.
 *
 * Each peer without a link is opened when due; a failed open backs off exponentially (with
 * jitter) up to `maxRetryDelay`, and a link that drops is re-opened at once. Nothing is
 * attempted while [ready] is false; becoming ready (e.g. the network came back) clears all
 * backoff. Transport-agnostic: the transport-specific reactions go in [onOpened] /
 * [onFailed].
 */
internal class LinkKeeper(
    private val name: String,
    private val sessions: SessionTransport<PeerId>,
    private val targets: suspend () -> Collection<PeerId>,
    private val ready: Flow<Boolean>,
    private val config: StateFlow<LinkKeeperConfig>,
    private val clock: Clock = Clock.System,
    /** After an open we started succeeded. */
    private val onOpened: suspend (PeerId) -> Unit = {},
    /**
     * After an open we started failed, with the session's last state (null: timed out or threw).
     * May return a delay that replaces the backoff before the next attempt.
     */
    private val onFailed: suspend (PeerId, SessionStatus?) -> Duration? = { _, _ -> null },
    private val random: Random = Random.Default,
) {
    private class PeerState {
        var failures: Int = 0
        var nextAttemptAt: Instant = Instant.DISTANT_PAST
        var opening: Boolean = false
    }

    private val lock = Mutex()
    private val peers = mutableMapOf<PeerId, PeerState>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var isReady = false
    private var job: Job? = null

    fun runIn(scope: CoroutineScope): Job {
        val keeperJob = scope.launch {
            launch {
                ready.distinctUntilChanged().collect { nowReady ->
                    isReady = nowReady
                    if (nowReady) nudgeAll(resetBackoff = true)
                }
            }
            launch {
                // A link that drops is re-opened promptly, not after its old backoff.
                sessions.sessionStates.disconnectedKeys().collect { peer -> nudge(peer, resetBackoff = true) }
            }
            val permits = Semaphore(config.value.maxConcurrentOpens)
            while (isActive) {
                val nextDue = try {
                    openDue(this, permits)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.warn(
                        component = LogComponent.ROUTER,
                        event = LogEvent.SESSION_FAILED,
                        message = "$name link keeper pass failed",
                        fields = mapOf("error" to e.toString()),
                    )
                    null
                }
                val recheck = config.value.recheckInterval
                val wait = nextDue?.let { (it - clock.now()).coerceIn(MIN_WAIT, recheck) } ?: recheck
                withTimeoutOrNull(wait) { wakeups.receive() }
            }
        }
        job = keeperJob
        return keeperJob
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Re-evaluates [peer] now; [resetBackoff] when there is fresh evidence it is reachable. */
    suspend fun nudge(peer: PeerId, resetBackoff: Boolean) {
        if (resetBackoff) lock.withLock { peers[peer]?.clearBackoff() }
        wakeups.trySend(Unit)
    }

    suspend fun nudgeAll(resetBackoff: Boolean) {
        if (resetBackoff) lock.withLock { peers.values.forEach { it.clearBackoff() } }
        wakeups.trySend(Unit)
    }

    private fun PeerState.clearBackoff() {
        failures = 0
        nextAttemptAt = Instant.DISTANT_PAST
    }

    /** Starts every due open; returns when the next backed-off peer becomes due (null: none). */
    private suspend fun openDue(scope: CoroutineScope, permits: Semaphore): Instant? {
        if (!isReady) return null
        val wanted = targets().toSet()
        val now = clock.now()
        var earliest: Instant? = null
        val toOpen = lock.withLock {
            peers.keys.retainAll(wanted)
            wanted.filter { peer ->
                val state = peers.getOrPut(peer) { PeerState() }
                when {
                    state.opening -> false
                    sessions.hasSession(peer) -> {
                        state.clearBackoff()
                        false
                    }

                    state.nextAttemptAt > now -> {
                        earliest = earliest?.let { minOf(it, state.nextAttemptAt) } ?: state.nextAttemptAt
                        false
                    }

                    else -> {
                        state.opening = true
                        true
                    }
                }
            }
        }
        toOpen.forEach { peer -> scope.launch { permits.withPermit { open(peer) } } }
        return earliest
    }

    private suspend fun open(peer: PeerId) {
        if (sessions.hasSession(peer)) {
            // Came up on its own meanwhile (e.g. the peer dialed us): nothing opened, nothing to report.
            lock.withLock { peers[peer]?.apply { clearBackoff(); opening = false } }
            return
        }
        var outcome: SessionStatus? = null
        val connected = try {
            coroutineScope {
                // Watch before opening, so a quick outcome can't slip past; a terminal state
                // only counts once this attempt has been seen negotiating (it may be a stale one).
                var negotiating = false
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    sessions.sessionStatesOf(peer).first { state ->
                        if (state.phase == SessionPhase.NEGOTIATING) negotiating = true
                        state.phase == SessionPhase.CONNECTED || (negotiating && state.phase.isTerminal)
                    }
                }
                sessions.openSession(peer)
                outcome = withTimeoutOrNull(config.value.openTimeout) { result.await() }
                result.cancel()
                outcome?.phase == SessionPhase.CONNECTED
            }
        } catch (e: CancellationException) {
            lock.withLock { peers[peer]?.opening = false }
            throw e
        } catch (e: Exception) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.SESSION_FAILED,
                message = "$name link open threw",
                fields = mapOf("peerId" to peer, "error" to e.toString()),
            )
            false
        }

        if (connected) {
            lock.withLock { peers[peer]?.apply { clearBackoff(); opening = false } }
            runCatchingNonCancellation { onOpened(peer) }
        } else {
            val override = runCatchingNonCancellation { onFailed(peer, outcome) }
            val delay = lock.withLock {
                val state = peers[peer] ?: return@withLock null
                state.failures++
                val wait = override ?: backoff(state.failures)
                state.nextAttemptAt = clock.now() + wait
                state.opening = false
                wait
            }
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.SESSION_FAILED,
                message = "$name link open failed",
                fields = mapOf("peerId" to peer, "reason" to outcome?.reason, "retryIn" to delay),
            )
        }
        wakeups.trySend(Unit)
    }

    private fun backoff(failures: Int): Duration {
        val configSnapshot = config.value
        val exponential = configSnapshot.retryDelay * 2.0.pow((failures - 1).coerceIn(0, 30))
        val capped = minOf(exponential, configSnapshot.maxRetryDelay)
        // ±25% jitter so peers that failed together don't all retry together.
        return capped * (0.75 + random.nextDouble() * 0.5)
    }

    private suspend fun <T> runCatchingNonCancellation(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.warn(
            component = LogComponent.ROUTER,
            event = LogEvent.SESSION_FAILED,
            message = "$name link keeper callback failed",
            fields = mapOf("error" to e.toString()),
        )
        null
    }

    private companion object {
        val MIN_WAIT: Duration = 100.milliseconds
    }
}
