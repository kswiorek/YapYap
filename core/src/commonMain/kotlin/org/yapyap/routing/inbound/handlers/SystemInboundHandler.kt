package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.SystemInboundResult
import kotlin.coroutines.cancellation.CancellationException

internal class SystemInboundHandler(
    private val ctx: RoutingContext,
) {
    suspend fun handle(env: BinaryEnvelope): SystemInboundResult {
        val systemEnvelope = runCatching { SystemEnvelope.decode(env.payload) }.getOrNull() ?: run {
            ctx.logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return SystemInboundResult.Ignored
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
            return SystemInboundResult.Ignored
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
            return SystemInboundResult.Ignored
        }

        return when (payload) {
            is SystemPayload.PacketAck -> {
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
                SystemInboundResult.RemoveFromOutbox(payload.packetId)
            }
            is SystemPayload.PacketNack -> {
                when (payload.reason) {
                    PacketNackReason.EXPIRED -> {
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
                        SystemInboundResult.RemoveFromOutbox(payload.packetId)
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
                        SystemInboundResult.Ignored
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
                        SystemInboundResult.Ignored
                    }
                }
            }
            // TODO Sprint 4: SystemPayload.Ping/Pong -> SystemInboundResult.PeerHeartbeat(...)
            // TODO Sprint 2: gap sync request payload -> SystemInboundResult.GapSyncRequested(...)
        }
    }
}
