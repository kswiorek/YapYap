package org.yapyap.routing.outbound

import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import org.yapyap.transport.webrtc.types.WebRtcSignal
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal class WebRtcBootstrapSignaler(
    private val ctx: RoutingContext,
    private val dispatcher: EnvelopeDispatcher,
) {
    suspend fun signal(signal: WebRtcSignal) {
        val protectContext = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = signal.target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val envelope = ctx.envelopeProtectionService.protectSignal(signal, protectContext)
        //TODO: Check ack for signal
        dispatcher.dispatch(
            envelope = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.SIGNAL,
                dispositionRequested = false,
                createdAt = protectContext.createdAt,
                expiresAt = protectContext.createdAt + 600.seconds,
                source = ctx.localDeviceId,
                target = signal.target,
                payload = envelope.encode(),
            ),
            transport = RouterTransport.TOR,
        )
    }
}
