package org.yapyap.backend.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityResolver
import org.yapyap.backend.crypto.SignatureProvider
import org.yapyap.backend.db.DefaultPacketDeduplicator
import org.yapyap.backend.db.PacketIdAllocator
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.protection.EnvelopeProtectContext
import org.yapyap.backend.protection.EnvelopeProtectionService
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider
import org.yapyap.backend.transport.tor.TorTransport
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.WebRtcTransport
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import kotlin.coroutines.cancellation.CancellationException

class DefaultRouter(
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val identityResolver: IdentityResolver,
    val packetIdAllocator: PacketIdAllocator,
    val packetDeduplicator: DefaultPacketDeduplicator,
    val envelopeProtectionService: EnvelopeProtectionService,
    val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
    val cryptoProvider: CryptoProvider,
    val signatureProvider: SignatureProvider,
    val logger: AppLogger,
): Router {
    private var started = false
    private var torEndpoint: TorEndpoint? = null
    private var localDeviceIdentity: DeviceIdentityRecord? = null

    private val incomingMessageFlow = MutableSharedFlow<MessagePayload>(replay = 1, extraBufferCapacity = 64)
    private var scope: CoroutineScope? = null
    private var torIncomingJob: Job? = null
    private var webRtcIncomingEnvelopeJob: Job? = null
    private var webRtcOutgoingJob: Job? = null

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

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        torIncomingJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            torTransport.incoming.collect { inbound ->
                runCatching { handleInboundEnvelope(inbound) }
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
                runCatching { handleInboundEnvelope(inbound) }
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
                        logger.error(
                            component = LogComponent.ROUTER,
                            event = LogEvent.SIGNAL_OUTBOUND_EMIT_FAILED,
                            message = "Failed to emit outbound WebRTC signal",
                            fields = mapOf("error" to e.toString()),
                        )
                    }
            }
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

    override suspend fun sendMessage(target: AccountId, payload: MessagePayload, forceTransport: RouterTransport) {
//        val messageEnvelope = MessageEnvelope(
//            messageId = payload.messageId,
//            source = localDeviceIdentity!!.deviceId,
//            target = target,
//            createdAtEpochSeconds = timeProvider.nowEpochSeconds(),
//            nonce = cryptoProvider.generateNonce(SignalSecurityScheme.SIGNED),
//            securityScheme = SignalSecurityScheme.SIGNED,
//            signature = signatureProvider.signDetached(
//                message = payload.encode()),
//            payload = payload,
//        )
//        //TODO sending to multiple peers
//        val binaryEnvelope = BinaryEnvelope(
//            packetId = packetIdAllocator.allocate(),
//            packetType = PacketType.MESSAGE,
//            createdAtEpochSeconds = messageEnvelope.createdAtEpochSeconds,
//            expiresAtEpochSeconds = messageEnvelope.createdAtEpochSeconds + 2 * 24 * 3600, // 2 days
//            source = messageEnvelope.source,
//            target = messageEnvelope.target,
//            payload = messageEnvelope.encode(),
//        )
//
//        when (forceTransport) {
//            RouterTransport.TOR -> torTransport.send(
//                target = identityResolver.resolveTorEndpointForDevice(target),
//                envelope = binaryEnvelope,
//            )
//            RouterTransport.WEBRTC -> webRtcTransport.sendEnvelope(
//                targetId = target,
//                envelope = binaryEnvelope,
//            ))
// TODO Send message
    }

    private suspend fun handleInboundEnvelope(inbound: BinaryEnvelope) {
        val receivedAtEpochSeconds = timeProvider.nowEpochSeconds()
        if (!packetDeduplicator.firstSeen(
                packetId = inbound.packetId,
                sourceDeviceId = inbound.source,
                receivedAtEpochSeconds = receivedAtEpochSeconds,
            )
        ) {
            return
        }

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
            return
        }

        when (inbound.packetType) {
            PacketType.SIGNAL -> handleSignalEnvelope(inbound, receivedAtEpochSeconds)
            PacketType.FILE -> handleFileEnvelope(inbound, receivedAtEpochSeconds)
            else -> { logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_UNKNOWN_TYPE,
                message = "Envelope ignored due to unknown packet type",
                fields = mapOf(
                    "packetType" to inbound.packetType,
                ),
            ) }
        }
    }

    private suspend fun handleSignalEnvelope(env: BinaryEnvelope, receivedAtEpochSeconds: Long) {
        val signalEnvelope = runCatching { WebRtcSignalEnvelope.decode(env.payload) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode signal envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return
        }
        val signal = runCatching { envelopeProtectionService.openSignal(signalEnvelope) }.getOrNull() ?: run {
            logger.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Failed to open signal envelope",
                fields = mapOf("error" to "protection_failed"),
            )
            return
        }
        webRtcTransport.handleBootstrapSignal(signal, receivedAtEpochSeconds = receivedAtEpochSeconds)
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

    private suspend fun handleFileEnvelope(env: BinaryEnvelope, receivedAtEpochSeconds: Long) {
        // TODO
    }
}