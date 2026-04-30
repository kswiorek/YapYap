package org.yapyap.backend.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityResolver
import org.yapyap.backend.db.DefaultPacketDeduplicator
import org.yapyap.backend.db.PacketIdAllocator
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.protection.EnvelopeProtectContext
import org.yapyap.backend.protection.EnvelopeProtectionService
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider
import org.yapyap.backend.transport.tor.TorInboundEnvelope
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
    val logger: AppLogger,
): Router {
    var started = false
    var torEndpoint: TorEndpoint? = null
    var localDeviceIdentity: DeviceIdentityRecord? = null

    private var scope: CoroutineScope? = null
    private var torIncomingJob: Job? = null
    private var webRtcOutgoingJob: Job? = null


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
                        message = "Failed to handle inbound envelope",
                        fields = mapOf("error" to e.toString()),
                    ) }
            }
        }

        webRtcOutgoingJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            webRtcTransport.outgoingSignals.collect { signal ->
                runCatching { handleWebRtcOutboundSignal(signal) }
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

    private suspend fun handleInboundEnvelope(inbound: TorInboundEnvelope) {
        val env = inbound.envelope

        if (!packetDeduplicator.firstSeen(
                packetId = env.packetId,
                sourceDeviceId = env.source,
                receivedAtEpochSeconds = inbound.receivedAtEpochSeconds,
            )
        ) {
            return
        }

        if (env.target != localDeviceIdentity?.deviceId) {
            logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Envelope ignored due to target mismatch",
                fields = mapOf(
                    "sourceDeviceId" to env.source,
                    "targetDeviceId" to env.target,
                    "localDeviceId" to localDeviceIdentity?.deviceId,
                ),
            )
            return
        }

        when (env.packetType) {
            PacketType.SIGNAL -> handleInboundSignalEnvelope(env)
            PacketType.FILE -> handleFileEnvelope(env)
            else -> { logger.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_UNKNOWN_TYPE,
                message = "Envelope ignored due to unknown packet type",
                fields = mapOf(
                    "packetType" to env.packetType,
                ),
            ) }
        }
    }

    private suspend fun handleInboundSignalEnvelope(env: BinaryEnvelope) {
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
        webRtcTransport.handleInboundSignal(signal, receivedAtEpochSeconds = env.createdAtEpochSeconds)
    }

    private suspend fun handleWebRtcOutboundSignal(signal: WebRtcSignal) {
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

    private suspend fun handleFileEnvelope(env: BinaryEnvelope) {
        // TODO
    }
}