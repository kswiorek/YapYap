package org.yapyap.transport.webrtc

import kotlinx.coroutines.flow.*
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.transport.webrtc.backend.WebRtcBackend
import org.yapyap.transport.webrtc.transport.WebRtcIncomingEnvelope
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.*

/** Recording fake — no WebRTC stack; satisfies all flows and records API usage. */
internal class RecordingWebRtcTransport : WebRtcTransport {

    private val incomingEnvelopesMutable = MutableSharedFlow<WebRtcIncomingEnvelope>(extraBufferCapacity = 64)
    private val incomingAvFramesMutable =
        MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 64)
    private val incomingFileFramesMutable = MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 256)
    private val fileChannelEventsMutable = MutableSharedFlow<WebRtcFileChannelEvent>(extraBufferCapacity = 64)
    private val outgoingBootstrapSignalsMutable = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val sessionStatesMutable = MutableStateFlow<Map<PeerId, WebRtcSessionState>>(emptyMap())
    private val incomingCallInvitesMutable =
        MutableSharedFlow<WebRtcIncomingAvSessionRequest>(extraBufferCapacity = 64)
    private val callStatesMutable = MutableSharedFlow<WebRtcAvSessionState>(extraBufferCapacity = 64)

    override val incomingEnvelopes = incomingEnvelopesMutable.asSharedFlow()
    override val incomingAvFrames = incomingAvFramesMutable.asSharedFlow()
    override val incomingFileFrames = incomingFileFramesMutable.asSharedFlow()
    override val fileChannelEvents = fileChannelEventsMutable.asSharedFlow()
    override val outgoingBootstrapSignals = outgoingBootstrapSignalsMutable.asSharedFlow()
    override val sessionStates: StateFlow<Map<PeerId, WebRtcSessionState>> = sessionStatesMutable.asStateFlow()
    override val incomingCallInvites = incomingCallInvitesMutable.asSharedFlow()
    override val callStates = callStatesMutable.asSharedFlow()

    val startCalls = mutableListOf<PeerId>()
    val stopCalls = mutableListOf<Unit>()
    val openSessionCalls = mutableListOf<PeerId>()
    val sendEnvelopeCalls = mutableListOf<Pair<PeerId, BinaryEnvelope>>()
    val closeSessionCalls = mutableListOf<PeerId>()
    val handleBootstrapCalls = mutableListOf<WebRtcSignal>()

    val inviteCallCalls = mutableListOf<Pair<PeerId, AvSessionOptions>>()
    val acceptCallCalls = mutableListOf<Pair<PeerId, AvSessionOptions>>()
    val rejectCallCalls = mutableListOf<Pair<PeerId, String>>()
    val updateCallOptionsCalls = mutableListOf<Pair<PeerId, AvSessionOptions>>()
    val endCallCalls = mutableListOf<Pair<PeerId, String?>>()
    val hasSessionCalls = mutableListOf<PeerId>()
    val openFileChannelCalls = mutableListOf<Pair<PeerId, String>>()
    val closeFileChannelCalls = mutableListOf<Pair<PeerId, String>>()
    val sendFileFrameCalls = mutableListOf<Triple<PeerId, String, ByteArray>>()
    val setFileThresholdCalls = mutableListOf<Triple<PeerId, String, Long>>()
    private val fileBufferedAmounts = mutableMapOf<Pair<PeerId, String>, Long>()

    /** Peers whose simulated envelope data channel is OPEN; only these report hasSession == true. */
    private val openChannelPeers = mutableSetOf<PeerId>()

    override suspend fun start(deviceId: PeerId) {
        startCalls.add(deviceId)
    }

    override suspend fun stop() {
        stopCalls.add(Unit)
    }

    override suspend fun openSession(key: PeerId) {
        openSessionCalls.add(key)
    }

    /**
     * Simulates the envelope data channel reaching OPEN for [peer]; hasSession(peer)
     * becomes true. Deliberately separate from [openSession]: like the real backend,
     * opening a session never synchronously opens the channel — usability arrives
     * later (and is what the CONNECTED state reports).
     */
    fun simulateEnvelopeChannelOpen(peer: PeerId) {
        openChannelPeers.add(peer)
    }

    /** Simulates the envelope data channel closing for [peer]; hasSession(peer) becomes false. */
    fun simulateEnvelopeChannelClosed(peer: PeerId) {
        openChannelPeers.remove(peer)
    }

    override suspend fun sendEnvelope(targetId: PeerId, envelope: BinaryEnvelope) {
        sendEnvelopeCalls.add(targetId to envelope)
    }

    override suspend fun closeSession(key: PeerId) {
        closeSessionCalls.add(key)
        simulateEnvelopeChannelClosed(key)
    }

    override suspend fun handleBootstrapSignal(signal: WebRtcSignal) {
        handleBootstrapCalls.add(signal)
    }

    override fun hasSession(key: PeerId): Boolean {
        hasSessionCalls.add(key)
        return key in openChannelPeers
    }

    override suspend fun inviteCall(peer: PeerId, options: AvSessionOptions) {
        inviteCallCalls.add(peer to options)
    }

    override suspend fun acceptCall(peer: PeerId, options: AvSessionOptions) {
        acceptCallCalls.add(peer to options)
    }

    override suspend fun rejectCall(peer: PeerId, reason: String) {
        rejectCallCalls.add(peer to reason)
    }

    override suspend fun updateCallOptions(peer: PeerId, options: AvSessionOptions) {
        updateCallOptionsCalls.add(peer to options)
    }

    override suspend fun endCall(peer: PeerId, reason: String?) {
        endCallCalls.add(peer to reason)
    }

    override suspend fun openFileChannel(target: PeerId, channelLabel: String) {
        openFileChannelCalls.add(target to channelLabel)
    }

    override suspend fun closeFileChannel(target: PeerId, channelLabel: String) {
        closeFileChannelCalls.add(target to channelLabel)
        fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Removed(target, channelLabel))
    }

    override suspend fun sendFileFrame(target: PeerId, channelLabel: String, payload: ByteArray) {
        sendFileFrameCalls.add(Triple(target, channelLabel, payload))
    }

    override fun fileChannelBufferedAmount(target: PeerId, channelLabel: String): Long? =
        fileBufferedAmounts[target to channelLabel]

    override suspend fun setFileChannelBufferedThreshold(target: PeerId, channelLabel: String, thresholdBytes: Long) {
        setFileThresholdCalls.add(Triple(target, channelLabel, thresholdBytes))
    }

    fun setFileBufferedAmount(target: PeerId, channelLabel: String, amount: Long) {
        fileBufferedAmounts[target to channelLabel] = amount
    }

    fun tryEmitFileFrame(frame: WebRtcDataFrame): Boolean = incomingFileFramesMutable.tryEmit(frame)

    fun tryEmitFileChannelEvent(event: WebRtcFileChannelEvent): Boolean = fileChannelEventsMutable.tryEmit(event)

    fun tryEmitIncomingEnvelope(e: WebRtcIncomingEnvelope): Boolean = incomingEnvelopesMutable.tryEmit(e)

    /** Records the last-known session state for [state]'s peer, mirroring the real transport. */
    fun setSessionState(state: WebRtcSessionState) {
        sessionStatesMutable.update { it + (state.peerId to state) }
    }
}

