package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.EnvelopeRoute
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.tor.TorTransport

class DefaultWebRtcTransport(
    private val backend: WebRtcBackend,
    private val torTransport: TorTransport,
    private val resolveTorEndpoint: (PeerId) -> TorEndpoint,
    private val clockEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val nonceGenerator: () -> ByteArray = { Random.Default.nextBytes(16) },
    private val packetIdGenerator: () -> PacketId = { PacketId.random() },
    private val signalTtlSeconds: Long = 300,
) : WebRtcTransport {

    private val incomingDataFlow = MutableSharedFlow<WebRtcIncomingDataFrame>(extraBufferCapacity = 64)
    private val incomingSessionRequestFlow = MutableSharedFlow<WebRtcIncomingSessionRequest>(extraBufferCapacity = 64)
    private val sessionStateFlow = MutableSharedFlow<WebRtcSessionState>(replay = 1, extraBufferCapacity = 64)

    override val incomingData: Flow<WebRtcIncomingDataFrame> = incomingDataFlow.asSharedFlow()
    override val incomingSessionRequests: Flow<WebRtcIncomingSessionRequest> = incomingSessionRequestFlow.asSharedFlow()
    override val sessionStates: Flow<WebRtcSessionState> = sessionStateFlow.asSharedFlow()

    private var started = false
    private var localPeer: PeerDescriptor? = null
    private var scope: CoroutineScope? = null

    private val pendingOffersBySession = mutableMapOf<String, WebRtcSignal>()
    private val peerBySession = mutableMapOf<String, PeerId>()

    private var backendSignalJob: Job? = null
    private var backendDataJob: Job? = null
    private var backendSessionEventsJob: Job? = null
    private var torIncomingJob: Job? = null

    override suspend fun start(localPeer: PeerDescriptor) {
        check(!started) { "WebRTC transport is already started" }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = localScope

        this.localPeer = localPeer
        backend.start(localPeer)
        torTransport.start(localPeer)

        backendSignalJob = localScope.launch {
            backend.outgoingSignals.collect { signal ->
                sendSignal(signal)
            }
        }

        backendDataJob = localScope.launch {
            backend.incomingDataFrames.collect { frame ->
                incomingDataFlow.emit(frame)
            }
        }

        backendSessionEventsJob = localScope.launch {
            backend.sessionEvents.collect { event ->
                when (event) {
                    is WebRtcSessionEvent.Connecting -> {
                        peerBySession[event.sessionId] = event.peer
                        sessionStateFlow.emit(
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.NEGOTIATING,
                            )
                        )
                    }
                    is WebRtcSessionEvent.Connected -> {
                        peerBySession[event.sessionId] = event.peer
                        sessionStateFlow.emit(
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.CONNECTED,
                            )
                        )
                    }
                    is WebRtcSessionEvent.Closed -> {
                        peerBySession.remove(event.sessionId)
                        pendingOffersBySession.remove(event.sessionId)
                        sessionStateFlow.emit(
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.CLOSED,
                            )
                        )
                    }
                    is WebRtcSessionEvent.Failed -> {
                        peerBySession.remove(event.sessionId)
                        pendingOffersBySession.remove(event.sessionId)
                        sessionStateFlow.emit(
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.FAILED,
                                reason = event.reason,
                            )
                        )
                    }
                }
            }
        }

        torIncomingJob = localScope.launch {
            torTransport.incoming.collect { inbound ->
                if (inbound.envelope.packetType != PacketType.SIGNAL) return@collect
                val signalEnvelope = runCatching { WebRtcSignalEnvelope.decode(inbound.envelope.payload) }
                    .getOrNull() ?: return@collect
                handleInboundSignalEnvelope(signalEnvelope)
            }
        }

        started = true
    }

    override suspend fun stop() {
        if (!started) return

        backendSignalJob?.cancel()
        backendDataJob?.cancel()
        backendSessionEventsJob?.cancel()
        torIncomingJob?.cancel()
        backendSignalJob = null
        backendDataJob = null
        backendSessionEventsJob = null
        torIncomingJob = null

        pendingOffersBySession.clear()
        peerBySession.clear()

        scope?.cancel()
        scope = null

        runCatching { backend.stop() }
        runCatching { torTransport.stop() }

        localPeer = null
        started = false
    }

    override suspend fun initiateSession(target: PeerId): String {
        check(started) { "WebRTC transport must be started before initiating session" }
        val sessionId = packetIdGenerator().toHex()
        peerBySession[sessionId] = target
        backend.openSession(target = target, sessionId = sessionId)
        return sessionId
    }

    override suspend fun acceptSession(sessionId: String) {
        check(started) { "WebRTC transport must be started before accepting session" }
        val pendingOffer = pendingOffersBySession.remove(sessionId)
            ?: error("No pending offer found for sessionId $sessionId")

        peerBySession[sessionId] = pendingOffer.source
        backend.handleRemoteSignal(pendingOffer)
        sessionStateFlow.emit(
            WebRtcSessionState(
                sessionId = sessionId,
                peer = pendingOffer.source,
                phase = WebRtcSessionPhase.NEGOTIATING,
            )
        )
    }

    override suspend fun rejectSession(sessionId: String, reason: String) {
        check(started) { "WebRTC transport must be started before rejecting session" }
        val local = requireNotNull(localPeer) { "Local peer is not available" }
        val pendingOffer = pendingOffersBySession.remove(sessionId)
            ?: error("No pending offer found for sessionId $sessionId")

        val rejectSignal = WebRtcSignal(
            sessionId = sessionId,
            kind = WebRtcSignalKind.REJECT,
            source = local.id,
            target = pendingOffer.source,
            payload = reason.encodeToByteArray(),
        )

        sendSignal(rejectSignal)
        sessionStateFlow.emit(
            WebRtcSessionState(
                sessionId = sessionId,
                peer = pendingOffer.source,
                phase = WebRtcSessionPhase.REJECTED,
                reason = reason,
            )
        )
    }

    override suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray) {
        check(started) { "WebRTC transport must be started before sending data" }
        backend.sendData(sessionId = sessionId, target = target, payload = payload)
    }

    override suspend fun closeSession(sessionId: String) {
        check(started) { "WebRTC transport must be started before closing session" }
        backend.closeSession(sessionId)
    }

    private suspend fun sendSignal(signal: WebRtcSignal) {
        val createdAt = clockEpochSeconds()

        val signalEnvelope = WebRtcSignalEnvelope(
            sessionId = signal.sessionId,
            kind = signal.kind,
            source = signal.source,
            target = signal.target,
            createdAtEpochSeconds = createdAt,
            nonce = nonceGenerator(),
            signature = null,
            payload = signal.payload,
        )

        val envelope = BinaryEnvelope(
            packetId = packetIdGenerator(),
            packetType = PacketType.SIGNAL,
            createdAtEpochSeconds = createdAt,
            expiresAtEpochSeconds = createdAt + signalTtlSeconds,
            hopCount = 0,
            route = EnvelopeRoute(
                destinationAccount = signal.target.accountName,
                destinationDevice = signal.target.deviceId,
                nextHopDevice = null,
            ),
            payload = signalEnvelope.encode(),
        )

        torTransport.send(
            target = resolveTorEndpoint(signal.target),
            envelope = envelope,
        )
    }

    private suspend fun handleInboundSignalEnvelope(signalEnvelope: WebRtcSignalEnvelope) {
        val local = requireNotNull(localPeer) { "Local peer is not available" }
        if (signalEnvelope.target != local.id) return

        val signal = WebRtcSignal(
            sessionId = signalEnvelope.sessionId,
            kind = signalEnvelope.kind,
            source = signalEnvelope.source,
            target = signalEnvelope.target,
            payload = signalEnvelope.payload,
        )

        when (signal.kind) {
            WebRtcSignalKind.OFFER -> {
                pendingOffersBySession[signal.sessionId] = signal
                peerBySession[signal.sessionId] = signal.source
                incomingSessionRequestFlow.emit(
                    WebRtcIncomingSessionRequest(
                        sessionId = signal.sessionId,
                        source = signal.source,
                        receivedAtEpochSeconds = signalEnvelope.createdAtEpochSeconds,
                    )
                )
                sessionStateFlow.emit(
                    WebRtcSessionState(
                        sessionId = signal.sessionId,
                        peer = signal.source,
                        phase = WebRtcSessionPhase.PENDING_DECISION,
                    )
                )
            }
            WebRtcSignalKind.REJECT -> {
                val reason = signal.payload.decodeToString()
                peerBySession.remove(signal.sessionId)
                pendingOffersBySession.remove(signal.sessionId)
                sessionStateFlow.emit(
                    WebRtcSessionState(
                        sessionId = signal.sessionId,
                        peer = signal.source,
                        phase = WebRtcSessionPhase.REJECTED,
                        reason = reason,
                    )
                )
            }
            else -> {
                backend.handleRemoteSignal(signal)
            }
        }
    }
}


