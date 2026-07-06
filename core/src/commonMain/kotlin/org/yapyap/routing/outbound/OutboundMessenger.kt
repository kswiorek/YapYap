package org.yapyap.routing.outbound

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.router.*
import org.yapyap.transport.TransportException
import kotlin.coroutines.cancellation.CancellationException

internal class OutboundMessenger(
    private val ctx: RoutingContext,
    private val dispatcher: EnvelopeDispatcher,
    private val transportPolicy: OutboundPolicy,
    private val packetOutbox: PacketOutbox,
    private val outboxProcessor: OutboxProcessor,
) {
    suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): SendMessageResult {
        val peers = ctx.identityResolver.getAllPeerDevicesForAccount(target)
        if (peers.isEmpty()) {
            ctx.logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.MESSAGE_NO_PEERS,
                message = "No peer devices found for target account",
                fields = mapOf("targetAccountId" to target),
            )
            return SendMessageResult(
                status = SendMessageStatus.FAILURE,
                peersTotal = 0,
                peersQueued = 0,
                failureKind = SendFailureKind.NO_PEERS,
            )
        }

        val outcomes = coroutineScope {
            peers.map { peer ->
                async {
                    sendMessageToPeer(
                        target = peer,
                        payload = payload,
                        forceTransport = forceTransport,
                    )
                }
            }.awaitAll()
        }
        return aggregateSendResults(outcomes)
    }

    private suspend fun sendMessageToPeer(
        target: PeerId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): PeerSendOutcome {
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAtEpochSeconds = ctx.timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
        )

        val messageEnvelope = try {
            ctx.envelopeProtectionService.protectMessage(payload, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            return outboundResultForProtectionFailure(target, e)
        }

        val binaryEnvelope = BinaryEnvelope(
            packetId = ctx.packetIdAllocator.allocate(ctx.timeProvider.nowEpochSeconds()),
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = messageEnvelope.createdAtEpochSeconds,
            expiresAtEpochSeconds = messageEnvelope.createdAtEpochSeconds + ctx.routerConfig.messageLifetimeSeconds,
            source = messageEnvelope.source,
            target = messageEnvelope.target,
            payload = messageEnvelope.encode(),
        )
        // TODO opening WebRTC session on demand if not exists, fallback to Tor if session cannot be established, etc

        val plan = transportPolicy.resolve(
            target = target,
            hasWebRtcSession = dispatcher.hasWebRtcSession(target),
            retries = 0,
            forced = forceTransport,
        )
        val nextRetryAt = ctx.timeProvider.nowEpochSeconds() + plan.retryDelaySeconds
        packetOutbox.enqueue(binaryEnvelope, nextRetryAt)
        outboxProcessor.wake()
        ctx.logger.debug(
            component = LogComponent.ROUTER,
            event = LogEvent.OUTBOX_MESSAGE_QUEUED,
            message = "Queued outbound message in outbox",
            fields = mapOf(
                "packetId" to binaryEnvelope.packetId,
                "target" to target,
                "transport" to plan.transport,
                "nextRetryAt" to nextRetryAt,
            ),
        )
        try {
            dispatcher.dispatch(binaryEnvelope, plan.transport)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            ctx.logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DISPATCH_FAILED,
                message = "Envelope dispatch failed: TransportException",
                fields = mapOf(
                    "packetId" to binaryEnvelope.packetId,
                    "target" to target,
                    "transport" to plan.transport,
                    "error" to e.toString(),
                ),
            )
        } catch (e: CryptoException) {
            ctx.logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DISPATCH_FAILED,
                message = "Envelope dispatch failed: CryptoException",
                fields = mapOf(
                    "packetId" to binaryEnvelope.packetId,
                    "target" to target,
                    "transport" to plan.transport,
                    "error" to e.toString(),
                ),
            )
        } finally {
            packetOutbox.recordAttempt(binaryEnvelope.packetId, nextRetryAt, ctx.timeProvider.nowEpochSeconds())
        }
        return PeerSendOutcome.Queued
    }

    private fun outboundResultForProtectionFailure(
        target: PeerId,
        exception: ProtectionException,
    ): PeerSendOutcome {
        val fields = mapOf(
            "targetDeviceId" to target,
            "disposition" to exception.disposition.name,
            "reason" to exception.reason.name,
        )
        return when (exception.disposition) {
            ProtectionDisposition.PERMANENT -> {
                ctx.logger.error(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                    message = "Message protection failed",
                    fields = fields,
                    throwable = exception,
                )
                PeerSendOutcome.PermanentFailure
            }
            ProtectionDisposition.RETRYABLE,
            ProtectionDisposition.DEFER,
                -> {
                ctx.logger.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                    message = "Message protection failed",
                    fields = fields + ("error" to exception.message),
                )
                PeerSendOutcome.NotReady
            }
        }
    }
}
