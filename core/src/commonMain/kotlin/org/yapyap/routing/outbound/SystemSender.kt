package org.yapyap.routing.outbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import org.yapyap.transport.tor.TorLinkId
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
        endpointOverride: TorEndpoint? = null,
        torReplyLink: TorLinkId? = null,
    ) {
        if (nackReason == null) {
            sendAck(inbound.packetId, inbound.source, inbound.packetType, transport, endpointOverride, torReplyLink)
        } else {
            sendNack(
                inbound.packetId,
                inbound.source,
                inbound.packetType,
                nackReason,
                transport,
                endpointOverride = endpointOverride,
                persistReason = false,
                torReplyLink = torReplyLink,
            )
        }
    }

    /**
     * @param endpointOverride out-of-band endpoint for sources with no local devices row
     *   (bootstrap ACKs/NACKs back to a newcomer/recovering device) — the transport-proven
     *   inbound endpoint, sent to verbatim instead of the DB lookup.
     * @param torReplyLink the Tor stream the acknowledged packet arrived on: answered on it
     *   while it is open, so the reply needs no dial of its own.
     */
    suspend fun sendAck(
        packetId: Uuid,
        source: PeerId,
        packetType: PacketType,
        transport: RouterTransport,
        endpointOverride: TorEndpoint? = null,
        torReplyLink: TorLinkId? = null,
    ) {
        val ackContext = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = source,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val ackPayload = SystemPayload.PacketAck(
            packetId,
            packetType,
        )
        sendSystemEnvelope(ackPayload, transport, ackContext, endpointOverride, torReplyLink)
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
        endpointOverride: TorEndpoint? = null,
        persistReason: Boolean = true,
        reasonText: String? = null,
        torReplyLink: TorLinkId? = null,
    ) {
        if (persistReason) {
            ctx.packetDeduplicator.markNacked(packetId, source, reason)
        }

        val ackContext = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = source,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val ackPayload = SystemPayload.PacketNack(
            packetId,
            packetType,
            reason,
            reasonText = reasonText,
        )
        sendSystemEnvelope(ackPayload, transport, ackContext, endpointOverride, torReplyLink)
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
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        sendSystemEnvelope(request, transportFor(target), context)
    }

    suspend fun sendSyncNack(target: PeerId, payload: SystemPayload.SyncNack) {
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        sendSystemEnvelope(payload, transportFor(target), context)
    }

    /**
     * Sends a fire-and-forget typing indicator over the preferred open link. Peers with no link
     * are silently skipped (no one-shot dial, no outbox persistence): an indicator that has to
     * wait for a connection would be stale by the time it arrives.
     */
    suspend fun sendTypingIndicator(
        target: PeerId,
        payload: SystemPayload.TypingIndicator,
    ) {
        val transport = transportPolicy.resolve(target, ctx.peerLinks.linksOf(target), retries = 0).transport ?: return

        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        sendSystemEnvelope(payload, transport, context)
        AppLog.debug(
            component = LogComponent.ROUTER,
            event = LogEvent.TYPING_INDICATOR_SENT,
            message = "Typing indicator sent",
            fields = mapOf(
                "target" to target,
                "transport" to transport,
                "roomId" to payload.roomId,
                "intervalMillis" to payload.intervalMillis,
            ),
        )
    }

    suspend fun sendPing(
        target: PeerId,
        payload: SystemPayload.Ping,
    ) {
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )

        sendSystemEnvelope(payload, transportFor(target), context)
        AppLog.debug(
            component = LogComponent.ROUTER,
            event = LogEvent.PING_SENT,
            message = "Ping sent",
            fields = mapOf(
                "target" to target,
            ),
        )
    }

    suspend fun sendLogOff(
        target: PeerId,
    ) {
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )

        sendSystemEnvelope(SystemPayload.LogOff, transportFor(target), context)
    }

    /**
     * The preferred open link to [target]; with none, a one-shot Tor dial. System traffic is
     * never parked waiting for a link (pings, sync requests and log-offs are best-effort).
     */
    private suspend fun transportFor(target: PeerId): RouterTransport =
        transportPolicy.resolve(target, ctx.peerLinks.linksOf(target), retries = 0).transport ?: RouterTransport.TOR

    private suspend fun sendSystemEnvelope(
        payload: SystemPayload,
        transport: RouterTransport,
        context: EnvelopeProtectContext,
        endpointOverride: TorEndpoint? = null,
        torReplyLink: TorLinkId? = null,
    ) {
        val protected = ctx.envelopeProtectionService.protectSystem(payload, context)
        val now = ctx.clock.now()
        val envelope = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.SYSTEM,
            dispositionRequested = false,
            createdAt = now,
            expiresAt = now + ctx.routerConfig.value.ackLifetime,
            source = ctx.localDeviceId,
            target = context.targetDeviceId,
            payload = protected.encode(),
        )
        dispatcher.dispatch(envelope, transport, endpointOverride, torReplyLink = torReplyLink)
    }
}