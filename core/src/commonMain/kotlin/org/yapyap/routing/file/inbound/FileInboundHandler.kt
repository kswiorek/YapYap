package org.yapyap.routing.file.inbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thin FILE dispatcher: decodes the envelope, routes foreign inner targets to the relay door
 * and local targets to the target/serve handlers. Chunk discipline (design §3.5): a CHUNK for
 * an unknown or unprocessed `transferId` is never ACKed and never stored — answered DECLINED.
 */
internal class FileInboundHandler(
    private val ctx: RoutingContext,
    private val protection: FileEnvelopeProtection,
    private val target: FileTargetHandler,
    private val door: FileRelayDoor,
    private val serve: FileServeHandler,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val fileEnvelope = try {
            FileEnvelope.decode(env.payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode file envelope",
                fields = mapOf("error" to e.toString()),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }
        if (fileEnvelope.target != ctx.localDeviceId) {
            return door.handleRelay(env, fileEnvelope)
        }
        val opened = try {
            protection.open(fileEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open file envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        return when (val payload = opened.payload) {
            is FilePayload.Offer -> target.handleOffer(env, fileEnvelope, payload)
            is FilePayload.EncryptedChunk -> target.handleChunk(env, fileEnvelope, payload)
            is FilePayload.Ack -> {
                target.handleAck(payload, fileEnvelope)
                InboundHandleResult.Success(sourceAuthenticated = true)
            }
            is FilePayload.Complete -> {
                target.handleComplete(fileEnvelope)
                InboundHandleResult.Success(sourceAuthenticated = true)
            }

            is FilePayload.Request -> serve.handleRequest(fileEnvelope, payload)
            is FilePayload.Cancel -> target.handleCancel(fileEnvelope, payload)
        }
    }
}
