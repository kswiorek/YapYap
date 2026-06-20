package org.yapyap.backend.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityResolver
import org.yapyap.backend.db.PacketDeduplicator
import org.yapyap.backend.db.PacketIdAllocator
import org.yapyap.backend.db.PacketOutbox
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.protection.EnvelopeProtectContext
import org.yapyap.backend.protection.EnvelopeProtectionService
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketNackReason
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.SystemEnvelope
import org.yapyap.backend.protocol.SystemPayload
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider
import org.yapyap.backend.transport.tor.TorIncomingEnvelope
import org.yapyap.backend.transport.tor.TorTransport
import org.yapyap.backend.transport.webrtc.WebRtcIncomingEnvelope
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.WebRtcTransport
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionPhase
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
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
    val cryptoProvider: CryptoProvider,
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
        )
        outboxRetryJob = outboxRetryLoop.runIn(s)

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
        //TODO Outbox logic
        val context = EnvelopeProtectContext(
            sourceDeviceId = localDeviceIdentity!!.deviceId,
            targetDeviceId = target,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.SIGNED),
        )

        val messageEnvelope = envelopeProtectionService.protectMessage(payload, context)

        val binaryEnvelope = BinaryEnvelope(
            packetId = packetIdAllocator.allocate(),
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
        packetOutbox.enqueue(binaryEnvelope, timeProvider.nowEpochSeconds() + plan.retryDelaySeconds)
        outboxRetryLoop.notifyChanged()
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
        packetOutbox.pruneExpired(now)
        for (entry in packetOutbox.listDue(now)) {
            val envelope = entry.envelope
            val outbound = transportPolicy.resolve(
                target = envelope.target,
                retries = entry.attempts,
                hasWebRtcSession = webRtcTransport.getSessionForPeer(envelope.target) != null,
            )
            dispatchEnvelope(envelope, outbound.transport)
            packetOutbox.recordAttempt(envelope.packetId, now + outbound.retryDelaySeconds)
        }
        outboxRetryLoop.notifyChanged()
        logger.info(
            LogComponent.ROUTER,
            LogEvent.OUTBOX_PROCESSED,
            "Processed outbox for due envelopes",
            mapOf("dueCount" to packetOutbox.listDue(now).size)
        )
    }

    private suspend fun handleWebRtcSessionState(state: WebRtcSessionState) {
        val phase = state.phase
        if (phase == WebRtcSessionPhase.CONNECTED) {
            packetOutbox.setDueForTarget(state.peerId, timeProvider.nowEpochSeconds())
            outboxRetryLoop.notifyChanged()
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
        var nackReason: PacketNackReason?

        when (inbound.packetType) {
            PacketType.SIGNAL -> nackReason = handleSignalEnvelope(inbound, receivedAtEpochSeconds)
            PacketType.FILE -> nackReason = handleFileEnvelope(inbound, receivedAtEpochSeconds)
            PacketType.MESSAGE -> nackReason = handleMessageEnvelope(inbound, receivedAtEpochSeconds)
            PacketType.SYSTEM -> {
                handleSystemEnvelope(inbound, receivedAtEpochSeconds)
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
                nackReason = PacketNackReason.UNSUPPORTED_TYPE
            }
        }
        //TODO better ACK logic: update device state in db, etc

        if (nackReason == null) {
            sendAck(inbound.packetId, inbound.source, inbound.packetType, transport)
        }
        else {
            sendNack(inbound.packetId, inbound.source, inbound.packetType, nackReason, transport)
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
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.SIGNED),
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
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.SIGNED),
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
            packetId = packetIdAllocator.allocate(),
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

    private suspend fun handleSignalEnvelope(env: BinaryEnvelope, receivedAtEpochSeconds: Long): PacketNackReason? {
        val signalEnvelope = runCatching { WebRtcSignalEnvelope.decode(env.payload) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode signal envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return PacketNackReason.DECODE_FAILED
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
            return PacketNackReason.WRONG_TARGET
        }
        val signal = runCatching { envelopeProtectionService.openSignal(signalEnvelope) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Failed to open signal envelope",
                fields = mapOf("error" to "protection_failed"),
            )
            return PacketNackReason.PROTECTION_FAILED
        }
        webRtcTransport.handleBootstrapSignal(signal, receivedAtEpochSeconds = receivedAtEpochSeconds)
        return null
    }

    private suspend fun handleWebRtcBootstrapSignal(signal: WebRtcSignal) {
        val context = EnvelopeProtectContext(
            sourceDeviceId = localDeviceIdentity!!.deviceId,
            targetDeviceId = signal.target,
            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
            securityScheme = SignalSecurityScheme.SIGNED,
            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.SIGNED),
        )
        val envelope = envelopeProtectionService.protectSignal(signal, context)

        torTransport.send(
            target = identityResolver.resolveTorEndpointForDevice(signal.target),
            envelope = BinaryEnvelope(
                packetId = packetIdAllocator.allocate(),
                packetType = PacketType.SIGNAL,
                createdAtEpochSeconds = context.createdAtEpochSeconds,
                expiresAtEpochSeconds = context.createdAtEpochSeconds + 600,
                source = localDeviceIdentity!!.deviceId,
                target = signal.target,
                payload = envelope.encode(),
            )
        )
    }

    private suspend fun handleFileEnvelope(env: BinaryEnvelope, receivedAtEpochSeconds: Long): PacketNackReason? {
        // TODO
        return null
    }

    private suspend fun handleMessageEnvelope(env: BinaryEnvelope, receivedAtEpochSeconds: Long): PacketNackReason? {
        //TODO validate envelope fields before decryption to avoid expensive operations on invalid envelopes
        //TODO check decryption
        val messageEnvelope = runCatching { MessageEnvelope.decode(env.payload) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode message envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return PacketNackReason.DECODE_FAILED
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
            return null
        }

        val payload = runCatching { envelopeProtectionService.openMessage(messageEnvelope) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Failed to open message envelope",
                fields = mapOf("error" to "protection_failed"),
            )
            return PacketNackReason.PROTECTION_FAILED
        }
        incomingMessageFlow.emit(payload)
        return null
    }
    private suspend fun handleSystemEnvelope(env: BinaryEnvelope, receivedAtEpochSeconds: Long) {
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

        val payload = runCatching { envelopeProtectionService.openSystem(systemEnvelope) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Failed to open system envelope",
                fields = mapOf("error" to "protection_failed"),
            )
            return
        }

        when (payload) {
            is SystemPayload.PacketAck -> {
                packetOutbox.markDelivered(payload.packetId)
                outboxRetryLoop.notifyChanged()
            }
            is SystemPayload.PacketNack -> {
                when (payload.reason) {
                    PacketNackReason.EXPIRED -> {
                        packetOutbox.markDelivered(payload.packetId)
                        outboxRetryLoop.notifyChanged()
                    }
                    else -> {
                        // keep retrying
                        // TODO add logic
                    }
                }
            }
            //TODO send message on ping
        }
    }
}