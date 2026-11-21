package org.yapyap.transport.webrtc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.transport.webrtc.backend.WebRtcBackend
import org.yapyap.transport.webrtc.transport.WebRtcIncomingEnvelope
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.AvSessionOptions
import org.yapyap.transport.webrtc.types.FILE_CHANNEL_PREFIX
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcAvChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcAvSessionState
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import org.yapyap.transport.webrtc.types.WebRtcIncomingAvSessionRequest
import org.yapyap.transport.webrtc.types.WebRtcSessionEvent
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import kotlin.uuid.Uuid

/** Recording fake — no WebRTC stack; satisfies all flows and records API usage. */
internal class RecordingWebRtcTransport : WebRtcTransport {

    private val incomingEnvelopesMutable = MutableSharedFlow<WebRtcIncomingEnvelope>(extraBufferCapacity = 64)
    private val incomingAvFramesMutable =
        MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 64)
    private val incomingFileFramesMutable = MutableSharedFlow<WebRtcFileFrame>(extraBufferCapacity = 256)
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
    val openFileChannelCalls = mutableListOf<PeerId>()
    val openedChannels = mutableListOf<FileChannelId>()
    val closeFileChannelCalls = mutableListOf<FileChannelId>()
    val sendFileFrameCalls = mutableListOf<Pair<FileChannelId, ByteArray>>()
    val awaitBufferedCalls = mutableListOf<Pair<FileChannelId, Long>>()

    /**
     * Every file frame sent and channel closed, in call order. Unlike the lists above these are
     * safe to consume while concurrent fetches keep sending (a pump forwarding to another node).
     */
    val sentFileFrames = Channel<Pair<FileChannelId, ByteArray>>(Channel.UNLIMITED)
    val closedFileChannels = Channel<FileChannelId>(Channel.UNLIMITED)
    private val fileCallsGuard = Mutex()

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

    override suspend fun openFileChannel(target: PeerId): FileChannelId {
        openFileChannelCalls.add(target)
        // Concurrent fetches open channels at once: labels must stay unique.
        val id = FileChannelId(target, "${FILE_CHANNEL_PREFIX}fake-${Uuid.random()}")
        fileCallsGuard.withLock { openedChannels.add(id) }
        return id
    }

    override suspend fun closeFileChannel(channel: FileChannelId) {
        fileCallsGuard.withLock { closeFileChannelCalls.add(channel) }
        closedFileChannels.trySend(channel)
        fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Removed(channel))
    }

    override suspend fun sendFileFrame(channel: FileChannelId, payload: ByteArray) {
        fileCallsGuard.withLock { sendFileFrameCalls.add(channel to payload) }
        sentFileFrames.trySend(channel to payload)
    }

    override suspend fun awaitBufferedBelow(channel: FileChannelId, thresholdBytes: Long) {
        awaitBufferedCalls.add(channel to thresholdBytes)
        // No real backpressure: the buffer is always below the threshold.
    }

    fun tryEmitFileFrame(frame: WebRtcFileFrame): Boolean = incomingFileFramesMutable.tryEmit(frame)

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
    private val incomingFileFramesMutable = MutableSharedFlow<WebRtcFileFrame>(extraBufferCapacity = 256)
    private val sessionEventsMutable = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)
    private val avChannelEventsMutable = MutableSharedFlow<WebRtcAvChannelEvent>(extraBufferCapacity = 64)
    private val fileChannelEventsMutable = MutableSharedFlow<WebRtcFileChannelEvent>(extraBufferCapacity = 64)

    override val outgoingSignals = outgoingSignalsMutable.asSharedFlow()
    override val incomingDataFrames = incomingDataFramesMutable.asSharedFlow()
    override val incomingFileFrames = incomingFileFramesMutable.asSharedFlow()
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
    val openFileChannelCalls = mutableListOf<PeerId>()
    val openedChannels = mutableListOf<FileChannelId>()
    val closeFileChannelCalls = mutableListOf<FileChannelId>()
    val sendFileFrameCalls = mutableListOf<Pair<FileChannelId, ByteArray>>()
    val awaitBufferedCalls = mutableListOf<Pair<FileChannelId, Long>>()
    private val openFileChannels = mutableSetOf<FileChannelId>()
    private var nextChannelIndex = 0

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
        val ids = openFileChannels.filter { it.peer == target }.toList()
        openFileChannels.removeAll { it.peer == target }
        for (id in ids) {
            fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Removed(id))
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

    override suspend fun openFileChannel(target: PeerId): FileChannelId {
        openFileChannelCalls.add(target)
        val id = FileChannelId(target, "${FILE_CHANNEL_PREFIX}fake-${nextChannelIndex++}")
        openFileChannels.add(id)
        openedChannels.add(id)
        fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Adding(id))
        fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Active(id))
        return id
    }

    override suspend fun closeFileChannel(channel: FileChannelId) {
        closeFileChannelCalls.add(channel)
        openFileChannels.remove(channel)
        fileChannelEventsMutable.tryEmit(WebRtcFileChannelEvent.Removed(channel))
    }

    override suspend fun sendFileFrame(channel: FileChannelId, payload: ByteArray) {
        sendFileFrameCalls.add(channel to payload)
    }

    override suspend fun awaitBufferedBelow(channel: FileChannelId, thresholdBytes: Long) {
        awaitBufferedCalls.add(channel to thresholdBytes)
    }

    fun tryEmitOutgoingSignal(s: WebRtcSignal): Boolean = outgoingSignalsMutable.tryEmit(s)

    fun tryEmitSessionEvent(e: WebRtcSessionEvent): Boolean = sessionEventsMutable.tryEmit(e)

    fun tryEmitFileChannelEvent(e: WebRtcFileChannelEvent): Boolean = fileChannelEventsMutable.tryEmit(e)

    fun tryEmitFileFrame(frame: WebRtcFileFrame): Boolean = incomingFileFramesMutable.tryEmit(frame)

    fun tryEmitDataFrame(frame: WebRtcDataFrame): Boolean = incomingDataFramesMutable.tryEmit(frame)
}
