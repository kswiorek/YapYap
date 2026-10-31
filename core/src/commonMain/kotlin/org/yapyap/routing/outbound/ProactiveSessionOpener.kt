package org.yapyap.routing.outbound

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RoutingContext
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.session.SessionTransport
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Result of a REQUIRED-mode session request ([ProactiveSessionOpener.awaitSession]).
 * Failure reasons are logged, not returned; callers branch on connected-vs-timeout
 * (e.g. file transfer NAT-failure fallback to Tor).
 */
internal sealed interface SessionOutcome {
    data object Connected : SessionOutcome
    data object Timeout : SessionOutcome
}

/**
 * Decides when to open sessions on demand (on a transport where links are not kept open all
 * the time — WebRTC by default) and owns the retry policy around it. Its counterpart for
 * links kept open in the background is [org.yapyap.routing.link.LinkKeeper].
 *
 * Two modes:
 * - [ensureSession] (BEST_EFFORT, typing / fast conversations): freshness-gated and
 *   backoff-limited, fire-and-forget. Failure is fine — messages fall back to Tor and the
 *   next caller retry re-opens.
 * - [awaitSession] (REQUIRED, files / calls): skips the freshness gate (explicit user intent)
 *   and retries until the session is usable or the budget expires.
 *
 * Deduplication of concurrent opens is NOT owned here: the backend's session map makes
 * [openSession][SessionTransport.openSession] idempotent.
 * The one piece of state kept here is a per-peer backoff timestamp, because after a session
 * FAILS or is closed the backend forgets it and repeated ticks would otherwise re-offer
 * every interval.
 */
internal class ProactiveSessionOpener(
    private val ctx: RoutingContext,
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
    private val sessions: SessionTransport<PeerId> = ctx.webRtcTransport,
) {
    @OptIn(InternalCoroutinesApi::class)
    private val backoffLock = SynchronizedObject()
    private val lastAttemptAt = mutableMapOf<PeerId, Instant>()

    private fun lastAttemptOf(peerId: PeerId): Instant? =
        @OptIn(InternalCoroutinesApi::class) synchronized(backoffLock) { lastAttemptAt[peerId] }

    private fun recordAttempt(peerId: PeerId, at: Instant) {
        @OptIn(InternalCoroutinesApi::class) synchronized(backoffLock) { lastAttemptAt[peerId] = at }
    }

    /**
     * BEST_EFFORT: opens a session to [peerId] if (a) it is not usable yet, (b) the backoff
     * window since the last attempt has elapsed, and (c) the peer was recently reachable.
     * Returns immediately; connection progress is observed by later calls.
     */
    suspend fun ensureSession(peerId: PeerId) {
        if (sessions.hasSession(peerId)) return

        val now = ctx.clock.now()
        val config = ctx.routerConfig.value

        val lastAttempt = lastAttemptOf(peerId)
        if (lastAttempt != null && now - lastAttempt < config.proactiveSessionRetryDelay) return

        // Freshness gate: only spend signaling (Tor round-trips) on peers that recently
        // showed signs of life.
        val lastSeen = peerAvailabilityRegistry.lastSeen(peerId) ?: return
        if (!peerAvailabilityRegistry.isOnline(peerId)) return
        if (now - lastSeen >= config.proactiveSessionFreshness) return

        recordAttempt(peerId, now)
        runCatching { sessions.openSession(peerId) }
            .onSuccess {
                AppLog.info(
                    component = LogComponent.ROUTER,
                    event = LogEvent.PROACTIVE_SESSION_OPENING,
                    message = "Proactively opening session",
                    fields = mapOf("peerId" to peerId, "lastSeen" to lastSeen),
                )
            }
            .onFailure { e ->
                if (e is CancellationException) throw e
                AppLog.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.SESSION_FAILED,
                    message = "Proactive session open failed",
                    fields = mapOf("peerId" to peerId, "error" to e.toString()),
                )
            }
    }

    /**
     * REQUIRED: best effort to establish a usable session to [peerId] within `timeout`.
     * Ignores the freshness gate and the backoff window — the caller explicitly needs the
     * session. Event-driven: waits on the peer's session states and re-issues the
     * idempotent open on a slow cadence as a safety net for silently lost signaling
     * (no event ever arrives) and as the rate limit after terminal states.
     *
     * The wait predicate pairs the stored state with ground truth: a replayed CONNECTED
     * is accepted only while the envelope channel is actually usable. In production the
     * two agree by construction (see the `sessionStates` contract), so the check is a
     * formality — but structurally a stale entry can delay this loop by at most one
     * reopen interval, never produce a false [SessionOutcome.Connected].
     *
     * @return [SessionOutcome.Connected] as soon as the envelope channel is usable,
     *   [SessionOutcome.Timeout] once the budget is exhausted.
     */
    suspend fun awaitSession(peerId: PeerId, timeout: Duration): SessionOutcome {
        if (sessions.hasSession(peerId)) return SessionOutcome.Connected

        val reopenInterval = ctx.routerConfig.value.sessionAwaitReopenDelay
        val deadline = ctx.clock.now() + timeout
        while (true) {
            if (ctx.clock.now() >= deadline) return SessionOutcome.Timeout

            try {
                sessions.openSession(peerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.SESSION_FAILED,
                    message = "Session open attempt failed while awaiting session",
                    fields = mapOf("peerId" to peerId, "error" to e.toString()),
                )
            }

            val wait = minOf(reopenInterval, (deadline - ctx.clock.now()).coerceAtLeast(Duration.ZERO))
            val connected = withTimeoutOrNull(wait) {
                sessions.sessionStatesOf(peerId)
                    .first { it.phase == SessionPhase.CONNECTED && sessions.hasSession(peerId) }
            }
            if (connected != null) return SessionOutcome.Connected
        }
    }
}