package org.yapyap.routing.outbound

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.retry.RetryLoop
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import org.yapyap.transport.TransportException
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class OutboxProcessor(
    private val ctx: RoutingContext,
    private val dispatcher: EnvelopeDispatcher,
    private val transportPolicy: OutboundPolicy,
    private val packetOutbox: PacketOutbox,
    maxIdlePoll: StateFlow<Duration>,
    /** Peer-level presence (router availability registry): evidence a "not found" onion is stale. */
    private val isPeerOnline: (PeerId) -> Boolean = { false },
    /**
     * A due entry found no open link to its peer and was parked; whoever keeps links open
     * may want to try that peer now.
     */
    private val onNoLink: suspend (PeerId) -> Unit = {},
) {
    /**
     * Peers whose last Tor dispatch failed with [TransportException.TorException.OnionNotFound].
     * Tor remembers such misses for minutes, so once the peer is known to be online again
     * the cached miss must be dropped or its messages keep failing instantly.
     */
    private val notFoundPeers = MutableStateFlow<Set<PeerId>>(emptySet())

    /**
     * Dispatches in progress (packet id → target). There is no processing round
     * to wait for: each due entry is claimed and sent on its own, so one slow
     * send (an offline peer can hold Tor for its whole budget) never delays
     * anything queued behind it. Guarded by [inFlightLock].
     */
    private val inFlight = HashMap<Uuid, PeerId>()
    private val inFlightLock = Mutex()

    /** Where sends run while [runIn] is active; null otherwise (sends then run inline, see [processDue]). */
    @Volatile
    private var dispatchScope: CoroutineScope? = null

    private val retryLoop = RetryLoop(
        earliestPendingRetryAt = { nextRetryWorthWaking() },
        clock = ctx.clock,
        processDue = { processDue() },
        maxIdlePoll = maxIdlePoll,
        onProcessFailed = { error ->
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PROCESS_FAILED,
                message = "Outbox processing failed",
                throwable = error,
            )
        },
    )

    fun runIn(scope: CoroutineScope): Job {
        val dispatchJob = SupervisorJob(scope.coroutineContext[Job])
        dispatchScope = CoroutineScope(scope.coroutineContext + dispatchJob)
        val loop = retryLoop.runIn(scope)
        loop.invokeOnCompletion {
            dispatchJob.cancel()
            dispatchScope = null
        }
        return loop
    }

    fun wake() {
        retryLoop.notifyChanged()
    }

    /** Whether [packetId] is a bootstrap packet in our outbox addressed to [target]. */
    suspend fun isPendingBootstrapTo(target: PeerId, packetId: Uuid): Boolean =
        packetOutbox.listAllForTarget(target).any {
            it.packetId == packetId && it.envelope.packetType == PacketType.BOOTSTRAP
        }

    suspend fun onOutboundPacketDelivered(packetId: Uuid) {
        packetOutbox.markDelivered(packetId)
        wake()
    }

    suspend fun enqueueAndWake(
        envelope: BinaryEnvelope,
        nextRetryAt: Instant,
        relayMessage: Boolean = false,
        targetEndpoint: TorEndpoint? = null,
    ) {
        packetOutbox.enqueue(envelope, nextRetryAt, relayMessage = relayMessage, targetEndpoint = targetEndpoint)
        wake()
    }

    /** [peerId] proved it is up (inbound traffic) or a link to it opened: its entries go now. */
    suspend fun onPeerOnline(peerId: PeerId) {
        val now = ctx.clock.now()
        // The peer just proved it's up; a miss Tor cached while it was down is now stale.
        if (peerId in notFoundPeers.value) refreshStaleLookup(peerId, reason = "peer came online")
        packetOutbox.setDueForTarget(peerId, now)
        wake()
        AppLog.info(
            component = LogComponent.ROUTER,
            event = LogEvent.OUTBOX_ONLINE_DUE_SET,
            message = "Peer online accelerated outbox retries",
            fields = mapOf(
                "peerId" to peerId,
                "nextRetryAt" to now,
            ),
        )
    }

    /**
     * Starts every due entry that isn't already in flight, within the in-flight
     * caps, and returns without waiting for the sends (while [runIn] is active).
     * Entries held back by a cap stay due; each finished send wakes the loop so
     * they go next. Each entry is claimed before its send — the attempt and next
     * retry time are recorded first — so an in-progress send stops counting as due.
     *
     * Not running ([runIn] inactive, e.g. tests driving it directly): the sends
     * run inline and this returns once they finish.
     */
    suspend fun processDue() {
        val now = ctx.clock.now()
        val config = ctx.routerConfig.value
        val pruned = packetOutbox.pruneExpired(now)
        val dueEntries = packetOutbox.listDue(now)
        val started = inFlightLock.withLock {
            buildList {
                for (entry in dueEntries) {
                    if (inFlight.size >= config.outboxMaxInFlight) break
                    val packetId = entry.envelope.packetId
                    val target = entry.envelope.target
                    if (packetId in inFlight) continue
                    if (inFlight.values.count { it == target } >= config.outboxMaxInFlightPerTarget) continue
                    inFlight[packetId] = target
                    add(entry)
                }
            }
        }
        if (dueEntries.isNotEmpty() || pruned > 0) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PROCESSED,
                message = "Processing due outbox entries",
                fields = mapOf(
                    "dueCount" to dueEntries.size,
                    "startedCount" to started.size,
                    "prunedCount" to pruned,
                ),
            )
        }
        if (started.isEmpty()) return
        val claimed = started.mapNotNull { entry -> claim(entry, now) }
        val parked = started.filter { entry -> claimed.none { it.entry === entry } }
        if (parked.isNotEmpty()) park(parked, now)
        if (claimed.isEmpty()) return
        val scope = dispatchScope
        if (scope != null) {
            claimed.forEach { dispatch -> scope.launch { send(dispatch) } }
        } else {
            coroutineScope { claimed.map { dispatch -> async { send(dispatch) } }.awaitAll() }
        }
    }

    /**
     * When the retry loop's timer should next fire. Entries that are overdue but
     * can't start (in flight, or held by a cap) don't count: finishing sends wake
     * the loop for those. Counting them would make the timer fire at once, again
     * and again, for as long as a send outlasts its retry delay.
     */
    private suspend fun nextRetryWorthWaking(): Instant? {
        val now = ctx.clock.now()
        val earliest = packetOutbox.earliestPendingRetryAt() ?: return null
        if (earliest > now) return earliest
        val config = ctx.routerConfig.value
        val startable = inFlightLock.withLock {
            inFlight.size < config.outboxMaxInFlight &&
                    packetOutbox.listDue(now).any { entry ->
                        entry.envelope.packetId !in inFlight &&
                                inFlight.values.count { it == entry.envelope.target } < config.outboxMaxInFlightPerTarget
                    }
        }
        return if (startable) earliest else packetOutbox.earliestPendingRetryAfter(now)
    }

    private class ClaimedDispatch(
        val entry: OutboxEntry,
        val transport: RouterTransport,
        val now: Instant,
        val nextRetryAt: Instant,
    )

    /**
     * Records the attempt and picks the link, or returns null when no link to the peer is
     * open: sends never dial a peer themselves (keeping links open happens in the background,
     * see [org.yapyap.routing.link.LinkKeeper]), so the entry waits — see [park]. Entries with
     * an explicit endpoint (bootstrap to a newcomer, never a linked peer) are the exception
     * and go out as a one-shot Tor dial.
     */
    private suspend fun claim(entry: OutboxEntry, now: Instant): ClaimedDispatch? {
        val envelope = entry.envelope
        val outbound = transportPolicy.resolve(
            target = envelope.target,
            links = ctx.peerLinks.linksOf(envelope.target),
            retries = entry.attempts,
        )
        val transport = outbound.transport
            ?: if (entry.targetEndpoint != null) RouterTransport.TOR else return null
        val nextRetryAt = now + outbound.retryDelay
        packetOutbox.recordAttempt(envelope.packetId, nextRetryAt, now)
        return ClaimedDispatch(entry, transport, now, nextRetryAt)
    }

    /**
     * Entries whose peer has no open link: pushed back by `noLinkRecheckDelay` without
     * counting an attempt (a missing link says nothing about this packet), released from
     * the in-flight set, and their peers reported through [onNoLink]. A link coming up
     * makes them due at once ([onPeerOnline]); the delay is only a safety net. Delivery
     * meanwhile rides on relay deposits and the pull path.
     */
    private suspend fun park(entries: List<OutboxEntry>, now: Instant) {
        val until = now + ctx.routerConfig.value.noLinkRecheckDelay
        entries.forEach { packetOutbox.deferRetry(it.packetId, until) }
        inFlightLock.withLock { entries.forEach { inFlight.remove(it.packetId) } }
        val peers = entries.map { it.envelope.target }.distinct()
        AppLog.debug(
            component = LogComponent.ROUTER,
            event = LogEvent.OUTBOX_PROCESSED,
            message = "No open link; outbox entries wait for one",
            fields = mapOf("count" to entries.size, "peers" to peers.size, "recheckAt" to until),
        )
        peers.forEach { peer -> runCatching { onNoLink(peer) } }
    }

    private suspend fun send(dispatch: ClaimedDispatch) {
        val envelope = dispatch.entry.envelope
        try {
            runCatching {
                dispatcher.dispatch(envelope, dispatch.transport, dispatch.entry.targetEndpoint)
            }.onSuccess {
                if (dispatch.transport == RouterTransport.TOR) notFoundPeers.update { it - envelope.target }
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_RETRY_DISPATCHED,
                    message = "Dispatched due outbox envelope",
                    fields = mapOf(
                        "packetId" to envelope.packetId,
                        "packetType" to envelope.packetType,
                        "target" to envelope.target,
                        "transport" to dispatch.transport,
                        "attempts" to dispatch.entry.attempts + 1,
                        "nextRetryAt" to dispatch.nextRetryAt,
                    ),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                // Retryable by design (offline peer, stale endpoint): the entry stays
                // queued with backoff and expiry prunes it. WARN, not ERROR — a stack
                // per attempt would bury real failures; the cause rides along as a field.
                AppLog.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_DISPATCH_FAILED,
                    message = "Failed to dispatch outbox envelope",
                    fields = mapOf(
                        "packetId" to envelope.packetId,
                        "target" to envelope.target,
                        "transport" to dispatch.transport,
                        "attempts" to dispatch.entry.attempts,
                        "nextRetryAt" to dispatch.nextRetryAt,
                        "error" to error.toString(),
                    ),
                )
                if (error is TransportException.TorException.OnionNotFound) {
                    notFoundPeers.update { it + envelope.target }
                    // Not found, yet the peer is online (it pinged us recently): Tor's miss is
                    // stale. Refresh and retry soon instead of after the normal backoff.
                    if (isPeerOnline(envelope.target) &&
                        refreshStaleLookup(envelope.target, reason = "online peer not found")
                    ) {
                        packetOutbox.setDueForTarget(
                            envelope.target,
                            dispatch.now + ctx.routerConfig.value.onionRefreshRetryDelay,
                        )
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                inFlightLock.withLock { inFlight.remove(envelope.packetId) }
            }
            // A slot freed: entries held back by a cap (or made due meanwhile) go now.
            wake()
        }
    }

    /** Asks the transport to drop Tor's cached lookups (throttled there); true when it did. */
    private suspend fun refreshStaleLookup(peerId: PeerId, reason: String): Boolean {
        val refreshed = runCatching { ctx.torTransport.refreshOnionLookups() }.getOrDefault(false)
        AppLog.info(
            component = LogComponent.ROUTER,
            event = LogEvent.OUTBOX_ONLINE_DUE_SET,
            message = if (refreshed) "Refreshed stale Tor lookup for peer" else "Stale Tor lookup for peer; refreshed recently, not again",
            fields = mapOf("peerId" to peerId, "reason" to reason),
        )
        return refreshed
    }
}
