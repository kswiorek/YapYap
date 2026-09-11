package org.yapyap.routing.inbound

import org.yapyap.crypto.CryptoException
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.TorEndpoint
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
        // resolveTorEndpointForDevice throws for devices with no row. That must not kill the inbound:
        // a pre-bootstrap sponsor (unknown source) is exactly who we want to route bootstrap packets
        // from. Unknown sources get no endpoint mapping here (their claimed device id is
        // unauthenticated at this layer).
        val knownEndpoint = try {
            ctx.identityResolver.resolveTorEndpointForDevice(inbound.envelope.source)
        } catch (e: CryptoException) {
            null
        }
        if (knownEndpoint == null) {
            // TODO(sprint 4 hardening): endpoint-claim policy for unauthenticated unknown devices —
            // today we neither create nor overwrite a mapping from an unverified claim. Known devices
            // keep the existing self-healing overwrite below (Tor onion rotation).
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.STARTED,
                message = "Tor inbound from unknown device; skipped endpoint reconciliation",
                fields = mapOf("sourceDeviceId" to inbound.envelope.source),
            )
        } else if (inbound.source != knownEndpoint) {
            ctx.identityResolver.updatePeerTorEndpoint(
                deviceId = inbound.envelope.source,
                torEndpoint = inbound.source,
            )
        }
        handle(inbound.envelope, RouterTransport.TOR, provenSourceEndpoint = inbound.source)
    }

    suspend fun handleWebRtcInbound(inbound: WebRtcIncomingEnvelope) {
        handle(inbound.envelope, RouterTransport.WEBRTC)
    }

    /**
     * @param provenSourceEndpoint transport-proven source endpoint (Tor only): the onion the
     *   packet actually arrived from, as opposed to anything claimed inside the payload. Passed
     *   through to ACK/NACKs so dispositions back to sources with no local devices row
     *   (bootstrap newcomer / recovering device) are deliverable without a DB lookup. For known
     *   peers it equals the reconciled row above, so preferring it is equivalent-or-fresher.
     */
    suspend fun handle(
        inbound: BinaryEnvelope,
        transport: RouterTransport,
        provenSourceEndpoint: TorEndpoint? = null,
    ) {
        val receivedAt = ctx.clock.now()
        peerAvailabilityRegistry.markReachable(inbound.source, receivedAt)
        // TODO(sprint-4d device status): banned-source check — drop envelopes whose source
        // device is BANNED (IdentityKeyRepository.getDeviceStatus, committed by the global
        // events fold) before dedup/dispatch. Unknown/absent status must NOT drop (absence
        // asserts nothing — pre-bootstrap sponsors and gap-skipped devices stay routable).
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
                    endpointOverride = provenSourceEndpoint,
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
                systemSender.sendNack(
                    inbound.packetId,
                    inbound.source,
                    inbound.packetType,
                    PacketNackReason.EXPIRED,
                    transport,
                    endpointOverride = provenSourceEndpoint
                )
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
                systemSender.sendNack(
                    inbound.packetId,
                    inbound.source,
                    inbound.packetType,
                    PacketNackReason.WRONG_TARGET,
                    transport,
                    endpointOverride = provenSourceEndpoint
                )
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
                    systemSender.sendAck(
                        inbound.packetId,
                        inbound.source,
                        inbound.packetType,
                        transport,
                        endpointOverride = provenSourceEndpoint
                    )
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
                        endpointOverride = provenSourceEndpoint,
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
