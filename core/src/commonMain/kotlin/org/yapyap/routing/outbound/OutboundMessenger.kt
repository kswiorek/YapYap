package org.yapyap.routing.outbound

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.*
import org.yapyap.transport.TransportException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

internal class OutboundMessenger(
    private val ctx: RoutingContext,
    private val dispatcher: EnvelopeDispatcher,
    private val transportPolicy: OutboundPolicy,
    private val outboxProcessor: OutboxProcessor,
    private val sessionOpener: ProactiveSessionOpener,
    private val relaySelectionPolicy: RelaySelectionPolicy,
) {
    suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): SendMessageResult {
        val peers = ctx.identityResolver.getAllPeerDevicesForAccount(target)
            .filter { it != ctx.localDeviceId }   // skip originating device only
        if (peers.isEmpty()) {
            AppLog.warn(
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

    private fun aggregateSendResults(outcomes: List<PeerSendOutcome>): SendMessageResult {
        val deviceCount = outcomes.size
        val queuedDevices = outcomes.count { it is PeerSendOutcome.Queued }
        val relaysDeposited = outcomes.sumOf { (it as? PeerSendOutcome.Queued)?.relaysDeposited ?: 0 }
        val notReady = outcomes.count { it is PeerSendOutcome.NotReady }
        val permanent = outcomes.count { it is PeerSendOutcome.PermanentFailure }

        val status = when (queuedDevices) {
            deviceCount -> SendMessageStatus.SUCCESS
            0 -> SendMessageStatus.FAILURE
            else -> SendMessageStatus.PARTIAL
        }

        val failureKind = when (status) {
            SendMessageStatus.SUCCESS -> null
            SendMessageStatus.FAILURE -> when {
                notReady == deviceCount -> SendFailureKind.NOT_READY
                permanent == deviceCount -> SendFailureKind.PERMANENT
                else -> SendFailureKind.MIXED
            }
            SendMessageStatus.PARTIAL -> when {
                permanent > 0 -> SendFailureKind.MIXED
                notReady > 0 -> SendFailureKind.NOT_READY
                else -> SendFailureKind.MIXED
            }
        }
        //TODO: more complete statistics for the gui
        return SendMessageResult(
            status = status,
            peersTotal = deviceCount + relaysDeposited,
            peersQueued = queuedDevices + relaysDeposited,
            failureKind = failureKind,
        )
    }

    internal suspend fun sendMessageToPeer(
        target: PeerId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
        supplementRelays: Boolean = true,
    ): PeerSendOutcome {
        val context = EnvelopeProtectContext(
            sourceDeviceId = ctx.localDeviceId,
            targetDeviceId = target,
            createdAt = ctx.clock.now(),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
        )

        val messageEnvelope = try {
            ctx.envelopeProtectionService.protectMessage(payload, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            return outboundResultForProtectionFailure(target, e)
        }

        val now = ctx.clock.now()

        val binaryEnvelope = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.MESSAGE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + ctx.routerConfig.value.binaryEnvelopeLifetime,
            source = ctx.localDeviceId,
            target = target,
            payload = messageEnvelope.encode(),
        )
        sessionOpener.ensureSession(target)

        val plan = transportPolicy.resolve(
            target = target,
            hasWebRtcSession = ctx.webRtcTransport.hasSession(target),
            retries = 0,
            forced = forceTransport,
        )
        val nextRetryAt = ctx.clock.now() + plan.retryDelay

        if (binaryEnvelope.encode().size.toLong() > ctx.transportLimits.value.maxRoutableBytes) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.SIZE_EXCEEDED,
                message = "Message envelope size exceeds maximum",
                fields = mapOf(
                    "size" to binaryEnvelope.encode().size,
                )
            )
            return PeerSendOutcome.PermanentFailure
        }

        outboxProcessor.enqueueAndWake(binaryEnvelope, nextRetryAt)
        AppLog.debug(
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
            AppLog.warn(
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
            AppLog.warn(
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
            outboxProcessor.recordSendAttempt(
                packetId = binaryEnvelope.packetId,
                nextRetryAt = nextRetryAt,
                at = ctx.clock.now(),
            )
        }

        // Supplement the direct attempt with store-and-forward relay deposits when the target has no
        // live WebRTC session. Relays hold a copy and forward it once the recipient surfaces.
        val relaysDeposited = if (supplementRelays && !ctx.webRtcTransport.hasSession(target)) {
            depositToRelays(target, messageEnvelope)
        } else {
            0
        }
        return PeerSendOutcome.Queued(relaysDeposited)
    }

    private suspend fun depositToRelays(targetDevice: PeerId, messageEnvelope: MessageEnvelope): Int {
        val relays = relaySelectionPolicy.selectRelays(targetDevice)
        if (relays.isEmpty()) return 0
        val now = ctx.clock.now()
        val lifetime = ctx.routerConfig.value.binaryEnvelopeLifetime
        relays.forEach { relay ->
            val relayEnvelope = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.MESSAGE,
                dispositionRequested = true,
                createdAt = now,
                expiresAt = now + lifetime,
                source = ctx.localDeviceId,
                target = relay,
                payload = messageEnvelope.encode(),
            )
            outboxProcessor.enqueueAndWake(relayEnvelope, nextRetryAt = now)
        }
        return relays.size
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
                AppLog.error(
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
                AppLog.warn(
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
