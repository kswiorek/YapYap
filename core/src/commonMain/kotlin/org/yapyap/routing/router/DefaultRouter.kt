package org.yapyap.routing.router

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
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
import org.yapyap.protocol.envelopes.*
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.outbox.OutboxRetryLoop
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.time.SystemEpochSecondsProvider
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcIncomingEnvelope
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
    private var started = false
    private var torEndpoint: TorEndpoint? = null
    private var localDeviceIdentity: DeviceIdentityRecord? = null

    private val incomingMessageFlow = MutableSharedFlow<MessagePayload>(replay = 1, extraBufferCapacity = 64)
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
                runCatching { handleTorInboundEnvelope(inbound) }
                    .onFailure {e ->
                        if (e is CancellationException) throw e
                        logger.error(
                        component = LogComponent.ROUTER,
                        event = LogEvent.ENVELOPE_HANDLE_FAILED,
                        message = "Failed to handle inbound Tor envelope",
                        fields = mapOf("error" to e.toString()),
                    ) }
            }
        }

        webRtcIncomingEnvelopeJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            webRtcTransport.incomingEnvelopes.collect { inbound ->
                runCatching { handleWebRtcInboundEnvelope(inbound) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
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

    override suspend fun sendMessage(target: AccountId, payload: MessagePayload, forceTransport: RouterTransport?) {
        val peers = identityResolver.getAllPeerDevicesForAccount(target)
        if (peers.isEmpty()) {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.MESSAGE_NO_PEERS,
                message = "No peer devices found for target account",
                fields = mapOf("targetAccountId" to target),
            )
            return
        }

        for (peer in peers) {
            sendMessageToPeer(target = peer, payload = payload, forceTransport = forceTransport)
        }
    }

    private suspend fun sendMessageToPeer(target: PeerId, payload: MessagePayload, forceTransport: RouterTransport? = null) {
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
            //TODO relay to upper layer or handle
            logOutboundProtectionFailure(
                message = "Failed to protect outbound message envelope",
                target = target,
                exception = e,
            )
            return
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
            hasWebRtcSession = webRtcTransport.getSessionForPeer(target) != null,
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
        dispatchEnvelope(binaryEnvelope, plan.transport)
    }

    private suspend fun dispatchEnvelope(
        envelope: BinaryEnvelope,
        transport: RouterTransport,
    ) {
        when (transport) {
            RouterTransport.TOR -> torTransport.send(
                identityResolver.resolveTorEndpointForDevice(envelope.target),
                envelope,
            )
            RouterTransport.WEBRTC -> {
                val session = webRtcTransport.getSessionForPeer(envelope.target)
                checkNotNull(session) { "No WebRTC session found for target ${envelope.target}" }
                webRtcTransport.sendEnvelope(
                    sessionId = session,
                    targetId = envelope.target,
                    envelope = envelope,
                )
            }
        }
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
        for (entry in dueEntries) {
            val envelope = entry.envelope
            val outbound = transportPolicy.resolve(
                target = envelope.target,
                retries = entry.attempts,
                hasWebRtcSession = webRtcTransport.getSessionForPeer(envelope.target) != null,
            )
            val nextRetryAt = now + outbound.retryDelaySeconds
            runCatching {
                dispatchEnvelope(envelope, outbound.transport)
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

    private suspend fun handleWebRtcInboundEnvelope(inbound: WebRtcIncomingEnvelope) {
        runCatching { handleInboundEnvelope(inbound.envelope, RouterTransport.WEBRTC) }
            .onFailure { e ->
                logger.error(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_HANDLE_FAILED,
                    message = "Failed to handle inbound WebRTC envelope",
                    fields = mapOf("error" to e.toString()),
                )
            }
    }

    private suspend fun handleTorInboundEnvelope(inbound: TorIncomingEnvelope) {
        if (inbound.source != identityResolver.resolveTorEndpointForDevice(inbound.envelope.source)) {
            identityResolver.updatePeerTorEndpoint(
                deviceId = inbound.envelope.source,
                torEndpoint = inbound.source,
            )
        }
        runCatching { handleInboundEnvelope(inbound.envelope, RouterTransport.TOR) }
            .onFailure { e ->
                logger.error(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_HANDLE_FAILED,
                    message = "Failed to handle inbound Tor envelope",
                    fields = mapOf("error" to e.toString()),
                )
            }
    }

    private suspend fun handleInboundEnvelope(inbound: BinaryEnvelope, transport: RouterTransport) {
        val receivedAtEpochSeconds = timeProvider.nowEpochSeconds()

        // DUPLICATE HANDLING
        if (!packetDeduplicator.firstSeen(
                packetId = inbound.packetId,
                sourceDeviceId = inbound.source,
                receivedAtEpochSeconds = receivedAtEpochSeconds,
            )
        ) {
            logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.PACKET_DUPLICATED,
                message = "Packet ignored due to duplicate",
                fields = mapOf(
                    "packetId" to inbound.packetId,
                    "packetType" to inbound.packetType,
                    "sourceDeviceId" to inbound.source,
                    "receivedAtEpochSeconds" to receivedAtEpochSeconds,
                ),
            )
            when (inbound.packetType) {
                PacketType.SYSTEM -> return
                else -> sendDispositionForDuplicate(inbound, transport, packetDeduplicator.getNackReason(inbound.packetId, inbound.source))
            }
            return
        }

        // EXPIRED HANDLING
        if (inbound.expiresAtEpochSeconds < receivedAtEpochSeconds) {
            logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_EXPIRED,
                message = "Envelope expired",
                fields = mapOf(
                    "expiresAtEpochSeconds" to inbound.expiresAtEpochSeconds,
                    "receivedAtEpochSeconds" to receivedAtEpochSeconds,
                ),
            )
            sendNack(inbound.packetId, inbound.source, inbound.packetType, PacketNackReason.EXPIRED, transport)
            return
        }

        // TARGET HANDLING
        if (inbound.target != localDeviceIdentity?.deviceId) {
            logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Envelope ignored due to target mismatch",
                fields = mapOf(
                    "sourceDeviceId" to inbound.source,
                    "targetDeviceId" to inbound.target,
                    "localDeviceId" to localDeviceIdentity?.deviceId,
                ),
            )
            sendNack(inbound.packetId, inbound.source, inbound.packetType, PacketNackReason.WRONG_TARGET, transport)
            return
        }
        var handleResult: InboundHandleResult

        when (inbound.packetType) {
            PacketType.SIGNAL -> handleResult = handleSignalEnvelope(inbound)
            PacketType.FILE -> handleResult = handleFileEnvelope(inbound)
            PacketType.MESSAGE -> handleResult = handleMessageEnvelope(inbound)
            PacketType.SYSTEM -> {
                handleSystemEnvelope(inbound)
                return
            }
            else -> {
                logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_UNKNOWN_TYPE,
                message = "Envelope ignored due to unknown packet type",
                fields = mapOf(
                    "packetType" to inbound.packetType,
                    ),
                )
                handleResult = InboundHandleResult.Rejected(PacketNackReason.UNSUPPORTED_TYPE)
            }
        }
        //TODO better ACK logic: update device state in db, etc

        when (handleResult) {
            InboundHandleResult.Success -> sendAck(inbound.packetId, inbound.source, inbound.packetType, transport)
            InboundHandleResult.Deferred -> logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Deferred inbound envelope until session prerequisites are met",
                fields = mapOf(
                    "packetId" to inbound.packetId,
                    "packetType" to inbound.packetType,
                    "sourceDeviceId" to inbound.source,
                ),
            )
            is InboundHandleResult.Rejected -> sendNack(
                inbound.packetId,
                inbound.source,
                inbound.packetType,
                handleResult.reason,
                transport,
            )
        }
    }

    private suspend fun sendDispositionForDuplicate(inbound: BinaryEnvelope, transport: RouterTransport, nackReason: PacketNackReason?) {
        // if correct target and not expired, send ack, else nack
        if (nackReason == null) {
            sendAck(inbound.packetId, inbound.source, inbound.packetType, transport)
        }
        else {
            sendNack(inbound.packetId, inbound.source, inbound.packetType, nackReason, transport, persistReason = false)
        }
    }

    private suspend fun sendAck(packetId: PacketId, source: PeerId, packetType: PacketType, transport: RouterTransport) {
        val ackContext = EnvelopeProtectContext(
            sourceDeviceId = localDeviceIdentity!!.deviceId,
            targetDeviceId = source,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val ackPayload = SystemPayload.PacketAck(
            packetId,
            packetType
        )
        sendAckEnvelope(ackPayload, transport, ackContext)
        logger.info(
            LogComponent.ROUTER,
            LogEvent.ACK_SENT,
            "ACK sent for packet $packetId",
            mapOf(
                "packetId" to packetId,
                "packetType" to packetType,
                "source" to source,
                "transport" to transport,
            )
        )
    }

    private suspend fun sendNack(packetId: PacketId, source: PeerId, packetType: PacketType, reason: PacketNackReason,
                                 transport: RouterTransport, persistReason: Boolean = true, reasonText: String? = null) {
        if (persistReason){
            packetDeduplicator.markNacked(packetId, source, reason)
        }

        val ackContext = EnvelopeProtectContext(
            sourceDeviceId = localDeviceIdentity!!.deviceId,
            targetDeviceId = source,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
        )
        val ackPayload = SystemPayload.PacketNack(
            packetId,
            packetType,
            reason,
            reasonText = reasonText,
        )
        sendAckEnvelope(ackPayload, transport, ackContext)
        logger.info(
            LogComponent.ROUTER,
            LogEvent.NACK_SENT,
            "NACK sent for packet $packetId",
            mapOf(
                "packetId" to packetId,
                "packetType" to packetType,
                "source" to source,
                "transport" to transport,
                "reason" to reason,
            )
        )
    }

    private suspend fun sendAckEnvelope(ackPayload: SystemPayload, transport: RouterTransport, context: EnvelopeProtectContext){
        val ackEnvelope = envelopeProtectionService.protectSystem(ackPayload, context)

        val ackEnv = BinaryEnvelope(
            packetId = packetIdAllocator.allocate(timeProvider.nowEpochSeconds()),
            packetType = PacketType.SYSTEM,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            expiresAtEpochSeconds = timeProvider.nowEpochSeconds() + routerConfig.ackLifetimeSeconds, // 1 hour
            source = localDeviceIdentity!!.deviceId,
            target = context.targetDeviceId,
            payload = ackEnvelope.encode(),
        )

        dispatchEnvelope(ackEnv, transport)
    }

    suspend fun testOpenWebRtcSession(target: PeerId, sessionId: String) {
        webRtcTransport.openSession(target, sessionId)
    }

    suspend fun testCloseWebRtcSession(sessionId: String) {
        webRtcTransport.closeSession(sessionId)
    }

    private suspend fun handleSignalEnvelope(env: BinaryEnvelope): InboundHandleResult {
        val signalEnvelope = runCatching { WebRtcSignalEnvelope.decode(env.payload) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode signal envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }
        if (signalEnvelope.target != localDeviceIdentity?.deviceId) {
            logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Signal envelope ignored due to target mismatch",
                fields = mapOf(
                    "sourceDeviceId" to signalEnvelope.source,
                    "targetDeviceId" to signalEnvelope.target,
                    "localDeviceId" to localDeviceIdentity?.deviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.WRONG_TARGET)
        }
        val signal = try {
            envelopeProtectionService.openSignal(signalEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open signal envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        webRtcTransport.handleBootstrapSignal(signal)
        return InboundHandleResult.Success
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

    private suspend fun handleFileEnvelope(env: BinaryEnvelope): InboundHandleResult {
        // TODO
        return InboundHandleResult.Success
    }

    private suspend fun handleMessageEnvelope(env: BinaryEnvelope): InboundHandleResult {
        //TODO validate envelope fields before decryption to avoid expensive operations on invalid envelopes
        //TODO check decryption
        val messageEnvelope = runCatching { MessageEnvelope.decode(env.payload) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }

        if (messageEnvelope.target != localDeviceIdentity?.deviceId) {
            logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Message envelope received for peer ${messageEnvelope.target}",
                fields = mapOf(
                    "sourceDeviceId" to messageEnvelope.source,
                    "targetDeviceId" to messageEnvelope.target,
                    "localDeviceId" to localDeviceIdentity?.deviceId,
                ),
            )
            //TODO relay logic
            return InboundHandleResult.Success
        }

        val payload = try {
            envelopeProtectionService.openMessage(messageEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open message envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }
        incomingMessageFlow.emit(payload)
        return InboundHandleResult.Success
    }

    private suspend fun handleSystemEnvelope(env: BinaryEnvelope) {
        val systemEnvelope = runCatching { SystemEnvelope.decode(env.payload) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return
        }

        if (systemEnvelope.target != localDeviceIdentity?.deviceId) {
            logger.error(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "System envelope received for peer ${systemEnvelope.target}",
                fields = mapOf(
                    "sourceDeviceId" to systemEnvelope.source,
                    "targetDeviceId" to systemEnvelope.target,
                    "localDeviceId" to localDeviceIdentity?.deviceId,
                ),
            )
            return
        }

        val payload = try {
            envelopeProtectionService.openSystem(systemEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open system envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return
        }

        when (payload) {
            is SystemPayload.PacketAck -> {
                packetOutbox.markDelivered(payload.packetId)
                outboxRetryLoop.notifyChanged()
                logger.debug(
                    component = LogComponent.ROUTER,
                    event = LogEvent.OUTBOX_ACK_RECEIVED,
                    message = "Removed acknowledged packet from outbox",
                    fields = mapOf(
                        "packetId" to payload.packetId,
                        "packetType" to payload.packetType,
                        "source" to systemEnvelope.source,
                    ),
                )
            }
            is SystemPayload.PacketNack -> {
                when (payload.reason) {
                    PacketNackReason.EXPIRED -> {
                        packetOutbox.markDelivered(payload.packetId)
                        outboxRetryLoop.notifyChanged()
                        logger.info(
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
                    }
                    PacketNackReason.PROTECTION_FAILED -> {
                        //Possibly message corrupted, retry
                        logger.warn(
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
                    }
                    else -> {
                        logger.debug(
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
                        // keep retrying
                        // TODO add logic
                    }
                }
            }
            //TODO send message on ping
        }
    }

    private fun logInboundProtectionFailure(
        message: String,
        packetId: PacketId,
        source: PeerId,
        exception: ProtectionException,
    ) {
        logger.warn(
            component = LogComponent.ROUTER,
            event = LogEvent.ENVELOPE_PROTECTION_FAILED,
            message = message,
            fields = mapOf(
                "packetId" to packetId,
                "sourceDeviceId" to source,
                "disposition" to exception.disposition.name,
                "reason" to exception.reason.name,
                "error" to exception.message,
            ),
        )
    }

    private fun logOutboundProtectionFailure(
        message: String,
        target: PeerId,
        exception: ProtectionException,
    ) {
        val fields = mapOf(
            "targetDeviceId" to target,
            "disposition" to exception.disposition.name,
            "reason" to exception.reason.name,
        )
        when (exception.disposition) {
            ProtectionDisposition.PERMANENT -> logger.error(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = message,
                throwable = exception,
                fields = fields,
            )
            ProtectionDisposition.RETRYABLE,
            ProtectionDisposition.DEFER,
            -> logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = message,
                fields = fields + ("error" to exception.message),
            )
        }
    }

    private fun inboundResultForProtectionFailure(ex: ProtectionException): InboundHandleResult =
        when (ex.disposition) {
            ProtectionDisposition.DEFER -> InboundHandleResult.Deferred
            ProtectionDisposition.PERMANENT -> InboundHandleResult.Rejected(
                if (ex is ProtectionException.InvalidEnvelope) {
                    PacketNackReason.DECODE_FAILED
                } else {
                    PacketNackReason.PROTECTION_FAILED
                },
            )
            ProtectionDisposition.RETRYABLE -> InboundHandleResult.Rejected(PacketNackReason.PROTECTION_FAILED)
        }
}