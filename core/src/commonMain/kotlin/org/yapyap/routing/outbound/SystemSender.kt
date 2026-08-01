package org.yapyap.routing.outbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import kotlin.uuid.Uuid

internal class SystemSender(
    private val ctx: RoutingContext,
    private val transportPolicy: OutboundPolicy,
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
        packetId: Uuid,
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
        AppLog.info(
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
        packetId: Uuid,
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
        AppLog.info(
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

    suspend fun sendSyncRequest(target: PeerId, request: SyncRequest) { //Possibly returns outcome
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAtEpochSeconds = ctx.timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val transport = transportPolicy.resolve(
            target,
            hasWebRtcSession = ctx.webRtcTransport.hasSession(target),
            retries = 0).transport
        sendSystemEnvelope(request, transport, context)
    }

    suspend fun sendSyncNack(target: PeerId, payload: SystemPayload.SyncNack) {
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAtEpochSeconds = ctx.timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val transport = transportPolicy.resolve(
            target,
            hasWebRtcSession = ctx.webRtcTransport.hasSession(target),
            retries = 0
        ).transport
        sendSystemEnvelope(payload, transport, context)
    }

    private suspend fun sendSystemEnvelope(
        payload: SystemPayload,
        transport: RouterTransport,
        context: EnvelopeProtectContext,
    ) {
        val protected = ctx.envelopeProtectionService.protectSystem(payload, context)
        val now = ctx.timeProvider.nowEpochSeconds()
        val envelope = BinaryEnvelope(
            packetId = Uuid.random(),
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