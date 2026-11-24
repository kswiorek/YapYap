package org.yapyap.routing.inbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.message.MessageInbound
import org.yapyap.routing.policy.NackAction
import org.yapyap.routing.policy.nackActionFor
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect

/**
 * The header-only, hop-signed SYSTEM family: fire-and-forget control traffic, never an outbox row. ACK/NACK
 * dispositions, pings, log-off and typing.
 *
 * This handler sits with the kernel's inbound pipeline rather than in a service package: SYSTEM
 * is a family whose meaning the kernel owns (ACK/NACK are outbox obligations; ping/log-off are
 * availability obligations). Every branch either maps to a kernel side effect or delegates to
 * the owning service's inbound face — typing is the only delegate. Sync requests are sealed
 * MESSAGE-family content (`SyncPayload.Request`) and never reach this handler.
 */
internal class SystemInboundHandler(
    private val messages: MessageInbound,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val payload = runCatching { SystemPayload.decode(env.routingHeader) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode system header",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
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
                        "source" to env.source,
                    ),
                )
                InboundHandleResult.Success(
                    sideEffects = listOf(InboundSideEffect.OutboxDisposition(payload.packetId)),
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
                            "source" to env.source,
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
                            "source" to env.source,
                        ),
                    )
                    InboundHandleResult.Success()
                }
            }

            is SystemPayload.TypingIndicator -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.TYPING_INDICATOR_RECEIVED,
                    message = "Typing indicator received",
                    fields = mapOf(
                        "source" to env.source,
                        "roomId" to payload.roomId,
                        "intervalMillis" to payload.intervalMillis,
                    ),
                )
                messages.onTyping(env.source, payload.roomId, payload.intervalMillis)
                InboundHandleResult.Success()
            }

            is SystemPayload.Ping -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.PING_HANDLED,
                    message = "Received ping",
                    fields = mapOf(
                        "source" to env.source,
                        "roomPayloadCount" to payload.roomFrontiers.size,
                    ),
                )
                InboundHandleResult.Success(
                    listOf(InboundSideEffect.PeerHeartbeat(env.source, payload)),
                )
            }

            is SystemPayload.LogOff -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.LOG_OFF_HANDLED,
                    message = "Received log off",
                    fields = mapOf(
                        "source" to env.source,
                    )
                )

                InboundHandleResult.Success(
                    listOf(InboundSideEffect.PeerOffline(env.source)),
                )
            }
        }
    }
}
