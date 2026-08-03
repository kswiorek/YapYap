package org.yapyap.routing.inbound.handlers

import org.yapyap.logging.AppLog
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
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return SystemInboundResult.Ignored
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
            return SystemInboundResult.Ignored
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
            return SystemInboundResult.Ignored
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
                SystemInboundResult.RemoveFromOutbox(payload.packetId)
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
                        SystemInboundResult.RemoveFromOutbox(payload.packetId)
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
                        SystemInboundResult.Ignored
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
                        SystemInboundResult.Ignored
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
                SystemInboundResult.SyncRequested(systemEnvelope.source, payload)
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
                SystemInboundResult.MarkPeerAttempted(systemEnvelope.source, payload.syncId)
            }
            // TODO Sprint 4: SystemPayload.Ping/Pong -> SystemInboundResult.PeerHeartbeat(...)
            // TODO Sprint 2: gap sync request payload -> SystemInboundResult.GapSyncRequested(...)
            //callback from SyncCoordinator to return requested message IDs
            else -> {TODO("Unhandled system payload: ${payload::class.simpleName ?: "unknown"}")}
        }
    }
}
