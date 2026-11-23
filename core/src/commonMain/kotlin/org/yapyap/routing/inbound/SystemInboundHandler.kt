package org.yapyap.routing.inbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.message.MessageInbound
import org.yapyap.routing.policy.NackAction
import org.yapyap.routing.policy.nackActionFor
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The signed-only SYSTEM family: fire-and-forget control traffic, never an outbox row. ACK/NACK
 * dispositions, pings, log-off and typing.
 *
 * This handler sits with the kernel's inbound pipeline rather than in a service package: SYSTEM
 * is a family whose meaning the kernel owns (ACK/NACK are outbox obligations; ping/log-off are
 * availability obligations). Every branch either maps to a kernel side effect or delegates to
 * the owning service's inbound face — typing is the only delegate. Sync requests are sealed
 * MESSAGE-family content (`SyncPayload.Request`) and never reach this handler.
 */
internal class SystemInboundHandler(
    private val ctx: RoutingContext,
    private val messages: MessageInbound,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val systemEnvelope = runCatching { SystemEnvelope.decode(env.payload) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }

        if (systemEnvelope.target != ctx.localDeviceId) {
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "System envelope received for peer ${systemEnvelope.target}",
                fields = mapOf(
                    "sourceDeviceId" to systemEnvelope.source,
                    "targetDeviceId" to systemEnvelope.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.WRONG_TARGET)
        }

        val payload = try {
            ctx.envelopeProtections.system.open(systemEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open system envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }

        return when (payload) {
            is SystemPayload.PacketAck -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_ACK_RECEIVED,
                    message = "Removed acknowledged packet from outbox",
                    fields = mapOf(
                        "packetId" to payload.packetId,
                        "packetType" to payload.packetType,
                        "source" to systemEnvelope.source,
                    ),
                )
                InboundHandleResult.Success(
                    sideEffects = listOf(InboundSideEffect.OutboxDisposition(payload.packetId)),
                    sourceAuthenticated = true,
                )
            }

            is SystemPayload.PacketNack -> when (nackActionFor(payload.reason)) {
                NackAction.REMOVE -> {
                    AppLog.info(
                        component = LogComponent.ROUTER,
                        event = LogEvent.OUTBOX_NACK_RECEIVED,
                        message = "Terminal NACK for outbox packet; stopped retrying",
                        fields = mapOf(
                            "packetId" to payload.packetId,
                            "packetType" to payload.packetType,
                            "reason" to payload.reason,
                            "source" to systemEnvelope.source,
                        ),
                    )
                    InboundHandleResult.Success(
                        sideEffects = listOf(
                            InboundSideEffect.OutboxDisposition(
                                payload.packetId,
                                // REMOVE is only ever decided for a recognized reason.
                                checkNotNull(payload.reason),
                            ),
                        ),
                    )
                }

                NackAction.KEEP -> {
                    AppLog.info(
                        component = LogComponent.ROUTER,
                        event = LogEvent.OUTBOX_NACK_RECEIVED,
                        message = "Healable NACK for outbox packet; keeping retry schedule",
                        fields = mapOf(
                            "packetId" to payload.packetId,
                            "packetType" to payload.packetType,
                            "reason" to payload.reason,
                            "source" to systemEnvelope.source,
                        ),
                    )
                    InboundHandleResult.Success(sourceAuthenticated = true)
                }
            }

            is SystemPayload.TypingIndicator -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.TYPING_INDICATOR_RECEIVED,
                    message = "Typing indicator received",
                    fields = mapOf(
                        "source" to systemEnvelope.source,
                        "roomId" to payload.roomId,
                        "intervalMillis" to payload.intervalMillis,
                    ),
                )
                messages.onTyping(systemEnvelope.source, payload.roomId, payload.intervalMillis)
                InboundHandleResult.Success(sourceAuthenticated = true)
            }

            is SystemPayload.Ping -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.PING_HANDLED,
                    message = "Received ping",
                    fields = mapOf(
                        "source" to systemEnvelope.source,
                        "roomPayloadCount" to payload.roomFrontiers.size,
                    ),
                )
                InboundHandleResult.Success(
                    listOf(InboundSideEffect.PeerHeartbeat(systemEnvelope.source, payload)),
                    sourceAuthenticated = true,
                )
            }

            is SystemPayload.LogOff -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.LOG_OFF_HANDLED,
                    message = "Received log off",
                    fields = mapOf(
                        "source" to systemEnvelope.source,
                    )
                )

                InboundHandleResult.Success(
                    listOf(InboundSideEffect.PeerOffline(systemEnvelope.source)),
                    sourceAuthenticated = true,
                )
            }
        }
    }
}
