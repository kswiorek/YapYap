package org.yapyap.routing.inbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LoggingTypes
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.inbound.handlers.SystemInboundHandler
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.SystemInboundResult
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.webrtc.transport.WebRtcIncomingEnvelope

internal class InboundEnvelopeProcessor(
    private val ctx: RoutingContext,
    private val ackResponder: AckResponder,
    private val handlers: Map<PacketType, InboundEnvelopeHandler>,
    private val systemHandler: SystemInboundHandler,
    private val outboxProcessor: OutboxProcessor,
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
        val receivedAtEpochSeconds = ctx.timeProvider.nowEpochSeconds()

        if (!ctx.packetDeduplicator.firstSeen(
                packetId = inbound.packetId,
                sourceDeviceId = inbound.source,
                receivedAtEpochSeconds = receivedAtEpochSeconds,
            )
        ) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LoggingTypes.PACKET_DUPLICATED,
                message = "Packet ignored due to duplicate",
                fields = mapOf(
                    "packetId" to inbound.packetId,
                    "packetType" to inbound.packetType,
                    "sourceDeviceId" to inbound.source,
                    "receivedAtEpochSeconds" to receivedAtEpochSeconds,
                ),
            )
            when (inbound.packetType) {
                PacketType.SYSTEM -> return
                else -> ackResponder.sendDispositionForDuplicate(
                    inbound,
                    transport,
                    ctx.packetDeduplicator.getNackReason(inbound.packetId, inbound.source),
                )
            }
            return
        }

        if (inbound.expiresAtEpochSeconds < receivedAtEpochSeconds) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LoggingTypes.ENVELOPE_EXPIRED,
                message = "Envelope expired",
                fields = mapOf(
                    "expiresAtEpochSeconds" to inbound.expiresAtEpochSeconds,
                    "receivedAtEpochSeconds" to receivedAtEpochSeconds,
                ),
            )
            ackResponder.sendNack(inbound.packetId, inbound.source, inbound.packetType, PacketNackReason.EXPIRED, transport)
            return
        }

        if (inbound.target != ctx.localDeviceId) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LoggingTypes.ENVELOPE_WRONG_TARGET,
                message = "Envelope ignored due to target mismatch",
                fields = mapOf(
                    "sourceDeviceId" to inbound.source,
                    "targetDeviceId" to inbound.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            ackResponder.sendNack(inbound.packetId, inbound.source, inbound.packetType, PacketNackReason.WRONG_TARGET, transport)
            return
        }

        when (inbound.packetType) {
            PacketType.SYSTEM -> {
                applySystemInboundResult(systemHandler.handle(inbound))
                return
            }
            else -> Unit
        }

        val handler = handlers[inbound.packetType]
        val handleResult = if (handler != null) {
            handler.handle(inbound)
        } else {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LoggingTypes.ENVELOPE_UNKNOWN_TYPE,
                message = "Envelope ignored due to unknown packet type",
                fields = mapOf(
                    "packetType" to inbound.packetType,
                ),
            )
            InboundHandleResult.Rejected(PacketNackReason.UNSUPPORTED_TYPE)
        }

        when (handleResult) {
            InboundHandleResult.Success -> ackResponder.sendAck(inbound.packetId, inbound.source, inbound.packetType, transport)
            InboundHandleResult.Deferred -> {
                AppLog.info(
                    component = LogComponent.ROUTER,
                    event = LoggingTypes.ENVELOPE_PROTECTION_FAILED,
                    message = "Deferred inbound envelope until session prerequisites are met",
                    fields = mapOf(
                        "packetId" to inbound.packetId,
                        "packetType" to inbound.packetType,
                        "sourceDeviceId" to inbound.source,
                    ),
                )
                ctx.packetDeduplicator.clearPacket(inbound.packetId, inbound.source)
            }
            is InboundHandleResult.Rejected -> ackResponder.sendNack(
                inbound.packetId,
                inbound.source,
                inbound.packetType,
                handleResult.reason,
                transport,
            )
        }
    }

    private fun applySystemInboundResult(result: SystemInboundResult) {
        when (result) {
            is SystemInboundResult.RemoveFromOutbox ->
                outboxProcessor.onOutboundPacketDelivered(result.packetId)
            SystemInboundResult.Ignored -> Unit
            // TODO Sprint 4: is SystemInboundResult.PeerHeartbeat -> peerPresenceService.record(result)
            // TODO Sprint 2: is SystemInboundResult.GapSyncRequested -> gapSyncCoordinator.onRequest(result)
        }
    }
}
