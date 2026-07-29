package org.yapyap.routing.outbound

import kotlinx.coroutines.*
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LoggingTypes
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

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
                event = LoggingTypes.OUTBOX_PROCESS_FAILED,
                message = "Outbox processing failed",
                throwable = error,
            )
        },
    )

    fun runIn(scope: CoroutineScope): Job = retryLoop.runIn(scope)

    fun wake() {
        retryLoop.notifyChanged()
    }

    fun onOutboundPacketDelivered(packetId: Uuid) {
        packetOutbox.markDelivered(packetId)
        wake()
    }

    fun enqueueAndWake(envelope: BinaryEnvelope, nextRetryAt: Long) {
        packetOutbox.enqueue(envelope, nextRetryAt)
        wake()
    }

    fun recordSendAttempt(packetId: Uuid, nextRetryAt: Long, now: Long) {
        packetOutbox.recordAttempt(packetId, nextRetryAt, now)
    }

    fun onWebRtcSessionConnected(peerId: PeerId) {
        val now = ctx.timeProvider.nowEpochSeconds()
        packetOutbox.setDueForTarget(peerId, now)
        wake()
        ctx.logger.info(
            component = LogComponent.ROUTER,
            event = LoggingTypes.OUTBOX_WEBRTC_DUE_SET,
            message = "WebRTC session connected; accelerated outbox retries for peer",
            fields = mapOf(
                "peerId" to peerId,
                "nextRetryAt" to now,
            ),
        )
    }

    fun pruneRelayOverCapacityOnBoot() {
        try {
            packetOutbox.pruneRelayOverCapacity(ctx.routerConfig.outboxMaxSizeBytes)
        } catch (e: Exception) {
            ctx.logger.error(
                component = LogComponent.ROUTER,
                event = LoggingTypes.OUTBOX_PRUNE_FAILED,
                message = "Failed to prune outbox for relay over capacity",
                throwable = e,
            )
        }
    }

    suspend fun processDue() {
        val now = ctx.timeProvider.nowEpochSeconds()
        val pruned = packetOutbox.pruneExpired(now)
        val dueEntries = packetOutbox.listDue(now)
        if (dueEntries.isNotEmpty() || pruned > 0) {
            ctx.logger.debug(
                component = LogComponent.ROUTER,
                event = LoggingTypes.OUTBOX_PROCESSED,
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
                event = LoggingTypes.OUTBOX_PROCESSED,
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
            hasWebRtcSession = ctx.webRtcTransport.hasSession(envelope.target),
        )
        val nextRetryAt = now + outbound.retryDelaySeconds
        runCatching {
            dispatcher.dispatch(envelope, outbound.transport)
        }.onSuccess {
            packetOutbox.recordAttempt(envelope.packetId, nextRetryAt, now)
            ctx.logger.debug(
                component = LogComponent.ROUTER,
                event = LoggingTypes.OUTBOX_RETRY_DISPATCHED,
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
                event = LoggingTypes.OUTBOX_DISPATCH_FAILED,
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
