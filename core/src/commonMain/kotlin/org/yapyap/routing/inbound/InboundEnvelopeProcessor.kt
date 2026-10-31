package org.yapyap.routing.inbound

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.yapyap.crypto.CryptoException
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.ping.PingProvider
import org.yapyap.routing.policy.NackAction
import org.yapyap.routing.policy.nackActionFor
import org.yapyap.routing.router.*
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.tor.TorLinkId
import org.yapyap.transport.webrtc.transport.WebRtcIncomingEnvelope
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

internal class InboundEnvelopeProcessor(
    private val ctx: RoutingContext,
    private val systemSender: SystemSender,
    private val handlers: Map<PacketType, InboundEnvelopeHandler>,
    private val outboxProcessor: OutboxProcessor,
    private val syncHandler: SyncHandler,
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
    private val pingProvider: PingProvider,
    /** Our bootstrap packets acknowledged by a not-yet-known device (see [handleUnknownSourceDisposition]). */
    private val bootstrapDeliveries: MutableSharedFlow<Uuid> = MutableSharedFlow(extraBufferCapacity = 16),
) {
    /**
     * Where ACK/NACK sends run while the router is started; null otherwise (sends then
     * run inline — tests drive the processor directly). A disposition usually goes back on
     * the stream its packet arrived on, but without one it is a full Tor dial back to the
     * sender, and inbound packets are handled one at a time: sent inline, one slow dial to a
     * peer held every packet behind it — observed as a burst of 35 messages stuck behind the
     * first one's ACK until the app was closed.
     */
    @Volatile
    private var dispositionScope: CoroutineScope? = null
    private var dispositionPermits = Semaphore(ctx.routerConfig.value.dispositionMaxInFlight)

    fun startDispositions(parent: CoroutineScope) {
        dispositionPermits = Semaphore(ctx.routerConfig.value.dispositionMaxInFlight)
        dispositionScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }

    fun stopDispositions() {
        dispositionScope?.cancel()
        dispositionScope = null
    }

    suspend fun handleTorInbound(inbound: TorIncomingEnvelope) {
        // No endpoint reconciliation here: the claimed source is unauthenticated at this
        // layer, so the stored mapping is healed only after the packet authenticates
        // (see reconcileSourceEndpoint). Unknown sources need no mapping — their
        // endpoints arrive via authenticated channels (see endpoint-claim policy there).
        handle(
            inbound.envelope,
            RouterTransport.TOR,
            provenSourceEndpoint = inbound.source,
            arrivalLink = inbound.link,
        )
    }

    suspend fun handleWebRtcInbound(inbound: WebRtcIncomingEnvelope) {
        handle(inbound.envelope, RouterTransport.WEBRTC)
    }

    /**
     * @param provenSourceEndpoint transport-proven source endpoint (Tor only): the onion the
     *   packet actually arrived from, as opposed to anything claimed inside the payload. Used
     *   for dispositions back to sources with no local devices row (bootstrap newcomer /
     *   recovering device) without a DB lookup, and — once the packet authenticates — to
     *   heal the source's stored mapping (Tor onion rotation). It is preferred over the
     *   stored row because it is the freshest transport evidence available.
     * @param arrivalLink the Tor stream the packet arrived on (null: one-shot frame, or
     *   WebRTC). Dispositions are answered on it, and an authenticated ping on it makes it
     *   the sender's Tor link (see [adoptArrivalLink]).
     */
    suspend fun handle(
        inbound: BinaryEnvelope,
        transport: RouterTransport,
        provenSourceEndpoint: TorEndpoint? = null,
        arrivalLink: TorLinkId? = null,
    ) {

        // Unknown-device policy [Sprint 4]: a source with no devices row is untrusted by
        // default — its packets are dropped silently (no ACK/NACK: answering would confirm
        // this onion serves that device id to an unauthenticated stranger). The single
        // exception is BOOTSTRAP: INTRO/RECOVERY_REQUEST carry their own out-of-band
        // authentication (preshared-secret AEAD / account-key signature, enforced inside
        // BootstrapInboundHandler), and onboarding/recovery cannot work any other way —
        // the newcomer/recovering device has no row yet by construction. BANNED always
        // wins, even for BOOTSTRAP (re-entry attempts are answered DECLINED by the
        // handler's own account-status policy).
        val deviceStatus = ctx.identityResolver.getDeviceStatusOrNull(inbound.source)
        if (deviceStatus == IdentityStatus.BANNED) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.BANNED_DEVICE_PACKET,
                message = "Inbound from banned device; skipped processing",
                fields = mapOf("sourceDeviceId" to inbound.source),
            )
            return
        }
        val knownDevice = deviceStatus != null
        if (!knownDevice && inbound.packetType == PacketType.SYSTEM && handleUnknownSourceDisposition(inbound)) {
            return
        }
        if (!knownDevice && inbound.packetType != PacketType.BOOTSTRAP) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Inbound from unknown device; skipped processing",
                fields = mapOf(
                    "sourceDeviceId" to inbound.source,
                    "packetType" to inbound.packetType,
                ),
            )
            return
        }

        val receivedAt = ctx.clock.now()
        // Unknown sources must not enter availability math: a newcomer/recovering device
        // is not a mesh peer yet, and scanner noise must not pollute swarm selection.
        if (knownDevice) {
            peerAvailabilityRegistry.markReachable(inbound.source, receivedAt)
        }

        if (!ctx.packetDeduplicator.firstSeen(
                packetId = inbound.packetId,
                sourceDeviceId = inbound.source,
                receivedAt = receivedAt,
            )
        ) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.PACKET_DUPLICATED,
                message = "Packet ignored due to duplicate",
                fields = mapOf(
                    "packetId" to inbound.packetId,
                    "packetType" to inbound.packetType,
                    "sourceDeviceId" to inbound.source,
                    "receivedAt" to receivedAt,
                ),
            )
            if (inbound.dispositionRequested) {
                sendDispositionBestEffort("duplicate") {
                    systemSender.sendDispositionForDuplicate(
                        inbound,
                        transport,
                        ctx.packetDeduplicator.getNackReason(inbound.packetId, inbound.source),
                        endpointOverride = provenSourceEndpoint,
                        torReplyLink = arrivalLink,
                    )
                }
            }
            return
        }

        if (inbound.expiresAt < ctx.clock.now()) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_EXPIRED,
                message = "Envelope expired",
                fields = mapOf(
                    "expiresAt" to inbound.expiresAt,
                    "receivedAt" to receivedAt,
                ),
            )
            if (inbound.dispositionRequested) {
                sendDispositionBestEffort("expired") {
                    systemSender.sendNack(
                        inbound.packetId,
                        inbound.source,
                        inbound.packetType,
                        PacketNackReason.EXPIRED,
                        transport,
                        endpointOverride = provenSourceEndpoint,
                        torReplyLink = arrivalLink,
                    )
                }
            }
            return
        }

        if (inbound.target != ctx.localDeviceId) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Envelope ignored due to target mismatch",
                fields = mapOf(
                    "sourceDeviceId" to inbound.source,
                    "targetDeviceId" to inbound.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            if (inbound.dispositionRequested) {
                sendDispositionBestEffort("wrong-target") {
                    systemSender.sendNack(
                        inbound.packetId,
                        inbound.source,
                        inbound.packetType,
                        PacketNackReason.WRONG_TARGET,
                        transport,
                        endpointOverride = provenSourceEndpoint,
                        torReplyLink = arrivalLink,
                    )
                }
            }
            return
        }

        val handler = handlers[inbound.packetType]
        val handleResult = if (handler != null) {
            handler.handle(inbound)
        } else {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_UNKNOWN_TYPE,
                message = "Envelope ignored due to unknown packet type",
                fields = mapOf(
                    "packetType" to inbound.packetType,
                ),
            )
            InboundHandleResult.Rejected(PacketNackReason.UNSUPPORTED_TYPE)
        }

        // Before the side effects: a ping's echo (a side effect) should already go out on the
        // link adopted here.
        if (handleResult is InboundHandleResult.Success
            && handleResult.sourceAuthenticated
            && provenSourceEndpoint != null
        ) {
            reconcileSourceEndpoint(inbound.source, provenSourceEndpoint)
            if (arrivalLink != null) adoptArrivalLink(inbound.source, handleResult, arrivalLink, provenSourceEndpoint)
        }

        applySideEffects(handleResult.sideEffects)

        when (handleResult) {
            is InboundHandleResult.Success ->
                if (inbound.dispositionRequested) {
                    sendDispositionBestEffort("ack") {
                        systemSender.sendAck(
                            inbound.packetId,
                            inbound.source,
                            inbound.packetType,
                            transport,
                            endpointOverride = provenSourceEndpoint,
                            torReplyLink = arrivalLink,
                        )
                    }
                }

            is InboundHandleResult.Deferred -> {
                AppLog.info(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                    message = "Deferred inbound envelope until session prerequisites are met",
                    fields = mapOf(
                        "packetId" to inbound.packetId,
                        "packetType" to inbound.packetType,
                        "sourceDeviceId" to inbound.source,
                    ),
                )
                ctx.packetDeduplicator.clearPacket(inbound.packetId, inbound.source)
            }

            is InboundHandleResult.Rejected ->
                if (inbound.dispositionRequested) {
                    sendDispositionBestEffort("nack") {
                        systemSender.sendNack(
                            inbound.packetId,
                            inbound.source,
                            inbound.packetType,
                            handleResult.reason,
                            transport,
                            endpointOverride = provenSourceEndpoint,
                            torReplyLink = arrivalLink,
                        )
                    }
                }
        }
    }

    /**
     * Dispositions (ACK/NACK) are best-effort by design: the inbound packet is
     * already handled and deduped at this point, so a failed disposition send
     * (peer unreachable mid-exchange) must never fail inbound processing — the
     * sender's retry + our duplicate-replay path re-ACK instead. Downgrades an
     * alarming ENVELOPE_HANDLE_FAILED into an expected transient warn.
     */
    /**
     * Sends a disposition without holding up inbound processing (see [dispositionScope]):
     * at most `dispositionMaxInFlight` at once, each bounded by `dispositionSendBudget`.
     * Best-effort by design — a lost ACK makes the sender resend, and the duplicate is
     * answered again.
     */
    private suspend fun sendDispositionBestEffort(
        what: String,
        send: suspend () -> Unit,
    ) {
        val scope = dispositionScope
        if (scope == null) {
            sendDispositionNow(what, send)
            return
        }
        val permits = dispositionPermits
        val budget = ctx.routerConfig.value.dispositionSendBudget
        scope.launch {
            permits.withPermit {
                val sent = withTimeoutOrNull(budget) { sendDispositionNow(what, send) }
                if (sent == null) {
                    AppLog.warn(
                        component = LogComponent.ROUTER,
                        event = LogEvent.OUTBOX_DISPATCH_FAILED,
                        message = "Gave up sending $what disposition within budget; sender retry will re-trigger it",
                        fields = mapOf("budget" to budget),
                    )
                }
            }
        }
    }

    private suspend fun sendDispositionNow(
        what: String,
        send: suspend () -> Unit,
    ) {
        runCatching { send() }.onFailure { error ->
            if (error is CancellationException) throw error
            if (error is kotlinx.coroutines.CancellationException) throw error
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_DISPATCH_FAILED,
                message = "Failed to send $what disposition; sender retry will re-trigger it",
                fields = mapOf("error" to error.toString()),
            )
        }
    }

    /**
     * Endpoint reconciliation [Sprint 4]: heals the source's stored Tor mapping (onion
     * rotation) from the transport-proven arrival onion — but only after the packet
     * authenticated ([InboundHandleResult.Success.sourceAuthenticated]). Rewriting on
     * arrival alone would let anyone who knows a peer's device id redirect its mapping
     * with a single spoofed packet (the packet itself would then fail authentication,
     * but the mapping would already be poisoned).
     *
     * Endpoint-claim policy [Sprint 4, decided]: mappings are never created from
     * unverified claims, so unknown devices gain no mapping here. Every flow needing
     * an unknown device's endpoint carries it through an authenticated channel
     * instead: the invite QR (out-of-band, user-scanned), the signed bootstrap
     * payload (`Intro`/`RecoveryRequest.torEndpoint`), or the transport-proven
     * disposition override for the immediate exchange.
     */
    private suspend fun reconcileSourceEndpoint(source: PeerId, provenEndpoint: TorEndpoint) {
        val knownEndpoint = try {
            ctx.identityResolver.resolveTorEndpointForDevice(source)
        } catch (_: CryptoException) {
            null
        }
        if (knownEndpoint == null) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Tor inbound from unknown device; skipped endpoint reconciliation",
                fields = mapOf("sourceDeviceId" to source),
            )
            return
        }
        ctx.peerLinks.bindTorEndpoint(source, provenEndpoint)
        if (provenEndpoint != knownEndpoint) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.PEER_ENDPOINT_HEALED,
                message = "Healed peer Tor endpoint from authenticated inbound",
                fields = mapOf(
                    "sourceDeviceId" to source,
                    "previousEndpoint" to knownEndpoint,
                    "provenEndpoint" to provenEndpoint,
                ),
            )
            ctx.identityResolver.updatePeerTorEndpoint(
                deviceId = source,
                torEndpoint = provenEndpoint,
            )
        }
    }

    /**
     * Makes the inbound Tor stream [link] the Tor link to [source] when [result] carries an
     * authenticated ping from [source] itself. A ping is never relayed (unlike messages, which
     * relays forward on their own streams), and only [source] and we ever see its pings to us,
     * so a signed ping arriving on a stream proves [source] dialed that stream. From then on
     * our own traffic to [source] can use it — no dial of our own needed.
     */
    private fun adoptArrivalLink(
        source: PeerId,
        result: InboundHandleResult.Success,
        link: TorLinkId,
        endpoint: TorEndpoint,
    ) {
        val heartbeatFromSource =
            result.sideEffects.any { it is InboundSideEffect.PeerHeartbeat && it.peerId == source }
        if (!heartbeatFromSource) return
        if (ctx.peerLinks.adoptTorLink(source, link, endpoint)) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.SESSION_STATE_CHANGED,
                message = "Peer's inbound Tor stream adopted as its link",
                fields = mapOf("peerId" to source, "endpoint" to endpoint),
            )
        }
    }

    /**
     * The one SYSTEM exception to the unknown-device policy: an ACK/NACK for a BOOTSTRAP
     * packet we sent to exactly this device. A recovering device asks a device it has no
     * row for yet, so it can't verify that device's signature on the reply; dropping the
     * reply left it re-sending the request every minute with no sign of progress.
     *
     * What authenticates the reply instead is the packet id: a random UUID only the
     * recipient of our request has seen (the Tor stream is end to end). A forged reply
     * could at worst stop our resends early, after which onboarding times out. Nothing
     * is ever sent back, so this reveals nothing to strangers. Returns false (and the
     * packet falls through to the normal drop) for anything else.
     */
    private suspend fun handleUnknownSourceDisposition(inbound: BinaryEnvelope): Boolean {
        val system = runCatching { SystemEnvelope.decode(inbound.payload) }.getOrNull() ?: return false
        if (system.source != inbound.source || system.target != ctx.localDeviceId) return false
        val payload = runCatching { system.decodePayload() }.getOrNull() ?: return false
        val (packetId, acked) = when (payload) {
            is SystemPayload.PacketAck -> payload.packetId to true
            is SystemPayload.PacketNack -> payload.packetId to false
            else -> return false
        }
        if (!outboxProcessor.isPendingBootstrapTo(inbound.source, packetId)) return false
        val remove = acked || nackActionFor((payload as SystemPayload.PacketNack).reason) == NackAction.REMOVE
        AppLog.info(
            component = LogComponent.ROUTER,
            event = if (acked) LogEvent.OUTBOX_ACK_RECEIVED else LogEvent.OUTBOX_NACK_RECEIVED,
            message = "Disposition for our bootstrap packet from a device not known yet",
            fields = mapOf(
                "packetId" to packetId,
                "sourceDeviceId" to inbound.source,
                "acked" to acked,
                "reason" to (payload as? SystemPayload.PacketNack)?.reason,
            ),
        )
        if (remove) outboxProcessor.onOutboundPacketDelivered(packetId)
        if (acked) bootstrapDeliveries.emit(packetId)
        return true
    }

    private suspend fun applySideEffects(sideEffects: List<InboundSideEffect>) {
        sideEffects.forEach { effect ->
            when (effect) {
                is InboundSideEffect.EnqueueForRelay ->
                    outboxProcessor.enqueueAndWake(
                        effect.envelope,
                        nextRetryAt = ctx.clock.now() + ctx.routerConfig.value.ackLifetime,
                        relayMessage = true,
                    )

                is InboundSideEffect.RemoveFromOutbox ->
                    outboxProcessor.onOutboundPacketDelivered(effect.packetId)

                is InboundSideEffect.SyncRequested ->
                    syncHandler.onSyncRequested(effect.sync, effect.peerId)

                is InboundSideEffect.MarkPeerAttempted ->
                    syncHandler.onMarkPeerAttempted(effect.syncId, effect.peerId)

                is InboundSideEffect.PeerHeartbeat -> pingProvider.handlePing(effect.peerId, effect.ping)
                is InboundSideEffect.PeerOffline -> peerAvailabilityRegistry.markOffline(effect.peerId)
            }
        }
    }
}