/** Recording lower-level WebRTC backend. */
internal class RecordingWebRtcBackend : WebRtcBackend {

    private val outgoingSignalsMutable = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val incomingDataFramesMutable = MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 256)
    private val sessionEventsMutable = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)
    private val avChannelEventsMutable = MutableSharedFlow<WebRtcAvChannelEvent>(extraBufferCapacity = 64)
    private val fileChannelEventsMutable = MutableSharedFlow<WebRtcFileChannelEvent>(extraBufferCapacity = 64)

    override val outgoingSignals = outgoingSignalsMutable.asSharedFlow()
    override val incomingDataFrames = incomingDataFramesMutable.asSharedFlow()
    override val sessionEvents = sessionEventsMutable.asSharedFlow()
    override val avChannelEvents = avChannelEventsMutable.asSharedFlow()
    override val fileChannelEvents = fileChannelEventsMutable.asSharedFlow()

    val startCalls = mutableListOf<PeerId>()
    val stopCalls = mutableListOf<Unit>()
    val openSessionCalls = mutableListOf<PeerId>()
    val handleRemoteSignalCalls = mutableListOf<WebRtcSignal>()
    val closeSessionCalls = mutableListOf<PeerId>()
    val sendDataCalls = mutableListOf<WebRtcDataFrame>()
    val addAvChannelCalls = mutableListOf<PeerId>()
    val removeAvChannelCalls = mutableListOf<PeerId>()
    val hasSessionCalls = mutableListOf<PeerId>()
    val openFileChannelCalls = mutableListOf<Pair<PeerId, String>>()
    val closeFileChannelCalls = mutableListOf<Pair<PeerId, String>>()
    val setThresholdCalls = mutableListOf<Triple<PeerId, String, Long>>()
    private val fileBufferedAmounts = mutableMapOf<Pair<PeerId, String>, Long>()
    private val openFileChannels = mutableSetOf<Pair<PeerId, String>>()

    /** Peers whose simulated envelope data channel is OPEN; only these report hasSession == true. */
    private val openChannelPeers = mutableSetOf<PeerId>()

    override suspend fun start(localDevice: PeerId) {
        startCalls.add(localDevice)
    }

    override suspend fun stop() {
        stopCalls.add(Unit)
    }

    override suspend fun openSession(key: PeerId) {
        openSessionCalls.add(key)
    }

    /** Simulates the envelope data channel reaching OPEN for [peer]; hasSession(peer) becomes true. */
    fun simulateEnvelopeChannelOpen(peer: PeerId) {
        openChannelPeers.add(peer)
    }

    override suspend fun handleRemoteSignal(signal: WebRtcSignal) {
        handleRemoteSignalCalls.add(signal)
    }

    override fun hasSession(target: PeerId): Boolean {
        hasSessionCalls.add(target)
        return target in openChannelPeers
    }

    override suspend fun closeSession(target: PeerId) {
        closeSessionCalls.add(target)
        openChannelPeers.remove(target)
        val labels = openFileChannels.filter { it.first == target }.map { it.second }.toList()
        openFileChannels.removeAll { it.first == target }
        for (label in labels) {
            fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Removed(target, label))
        }
    }

    override suspend fun sendData(dataFrame: WebRtcDataFrame) {
        sendDataCalls.add(dataFrame)
    }

    override suspend fun addAvChannel(target: PeerId) {
        addAvChannelCalls.add(target)
    }

    override suspend fun removeAvChannel(target: PeerId) {
        removeAvChannelCalls.add(target)
    }

    override suspend fun openFileChannel(target: PeerId, channelLabel: String) {
        openFileChannelCalls.add(target to channelLabel)
        openFileChannels.add(target to channelLabel)
        fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Adding(target, channelLabel))
        fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Active(target, channelLabel))
    }

    override suspend fun closeFileChannel(target: PeerId, channelLabel: String) {
        closeFileChannelCalls.add(target to channelLabel)
        openFileChannels.remove(target to channelLabel)
        fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Removed(target, channelLabel))
    }

    override suspend fun setFileChannelBufferedThreshold(target: PeerId, channelLabel: String, thresholdBytes: Long) {
        setThresholdCalls.add(Triple(target, channelLabel, thresholdBytes))
    }

    override fun fileChannelBufferedAmount(target: PeerId, channelLabel: String): Long? =
        fileBufferedAmounts[target to channelLabel]

    fun setFileBufferedAmount(target: PeerId, channelLabel: String, amount: Long) {
        fileBufferedAmounts[target to channelLabel] = amount
    }

    fun tryEmitOutgoingSignal(s: WebRtcSignal): Boolean = outgoingSignalsMutable.tryEmit(s)

    fun tryEmitSessionEvent(e: WebRtcSessionEvent): Boolean = sessionEventsMutable.tryEmit(e)

    fun tryEmitFileChannelEvent(e: WebRtcFileChannelEvent): Boolean = fileChannelEventsMutable.tryEmit(e)

    fun tryEmitFileFrame(frame: WebRtcDataFrame): Boolean = incomingDataFramesMutable.tryEmit(frame)
}
