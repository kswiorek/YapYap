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
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.ByteReader
import org.yapyap.backend.protocol.ByteWriter
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.AvQualityTier
import org.yapyap.backend.transport.webrtc.types.WebRtcAvSessionPhase
import org.yapyap.backend.transport.webrtc.types.WebRtcAvSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcIncomingAvSessionRequest
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionPhase
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import org.yapyap.backend.transport.webrtc.types.WebRtcSignalKind
import kotlin.time.Clock

class DefaultWebRtcTransport(
    private val backend: WebRtcBackend,
    private val logger: AppLogger = NoopAppLogger,
) : WebRtcTransport {

    private val incomingEnvelopeFlow = MutableSharedFlow<WebRtcIncomingEnvelope>(extraBufferCapacity = 64)
    private val incomingAvFrameFlow = MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 64)
    private val sessionStateFlow = MutableStateFlow<WebRtcSessionState?>(null)
    private val incomingCallInviteFlow = MutableSharedFlow<WebRtcIncomingAvSessionRequest>(replay = 1, extraBufferCapacity = 64)
    private val callStateFlow = MutableStateFlow<WebRtcAvSessionState?>(null)
    private val outgoingBootstrapSignalFlow = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)

    override val incomingEnvelopes: Flow<WebRtcIncomingEnvelope> = incomingEnvelopeFlow.asSharedFlow()
    override val incomingAvFrames: Flow<WebRtcDataFrame> = incomingAvFrameFlow.asSharedFlow()
    override val sessionStates: Flow<WebRtcSessionState> = sessionStateFlow.filterNotNull()
    override val incomingCallInvites: Flow<WebRtcIncomingAvSessionRequest> = incomingCallInviteFlow.asSharedFlow()
    override val callStates: Flow<WebRtcAvSessionState> = callStateFlow.filterNotNull()
    override val outgoingBootstrapSignals: Flow<WebRtcSignal> = outgoingBootstrapSignalFlow.asSharedFlow()

    private var started = false
    private var localDevice: PeerId? = null
    private var scope: CoroutineScope? = null

    private val peerBySession = mutableMapOf<String, PeerId>()
    private val pendingIncomingCallBySession = mutableMapOf<String, WebRtcIncomingAvSessionRequest>()
    private val avPeerBySession = mutableMapOf<String, PeerId>()
    private val avOptionsBySession = mutableMapOf<String, AvSessionOptions>()

    private var backendSignalJob: Job? = null
    private var backendDataJob: Job? = null
    private var backendSessionEventsJob: Job? = null
    private var backendAvChannelEventsJob: Job? = null

    override suspend fun start(deviceId: PeerId) {
        check(!started) { "WebRTC transport is already started" }
        backend.start(deviceId)
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = localScope

        this.localDevice = deviceId

        val backendSignalsCollectorReady = CompletableDeferred<Unit>()

        backendSignalJob = localScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.outgoingSignals
                .onStart { backendSignalsCollectorReady.complete(Unit) }
                .collect { signal -> outgoingBootstrapSignalFlow.emit(signal) }
        }

        backendDataJob = localScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.incomingDataFrames.collect { frame ->
                handleIncomingFrame(frame)
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
                        logger.debug(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "WebRTC session negotiating",
                            fields = mapOf("sessionId" to event.sessionId, "peer" to event.peer, "phase" to WebRtcSessionPhase.NEGOTIATING.name),
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
                        logger.info(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "WebRTC session connected",
                            fields = mapOf("sessionId" to event.sessionId, "peer" to event.peer, "phase" to WebRtcSessionPhase.CONNECTED.name),
                        )
                    }
                    is WebRtcSessionEvent.Closed -> {
                        peerBySession.remove(event.sessionId)
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.CLOSED,
                            )
                        logger.info(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "WebRTC session closed",
                            fields = mapOf("sessionId" to event.sessionId, "peer" to event.peer, "phase" to WebRtcSessionPhase.CLOSED.name),
                        )
                    }
                    is WebRtcSessionEvent.Failed -> {
                        peerBySession.remove(event.sessionId)
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                sessionId = event.sessionId,
                                peer = event.peer,
                                phase = WebRtcSessionPhase.FAILED,
                                reason = event.reason,
                            )
                        logger.warn(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.SESSION_FAILED,
                            message = "WebRTC session failed",
                            fields = mapOf("sessionId" to event.sessionId, "peer" to event.peer, "reason" to event.reason),
                        )
                    }
                }
            }
        }

        backendAvChannelEventsJob = localScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.avChannelEvents.collect { event ->
                when (event) {
                    is WebRtcAvChannelEvent.Adding -> {
                        callStateFlow.value = WebRtcAvSessionState(
                            sessionId = event.sessionId,
                            peer = event.peer,
                            phase = WebRtcAvSessionPhase.NEGOTIATING,
                            options = avOptionsBySession[event.sessionId],
                        )
                    }
                    is WebRtcAvChannelEvent.Active -> {
                        avPeerBySession[event.sessionId] = event.peer
                        callStateFlow.value = WebRtcAvSessionState(
                            sessionId = event.sessionId,
                            peer = event.peer,
                            phase = WebRtcAvSessionPhase.ACTIVE,
                            options = avOptionsBySession[event.sessionId],
                        )
                    }
                    is WebRtcAvChannelEvent.Removed -> {
                        avPeerBySession.remove(event.sessionId)
                        pendingIncomingCallBySession.remove(event.sessionId)
                        avOptionsBySession.remove(event.sessionId)
                        callStateFlow.value = WebRtcAvSessionState(
                            sessionId = event.sessionId,
                            peer = event.peer,
                            phase = WebRtcAvSessionPhase.ENDED,
                        )
                    }
                    is WebRtcAvChannelEvent.Failed -> {
                        avPeerBySession.remove(event.sessionId)
                        pendingIncomingCallBySession.remove(event.sessionId)
                        avOptionsBySession.remove(event.sessionId)
                        callStateFlow.value = WebRtcAvSessionState(
                            sessionId = event.sessionId,
                            peer = event.peer,
                            phase = WebRtcAvSessionPhase.FAILED,
                            reason = event.reason,
                        )
                    }
                }
            }
        }

        backendSignalsCollectorReady.await()

        started = true
        logger.info(
            component = LogComponent.WEBRTC_TRANSPORT,
            event = LogEvent.STARTED,
            message = "WebRTC transport started",
            fields = mapOf("deviceId" to deviceId),
        )
    }

    override suspend fun stop() {
        if (!started) return
        backendSignalJob?.cancel()
        backendDataJob?.cancel()
        backendSessionEventsJob?.cancel()
        backendAvChannelEventsJob?.cancel()
        backendSignalJob = null
        backendDataJob = null
        backendSessionEventsJob = null
        backendAvChannelEventsJob = null

        peerBySession.clear()
        pendingIncomingCallBySession.clear()
        avPeerBySession.clear()
        avOptionsBySession.clear()

        scope?.cancel()
        scope = null

        runCatching { backend.stop() }

        localDevice = null
        started = false
        logger.info(
            component = LogComponent.WEBRTC_TRANSPORT,
            event = LogEvent.STOPPED,
            message = "WebRTC transport stopped",
        )
    }

    override suspend fun openSession(target: PeerId, sessionId: String) {
        check(started) { "WebRTC transport must be started before opening session" }
        peerBySession[sessionId] = target
        backend.openSession(target = target, sessionId = sessionId)
    }

    override suspend fun getSessionForPeer(target: PeerId): String? {
        return peerBySession.entries.find { it.value == target }?.key
    }

    override suspend fun sendEnvelope(sessionId: String, targetId: PeerId, envelope: BinaryEnvelope) {
        check(started) { "WebRTC transport must be started before sending data" }
        val local = requireNotNull(localDevice) { "Local device is not available" }
        val payload = envelope.encode()
        backend.sendData(
            WebRtcDataFrame(
                sessionId = sessionId,
                source = local,
                target = targetId,
                dataType = WebRtcDataType.ENVELOPE_BINARY,
                payload = payload,
            )
        )
    }

    override suspend fun closeSession(sessionId: String) {
        check(started) { "WebRTC transport must be started before closing session" }
        backend.closeSession(sessionId)
    }

    override suspend fun inviteCall(target: PeerId, sessionId: String, options: AvSessionOptions) {
        check(started) { "WebRTC transport must be started before inviting call" }
        avPeerBySession[sessionId] = target
        avOptionsBySession[sessionId] = options
        backend.addAvChannel(sessionId = sessionId)
        sendAvControl(sessionId = sessionId, target = target, message = AvControlMessage.Invite(options))
        callStateFlow.value = WebRtcAvSessionState(
            sessionId = sessionId,
            peer = target,
            phase = WebRtcAvSessionPhase.NEGOTIATING,
            options = options,
        )
    }

    override suspend fun acceptCall(sessionId: String, options: AvSessionOptions) {
        check(started) { "WebRTC transport must be started before accepting call" }
        val request = pendingIncomingCallBySession.remove(sessionId)
            ?: error("No pending call invite found for sessionId $sessionId")
        avPeerBySession[sessionId] = request.source
        avOptionsBySession[sessionId] = options
        backend.addAvChannel(sessionId = sessionId)
        sendAvControl(sessionId = sessionId, target = request.source, message = AvControlMessage.Accept(options))
        callStateFlow.value = WebRtcAvSessionState(
            sessionId = sessionId,
            peer = request.source,
            phase = WebRtcAvSessionPhase.ACTIVE,
            options = options,
        )
    }

    override suspend fun rejectCall(sessionId: String, reason: String) {
        check(started) { "WebRTC transport must be started before rejecting call" }
        val request = pendingIncomingCallBySession.remove(sessionId)
            ?: error("No pending call invite found for sessionId $sessionId")
        sendAvControl(sessionId = sessionId, target = request.source, message = AvControlMessage.Reject(reason))
        backend.removeAvChannel(sessionId)
        avPeerBySession.remove(sessionId)
        avOptionsBySession.remove(sessionId)
        callStateFlow.value = WebRtcAvSessionState(
            sessionId = sessionId,
            peer = request.source,
            phase = WebRtcAvSessionPhase.REJECTED,
            reason = reason,
        )
    }

    override suspend fun updateCallOptions(sessionId: String, options: AvSessionOptions) {
        check(started) { "WebRTC transport must be started before updating call options" }
        val peer = avPeerBySession[sessionId] ?: error("No AV peer found for sessionId $sessionId")
        avOptionsBySession[sessionId] = options
        sendAvControl(sessionId = sessionId, target = peer, message = AvControlMessage.Update(options))
        callStateFlow.value = WebRtcAvSessionState(
            sessionId = sessionId,
            peer = peer,
            phase = WebRtcAvSessionPhase.ACTIVE,
            options = options,
        )
    }

    override suspend fun endCall(sessionId: String, reason: String?) {
        check(started) { "WebRTC transport must be started before ending call" }
        val peer = avPeerBySession[sessionId] ?: peerBySession[sessionId] ?: return
        sendAvControl(sessionId = sessionId, target = peer, message = AvControlMessage.End(reason))
        backend.removeAvChannel(sessionId)
        avPeerBySession.remove(sessionId)
        pendingIncomingCallBySession.remove(sessionId)
        avOptionsBySession.remove(sessionId)
        callStateFlow.value = WebRtcAvSessionState(
            sessionId = sessionId,
            peer = peer,
            phase = WebRtcAvSessionPhase.ENDED,
            reason = reason,
        )
    }

    override suspend fun handleBootstrapSignal(signal: WebRtcSignal, receivedAtEpochSeconds: Long) {
        val local = requireNotNull(localDevice) { "Local device is not available" }
        if (signal.target != local) {
            logger.debug(
                component = LogComponent.WEBRTC_TRANSPORT,
                event = LogEvent.SIGNAL_INBOUND_DROPPED_WRONG_TARGET,
                message = "Dropped inbound signal for different target",
                fields = mapOf("sessionId" to signal.sessionId, "target" to signal.target, "localDevice" to local),
            )
            return
        }

        when (signal.kind) {
            WebRtcSignalKind.OFFER -> {
                peerBySession[signal.sessionId] = signal.source
                sessionStateFlow.value =
                    WebRtcSessionState(
                        sessionId = signal.sessionId,
                        peer = signal.source,
                        phase = WebRtcSessionPhase.NEGOTIATING,
                    )
                backend.handleRemoteSignal(signal)
                logger.debug(
                    component = LogComponent.WEBRTC_TRANSPORT,
                    event = LogEvent.SIGNAL_INBOUND_HANDLED,
                    message = "Handled inbound OFFER signal",
                    fields = mapOf("sessionId" to signal.sessionId, "source" to signal.source),
                )
            }
            WebRtcSignalKind.REJECT -> {
                val reason = signal.payload.decodeToString()
                peerBySession.remove(signal.sessionId)
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
                logger.debug(
                    component = LogComponent.WEBRTC_TRANSPORT,
                    event = LogEvent.SIGNAL_INBOUND_HANDLED,
                    message = "Handled inbound WebRTC signal",
                    fields = mapOf("sessionId" to signal.sessionId, "kind" to signal.kind.name, "source" to signal.source),
                )
            }
        }
    }

    private suspend fun handleIncomingFrame(frame: WebRtcDataFrame) {
        val peer = peerBySession[frame.sessionId]
        if (peer == null) {
            logger.debug(
                component = LogComponent.WEBRTC_TRANSPORT,
                event = LogEvent.INCOMING_FRAME_DROPPED_UNKNOWN_SESSION,
                message = "Dropped incoming frame for unknown session",
                fields = mapOf("sessionId" to frame.sessionId),
            )
            return
        }

        when (frame.dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> {
                val envelope = runCatching { BinaryEnvelope.decode(frame.payload) }
                    .getOrElse { error ->
                        logger.warn(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.ENVELOPE_DECODE_FAILED,
                            message = "Failed to decode incoming WebRTC envelope frame",
                            fields = mapOf("sessionId" to frame.sessionId, "peer" to peer, "error" to error.toString()),
                        )
                        return
                    }
                incomingEnvelopeFlow.emit(WebRtcIncomingEnvelope(
                    sessionId = frame.sessionId,
                    source = peer,
                    envelope = envelope,
                ))
            }
            WebRtcDataType.AV_DATA -> {
                if (handleAvControlFrame(frame, peer)) return
                incomingAvFrameFlow.emit(frame)
            }
        }
    }

    private suspend fun sendAvControl(sessionId: String, target: PeerId, message: AvControlMessage) {
        val local = requireNotNull(localDevice) { "Local device is not available" }
        backend.sendData(
            WebRtcDataFrame(
                sessionId = sessionId,
                source = local,
                target = target,
                dataType = WebRtcDataType.AV_DATA,
                payload = encodeAvControlMessage(message),
            )
        )
    }

    private suspend fun handleAvControlFrame(frame: WebRtcDataFrame, peer: PeerId): Boolean {
        val message = decodeAvControlMessage(frame.payload) ?: return false
        when (message) {
            is AvControlMessage.Invite -> {
                avPeerBySession[frame.sessionId] = peer
                avOptionsBySession[frame.sessionId] = message.options
                val invite = WebRtcIncomingAvSessionRequest(
                    sessionId = frame.sessionId,
                    source = peer,
                    options = message.options,
                    receivedAtEpochSeconds = Clock.System.now().epochSeconds,
                )
                pendingIncomingCallBySession[frame.sessionId] = invite
                incomingCallInviteFlow.emit(invite)
                callStateFlow.value = WebRtcAvSessionState(
                    sessionId = frame.sessionId,
                    peer = peer,
                    phase = WebRtcAvSessionPhase.PENDING_DECISION,
                    options = message.options,
                )
            }
            is AvControlMessage.Accept -> {
                avPeerBySession[frame.sessionId] = peer
                avOptionsBySession[frame.sessionId] = message.options
                callStateFlow.value = WebRtcAvSessionState(
                    sessionId = frame.sessionId,
                    peer = peer,
                    phase = WebRtcAvSessionPhase.ACTIVE,
                    options = message.options,
                )
            }
            is AvControlMessage.Reject -> {
                pendingIncomingCallBySession.remove(frame.sessionId)
                avPeerBySession.remove(frame.sessionId)
                avOptionsBySession.remove(frame.sessionId)
                backend.removeAvChannel(frame.sessionId)
                callStateFlow.value = WebRtcAvSessionState(
                    sessionId = frame.sessionId,
                    peer = peer,
                    phase = WebRtcAvSessionPhase.REJECTED,
                    reason = message.reason,
                )
            }
            is AvControlMessage.Update -> {
                avOptionsBySession[frame.sessionId] = message.options
                callStateFlow.value = WebRtcAvSessionState(
                    sessionId = frame.sessionId,
                    peer = peer,
                    phase = WebRtcAvSessionPhase.ACTIVE,
                    options = message.options,
                )
            }
            is AvControlMessage.End -> {
                pendingIncomingCallBySession.remove(frame.sessionId)
                avPeerBySession.remove(frame.sessionId)
                avOptionsBySession.remove(frame.sessionId)
                backend.removeAvChannel(frame.sessionId)
                callStateFlow.value = WebRtcAvSessionState(
                    sessionId = frame.sessionId,
                    peer = peer,
                    phase = WebRtcAvSessionPhase.ENDED,
                    reason = message.reason,
                )
            }
        }
        logger.debug(
            component = LogComponent.WEBRTC_TRANSPORT,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Handled inbound AV control frame",
            fields = mapOf("sessionId" to frame.sessionId, "peer" to peer, "kind" to message.kindName),
        )
        return true
    }
}

