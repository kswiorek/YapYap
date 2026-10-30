package org.yapyap.routing.outbound

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.retry.RetryLoop
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import org.yapyap.transport.TransportException
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
) {
    /**
     * Peers whose last Tor dispatch failed with [TransportException.TorException.OnionNotFound].
     * Tor remembers such misses for minutes, so once the peer is known to be online again
     * the cached miss must be dropped or its messages keep failing instantly.
     */
    private val notFoundPeers = MutableStateFlow<Set<PeerId>>(emptySet())

    private val retryLoop = RetryLoop(
        earliestPendingRetryAt = { packetOutbox.earliestPendingRetryAt() },
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

    fun runIn(scope: CoroutineScope): Job = retryLoop.runIn(scope)

    fun wake() {
        retryLoop.notifyChanged()
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

    suspend fun processDue() {
        val now = ctx.clock.now()
        val pruned = packetOutbox.pruneExpired(now)
        val dueEntries = packetOutbox.listDue(now)
        if (dueEntries.isNotEmpty() || pruned > 0) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PROCESSED,
                message = "Processing due outbox entries",
                fields = mapOf(
                    "dueCount" to dueEntries.size,
                    "prunedCount" to pruned,
                ),
            )
        }
        if (dueEntries.isNotEmpty()) {
            coroutineScope {
                dueEntries.map { entry ->
                    async { processDueEntry(entry, now) }
                }.awaitAll()
            }
        }
        wake()
        if (dueEntries.isNotEmpty()) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PROCESSED,
                message = "Processed outbox for due envelopes",
                fields = mapOf("dueCount" to dueEntries.size),
            )
        }
    }

    private suspend fun processDueEntry(entry: OutboxEntry, now: Instant) {
        val envelope = entry.envelope
        val outbound = transportPolicy.resolve(
            target = envelope.target,
            retries = entry.attempts,
            hasWebRtcSession = ctx.webRtcTransport.hasSession(envelope.target),
        )
        val nextRetryAt = now + outbound.retryDelay
        runCatching {
            dispatcher.dispatch(envelope, outbound.transport, entry.targetEndpoint)
        }.onSuccess {
            if (outbound.transport == RouterTransport.TOR) notFoundPeers.update { it - envelope.target }
            packetOutbox.recordAttempt(envelope.packetId, nextRetryAt, now)
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_RETRY_DISPATCHED,
                message = "Dispatched due outbox envelope",
                fields = mapOf(
                    "packetId" to envelope.packetId,
                    "packetType" to envelope.packetType,
                    "target" to envelope.target,
                    "transport" to outbound.transport,
                    "attempts" to entry.attempts + 1,
                    "nextRetryAt" to nextRetryAt,
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
                    "transport" to outbound.transport,
                    "attempts" to entry.attempts,
                    "nextRetryAt" to nextRetryAt,
                    "error" to error.toString(),
                ),
            )
            packetOutbox.recordAttempt(envelope.packetId, nextRetryAt, now)
            if (error is TransportException.TorException.OnionNotFound) {
                notFoundPeers.update { it + envelope.target }
                // Not found, yet the peer is online (it pinged us recently): Tor's miss is
                // stale. Refresh and retry soon instead of after the normal backoff.
                if (isPeerOnline(envelope.target) && refreshStaleLookup(
                        envelope.target,
                        reason = "online peer not found"
                    )
                ) {
                    packetOutbox.setDueForTarget(envelope.target, now + ctx.routerConfig.value.onionRefreshRetryDelay)
                    wake()
                }
            }
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
