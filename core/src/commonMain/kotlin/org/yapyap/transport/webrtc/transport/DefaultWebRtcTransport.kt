package org.yapyap.transport.webrtc.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.TransportException
import org.yapyap.transport.webrtc.backend.WebRtcBackend
import org.yapyap.transport.webrtc.types.*
import kotlin.time.Clock

class DefaultWebRtcTransport(
    private val backend: WebRtcBackend,
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

    private val pendingIncomingCallByPeer = mutableMapOf<PeerId, WebRtcIncomingAvSessionRequest>()
    private val avOptionsByPeer = mutableMapOf<PeerId, AvSessionOptions>()

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
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                peerId = event.peer,
                                phase = WebRtcSessionPhase.NEGOTIATING,
                            )
                        AppLog.debug(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "WebRTC session negotiating",
                            fields = mapOf("peer" to event.peer, "phase" to WebRtcSessionPhase.NEGOTIATING.name),
                        )
                    }
                    is WebRtcSessionEvent.Connected -> {
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                peerId = event.peer,
                                phase = WebRtcSessionPhase.CONNECTED,
                            )
                        AppLog.info(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "WebRTC session connected",
                            fields = mapOf("peer" to event.peer, "phase" to WebRtcSessionPhase.CONNECTED.name),
                        )
                    }
                    is WebRtcSessionEvent.Closed -> {
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                peerId = event.peer,
                                phase = WebRtcSessionPhase.CLOSED,
                            )
                        AppLog.info(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "WebRTC session closed",
                            fields = mapOf("peer" to event.peer, "phase" to WebRtcSessionPhase.CLOSED.name),
                        )
                    }
                    is WebRtcSessionEvent.Failed -> {
                        sessionStateFlow.value =
                            WebRtcSessionState(
                                peerId = event.peer,
                                phase = WebRtcSessionPhase.FAILED,
                                reason = event.reason,
                            )
                        AppLog.warn(
                            component = LogComponent.WEBRTC_TRANSPORT,
                            event = LogEvent.SESSION_FAILED,
                            message = "WebRTC session failed",
                            fields = mapOf("peer" to event.peer, "reason" to event.reason),
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
                            peer = event.peer,
                            phase = WebRtcAvSessionPhase.NEGOTIATING,
                            options = avOptionsByPeer[event.peer],
                        )
                    }
                    is WebRtcAvChannelEvent.Active -> {
                        callStateFlow.value = WebRtcAvSessionState(
                            peer = event.peer,
                            phase = WebRtcAvSessionPhase.ACTIVE,
                            options = avOptionsByPeer[event.peer],
                        )
                    }
                    is WebRtcAvChannelEvent.Removed -> {
                        pendingIncomingCallByPeer.remove(event.peer)
                        avOptionsByPeer.remove(event.peer)
                        callStateFlow.value = WebRtcAvSessionState(
                            peer = event.peer,
                            phase = WebRtcAvSessionPhase.ENDED,
                        )
                    }
                    is WebRtcAvChannelEvent.Failed -> {
                        pendingIncomingCallByPeer.remove(event.peer)
                        avOptionsByPeer.remove(event.peer)
                        callStateFlow.value = WebRtcAvSessionState(
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
        AppLog.info(
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

        pendingIncomingCallByPeer.clear()
        avOptionsByPeer.clear()

        scope?.cancel()
        scope = null

        runCatching { backend.stop() }

        localDevice = null
        started = false
        AppLog.info(
            component = LogComponent.WEBRTC_TRANSPORT,
            event = LogEvent.STOPPED,
            message = "WebRTC transport stopped",
        )
    }

    override suspend fun openSession(target: PeerId) {
        check(started) { "WebRTC transport must be started before opening session" }
        backend.openSession(target = target)
    }

    override suspend fun sendEnvelope(targetId: PeerId, envelope: BinaryEnvelope) {
        check(started) { "WebRTC transport must be started before sending data" }
        val local = requireNotNull(localDevice) { "Local device is not available" }
        val payload = envelope.encode()
        backend.sendData(
            WebRtcDataFrame(
                source = local,
                target = targetId,
                dataType = WebRtcDataType.ENVELOPE_BINARY,
                payload = payload,
            )
        )
    }

    override suspend fun closeSession(targetId: PeerId) {
        check(started) { "WebRTC transport must be started before closing session" }
        backend.closeSession(targetId)
    }

    override suspend fun inviteCall(peer: PeerId, options: AvSessionOptions) {
        check(started) { "WebRTC transport must be started before inviting call" }
        avOptionsByPeer[peer] = options
        backend.addAvChannel(peer)
        sendAvControl(target = peer, message = AvControlMessage.Invite(options))
        callStateFlow.value = WebRtcAvSessionState(
            peer = peer,
            phase = WebRtcAvSessionPhase.NEGOTIATING,
            options = options,
        )
    }

    override suspend fun acceptCall(peer: PeerId, options: AvSessionOptions) {
        check(started) { "WebRTC transport must be started before accepting call" }
        val request = pendingIncomingCallByPeer.remove(peer)
            ?: error("No pending call invite found for sessionId $peer")
        avOptionsByPeer[peer] = options
        backend.addAvChannel(peer)
        sendAvControl(target = request.source, message = AvControlMessage.Accept(options))
        callStateFlow.value = WebRtcAvSessionState(
            peer = request.source,
            phase = WebRtcAvSessionPhase.ACTIVE,
            options = options,
        )
    }

    override suspend fun rejectCall(peer: PeerId, reason: String) {
        check(started) { "WebRTC transport must be started before rejecting call" }
        val request = pendingIncomingCallByPeer.remove(peer)
            ?: error("No pending call invite found for sessionId $peer")
        sendAvControl(target = request.source, message = AvControlMessage.Reject(reason))
        backend.removeAvChannel(peer)
        avOptionsByPeer.remove(peer)
        callStateFlow.value = WebRtcAvSessionState(
            peer = request.source,
            phase = WebRtcAvSessionPhase.REJECTED,
            reason = reason,
        )
    }

    override suspend fun updateCallOptions(peer: PeerId, options: AvSessionOptions) {
        check(started) { "WebRTC transport must be started before updating call options" }
        avOptionsByPeer[peer] = options
        sendAvControl(target = peer, message = AvControlMessage.Update(options))
        callStateFlow.value = WebRtcAvSessionState(
            peer = peer,
            phase = WebRtcAvSessionPhase.ACTIVE,
            options = options,
        )
    }

    override suspend fun endCall(peer: PeerId, reason: String?) {
        check(started) { "WebRTC transport must be started before ending call" }
        sendAvControl(target = peer, message = AvControlMessage.End(reason))
        backend.removeAvChannel(peer)
        pendingIncomingCallByPeer.remove(peer)
        avOptionsByPeer.remove(peer)
        callStateFlow.value = WebRtcAvSessionState(
            peer = peer,
            phase = WebRtcAvSessionPhase.ENDED,
            reason = reason,
        )
    }

    override suspend fun handleBootstrapSignal(signal: WebRtcSignal) {
        val local = requireNotNull(localDevice) { "Local device is not available" }
        if (signal.target != local) {
            throw TransportException.WebRtcException.WrongTargetException(signal.target)
        }
        backend.handleRemoteSignal(signal)
        when (signal.kind) {
            WebRtcSignalKind.OFFER -> {
                sessionStateFlow.value =
                    WebRtcSessionState(
                        peerId = signal.source,
                        phase = WebRtcSessionPhase.NEGOTIATING,
                    )
                AppLog.debug(
                    component = LogComponent.WEBRTC_TRANSPORT,
                    event = LogEvent.SIGNAL_INBOUND_HANDLED,
                    message = "Handled inbound OFFER signal",
                    fields = mapOf("source" to signal.source),
                )
            }
            WebRtcSignalKind.REJECT -> {
                val reason = signal.payload.decodeToString()
                sessionStateFlow.value =
                    WebRtcSessionState(
                        peerId = signal.source,
                        phase = WebRtcSessionPhase.REJECTED,
                        reason = reason,
                    )
            }
            else -> {
                AppLog.debug(
                    component = LogComponent.WEBRTC_TRANSPORT,
                    event = LogEvent.SIGNAL_INBOUND_HANDLED,
                    message = "Handled inbound WebRTC signal",
                    fields = mapOf("kind" to signal.kind.name, "source" to signal.source),
                )
            }
        }
    }

    override fun hasSession(peerId: PeerId): Boolean {
        return backend.hasSession(peerId)
    }

    private suspend fun handleIncomingFrame(frame: WebRtcDataFrame) {
        when (frame.dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> {
                val envelope = try {
                    BinaryEnvelope.decode(frame.payload)
                } catch (e: Exception) {
                    throw TransportException.WebRtcException.DecodeError("BinaryEnvelope decode error: ${e.message}")
                }
                incomingEnvelopeFlow.emit(WebRtcIncomingEnvelope(
                    source = frame.source,
                    envelope = envelope,
                ))
            }
            WebRtcDataType.AV_DATA -> {
                handleAvControlFrame(frame)
                incomingAvFrameFlow.emit(frame)
            }
        }
    }

    private suspend fun sendAvControl(target: PeerId, message: AvControlMessage) {
        val local = requireNotNull(localDevice) { "Local device is not available" }
        backend.sendData(
            WebRtcDataFrame(
                source = local,
                target = target,
                dataType = WebRtcDataType.AV_DATA,
                payload = message.encode(),
            )
        )
    }

    private suspend fun handleAvControlFrame(frame: WebRtcDataFrame) {
        val message = try {
            AvControlMessage.decode(frame.payload)
        } catch (e: Exception) {
            throw TransportException.WebRtcException.DecodeError("AvControlMessage decode error: ${e.message}")
        }
        when (message) {
            is AvControlMessage.Invite -> {
                avOptionsByPeer[frame.source] = message.options
                val invite = WebRtcIncomingAvSessionRequest(
                    source = frame.source,
                    options = message.options,
                    receivedAtEpochSeconds = Clock.System.now().epochSeconds,
                )
                pendingIncomingCallByPeer[frame.source] = invite
                incomingCallInviteFlow.emit(invite)
                callStateFlow.value = WebRtcAvSessionState(
                    peer = frame.source,
                    phase = WebRtcAvSessionPhase.PENDING_DECISION,
                    options = message.options,
                )
            }
            is AvControlMessage.Accept -> {
                avOptionsByPeer[frame.source] = message.options
                callStateFlow.value = WebRtcAvSessionState(
                    peer = frame.source,
                    phase = WebRtcAvSessionPhase.ACTIVE,
                    options = message.options,
                )
            }
            is AvControlMessage.Reject -> {
                pendingIncomingCallByPeer.remove(frame.source)
                avOptionsByPeer.remove(frame.source)
                backend.removeAvChannel(frame.source)
                callStateFlow.value = WebRtcAvSessionState(
                    peer = frame.source,
                    phase = WebRtcAvSessionPhase.REJECTED,
                    reason = message.reason,
                )
            }
            is AvControlMessage.Update -> {
                avOptionsByPeer[frame.source] = message.options
                callStateFlow.value = WebRtcAvSessionState(
                    peer = frame.source,
                    phase = WebRtcAvSessionPhase.ACTIVE,
                    options = message.options,
                )
            }
            is AvControlMessage.End -> {
                pendingIncomingCallByPeer.remove(frame.source)
                avOptionsByPeer.remove(frame.source)
                backend.removeAvChannel(frame.source)
                callStateFlow.value = WebRtcAvSessionState(
                    peer = frame.source,
                    phase = WebRtcAvSessionPhase.ENDED,
                    reason = message.reason,
                )
            }
        }
        AppLog.debug(
            component = LogComponent.WEBRTC_TRANSPORT,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Handled inbound AV control frame",
            fields = mapOf("peer" to frame.source, "kind" to message.kindName),
        )
    }
}

private sealed interface AvControlMessage {
    val kindName: String

    data class Invite(val options: AvSessionOptions) : AvControlMessage { override val kindName: String = "INVITE" }
    data class Accept(val options: AvSessionOptions) : AvControlMessage { override val kindName: String = "ACCEPT" }
    data class Reject(val reason: String) : AvControlMessage { override val kindName: String = "REJECT" }
    data class Update(val options: AvSessionOptions) : AvControlMessage { override val kindName: String = "UPDATE" }
    data class End(val reason: String?) : AvControlMessage { override val kindName: String = "END" }

    fun encode(): ByteArray {
        val writer = ByteWriter(32)
        writer.writeBytes(AV_CONTROL_MAGIC)
        when (this) {
            is Invite -> {
                writer.writeByte(1)
                writeAvSessionOptions(writer, this.options)
            }
            is Accept -> {
                writer.writeByte(2)
                writeAvSessionOptions(writer, this.options)
            }
            is Reject -> {
                writer.writeByte(3)
                writer.writeString(this.reason)
            }
            is Update -> {
                writer.writeByte(4)
                writeAvSessionOptions(writer, this.options)
            }
            is End -> {
                writer.writeByte(5)
                writer.writeNullableString(this.reason)
            }
        }
        return writer.toByteArray()
    }

    private fun writeAvSessionOptions(writer: ByteWriter, options: AvSessionOptions) {
        val flags = (if (options.audioEnabled) 1 else 0) or
                (if (options.videoEnabled) 1 shl 1 else 0) or
                (if (options.screenShareEnabled) 1 shl 2 else 0)
        writer.writeByte(flags)
        writer.writeByte(options.qualityTier.ordinal)
    }

    companion object {
        fun decode(bytes: ByteArray): AvControlMessage = decodeAvControlMessage(bytes)

        private val AV_CONTROL_MAGIC = byteArrayOf('Y'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

        private fun decodeAvControlMessage(bytes: ByteArray): AvControlMessage =
            run {
                val reader = ByteReader(bytes)
                val magic = reader.readBytes(AV_CONTROL_MAGIC.size)
                if (!magic.contentEquals(AV_CONTROL_MAGIC)) error("Invalid AV control message magic")
                when (reader.readUnsignedByte()) {
                    1 -> Invite(readAvSessionOptions(reader))
                    2 -> Accept(readAvSessionOptions(reader))
                    3 -> Reject(reader.readString())
                    4 -> Update(readAvSessionOptions(reader))
                    5 -> End(reader.readNullableString())
                    else -> error("Unknown AV control message kind")
                }.also {
                    reader.requireFullyRead()
                }
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
    }
}


