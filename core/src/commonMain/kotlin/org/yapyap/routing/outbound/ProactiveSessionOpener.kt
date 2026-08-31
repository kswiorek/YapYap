package org.yapyap.routing.outbound

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

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
 * Decides when to proactively open WebRTC sessions and owns the retry policy around it.
 *
 * Two modes:
 * - [ensureSession] (BEST_EFFORT, typing / fast conversations): freshness-gated and
 *   backoff-limited, fire-and-forget. Failure is fine — messages fall back to Tor and the
 *   next caller retry re-opens.
 * - [awaitSession] (REQUIRED, files / calls): skips the freshness gate (explicit user intent)
 *   and retries until the session is usable or the budget expires.
 *
 * Deduplication of concurrent opens is NOT owned here: the backend's session map makes
 * [openSession][org.yapyap.transport.webrtc.transport.WebRtcTransport.openSession] idempotent.
 * The one piece of state kept here is a per-peer backoff timestamp, because after a session
 * FAILS or is closed the backend forgets it and repeated ticks would otherwise re-offer
 * every interval.
 */
internal class ProactiveSessionOpener(
    private val ctx: RoutingContext,
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
) {
    @OptIn(InternalCoroutinesApi::class)
    private val backoffLock = SynchronizedObject()
    private val lastAttemptAt = mutableMapOf<PeerId, Long>()

    private fun lastAttemptOf(peerId: PeerId): Long? =
        @OptIn(InternalCoroutinesApi::class) synchronized(backoffLock) { lastAttemptAt[peerId] }

    private fun recordAttempt(peerId: PeerId, now: Long) {
        @OptIn(InternalCoroutinesApi::class) synchronized(backoffLock) { lastAttemptAt[peerId] = now }
    }

    /**
     * BEST_EFFORT: opens a session to [peerId] if (a) it is not usable yet, (b) the backoff
     * window since the last attempt has elapsed, and (c) the peer was recently reachable.
     * Returns immediately; connection progress is observed by later calls.
     */
    suspend fun ensureSession(peerId: PeerId) {
        if (ctx.webRtcTransport.hasSession(peerId)) return

        val now = ctx.timeProvider.nowEpochSeconds()
        val config = ctx.routerConfig.value

        val lastAttempt = lastAttemptOf(peerId)
        if (lastAttempt != null && now - lastAttempt < config.proactiveSessionRetryDelay.inWholeSeconds) return

        // Freshness gate: only spend signaling (Tor round-trips) on peers that recently
        // showed signs of life.
        val lastSeen = peerAvailabilityRegistry.lastSeenEpoch(peerId) ?: return
        if (!peerAvailabilityRegistry.isOnline(peerId)) return
        if (now - lastSeen >= config.proactiveSessionFreshness.inWholeSeconds) return

        recordAttempt(peerId, now)
        runCatching { ctx.webRtcTransport.openSession(peerId) }
            .onSuccess {
                AppLog.info(
                    component = LogComponent.ROUTER,
                    event = LogEvent.PROACTIVE_SESSION_OPENING,
                    message = "Proactively opening WebRTC session",
                    fields = mapOf("peerId" to peerId, "lastSeenEpoch" to lastSeen),
                )
            }
            .onFailure { e ->
                if (e is CancellationException) throw e
                AppLog.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.SESSION_FAILED,
                    message = "Proactive WebRTC session open failed",
                    fields = mapOf("peerId" to peerId, "error" to e.toString()),
                )
            }
    }

    /**
     * REQUIRED: best effort to establish a usable session to [peerId] within [timeoutSeconds].
     * Ignores the freshness gate and the backoff window — the caller explicitly needs the
     * session. Polls session usability and re-issues idempotent opens; the poll-based wait
     * deliberately avoids subscribing to [sessionStates] so stale/replayed session events
     * from earlier negotiations cannot short-circuit the loop.
     *
     * @return [SessionOutcome.Connected] as soon as the envelope channel is usable,
     *   [SessionOutcome.Timeout] once the budget is exhausted.
     */
    suspend fun awaitSession(peerId: PeerId, timeoutSeconds: Long): SessionOutcome {
        //TODO: event driven
        val deadline = ctx.timeProvider.nowEpochSeconds() + timeoutSeconds
        val pollMillis = SESSION_AWAIT_POLL_MILLIS
        while (true) {
            if (ctx.webRtcTransport.hasSession(peerId)) return SessionOutcome.Connected
            if (ctx.timeProvider.nowEpochSeconds() >= deadline) return SessionOutcome.Timeout
            try {
                ctx.webRtcTransport.openSession(peerId)
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
            delay(pollMillis)
        }
    }

    private companion object {
        const val SESSION_AWAIT_POLL_MILLIS = 1_000L
    }
}
