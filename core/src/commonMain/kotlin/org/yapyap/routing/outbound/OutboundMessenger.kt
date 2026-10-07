package org.yapyap.routing.outbound

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

internal class OutboundMessenger(
    private val ctx: RoutingContext,
    private val outboxProcessor: OutboxProcessor,
    private val sessionOpener: ProactiveSessionOpener,
    private val relaySelectionPolicy: RelaySelectionPolicy,
) {
    suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
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
        val deferred = outcomes.count { it is PeerSendOutcome.Deferred }
        val permanent = outcomes.count { it is PeerSendOutcome.PermanentFailure }

        val status = when (queuedDevices) {
            deviceCount -> SendMessageStatus.SUCCESS
            0 -> SendMessageStatus.FAILURE
            else -> SendMessageStatus.PARTIAL
        }

        val failureKind = when (status) {
            SendMessageStatus.SUCCESS -> null
            SendMessageStatus.FAILURE -> when {
                deferred == deviceCount -> SendFailureKind.DEFERRED
                permanent == deviceCount -> SendFailureKind.PERMANENT
                else -> SendFailureKind.MIXED
            }

            SendMessageStatus.PARTIAL -> when {
                permanent > 0 -> SendFailureKind.MIXED
                deferred > 0 -> SendFailureKind.DEFERRED
                else -> SendFailureKind.MIXED
            }
        }
        //TODO: [Finishing touches] more complete statistics for the gui
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
        supplementRelays: Boolean = true,
    ): PeerSendOutcome {
        // Pre-warm the transport even when protection below fails: a deferred message still
        // benefits from a WebRTC session that is opening while its crypto session lands.
        sessionOpener.ensureSession(target)

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

        // Single dispatch path: enqueue due immediately; the RetryLoop owns dispatch and
        // attempt recording. (The old direct-dispatch path and its finally-recordSendAttempt
        // are gone — transport selection happens in the outbox loop, per attempt.)
        val nextRetryAt = ctx.clock.now()
        outboxProcessor.enqueueAndWake(binaryEnvelope, nextRetryAt)
        AppLog.debug(
            component = LogComponent.ROUTER,
            event = LogEvent.OUTBOX_MESSAGE_QUEUED,
            message = "Queued outbound message in outbox",
            fields = mapOf(
                "packetId" to binaryEnvelope.packetId,
                "target" to target,
                "nextRetryAt" to nextRetryAt,
            ),
        )

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

            // No staging: the payload stays in the local DAG and delivery falls back
            // to the pull path (ping frontiers + sync). The DEFERRED failure kind tells
            // the caller (and later the GUI) the message is pending, not dead.
            ProtectionDisposition.DEFER -> {
                AppLog.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                    message = "Message protection deferred; delivery falls back to sync pull",
                    fields = fields + ("error" to exception.message),
                )
                PeerSendOutcome.Deferred
            }
        }
    }
}
