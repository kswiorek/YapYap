package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId

class DefaultWebRtcTransport(
    private val backend: WebRtcBackend,
    private val packetIdGenerator: () -> PacketId = { PacketId.random() },
) : WebRtcTransport {

    private val incomingDataFlow = MutableSharedFlow<WebRtcIncomingDataFrame>(extraBufferCapacity = 64)
    private val incomingSessionRequestFlow = MutableSharedFlow<WebRtcIncomingSessionRequest>(replay = 1, extraBufferCapacity = 64)
    private val sessionStateFlow = MutableStateFlow<WebRtcSessionState?>(null)
    private val outgoingSignalFlow = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)

    override val incomingData: Flow<WebRtcIncomingDataFrame> = incomingDataFlow.asSharedFlow()
    override val incomingSessionRequests: Flow<WebRtcIncomingSessionRequest> = incomingSessionRequestFlow.asSharedFlow()
    override val sessionStates: Flow<WebRtcSessionState> = sessionStateFlow.filterNotNull()
    val outgoingSignals: Flow<WebRtcSignal> = outgoingSignalFlow.asSharedFlow()

    private var started = false
    private var localPeer: PeerDescriptor? = null
    private var scope: CoroutineScope? = null

    private val pendingOffersBySession = mutableMapOf<String, WebRtcSignal>()
    private val peerBySession = mutableMapOf<String, PeerId>()

    private var backendSignalJob: Job? = null
    private var backendDataJob: Job? = null
    private var backendSessionEventsJob: Job? = null

    override suspend fun start(localPeer: PeerDescriptor) {
        check(!started) { "WebRTC transport is already started" }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = localScope

        this.localPeer = localPeer
        backend.start(localPeer)

        val backendSignalsCollectorReady = CompletableDeferred<Unit>()

        backendSignalJob = localScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.outgoingSignals
                .onStart { backendSignalsCollectorReady.complete(Unit) }
                .collect { signal -> outgoingSignalFlow.emit(signal) }
        }

        backendDataJob = localScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.incomingDataFrames.collect { frame ->
                incomingDataFlow.emit(frame)
            }
        }

        backendSessionEventsJob = localScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.sessionEvents.collect { event ->
                when (event) {
                    is WebRtcSessionEvent.Connecting -> {
                        peerBySession[event.sessionId] = event.peer
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.NEGOTIATING,
                            )
                    }
                    is WebRtcSessionEvent.Connected -> {
                        peerBySession[event.sessionId] = event.peer
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.CONNECTED,
                            )
                    }
                    is WebRtcSessionEvent.Closed -> {
                        peerBySession.remove(event.sessionId)
                        pendingOffersBySession.remove(event.sessionId)
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.CLOSED,
                            )
                    }
                    is WebRtcSessionEvent.Failed -> {
                        peerBySession.remove(event.sessionId)
                        pendingOffersBySession.remove(event.sessionId)
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.FAILED,
                                reason = event.reason,
                            )
                    }
                }
            }
        }

        backendSignalsCollectorReady.await()

        started = true
    }

    override suspend fun stop() {
        if (!started) return

        backendSignalJob?.cancel()
        backendDataJob?.cancel()
        backendSessionEventsJob?.cancel()
        backendSignalJob = null
        backendDataJob = null
        backendSessionEventsJob = null

        pendingOffersBySession.clear()
        peerBySession.clear()

        scope?.cancel()
        scope = null

        runCatching { backend.stop() }

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
        sessionStateFlow.value =
            WebRtcSessionState(
                sessionId = sessionId,
                peer = pendingOffer.source,
                phase = WebRtcSessionPhase.NEGOTIATING,
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

        sessionStateFlow.value =
            WebRtcSessionState(
                sessionId = sessionId,
                peer = pendingOffer.source,
                phase = WebRtcSessionPhase.REJECTED,
                reason = reason,
            )
        

        // Best-effort: reject state is a local decision; signaling may be delayed.
        scope?.launch { sendSignal(rejectSignal) } ?: sendSignal(rejectSignal)
    }

    override suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray) {
        check(started) { "WebRTC transport must be started before sending data" }
        backend.sendData(sessionId = sessionId, target = target, payload = payload)
    }

    override suspend fun closeSession(sessionId: String) {
        check(started) { "WebRTC transport must be started before closing session" }
        backend.closeSession(sessionId)
    }

    suspend fun handleInboundSignal(signal: WebRtcSignal, receivedAtEpochSeconds: Long) {
        val local = requireNotNull(localPeer) { "Local peer is not available" }
        if (signal.target != local.id) return

        when (signal.kind) {
            WebRtcSignalKind.OFFER -> {
                pendingOffersBySession[signal.sessionId] = signal
                peerBySession[signal.sessionId] = signal.source
                incomingSessionRequestFlow.emit(
                    WebRtcIncomingSessionRequest(
                        sessionId = signal.sessionId,
                        source = signal.source,
                        receivedAtEpochSeconds = receivedAtEpochSeconds,
                    )
                )
                sessionStateFlow.value =
                    WebRtcSessionState(
                        sessionId = signal.sessionId,
                        peer = signal.source,
                        phase = WebRtcSessionPhase.PENDING_DECISION,
                    )
            }
            WebRtcSignalKind.REJECT -> {
                val reason = signal.payload.decodeToString()
                peerBySession.remove(signal.sessionId)
                pendingOffersBySession.remove(signal.sessionId)
                sessionStateFlow.value =
                    WebRtcSessionState(
                        sessionId = signal.sessionId,
                        peer = signal.source,
                        phase = WebRtcSessionPhase.REJECTED,
                        reason = reason,
                    )
            }
            else -> {
                backend.handleRemoteSignal(signal)
            }
        }
    }

    private suspend fun sendSignal(signal: WebRtcSignal) {
        outgoingSignalFlow.emit(signal)
    }
}


