package org.yapyap.routing.outbound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The SIGNAL family's outbound half: a WebRTC session-establishment message from our transport
 * to a peer's, sealed and dispatched over Tor. Purely a transport-to-transport pass-through —
 * no outbox row, no account, no retry — one half of the pair with
 * [org.yapyap.routing.inbound.SignalInboundHandler].
 *
 * (Formerly `WebRtcBootstrapSignaler`; "bootstrap" here never meant the onboarding bootstrap
 * family, it meant bootstrapping a WebRTC session.)
 */
internal class WebRtcSignaler(
    private val ctx: RoutingContext,
    private val dispatcher: EnvelopeDispatcher,
) {
    /** Subscribes to the transport's outbound signals; the router never hand-pumps them. */
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        ctx.webRtcTransport.outgoingBootstrapSignals.collect { signal ->
            try {
                signal(signal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ephemeral by design: the session attempt fails and the opener retries.
            }
        }
    }

    suspend fun signal(signal: WebRtcSignal) {
        val protectContext = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = signal.target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val envelope = ctx.envelopeProtections.signal.protect(signal, protectContext)
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
