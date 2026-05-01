package org.yapyap.backend.transport.webrtc

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import org.yapyap.backend.transport.webrtc.types.WebRtcSignalKind

class JvmWebRtcBackend(
    private val config: WebRtcConfig = WebRtcConfig(),
    private val logger: AppLogger = NoopAppLogger,
) : WebRtcBackend {

    private val outgoingSignalFlow = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val incomingDataFlow = MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 64)
    private val sessionEventFlow = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)
    private val avChannelEventFlow = MutableSharedFlow<WebRtcAvChannelEvent>(extraBufferCapacity = 64)
    private val callbackScope = CoroutineScope(Dispatchers.Default)

    override val outgoingSignals: Flow<WebRtcSignal> = outgoingSignalFlow.asSharedFlow()
    override val incomingDataFrames: Flow<WebRtcDataFrame> = incomingDataFlow.asSharedFlow()
    override val sessionEvents: Flow<WebRtcSessionEvent> = sessionEventFlow.asSharedFlow()
    override val avChannelEvents: Flow<WebRtcAvChannelEvent> = avChannelEventFlow.asSharedFlow()

    private var localDevice: String? = null
    private var factory: PeerConnectionFactory? = null
    private val sessions = ConcurrentHashMap<String, Session>()

    override suspend fun start(localDevice: String) {
        check(this.localDevice == null) { "WebRTC backend is already started" }
        this.localDevice = localDevice
        this.factory = PeerConnectionFactory()
        logger.info(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.STARTED,
            message = "JVM WebRTC backend started",
            fields = mapOf("deviceId" to localDevice),
        )
    }

    override suspend fun stop() {
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        factory?.dispose()
        factory = null
        localDevice = null
        logger.info(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.STOPPED,
            message = "JVM WebRTC backend stopped",
        )
    }

    override suspend fun openSession(target: String, sessionId: String) {
        val local = requireNotNull(localDevice) { "WebRTC backend must be started before opening session" }
        val peerConnection = createPeerConnection(sessionId = sessionId, remotePeer = target)
        val session = Session(sessionId = sessionId, remotePeer = target, peerConnection = peerConnection)
        check(sessions.putIfAbsent(sessionId, session) == null) { "Session already exists: $sessionId" }

        val channelInit = RTCDataChannelInit().also { init ->
            init.ordered = config.orderedDataChannel
            config.maxRetransmits?.let { init.maxRetransmits = it }
            config.maxPacketLifeTimeMs?.let { init.maxPacketLifeTime = it }
        }
        val label = envelopeChannelLabel(sessionId)
        val channel = peerConnection.createDataChannel(label, channelInit)
        attachDataChannel(session, channel, WebRtcDataType.ENVELOPE_BINARY)
        emitSessionEvent(WebRtcSessionEvent.Connecting(sessionId = sessionId, peer = target))

        peerConnection.createOffer(
            RTCOfferOptions(),
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    peerConnection.setLocalDescription(
                        description,
                        object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                emitSignal(
                                    WebRtcSignal(
                                        sessionId = sessionId,
                                        kind = WebRtcSignalKind.OFFER,
                                        source = local,
                                        target = target,
                                        payload = description.sdp.encodeToByteArray(),
                                    )
                                )
                            }

                            override fun onFailure(error: String) {
                                emitSessionEvent(
                                    WebRtcSessionEvent.Failed(
                                        sessionId = sessionId,
                                        peer = target,
                                        reason = "Failed to set local offer: $error",
                                    )
                                )
                            }
                        }
                    )
                }

                override fun onFailure(error: String) {
                    emitSessionEvent(
                        WebRtcSessionEvent.Failed(
                            sessionId = sessionId,
                            peer = target,
                            reason = "Failed to create offer: $error",
                        )
                    )
                }
            }
        )
    }

    override suspend fun handleRemoteSignal(signal: WebRtcSignal) {
        val local = requireNotNull(localDevice) { "WebRTC backend must be started before applying remote signal" }
        logger.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Applying inbound WebRTC signal",
            fields = mapOf("sessionId" to signal.sessionId, "kind" to signal.kind.name, "source" to signal.source),
        )
        when (signal.kind) {
            WebRtcSignalKind.OFFER -> {
                val session = sessions.computeIfAbsent(signal.sessionId) {
                    val pc = createPeerConnection(sessionId = signal.sessionId, remotePeer = signal.source)
                    Session(sessionId = signal.sessionId, remotePeer = signal.source, peerConnection = pc)
                }
                emitSessionEvent(WebRtcSessionEvent.Connecting(signal.sessionId, signal.source))
                val offer = RTCSessionDescription(RTCSdpType.OFFER, signal.payload.decodeToString())
                session.peerConnection.setRemoteDescription(
                    offer,
                    object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            session.peerConnection.createAnswer(
                                RTCAnswerOptions(),
                                object : CreateSessionDescriptionObserver {
                                    override fun onSuccess(answer: RTCSessionDescription) {
                                        session.peerConnection.setLocalDescription(
                                            answer,
                                            object : SetSessionDescriptionObserver {
                                                override fun onSuccess() {
                                                    emitSignal(
                                                        WebRtcSignal(
                                                            sessionId = signal.sessionId,
                                                            kind = WebRtcSignalKind.ANSWER,
                                                            source = local,
                                                            target = signal.source,
                                                            payload = answer.sdp.encodeToByteArray(),
                                                        )
                                                    )
                                                }

                                                override fun onFailure(error: String) {
                                                    emitSessionEvent(
                                                        WebRtcSessionEvent.Failed(
                                                            sessionId = signal.sessionId,
                                                            peer = signal.source,
                                                            reason = "Failed to set local answer: $error",
                                                        )
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    override fun onFailure(error: String) {
                                        emitSessionEvent(
                                            WebRtcSessionEvent.Failed(
                                                sessionId = signal.sessionId,
                                                peer = signal.source,
                                                reason = "Failed to create answer: $error",
                                            )
                                        )
                                    }
                                }
                            )
                        }

                        override fun onFailure(error: String) {
                            emitSessionEvent(
                                WebRtcSessionEvent.Failed(
                                    sessionId = signal.sessionId,
                                    peer = signal.source,
                                    reason = "Failed to set remote offer: $error",
                                )
                            )
                        }
                    }
                )
            }

            WebRtcSignalKind.ANSWER -> {
                val session = sessions[signal.sessionId] ?: return
                val answer = RTCSessionDescription(RTCSdpType.ANSWER, signal.payload.decodeToString())
                session.peerConnection.setRemoteDescription(
                    answer,
                    object : SetSessionDescriptionObserver {
                        override fun onSuccess() = Unit
                        override fun onFailure(error: String) {
                            emitSessionEvent(
                                WebRtcSessionEvent.Failed(
                                    sessionId = signal.sessionId,
                                    peer = session.remotePeer,
                                    reason = "Failed to set remote answer: $error",
                                )
                            )
                        }
                    }
                )
            }

            WebRtcSignalKind.ICE -> {
                val session = sessions[signal.sessionId] ?: return
                val candidate = decodeIceCandidate(signal.payload) ?: return
                session.peerConnection.addIceCandidate(candidate)
            }

            WebRtcSignalKind.REJECT,
            WebRtcSignalKind.CANCEL,
            -> {
                val session = sessions.remove(signal.sessionId) ?: return
                session.dispose()
                emitSessionEvent(WebRtcSessionEvent.Closed(signal.sessionId, session.remotePeer))
            }
        }
    }

    override suspend fun closeSession(sessionId: String) {
        check(localDevice != null) { "WebRTC backend must be started before closing session" }
        val session = sessions.remove(sessionId) ?: return
        session.dispose()
        emitSessionEvent(WebRtcSessionEvent.Closed(sessionId, session.remotePeer))
    }

    override suspend fun sendData(dataFrame: WebRtcDataFrame) {
        check(localDevice != null) { "WebRTC backend must be started before sending data" }
        val local = requireNotNull(localDevice)
        val session = sessions[dataFrame.sessionId] ?: error("Unknown sessionId: ${dataFrame.sessionId}")
        check(session.remotePeer == dataFrame.target) { "Session target mismatch for sessionId ${dataFrame.sessionId}" }
        check(dataFrame.source == local) { "Frame source mismatch for local device ${dataFrame.source}" }
        val channel = when (dataFrame.dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> session.envelopeDataChannel
            WebRtcDataType.AV_DATA -> session.avDataChannel
        } ?: error("No ${dataFrame.dataType} channel available for sessionId: ${dataFrame.sessionId}")
        check(channel.state == RTCDataChannelState.OPEN) { "Data channel is not open for sessionId: ${dataFrame.sessionId}" }
        channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(dataFrame.payload), true))
        logger.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Sent WebRTC data frame",
            fields = mapOf(
                "sessionId" to dataFrame.sessionId,
                "target" to dataFrame.target,
                "dataType" to dataFrame.dataType.name,
                "payloadSize" to dataFrame.payload.size,
            ),
        )
    }

    override suspend fun addAvChannel(sessionId: String) {
        check(localDevice != null) { "WebRTC backend must be started before adding AV channel" }
        val session = sessions[sessionId] ?: error("Unknown sessionId: $sessionId")
        logger.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "Adding AV data channel",
            fields = mapOf("sessionId" to sessionId, "peer" to session.remotePeer),
        )
        addAvChannelInternal(session)
    }

    override suspend fun removeAvChannel(sessionId: String) {
        check(localDevice != null) { "WebRTC backend must be started before removing AV channel" }
        val session = sessions[sessionId] ?: return
        logger.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "Removing AV data channel",
            fields = mapOf("sessionId" to sessionId, "peer" to session.remotePeer),
        )
        removeAvChannelInternal(session)
    }

    private fun addAvChannelInternal(session: Session) {
        emitAvChannelEvent(WebRtcAvChannelEvent.Adding(sessionId = session.sessionId, peer = session.remotePeer))
        val existing = session.avDataChannel
        if (existing != null && existing.state != RTCDataChannelState.CLOSED) {
            emitAvChannelEvent(WebRtcAvChannelEvent.Active(sessionId = session.sessionId, peer = session.remotePeer))
            return
        }
        val channelInit = RTCDataChannelInit().also { init ->
            init.ordered = false
            init.maxRetransmits = 0
        }
        val channel = session.peerConnection.createDataChannel(avChannelLabel(session.sessionId), channelInit)
        attachDataChannel(session, channel, WebRtcDataType.AV_DATA)
        emitAvChannelEvent(WebRtcAvChannelEvent.Active(sessionId = session.sessionId, peer = session.remotePeer))
    }

    private fun removeAvChannelInternal(session: Session) {
        val channel = session.avDataChannel ?: return
        session.avDataChannel = null
        runCatching {
            channel.unregisterObserver()
            channel.close()
            channel.dispose()
        }
        logger.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "AV data channel removed",
            fields = mapOf("sessionId" to session.sessionId, "peer" to session.remotePeer),
        )
        emitAvChannelEvent(WebRtcAvChannelEvent.Removed(sessionId = session.sessionId, peer = session.remotePeer))
    }

    private fun createPeerConnection(sessionId: String, remotePeer: String): RTCPeerConnection {
        val rtcConfig = RTCConfiguration().also { configuration ->
            configuration.iceServers = config.iceServers.map { serverConfig ->
                RTCIceServer().also { server ->
                    server.urls = serverConfig.urls
                    server.username = serverConfig.username.orEmpty()
                    server.password = serverConfig.password.orEmpty()
                }
            }
        }
        val local = requireNotNull(localDevice) { "WebRTC backend is not started" }
        val factory = requireNotNull(factory) { "PeerConnectionFactory is not available" }
        return factory.createPeerConnection(
            rtcConfig,
            object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    emitSignal(
                        WebRtcSignal(
                            sessionId = sessionId,
                            kind = WebRtcSignalKind.ICE,
                            source = local,
                            target = remotePeer,
                            payload = encodeIceCandidate(candidate),
                        )
                    )
                }

                override fun onConnectionChange(state: RTCPeerConnectionState) {
                    when (state) {
                        RTCPeerConnectionState.CONNECTING ->
                            emitSessionEvent(WebRtcSessionEvent.Connecting(sessionId, remotePeer))

                        RTCPeerConnectionState.CONNECTED ->
                            emitSessionEvent(WebRtcSessionEvent.Connected(sessionId, remotePeer))

                        RTCPeerConnectionState.FAILED ->
                            emitSessionEvent(
                                WebRtcSessionEvent.Failed(
                                    sessionId = sessionId,
                                    peer = remotePeer,
                                    reason = "Peer connection entered FAILED state",
                                )
                            )

                        RTCPeerConnectionState.CLOSED ->
                            emitSessionEvent(WebRtcSessionEvent.Closed(sessionId, remotePeer))

                        RTCPeerConnectionState.DISCONNECTED,
                        RTCPeerConnectionState.NEW,
                        -> Unit
                    }
                }

                override fun onDataChannel(channel: RTCDataChannel) {
                    sessions[sessionId]?.let { session ->
                        val dataType = inferDataTypeFromLabel(channel.label, sessionId)
                        logger.debug(
                            component = LogComponent.WEBRTC_BACKEND,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "Attached inbound data channel",
                            fields = mapOf("sessionId" to sessionId, "peer" to session.remotePeer, "dataType" to dataType.name),
                        )
                        attachDataChannel(session, channel, dataType)
                    }
                }
            }
        )
    }

    private fun attachDataChannel(session: Session, channel: RTCDataChannel, dataType: WebRtcDataType) {
        when (dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> session.envelopeDataChannel = channel
            WebRtcDataType.AV_DATA -> {
                session.avDataChannel = channel
                emitAvChannelEvent(WebRtcAvChannelEvent.Active(session.sessionId, session.remotePeer))
            }
        }
        channel.registerObserver(
            object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() = Unit

                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    logger.debug(
                        component = LogComponent.WEBRTC_BACKEND,
                        event = LogEvent.SIGNAL_INBOUND_HANDLED,
                        message = "Received WebRTC data frame",
                        fields = mapOf(
                            "sessionId" to session.sessionId,
                            "peer" to session.remotePeer,
                            "dataType" to dataType.name,
                            "payloadSize" to bytes.size,
                        ),
                    )
                    emitIncomingData(
                        WebRtcDataFrame(
                            sessionId = session.sessionId,
                            source = session.remotePeer,
                            target = requireNotNull(localDevice),
                            dataType = dataType,
                            payload = bytes,
                        )
                    )
                }
            }
        )
    }

    private fun inferDataTypeFromLabel(label: String?, sessionId: String): WebRtcDataType {
        if (label == avChannelLabel(sessionId)) return WebRtcDataType.AV_DATA
        if (label == envelopeChannelLabel(sessionId)) {
            return WebRtcDataType.ENVELOPE_BINARY
        }
        return WebRtcDataType.ENVELOPE_BINARY
    }

    private fun envelopeChannelLabel(sessionId: String): String = "${config.dataChannelLabelPrefix}-env-$sessionId"

    private fun avChannelLabel(sessionId: String): String = "${config.dataChannelLabelPrefix}-av-$sessionId"

    private fun emitSignal(signal: WebRtcSignal) {
        logger.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Emitting outbound WebRTC signal",
            fields = mapOf("sessionId" to signal.sessionId, "kind" to signal.kind.name, "target" to signal.target),
        )
        callbackScope.launch {
            outgoingSignalFlow.emit(signal)
        }
    }

    private fun emitIncomingData(frame: WebRtcDataFrame) {
        callbackScope.launch {
            incomingDataFlow.emit(frame)
        }
    }

    private fun emitSessionEvent(event: WebRtcSessionEvent) {
        when (event) {
            is WebRtcSessionEvent.Failed -> logger.warn(
                component = LogComponent.WEBRTC_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "WebRTC backend session failed",
                fields = mapOf("sessionId" to event.sessionId, "peer" to event.peer, "reason" to event.reason),
            )
            else -> logger.debug(
                component = LogComponent.WEBRTC_BACKEND,
                event = LogEvent.SESSION_STATE_CHANGED,
                message = "WebRTC backend session event",
                fields = mapOf("type" to event::class.simpleName, "sessionId" to when (event) {
                    is WebRtcSessionEvent.Connecting -> event.sessionId
                    is WebRtcSessionEvent.Connected -> event.sessionId
                    is WebRtcSessionEvent.Closed -> event.sessionId
                    is WebRtcSessionEvent.Failed -> event.sessionId
                }),
            )
        }
        callbackScope.launch {
            sessionEventFlow.emit(event)
        }
    }

    private fun emitAvChannelEvent(event: WebRtcAvChannelEvent) {
        callbackScope.launch {
            avChannelEventFlow.emit(event)
        }
    }

    private data class Session(
        val sessionId: String,
        val remotePeer: String,
        val peerConnection: RTCPeerConnection,
        var envelopeDataChannel: RTCDataChannel? = null,
        var avDataChannel: RTCDataChannel? = null,
    ) {
        private val disposed = AtomicBoolean(false)

        fun dispose() {
            if (!disposed.compareAndSet(false, true)) return
            runCatching {
                envelopeDataChannel?.unregisterObserver()
                envelopeDataChannel?.close()
                envelopeDataChannel?.dispose()
                avDataChannel?.unregisterObserver()
                avDataChannel?.close()
                avDataChannel?.dispose()
            }
            runCatching { peerConnection.close() }
        }
    }
}

