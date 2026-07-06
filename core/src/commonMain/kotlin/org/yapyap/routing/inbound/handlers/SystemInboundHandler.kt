package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

internal fun interface OutboxChangeNotifier {
    fun notifyChanged()
}

internal class SystemInboundHandler(
    private val ctx: RoutingContext,
    private val packetOutbox: PacketOutbox,
    private val outboxChangeNotifier: OutboxChangeNotifier,
) {
    suspend fun handle(env: BinaryEnvelope) {
        val systemEnvelope = runCatching { SystemEnvelope.decode(env.payload) }.getOrNull() ?: run {
            ctx.logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return
        }

        if (systemEnvelope.target != ctx.localDeviceId) {
            ctx.logger.error(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "System envelope received for peer ${systemEnvelope.target}",
                fields = mapOf(
                    "sourceDeviceId" to systemEnvelope.source,
                    "targetDeviceId" to systemEnvelope.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            return
        }

        val payload = try {
            ctx.envelopeProtectionService.openSystem(systemEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            ctx.logInboundProtectionFailure(
                message = "Failed to open system envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return
        }

        when (payload) {
            is SystemPayload.PacketAck -> {
                packetOutbox.markDelivered(payload.packetId)
                outboxChangeNotifier.notifyChanged()
                ctx.logger.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_ACK_RECEIVED,
                    message = "Removed acknowledged packet from outbox",
                    fields = mapOf(
                        "packetId" to payload.packetId,
                        "packetType" to payload.packetType,
                        "source" to systemEnvelope.source,
                    ),
                )
            }
            is SystemPayload.PacketNack -> {
                when (payload.reason) {
                    PacketNackReason.EXPIRED -> {
                        packetOutbox.markDelivered(payload.packetId)
                        outboxChangeNotifier.notifyChanged()
                        ctx.logger.info(
                            component = LogComponent.ROUTER,
                            event = LogEvent.OUTBOX_NACK_RECEIVED,
                            message = "Stopped retrying expired packet after NACK",
                            fields = mapOf(
                                "packetId" to payload.packetId,
                                "packetType" to payload.packetType,
                                "reason" to payload.reason,
                                "source" to systemEnvelope.source,
                            ),
                        )
                    }
                    PacketNackReason.PROTECTION_FAILED -> {
                        ctx.logger.warn(
                            component = LogComponent.ROUTER,
                            event = LogEvent.OUTBOX_NACK_RECEIVED,
                            message = "Received NACK for outbox packet due to protection failure; will retry",
                            fields = mapOf(
                                "packetId" to payload.packetId,
                                "packetType" to payload.packetType,
                                "reason" to payload.reason,
                                "source" to systemEnvelope.source,
                            ),
                        )
                    }
                    else -> {
                        ctx.logger.debug(
                            component = LogComponent.ROUTER,
                            event = LogEvent.OUTBOX_NACK_RECEIVED,
                            message = "Received NACK for outbox packet; keeping retry schedule",
                            fields = mapOf(
                                "packetId" to payload.packetId,
                                "packetType" to payload.packetType,
                                "reason" to payload.reason,
                                "source" to systemEnvelope.source,
                            ),
                        )
                        // keep retrying
                        // TODO add logic
                    }
                }
            }
            // TODO send message on ping
        }
    }
}
