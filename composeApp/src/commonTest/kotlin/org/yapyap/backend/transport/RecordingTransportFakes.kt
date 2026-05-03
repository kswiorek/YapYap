package org.yapyap.backend.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.tor.TorBackend
import org.yapyap.backend.transport.tor.TorIncomingFrame
import org.yapyap.backend.transport.tor.TorIncomingEnvelope
import org.yapyap.backend.transport.tor.TorTransport
import org.yapyap.backend.transport.webrtc.WebRtcAvChannelEvent
import org.yapyap.backend.transport.webrtc.WebRtcBackend
import org.yapyap.backend.transport.webrtc.WebRtcDataFrame
import org.yapyap.backend.transport.webrtc.WebRtcIncomingEnvelope
import org.yapyap.backend.transport.webrtc.WebRtcSessionEvent
import org.yapyap.backend.transport.webrtc.WebRtcTransport
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcAvSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcIncomingAvSessionRequest
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

/** Recording fake — no real Tor mesh. */
class RecordingTorTransport(
    val advertisedEndpoint: TorEndpoint = TorEndpoint(onionAddress = "fake.onion", port = 80),
) : TorTransport {

    private val incomingMutable = MutableSharedFlow<TorIncomingEnvelope>(extraBufferCapacity = 64)
    override val incoming: Flow<TorIncomingEnvelope> = incomingMutable.asSharedFlow()

    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    val sends = mutableListOf<Pair<TorEndpoint, BinaryEnvelope>>()

    override suspend fun start(): TorEndpoint {
        startCalls++
        return advertisedEndpoint
    }

    override suspend fun stop() {
        stopCalls++
    }

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope) {
        sends.add(target to envelope)
    }

    fun tryEmitIncoming(envelope: TorIncomingEnvelope): Boolean = incomingMutable.tryEmit(envelope)
}

/** Recording fake — no WebRTC stack; satisfies all flows and records API usage. */
class RecordingWebRtcTransport : WebRtcTransport {

    private val incomingEnvelopesMutable = MutableSharedFlow<WebRtcIncomingEnvelope>(extraBufferCapacity = 64)
    private val incomingAvFramesMutable =
        MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 64)
    private val outgoingBootstrapSignalsMutable = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val sessionStatesMutable = MutableSharedFlow<WebRtcSessionState>(extraBufferCapacity = 64)
    private val incomingCallInvitesMutable =
        MutableSharedFlow<WebRtcIncomingAvSessionRequest>(extraBufferCapacity = 64)
    private val callStatesMutable = MutableSharedFlow<WebRtcAvSessionState>(extraBufferCapacity = 64)

    override val incomingEnvelopes = incomingEnvelopesMutable.asSharedFlow()
    override val incomingAvFrames = incomingAvFramesMutable.asSharedFlow()
    override val outgoingBootstrapSignals = outgoingBootstrapSignalsMutable.asSharedFlow()
    override val sessionStates = sessionStatesMutable.asSharedFlow()
    override val incomingCallInvites = incomingCallInvitesMutable.asSharedFlow()
    override val callStates = callStatesMutable.asSharedFlow()

    val startCalls = mutableListOf<PeerId>()
    val stopCalls = mutableListOf<Unit>()
    val openSessionCalls = mutableListOf<Pair<PeerId, String>>()
    val sendEnvelopeCalls = mutableListOf<Triple<String, PeerId, BinaryEnvelope>>()
    val closeSessionCalls = mutableListOf<String>()
    val handleBootstrapCalls = mutableListOf<Pair<WebRtcSignal, Long>>()
    val getSessionForPeerCalls = mutableListOf<PeerId>()
    var sessionForPeerResult: String? = null

    val inviteCallCalls = mutableListOf<Triple<PeerId, String, AvSessionOptions>>()
    val acceptCallCalls = mutableListOf<Pair<String, AvSessionOptions>>()
    val rejectCallCalls = mutableListOf<Pair<String, String>>()
    val updateCallOptionsCalls = mutableListOf<Pair<String, AvSessionOptions>>()
    val endCallCalls = mutableListOf<Pair<String, String?>>()

    override suspend fun start(deviceId: PeerId) {
        startCalls.add(deviceId)
    }

    override suspend fun stop() {
        stopCalls.add(Unit)
    }

    override suspend fun openSession(target: PeerId, sessionId: String) {
        openSessionCalls.add(target to sessionId)
    }

    override suspend fun sendEnvelope(sessionId: String, targetId: PeerId, envelope: BinaryEnvelope) {
        sendEnvelopeCalls.add(Triple(sessionId, targetId, envelope))
    }

    override suspend fun closeSession(sessionId: String) {
        closeSessionCalls.add(sessionId)
    }

    override suspend fun handleBootstrapSignal(signal: WebRtcSignal, receivedAtEpochSeconds: Long) {
        handleBootstrapCalls.add(signal to receivedAtEpochSeconds)
    }

    override suspend fun getSessionForPeer(target: PeerId): String? {
        getSessionForPeerCalls.add(target)
        return sessionForPeerResult
    }

    override suspend fun inviteCall(target: PeerId, sessionId: String, options: AvSessionOptions) {
        inviteCallCalls.add(Triple(target, sessionId, options))
    }

    override suspend fun acceptCall(sessionId: String, options: AvSessionOptions) {
        acceptCallCalls.add(sessionId to options)
    }

    override suspend fun rejectCall(sessionId: String, reason: String) {
        rejectCallCalls.add(sessionId to reason)
    }

    override suspend fun updateCallOptions(sessionId: String, options: AvSessionOptions) {
        updateCallOptionsCalls.add(sessionId to options)
    }

    override suspend fun endCall(sessionId: String, reason: String?) {
        endCallCalls.add(sessionId to reason)
    }

    fun tryEmitIncomingEnvelope(e: WebRtcIncomingEnvelope): Boolean = incomingEnvelopesMutable.tryEmit(e)
}

