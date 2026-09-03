package org.yapyap.routing.inbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.ping.PingProvider
import org.yapyap.routing.router.*
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.webrtc.transport.WebRtcIncomingEnvelope

internal class InboundEnvelopeProcessor(
    private val ctx: RoutingContext,
    private val systemSender: SystemSender,
    private val handlers: Map<PacketType, InboundEnvelopeHandler>,
    private val outboxProcessor: OutboxProcessor,
    private val syncHandler: SyncHandler,
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
    private val pingProvider: PingProvider,
) {
    suspend fun handleTorInbound(inbound: TorIncomingEnvelope) {
        if (inbound.source != ctx.identityResolver.resolveTorEndpointForDevice(inbound.envelope.source)) {
            ctx.identityResolver.updatePeerTorEndpoint(
                deviceId = inbound.envelope.source,
                torEndpoint = inbound.source,
            )
        }
        handle(inbound.envelope, RouterTransport.TOR)
    }

    suspend fun handleWebRtcInbound(inbound: WebRtcIncomingEnvelope) {
        handle(inbound.envelope, RouterTransport.WEBRTC)
    }

    suspend fun handle(inbound: BinaryEnvelope, transport: RouterTransport) {
        val receivedAt = ctx.clock.now()
        peerAvailabilityRegistry.markReachable(inbound.source, receivedAt)
        if (!ctx.packetDeduplicator.firstSeen(
                packetId = inbound.packetId,
                sourceDeviceId = inbound.source,
                receivedAt = receivedAt,
            )
        ) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.PACKET_DUPLICATED,
                message = "Packet ignored due to duplicate",
                fields = mapOf(
                    "packetId" to inbound.packetId,
                    "packetType" to inbound.packetType,
                    "sourceDeviceId" to inbound.source,
                    "receivedAt" to receivedAt,
                ),
            )
            if (inbound.dispositionRequested) {
                systemSender.sendDispositionForDuplicate(
                    inbound,
                    transport,
                    ctx.packetDeduplicator.getNackReason(inbound.packetId, inbound.source),
                )
            }
            return
        }

        if (inbound.expiresAt < ctx.clock.now()) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_EXPIRED,
                message = "Envelope expired",
                fields = mapOf(
                    "expiresAt" to inbound.expiresAt,
                    "receivedAt" to receivedAt,
                ),
            )
            if (inbound.dispositionRequested) {
                systemSender.sendNack(inbound.packetId, inbound.source, inbound.packetType, PacketNackReason.EXPIRED, transport)
            }
            return
        }

        if (inbound.target != ctx.localDeviceId) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Envelope ignored due to target mismatch",
                fields = mapOf(
                    "sourceDeviceId" to inbound.source,
                    "targetDeviceId" to inbound.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            if (inbound.dispositionRequested) {
                systemSender.sendNack(inbound.packetId, inbound.source, inbound.packetType, PacketNackReason.WRONG_TARGET, transport)
            }
            return
        }

        val handler = handlers[inbound.packetType]
        val handleResult = if (handler != null) {
            handler.handle(inbound)
        } else {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_UNKNOWN_TYPE,
                message = "Envelope ignored due to unknown packet type",
                fields = mapOf(
                    "packetType" to inbound.packetType,
                ),
            )
            InboundHandleResult.Rejected(PacketNackReason.UNSUPPORTED_TYPE)
        }

        applySideEffects(handleResult.sideEffects)

        when (handleResult) {
            is InboundHandleResult.Success ->
                if (inbound.dispositionRequested) {
                    systemSender.sendAck(inbound.packetId, inbound.source, inbound.packetType, transport)
                }
            is InboundHandleResult.Deferred -> {
                AppLog.info(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                    message = "Deferred inbound envelope until session prerequisites are met",
                    fields = mapOf(
                        "packetId" to inbound.packetId,
                        "packetType" to inbound.packetType,
                        "sourceDeviceId" to inbound.source,
                    ),
                )
                ctx.packetDeduplicator.clearPacket(inbound.packetId, inbound.source)
            }
            is InboundHandleResult.Rejected ->
                if (inbound.dispositionRequested) {
                    systemSender.sendNack(
                        inbound.packetId,
                        inbound.source,
                        inbound.packetType,
                        handleResult.reason,
                        transport,
                    )
                }
        }
    }

    private suspend fun applySideEffects(sideEffects: List<InboundSideEffect>) {
        sideEffects.forEach { effect ->
            when (effect) {
                is InboundSideEffect.EnqueueForRelay ->
                    outboxProcessor.enqueueAndWake(
                        effect.envelope,
                        nextRetryAt = ctx.clock.now() + ctx.routerConfig.value.ackLifetime,
                        relayMessage = true,
                    )
                is InboundSideEffect.RemoveFromOutbox ->
                    outboxProcessor.onOutboundPacketDelivered(effect.packetId)
                is InboundSideEffect.SyncRequested ->
                    syncHandler.onSyncRequested(effect.sync, effect.peerId)
                is InboundSideEffect.MarkPeerAttempted ->
                    syncHandler.onMarkPeerAttempted(effect.syncId, effect.peerId)
                is InboundSideEffect.PeerHeartbeat -> pingProvider.handlePing(effect.peerId, effect.ping)
                is InboundSideEffect.PeerOffline -> peerAvailabilityRegistry.markOffline(effect.peerId)
            }
        }
    }
}
