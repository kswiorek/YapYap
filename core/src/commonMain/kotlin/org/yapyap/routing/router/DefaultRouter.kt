package org.yapyap.routing.router

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketIdAllocator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.inbound.AckResponder
import org.yapyap.routing.inbound.InboundEnvelopeProcessor
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.inbound.handlers.MessageInboundHandler
import org.yapyap.routing.inbound.handlers.OutboxChangeNotifier
import org.yapyap.routing.inbound.handlers.SignalInboundHandler
import org.yapyap.routing.inbound.handlers.SystemInboundHandler
import org.yapyap.routing.outbox.OutboxRetryLoop
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.time.SystemEpochSecondsProvider
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import org.yapyap.transport.webrtc.types.WebRtcSignal
import kotlin.coroutines.cancellation.CancellationException

class DefaultRouter(
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val identityResolver: IdentityResolver,
    val packetIdAllocator: PacketIdAllocator,
    val packetDeduplicator: PacketDeduplicator,
    val packetOutbox: PacketOutbox,
    val envelopeProtectionService: EnvelopeProtectionService,
    val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
    val logger: AppLogger,
    val routerConfig: RouterConfig,
    val transportPolicy: OutboundPolicy = SessionOrTorPolicy(routerConfig),
): Router {
    private val routingContext = RoutingContext(
        identityResolver = identityResolver,
        packetIdAllocator = packetIdAllocator,
        packetDeduplicator = packetDeduplicator,
        envelopeProtectionService = envelopeProtectionService,
        torTransport = torTransport,
        webRtcTransport = webRtcTransport,
        timeProvider = timeProvider,
        logger = logger,
        routerConfig = routerConfig,
    )
    private val envelopeDispatcher = EnvelopeDispatcher(routingContext)
    private val ackResponder = AckResponder(routingContext, envelopeDispatcher)
    private val incomingMessageFlow = MutableSharedFlow<MessagePayload>(replay = 1, extraBufferCapacity = 64)
    private val outboxChangeNotifier = object : OutboxChangeNotifier {
        private var notifier: OutboxChangeNotifier? = null
        fun bind(notifier: OutboxChangeNotifier) { this.notifier = notifier }
        override fun notifyChanged() { notifier?.notifyChanged() }
    }
    private val inboundEnvelopeProcessor = InboundEnvelopeProcessor(
        ctx = routingContext,
        ackResponder = ackResponder,
        handlers = mapOf(
            PacketType.MESSAGE to MessageInboundHandler(routingContext, incomingMessageFlow),
            PacketType.SIGNAL to SignalInboundHandler(routingContext),
            PacketType.FILE to FileInboundHandler(),
        ),
        systemHandler = SystemInboundHandler(
            ctx = routingContext,
            packetOutbox = packetOutbox,
            outboxChangeNotifier = outboxChangeNotifier,
        ),
    )

    private var started = false
    private var torEndpoint: TorEndpoint? = null
    private var localDeviceIdentity: DeviceIdentityRecord? = null

    private var scope: CoroutineScope? = null
    private var torIncomingJob: Job? = null
    private var webRtcIncomingEnvelopeJob: Job? = null
    private var webRtcOutgoingJob: Job? = null
    private var webRtcSessionJob: Job? = null
    private lateinit var outboxRetryLoop: OutboxRetryLoop
    private var outboxRetryJob: Job? = null

    override val incomingMessages: Flow<MessagePayload> = incomingMessageFlow.asSharedFlow()

    override suspend fun start() {
        check(!started) { "Router is already started" }
        localDeviceIdentity = identityResolver.getLocalDeviceIdentityRecord()
        routingContext.localDeviceIdentity = localDeviceIdentity!!
        packetIdAllocator.assignLocalDevice(localDeviceIdentity!!.deviceId)

        try {
            torEndpoint = torTransport.start()
            webRtcTransport.start(localDeviceIdentity!!.deviceId)
        }
        catch (e: Exception) {
            webRtcTransport.stop()
            torTransport.stop()
            throw e
        }
        checkNotNull(torEndpoint) { "Tor endpoint must be initialized after starting transport" }
        identityResolver.updatePeerTorEndpoint(
            deviceId = localDeviceIdentity!!.deviceId,
            torEndpoint = torEndpoint!!,
        )

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        torIncomingJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            torTransport.incoming.collect { inbound ->
                runCatching { inboundEnvelopeProcessor.handleTorInbound(inbound) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        logger.error(
                            component = LogComponent.ROUTER,
                            event = LogEvent.ENVELOPE_HANDLE_FAILED,
                            message = "Failed to handle inbound Tor envelope",
                            fields = mapOf("error" to e.toString()),
                        )
                    }
            }
        }

        webRtcIncomingEnvelopeJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            webRtcTransport.incomingEnvelopes.collect { inbound ->
                runCatching { inboundEnvelopeProcessor.handleWebRtcInbound(inbound) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        logger.error(
                            component = LogComponent.ROUTER,
                            event = LogEvent.ENVELOPE_HANDLE_FAILED,
                            message = "Failed to handle inbound WebRTC envelope",
                            fields = mapOf("error" to e.toString()),
                        )
                    }
            }
        }

        webRtcOutgoingJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            webRtcTransport.outgoingBootstrapSignals.collect { signal ->
                runCatching { handleWebRtcBootstrapSignal(signal) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                    }
            }
        }

        webRtcSessionJob = s.launch {
            webRtcTransport.sessionStates.collect { state ->
                handleWebRtcSessionState(state)
            }
        }

        outboxRetryLoop = OutboxRetryLoop(
            outbox = packetOutbox,
            time = timeProvider,
            processDue = { processDueOutbox() },
            maxIdlePollSeconds = routerConfig.outboxMaxIdlePollSeconds,
            onProcessFailed = { error ->
                logger.error(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_PROCESS_FAILED,
                    message = "Outbox processing failed",
                    throwable = error,
                )
            },
        )
        outboxChangeNotifier.bind(OutboxChangeNotifier { outboxRetryLoop.notifyChanged() })
        outboxRetryJob = outboxRetryLoop.runIn(s)

        try {
            packetOutbox.pruneRelayOverCapacity(routerConfig.outboxMaxSizeBytes)
        }
        catch (e: Exception) {
            logger.error(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PRUNE_FAILED,
                message = "Failed to prune outbox for relay over capacity",
                throwable = e,
            )
        }

        logger.info(
            component = LogComponent.ROUTER,
            event = LogEvent.STARTED,
            message = "Router started",
            fields = mapOf("torEndpoint" to torEndpoint.toString()),
        )
        started = true

    }

    override suspend fun stop() {
        if (!started) return

        webRtcTransport.stop()
        torTransport.stop()
        torIncomingJob?.cancel()
        torIncomingJob = null

        webRtcOutgoingJob?.cancel()
        webRtcOutgoingJob = null
        webRtcIncomingEnvelopeJob?.cancel()
        webRtcIncomingEnvelopeJob = null
        webRtcSessionJob?.cancel()
        webRtcSessionJob = null
        outboxRetryJob?.cancel()
        outboxRetryJob = null
        scope?.cancel()
        scope = null

        logger.info(
            component = LogComponent.ROUTER,
            event = LogEvent.STOPPED,
            message = "Router stopped",
            fields = mapOf("torEndpoint" to torEndpoint.toString()),
        )
        started = false
    }

    override fun isRunning(): Boolean {
        return started
    }

    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): SendMessageResult {
        check(started) { "Router must be started before sending messages" }
        val peers = identityResolver.getAllPeerDevicesForAccount(target)
        if (peers.isEmpty()) {
            logger.warn(
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
        forceTransport: RouterTransport? = null,
    ): PeerSendOutcome {
        val context = EnvelopeProtectContext(
            sourceDeviceId = localDeviceIdentity!!.deviceId,
            targetDeviceId = target,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
        )

        val messageEnvelope = try {
            envelopeProtectionService.protectMessage(payload, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            return handleOutboundProtectionFailure(target, e)
        }

        val binaryEnvelope = BinaryEnvelope(
            packetId = packetIdAllocator.allocate(timeProvider.nowEpochSeconds()),
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = messageEnvelope.createdAtEpochSeconds,
            expiresAtEpochSeconds = messageEnvelope.createdAtEpochSeconds + routerConfig.messageLifetimeSeconds, // 2 days
            source = messageEnvelope.source,
            target = messageEnvelope.target,
            payload = messageEnvelope.encode(),
        )
        //TODO opening WebRTC session on demand if not exists, fallback to Tor if session cannot be established, etc

        val plan = transportPolicy.resolve(
            target = target,
            hasWebRtcSession = envelopeDispatcher.hasWebRtcSession(target),
            retries = 0,
            forced = forceTransport,
        )
        val nextRetryAt = timeProvider.nowEpochSeconds() + plan.retryDelaySeconds
        packetOutbox.enqueue(binaryEnvelope, nextRetryAt)
        outboxRetryLoop.notifyChanged()
        logger.debug(
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
            envelopeDispatcher.dispatch(binaryEnvelope, plan.transport)
        }
        catch (e: CancellationException) {throw e}
        catch (e: TransportException) {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DISPATCH_FAILED,
                message = "Envelope dispatch failed: TransportException",
                fields = mapOf(
                    "packetId" to binaryEnvelope.packetId,
                    "target" to target,
                    "transport" to plan.transport,
                    "error" to e.toString(),
                )
            )
        }
        catch (e: CryptoException) {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DISPATCH_FAILED,
                message = "Envelope dispatch failed: CryptoException",
                fields = mapOf(
                    "packetId" to binaryEnvelope.packetId,
                    "target" to target,
                    "transport" to plan.transport,
                    "error" to e.toString(),
                )
            )
        }
        finally {
            packetOutbox.recordAttempt(binaryEnvelope.packetId, nextRetryAt, timeProvider.nowEpochSeconds())
        }
        return PeerSendOutcome.Queued
    }

    private suspend fun processDueOutbox() {
        val now = timeProvider.nowEpochSeconds()
        val pruned = packetOutbox.pruneExpired(now)
        val dueEntries = packetOutbox.listDue(now)
        if (dueEntries.isNotEmpty() || pruned > 0) {
            logger.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PROCESSED,
                message = "Processing due outbox entries",
                fields = mapOf(
                    "dueCount" to dueEntries.size,
                    "prunedCount" to pruned,
                ),
            )
        }
        if (dueEntries.isNotEmpty()) {
            coroutineScope {
                dueEntries.map { entry ->
                    async { processDueOutboxEntry(entry, now) }
                }.awaitAll()
            }
        }
        outboxRetryLoop.notifyChanged()
        if (dueEntries.isNotEmpty()) {
            logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_PROCESSED,
                message = "Processed outbox for due envelopes",
                fields = mapOf("dueCount" to dueEntries.size),
            )
        }
    }

    private suspend fun processDueOutboxEntry(entry: OutboxEntry, now: Long) {
        val envelope = entry.envelope
        val outbound = transportPolicy.resolve(
            target = envelope.target,
            retries = entry.attempts,
            hasWebRtcSession = envelopeDispatcher.hasWebRtcSession(envelope.target),
        )
        val nextRetryAt = now + outbound.retryDelaySeconds
        runCatching {
            envelopeDispatcher.dispatch(envelope, outbound.transport)
        }.onSuccess {
            packetOutbox.recordAttempt(envelope.packetId, nextRetryAt, now)
            logger.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_RETRY_DISPATCHED,
                message = "Dispatched due outbox envelope",
                fields = mapOf(
                    "packetId" to envelope.packetId,
                    "packetType" to envelope.packetType,
                    "target" to envelope.target,
                    "transport" to outbound.transport,
                    "attempts" to entry.attempts + 1,
                    "nextRetryAt" to nextRetryAt,
                ),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            logger.error(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_DISPATCH_FAILED,
                message = "Failed to dispatch outbox envelope",
                throwable = error,
                fields = mapOf(
                    "packetId" to envelope.packetId,
                    "target" to envelope.target,
                    "transport" to outbound.transport,
                    "attempts" to entry.attempts,
                    "nextRetryAt" to nextRetryAt,
                ),
            )
            packetOutbox.recordAttempt(envelope.packetId, nextRetryAt, now)
        }
    }

    private fun handleWebRtcSessionState(state: WebRtcSessionState) {
        val phase = state.phase
        if (phase == WebRtcSessionPhase.CONNECTED) {
            val now = timeProvider.nowEpochSeconds()
            packetOutbox.setDueForTarget(state.peerId, now)
            outboxRetryLoop.notifyChanged()
            logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_WEBRTC_DUE_SET,
                message = "WebRTC session connected; accelerated outbox retries for peer",
                fields = mapOf(
                    "peerId" to state.peerId,
                    "sessionId" to state.sessionId,
                    "nextRetryAt" to now,
                ),
            )
        }
    }

    suspend fun testOpenWebRtcSession(target: PeerId, sessionId: String) {
        webRtcTransport.openSession(target, sessionId)
    }

    suspend fun testCloseWebRtcSession(sessionId: String) {
        webRtcTransport.closeSession(sessionId)
    }

    private suspend fun handleWebRtcBootstrapSignal(signal: WebRtcSignal) {
        val context = EnvelopeProtectContext(
            sourceDeviceId = localDeviceIdentity!!.deviceId,
            targetDeviceId = signal.target,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val envelope = envelopeProtectionService.protectSignal(signal, context)

        torTransport.send(
            target = identityResolver.resolveTorEndpointForDevice(signal.target),
            envelope = BinaryEnvelope(
                packetId = packetIdAllocator.allocate(timeProvider.nowEpochSeconds()),
                packetType = PacketType.SIGNAL,
                createdAtEpochSeconds = context.createdAtEpochSeconds,
                expiresAtEpochSeconds = context.createdAtEpochSeconds + 600,
                source = localDeviceIdentity!!.deviceId,
                target = signal.target,
                payload = envelope.encode(),
            )
        )
    }

    private fun handleOutboundProtectionFailure(
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
                logger.error(
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
                logger.warn(
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