private fun encodeIceCandidate(candidate: RTCIceCandidate): ByteArray {
    val mid = candidate.sdpMid
    val sdp = candidate.sdp
    val midBytes = mid.encodeToByteArray()
    val sdpBytes = sdp.encodeToByteArray()
    val buffer = ByteBuffer.allocate(2 + midBytes.size + 4 + 4 + sdpBytes.size)
    buffer.putShort(midBytes.size.toShort())
    buffer.put(midBytes)
    buffer.putInt(candidate.sdpMLineIndex)
    buffer.putInt(sdpBytes.size)
    buffer.put(sdpBytes)
    return buffer.array()
}

private fun decodeIceCandidate(bytes: ByteArray): RTCIceCandidate? {
    if (bytes.size < 2 + 4 + 4) return null
    return runCatching {
        val buffer = ByteBuffer.wrap(bytes)
        val midLen = buffer.short.toInt() and 0xffff
        if (midLen < 0 || midLen > buffer.remaining()) return null
        val midBytes = ByteArray(midLen)
        buffer.get(midBytes)
        val mLineIndex = buffer.int
        val sdpLen = buffer.int
        if (sdpLen < 0 || sdpLen > buffer.remaining()) return null
        val sdpBytes = ByteArray(sdpLen)
        buffer.get(sdpBytes)
        RTCIceCandidate(
            midBytes.decodeToString(),
            mLineIndex,
            sdpBytes.decodeToString(),
        )
    }.getOrNull()
}