/** Recording lower-level Tor backend (byte payloads). */
class RecordingTorBackend : TorBackend {

    private val framesMutable = MutableSharedFlow<TorIncomingFrame>(extraBufferCapacity = 64)
    override val incomingFrames = framesMutable.asSharedFlow()

    val startCalls = mutableListOf<Int?>()
    val stopCalls = mutableListOf<Unit>()
    val sends = mutableListOf<Pair<TorEndpoint, ByteArray>>()
    var nextEndpoint: TorEndpoint = TorEndpoint("backend.onion", 9050)

    override suspend fun start(localPort: Int?): TorEndpoint {
        startCalls.add(localPort)
        return nextEndpoint
    }

    override suspend fun stop() {
        stopCalls.add(Unit)
    }

    override suspend fun send(target: TorEndpoint, payload: ByteArray) {
        sends.add(target to payload.copyOf())
    }

    fun tryEmitFrame(frame: TorIncomingFrame): Boolean = framesMutable.tryEmit(frame)
}

/** Recording lower-level WebRTC backend. */
class RecordingWebRtcBackend : WebRtcBackend {

    private val outgoingSignalsMutable = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val incomingDataFramesMutable = MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 64)
    private val sessionEventsMutable = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)
    private val avChannelEventsMutable = MutableSharedFlow<WebRtcAvChannelEvent>(extraBufferCapacity = 64)

    override val outgoingSignals = outgoingSignalsMutable.asSharedFlow()
    override val incomingDataFrames = incomingDataFramesMutable.asSharedFlow()
    override val sessionEvents = sessionEventsMutable.asSharedFlow()
    override val avChannelEvents = avChannelEventsMutable.asSharedFlow()

    val startCalls = mutableListOf<PeerId>()
    val stopCalls = mutableListOf<Unit>()
    val openSessionCalls = mutableListOf<Pair<PeerId, String>>()
    val handleRemoteSignalCalls = mutableListOf<WebRtcSignal>()
    val closeSessionCalls = mutableListOf<String>()
    val sendDataCalls = mutableListOf<WebRtcDataFrame>()
    val addAvChannelCalls = mutableListOf<String>()
    val removeAvChannelCalls = mutableListOf<String>()

    override suspend fun start(localDevice: PeerId) {
        startCalls.add(localDevice)
    }

    override suspend fun stop() {
        stopCalls.add(Unit)
    }

    override suspend fun openSession(target: PeerId, sessionId: String) {
        openSessionCalls.add(target to sessionId)
    }

    override suspend fun handleRemoteSignal(signal: WebRtcSignal) {
        handleRemoteSignalCalls.add(signal)
    }

    override suspend fun closeSession(sessionId: String) {
        closeSessionCalls.add(sessionId)
    }

    override suspend fun sendData(dataFrame: WebRtcDataFrame) {
        sendDataCalls.add(dataFrame)
    }

    override suspend fun addAvChannel(sessionId: String) {
        addAvChannelCalls.add(sessionId)
    }

    override suspend fun removeAvChannel(sessionId: String) {
        removeAvChannelCalls.add(sessionId)
    }

    fun tryEmitOutgoingSignal(s: WebRtcSignal): Boolean = outgoingSignalsMutable.tryEmit(s)
}
