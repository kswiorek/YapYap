package org.yapyap.routing.outbound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.envelopes.SignalHeader
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * The SIGNAL family's outbound half: a WebRTC session-establishment message from our transport
 * to a peer's, sealed and dispatched over Tor. Purely a transport-to-transport pass-through —
 * no outbox row, no account, no retry — one half of the pair with
 * [org.yapyap.routing.inbound.SignalInboundHandler].
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
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_DISPATCH_FAILED,
                    message = "WebRTC signal not sent",
                    fields = mapOf("target" to signal.target, "error" to e.toString()),
                )
            }
        }
    }

    suspend fun signal(signal: WebRtcSignal) {
        val envelope = ctx.envelope(
            packetType = PacketType.SIGNAL,
            target = signal.target,
            routingHeader = SignalHeader(signal.kind).encode(),
            content = ctx.envelopeProtections.signal.seal(signal),
            expiresAt = ctx.clock.now() + SIGNAL_LIFETIME,
            dispositionRequested = false,
        )
        dispatcher.dispatch(envelope = envelope, transport = RouterTransport.TOR)
    }

    private companion object {
        val SIGNAL_LIFETIME = 600.seconds
    }
}