private sealed interface AvControlMessage {
    val kindName: String

    data class Invite(val options: AvSessionOptions) : AvControlMessage { override val kindName: String = "INVITE" }
    data class Accept(val options: AvSessionOptions) : AvControlMessage { override val kindName: String = "ACCEPT" }
    data class Reject(val reason: String) : AvControlMessage { override val kindName: String = "REJECT" }
    data class Update(val options: AvSessionOptions) : AvControlMessage { override val kindName: String = "UPDATE" }
    data class End(val reason: String?) : AvControlMessage { override val kindName: String = "END" }
}

private val AV_CONTROL_MAGIC = byteArrayOf('Y'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

private fun encodeAvControlMessage(message: AvControlMessage): ByteArray {
    val writer = ByteWriter(32)
    writer.writeBytes(AV_CONTROL_MAGIC)
    when (message) {
        is AvControlMessage.Invite -> {
            writer.writeByte(1)
            writeAvSessionOptions(writer, message.options)
        }
        is AvControlMessage.Accept -> {
            writer.writeByte(2)
            writeAvSessionOptions(writer, message.options)
        }
        is AvControlMessage.Reject -> {
            writer.writeByte(3)
            writer.writeString(message.reason)
        }
        is AvControlMessage.Update -> {
            writer.writeByte(4)
            writeAvSessionOptions(writer, message.options)
        }
        is AvControlMessage.End -> {
            writer.writeByte(5)
            writer.writeNullableString(message.reason)
        }
    }
    return writer.toByteArray()
}

private fun decodeAvControlMessage(bytes: ByteArray): AvControlMessage? {
    return runCatching {
        val reader = ByteReader(bytes)
        val magic = reader.readBytes(AV_CONTROL_MAGIC.size)
        if (!magic.contentEquals(AV_CONTROL_MAGIC)) return null
        when (reader.readUnsignedByte()) {
            1 -> AvControlMessage.Invite(readAvSessionOptions(reader))
            2 -> AvControlMessage.Accept(readAvSessionOptions(reader))
            3 -> AvControlMessage.Reject(reader.readString())
            4 -> AvControlMessage.Update(readAvSessionOptions(reader))
            5 -> AvControlMessage.End(reader.readNullableString())
            else -> return null
        }.also {
            reader.requireFullyRead()
        }
    }.getOrNull()
}

private fun writeAvSessionOptions(writer: ByteWriter, options: AvSessionOptions) {
    val flags = (if (options.audioEnabled) 1 else 0) or
        (if (options.videoEnabled) 1 shl 1 else 0) or
        (if (options.screenShareEnabled) 1 shl 2 else 0)
    writer.writeByte(flags)
    writer.writeByte(options.qualityTier.ordinal)
}

private fun readAvSessionOptions(reader: ByteReader): AvSessionOptions {
    val flags = reader.readUnsignedByte()
    val quality = reader.readUnsignedByte()
    return AvSessionOptions(
        audioEnabled = (flags and 1) != 0,
        videoEnabled = (flags and (1 shl 1)) != 0,
        screenShareEnabled = (flags and (1 shl 2)) != 0,
        qualityTier = AvQualityTier.entries.getOrElse(quality) { AvQualityTier.MEDIUM },
    )
}


