package org.yapyap.routing.outbound

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Fans a typing indicator out to the devices of [targets] (room members).
 *
 * The send cadence ([interval]) is owned by the caller (orchestrator) and only crosses
 * here to be stamped into the payload — receivers derive their idle-timeout from the announced
 * value, so the router never needs it from config.
 *
 * Per device, either delivers now over an open WebRTC session, or asks the
 * [ProactiveSessionOpener] to pre-warm one so a later tick can deliver. Delivery is
 * WebRTC-only: there is deliberately no Tor fallback and no outbox persistence, since a
 * typing indicator relayed through Tor store-and-forward would be stale on arrival.
 */
internal class TypingIndicatorDispatcher(
    private val ctx: RoutingContext,
    private val systemSender: SystemSender,
    private val sessionOpener: ProactiveSessionOpener,
) {
    suspend fun dispatch(
        targets: Collection<AccountId>,
        roomId: String,
        interval: Duration,
    ) {
        if (targets.isEmpty()) return

        val payload = SystemPayload.TypingIndicator(
            roomId = roomId,
            intervalMillis = interval.inWholeMilliseconds.toInt(),
        )
        val devices = ctx.identityResolver.getAllPeerDevicesForAccounts(targets)
            .filter { it != ctx.localDeviceId }
        if (devices.isEmpty()) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.MESSAGE_NO_PEERS,
                message = "No peer devices to receive typing indicator",
                fields = mapOf("roomId" to roomId),
            )
            return
        }

        coroutineScope {
            devices.map { device ->
                async {
                    // Per-device isolation: a failure delivering to one device must neither
                    // propagate to the caller nor cancel the sibling sends.
                    try {
                        if (ctx.webRtcTransport.hasSession(device)) {
                            systemSender.sendTypingIndicator(device, payload)
                        } else {
                            sessionOpener.ensureSession(device)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.warn(
                            component = LogComponent.ROUTER,
                            event = LogEvent.TYPING_INDICATOR_SENT,
                            message = "Typing indicator dispatch to device failed",
                            fields = mapOf("device" to device, "roomId" to roomId, "error" to e.toString()),
                        )
                    }
                }
            }.awaitAll()
        }
    }
}
