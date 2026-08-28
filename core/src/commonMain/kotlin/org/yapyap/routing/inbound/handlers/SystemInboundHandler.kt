package org.yapyap.routing.inbound.handlers

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TypingIndicatorEvent
import kotlin.coroutines.cancellation.CancellationException

internal class SystemInboundHandler(
    private val ctx: RoutingContext,
    private val typingIndicatorFlow: MutableSharedFlow<TypingIndicatorEvent>
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
            ctx.envelopeProtectionService.openSystem(systemEnvelope)
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
                    sideEffects = listOf(InboundSideEffect.RemoveFromOutbox(payload.packetId)),
                )
            }
            is SystemPayload.PacketNack -> {
                when (payload.reason) {
                    PacketNackReason.EXPIRED -> {
                        AppLog.info(
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
                        InboundHandleResult.Success(
                            sideEffects = listOf(InboundSideEffect.RemoveFromOutbox(payload.packetId)),
                        )
                    }
                    PacketNackReason.PROTECTION_FAILED -> {
                        AppLog.warn(
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
                        InboundHandleResult.Success()
                    }
                    else -> {
                        AppLog.debug(
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
                        InboundHandleResult.Success()
                    }
                }
            }
            is SystemPayload.SyncRequest -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.SYNC_REQUEST_RECEIVED,
                    message = "Received sync request",
                    fields = mapOf(
                        "source" to systemEnvelope.source,
                        "roomId" to payload.roomId,
                        "syncId" to payload.syncId,
                    )
                )
                InboundHandleResult.Success(
                    sideEffects = listOf(InboundSideEffect.SyncRequested(systemEnvelope.source, payload)),
                )
            }
            is SystemPayload.SyncNack -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.SYNC_NACK_RECEIVED,
                    message = "Received sync NACK",
                    fields = mapOf(
                        "source" to systemEnvelope.source,
                        "syncId" to payload.syncId,
                        "reason" to payload.reason,
                    ),
                )
                InboundHandleResult.Success(
                    sideEffects = listOf(InboundSideEffect.MarkPeerAttempted(systemEnvelope.source, payload.syncId)),
                )
            }
            is SystemPayload.TypingIndicator -> {
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.TYPING_INDICATOR_RECEIVED,
                    message = "Typing indicator received",
                    fields = mapOf(
                        "source" to systemEnvelope.source,
                        "roomId" to payload.roomId,
                        "intervalSeconds" to payload.intervalSeconds,
                    ),
                )
                val accountId = ctx.identityResolver.getAccountIdForDevice(systemEnvelope.source)
                if (accountId != null) {
                    typingIndicatorFlow.emit(
                        TypingIndicatorEvent(
                            senderAccountId = accountId,
                            roomId = payload.roomId,
                            intervalSeconds = payload.intervalSeconds,
                            receivedAtEpochSeconds = ctx.timeProvider.nowEpochSeconds(),
                        )
                    )
                }
                InboundHandleResult.Success()
            }
            // TODO Sprint 4: SystemPayload.Ping/Pong -> InboundSideEffect.PeerHeartbeat(...)
            else -> {TODO("Unhandled system payload: ${payload::class.simpleName ?: "unknown"}")}
        }
    }
}
