package org.yapyap.routing.outbound

import kotlinx.coroutines.*
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.packet.PacketId
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

internal class OutboxProcessor(
    private val ctx: RoutingContext,
    private val dispatcher: EnvelopeDispatcher,
    private val transportPolicy: OutboundPolicy,
    private val packetOutbox: PacketOutbox,
    maxIdlePollSeconds: Long,
) {
    private val retryLoop = OutboxRetryLoop(
        outbox = packetOutbox,
        time = ctx.timeProvider,
        processDue = { processDue() },
        maxIdlePollSeconds = maxIdlePollSeconds,
        onProcessFailed = { error ->
            ctx.logger.error(
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

    fun onOutboundPacketDelivered(packetId: PacketId) {
        packetOutbox.markDelivered(packetId)
        wake()
    }

    fun onWebRtcSessionConnected(peerId: PeerId, sessionId: String) {
        val now = ctx.timeProvider.nowEpochSeconds()
        packetOutbox.setDueForTarget(peerId, now)
        wake()
        ctx.logger.info(
            component = LogComponent.ROUTER,
            event = LogEvent.OUTBOX_WEBRTC_DUE_SET,
            message = "WebRTC session connected; accelerated outbox retries for peer",
            fields = mapOf(
                "peerId" to peerId,
                "sessionId" to sessionId,
                "nextRetryAt" to now,
            ),
        )
    }

    suspend fun processDue() {
        val now = ctx.timeProvider.nowEpochSeconds()
        val pruned = packetOutbox.pruneExpired(now)
        val dueEntries = packetOutbox.listDue(now)
        if (dueEntries.isNotEmpty() || pruned > 0) {
            ctx.logger.debug(
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
            ctx.logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PROCESSED,
                message = "Processed outbox for due envelopes",
                fields = mapOf("dueCount" to dueEntries.size),
            )
        }
    }

    private suspend fun processDueEntry(entry: OutboxEntry, now: Long) {
        val envelope = entry.envelope
        val outbound = transportPolicy.resolve(
            target = envelope.target,
            retries = entry.attempts,
            hasWebRtcSession = dispatcher.hasWebRtcSession(envelope.target),
        )
        val nextRetryAt = now + outbound.retryDelaySeconds
        runCatching {
            dispatcher.dispatch(envelope, outbound.transport)
        }.onSuccess {
            packetOutbox.recordAttempt(envelope.packetId, nextRetryAt, now)
            ctx.logger.debug(
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
            ctx.logger.error(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_DISPATCH_FAILED,
                message = "Failed to dispatch outbox envelope",
                throwable = error,
                fields = mapOf(
                    "packetId" to envelope.packetId,
                    "target" to envelope.target,
                    "transport" to outbound.transport,
                    "attempts" to entry.attempts,
                    "nextRetryAt" to nextRetryAt,
                ),
            )
            packetOutbox.recordAttempt(envelope.packetId, nextRetryAt, now)
        }
    }
}
