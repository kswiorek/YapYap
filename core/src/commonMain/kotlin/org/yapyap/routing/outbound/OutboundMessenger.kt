package org.yapyap.routing.outbound

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.packet.OutboxTag
import org.yapyap.persistence.packet.RelayBackup
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.AccountPushReport
import org.yapyap.routing.router.PeerSendOutcome
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

internal class OutboundMessenger(
    private val ctx: RoutingContext,
    private val outboxProcessor: OutboxProcessor,
    private val sessionOpener: ProactiveSessionOpener,
) {
    suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
    ): AccountPushReport {
        val peers = ctx.identityResolver.getAllPeerDevicesForAccount(target)
            .filter { it != ctx.localDeviceId }   // skip originating device only
        if (peers.isEmpty()) {
            if (target == ctx.identityResolver.getLocalAccountId()) {
                // Own account, no other devices: the steady state for single-device
                // users — the pull path converges own devices regardless. Quiet by
                // design, never a failure.
                AppLog.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.MESSAGE_NO_PEERS,
                    message = "Own account has no other devices; delivery falls back to sync pull",
                    fields = mapOf("targetAccountId" to target),
                )
            } else {
                AppLog.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.MESSAGE_NO_PEERS,
                    message = "No peer devices found for target account",
                    fields = mapOf("targetAccountId" to target),
                )
            }
            return AccountPushReport(
                devicesTotal = 0,
                devicesQueued = 0,
                devicesDeferred = 0,
                devicesFailed = 0,
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

    private fun aggregateSendResults(outcomes: List<PeerSendOutcome>): AccountPushReport {
        val queued = outcomes.count { it is PeerSendOutcome.Queued }
        val deferred = outcomes.count { it is PeerSendOutcome.Deferred }
        val failed = outcomes.count { it is PeerSendOutcome.PermanentFailure }
        val relaysDeposited = outcomes.sumOf { (it as? PeerSendOutcome.Queued)?.relaysDeposited ?: 0 }
        if (relaysDeposited > 0) {
            // Router-internal delivery mechanism: diagnostics only, never a destination count.
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                message = "Relay deposits enqueued alongside direct delivery",
                fields = mapOf("relaysDeposited" to relaysDeposited),
            )
        }
        return AccountPushReport(
            devicesTotal = outcomes.size,
            devicesQueued = queued,
            devicesDeferred = deferred,
            devicesFailed = failed,
        )
    }

    internal suspend fun sendMessageToPeer(
        target: PeerId,
        payload: MessagePayload,
        supplementRelays: Boolean = true,
    ): PeerSendOutcome {
        // Pre-warm a WebRTC session even when protection below fails: a deferred message still
        // benefits from a session that is opening while its crypto session lands.
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
        outboxProcessor.enqueueAndWake(
            binaryEnvelope,
            nextRetryAt,
            relayBackup = if (supplementRelays) RelayBackup.PENDING else RelayBackup.NONE,
            tag = OutboxTag(OutboxTag.Kind.MESSAGE, payload.messageId),
        )
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

        // With an open link the message goes direct and relays carry nothing unless it goes
        // unacknowledged (the outbox backs it up then). With none, back it up now: relays hold
        // a copy and forward it once the recipient surfaces, sender online or not.
        val relaysDeposited = if (supplementRelays && ctx.peerLinks.linksOf(target).isEmpty()) {
            outboxProcessor.backUpToRelays(binaryEnvelope, reason = "no link")
        } else {
            0
        }
        return PeerSendOutcome.Queued(relaysDeposited)
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
