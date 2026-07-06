package org.yapyap.routing.inbound

import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.outbound.EnvelopeDispatcher
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext

internal class AckResponder(
    private val ctx: RoutingContext,
    private val dispatcher: EnvelopeDispatcher,
) {
    suspend fun sendDispositionForDuplicate(
        inbound: BinaryEnvelope,
        transport: RouterTransport,
        nackReason: PacketNackReason?,
    ) {
        if (nackReason == null) {
            sendAck(inbound.packetId, inbound.source, inbound.packetType, transport)
        } else {
            sendNack(
                inbound.packetId,
                inbound.source,
                inbound.packetType,
                nackReason,
                transport,
                persistReason = false,
            )
        }
    }

    suspend fun sendAck(
        packetId: PacketId,
        source: PeerId,
        packetType: PacketType,
        transport: RouterTransport,
    ) {
        val ackContext = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = source,
            createdAtEpochSeconds = ctx.timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val ackPayload = SystemPayload.PacketAck(
            packetId,
            packetType,
        )
        sendSystemEnvelope(ackPayload, transport, ackContext)
        ctx.logger.info(
            LogComponent.ROUTER,
            LogEvent.ACK_SENT,
            "ACK sent for packet $packetId",
            mapOf(
                "packetId" to packetId,
                "packetType" to packetType,
                "source" to source,
                "transport" to transport,
            ),
        )
    }

    suspend fun sendNack(
        packetId: PacketId,
        source: PeerId,
        packetType: PacketType,
        reason: PacketNackReason,
        transport: RouterTransport,
        persistReason: Boolean = true,
        reasonText: String? = null,
    ) {
        if (persistReason) {
            ctx.packetDeduplicator.markNacked(packetId, source, reason)
        }

        val ackContext = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = source,
            createdAtEpochSeconds = ctx.timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val ackPayload = SystemPayload.PacketNack(
            packetId,
            packetType,
            reason,
            reasonText = reasonText,
        )
        sendSystemEnvelope(ackPayload, transport, ackContext)
        ctx.logger.info(
            LogComponent.ROUTER,
            LogEvent.NACK_SENT,
            "NACK sent for packet $packetId",
            mapOf(
                "packetId" to packetId,
                "packetType" to packetType,
                "source" to source,
                "transport" to transport,
                "reason" to reason,
            ),
        )
    }

    private suspend fun sendSystemEnvelope(
        payload: SystemPayload,
        transport: RouterTransport,
        context: EnvelopeProtectContext,
    ) {
        val protected = ctx.envelopeProtectionService.protectSystem(payload, context)
        val now = ctx.timeProvider.nowEpochSeconds()
        val envelope = BinaryEnvelope(
            packetId = ctx.packetIdAllocator.allocate(now),
            packetType = PacketType.SYSTEM,
            createdAtEpochSeconds = now,
            expiresAtEpochSeconds = now + ctx.routerConfig.ackLifetimeSeconds,
            source = ctx.localDeviceId,
            target = context.targetDeviceId,
            payload = protected.encode(),
        )
        dispatcher.dispatch(envelope, transport)
    }
}
