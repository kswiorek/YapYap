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
import org.yapyap.backend.protocol.DeviceAddress
import org.yapyap.backend.transport.webrtc.types.AvControlUpdate
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import org.yapyap.backend.transport.webrtc.types.WebRtcSignalKind

class JvmWebRtcBackend(
    private val config: WebRtcConfig = WebRtcConfig(),
) : WebRtcBackend {

    private val outgoingSignalFlow = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val incomingDataFlow = MutableSharedFlow<WebRtcIncomingDataFrame>(extraBufferCapacity = 64)
    private val sessionEventFlow = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)
    private val avSessionEventFlow = MutableSharedFlow<WebRtcAvSessionEvent>(extraBufferCapacity = 64)
    private val callbackScope = CoroutineScope(Dispatchers.Default)

    override val outgoingSignals: Flow<WebRtcSignal> = outgoingSignalFlow.asSharedFlow()
    override val incomingDataFrames: Flow<WebRtcIncomingDataFrame> = incomingDataFlow.asSharedFlow()
    override val sessionEvents: Flow<WebRtcSessionEvent> = sessionEventFlow.asSharedFlow()
    override val avSessionEvents: Flow<WebRtcAvSessionEvent> = avSessionEventFlow.asSharedFlow()

    private var localDevice: DeviceAddress? = null
    private var factory: PeerConnectionFactory? = null
    private val sessions = ConcurrentHashMap<String, Session>()

    override suspend fun start(localDevice: DeviceAddress) {
        check(this.localDevice == null) { "WebRTC backend is already started" }
        this.localDevice = localDevice
        this.factory = PeerConnectionFactory()
    }

    override suspend fun stop() {
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        factory?.dispose()
        factory = null
        localDevice = null
    }

    override suspend fun openSession(target: DeviceAddress, sessionId: String) {
        val local = requireNotNull(localDevice) { "WebRTC backend must be started before opening session" }
        val peerConnection = createPeerConnection(sessionId = sessionId, remotePeer = target)
        val session = Session(sessionId = sessionId, remotePeer = target, peerConnection = peerConnection)
        check(sessions.putIfAbsent(sessionId, session) == null) { "Session already exists: $sessionId" }

        val channelInit = RTCDataChannelInit().also { init ->
            init.ordered = config.orderedDataChannel
            config.maxRetransmits?.let { init.maxRetransmits = it }
            config.maxPacketLifeTimeMs?.let { init.maxPacketLifeTime = it }
        }
        val label = "${config.dataChannelLabelPrefix}-$sessionId"
        val channel = peerConnection.createDataChannel(label, channelInit)
        attachDataChannel(session, channel)
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
            WebRtcSignalKind.AV_OFFER,
            WebRtcSignalKind.AV_ANSWER,
            WebRtcSignalKind.AV_UPDATE,
            WebRtcSignalKind.AV_END,
            WebRtcSignalKind.AV_REJECT,
            -> Unit
        }
    }

    override suspend fun closeSession(sessionId: String) {
        check(localDevice != null) { "WebRTC backend must be started before closing session" }
        val session = sessions.remove(sessionId) ?: return
        session.dispose()
        emitSessionEvent(WebRtcSessionEvent.Closed(sessionId, session.remotePeer))
    }

    override suspend fun sendData(sessionId: String, target: DeviceAddress, payload: ByteArray) {
        check(localDevice != null) { "WebRTC backend must be started before sending data" }
        val session = sessions[sessionId] ?: error("Unknown sessionId: $sessionId")
        check(session.remotePeer == target) { "Session target mismatch for sessionId $sessionId" }
        val channel = session.dataChannel ?: error("No data channel available for sessionId: $sessionId")
        check(channel.state == RTCDataChannelState.OPEN) { "Data channel is not open for sessionId: $sessionId" }
        channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(payload), true))
    }

    override suspend fun openAvSession(target: DeviceAddress, sessionId: String, options: AvSessionOptions) {
        check(localDevice != null) { "WebRTC backend must be started before opening AV session" }
        val local = requireNotNull(localDevice)
        emitSignal(
            WebRtcSignal(
                sessionId = sessionId,
                kind = WebRtcSignalKind.AV_OFFER,
                source = local,
                target = target,
                payload = encodeAvSessionOptions(options),
            )
        )
        emitAvSessionEvent(
            WebRtcAvSessionEvent.Negotiating(
                sessionId = sessionId,
                peer = target,
                options = options,
            )
        )
    }

    override suspend fun acceptAvSession(sessionId: String, options: AvSessionOptions) {
        val peer = sessions[sessionId]?.remotePeer
        if (peer != null) {
            emitAvSessionEvent(WebRtcAvSessionEvent.Active(sessionId = sessionId, peer = peer, options = options))
        }
    }

    override suspend fun rejectAvSession(sessionId: String, reason: String) {
        val peer = sessions[sessionId]?.remotePeer ?: return
        emitAvSessionEvent(WebRtcAvSessionEvent.Rejected(sessionId = sessionId, peer = peer, reason = reason))
    }

    override suspend fun updateAvControls(sessionId: String, update: AvControlUpdate) {
        check(localDevice != null) { "WebRTC backend must be started before updating AV controls" }
    }

    override suspend fun closeAvSession(sessionId: String) {
        val peer = sessions[sessionId]?.remotePeer ?: return
        emitAvSessionEvent(WebRtcAvSessionEvent.Ended(sessionId = sessionId, peer = peer))
    }

    private fun createPeerConnection(sessionId: String, remotePeer: DeviceAddress): RTCPeerConnection {
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
                    sessions[sessionId]?.let { attachDataChannel(it, channel) }
                }
            }
        )
    }

    private fun attachDataChannel(session: Session, channel: RTCDataChannel) {
        session.dataChannel = channel
        channel.registerObserver(
            object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() = Unit

                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    emitIncomingData(
                        WebRtcIncomingDataFrame(
                            sessionId = session.sessionId,
                            source = session.remotePeer,
                            payload = bytes,
                        )
                    )
                }
            }
        )
    }

    private fun emitSignal(signal: WebRtcSignal) {
        callbackScope.launch {
            outgoingSignalFlow.emit(signal)
        }
    }

    private fun emitIncomingData(frame: WebRtcIncomingDataFrame) {
        callbackScope.launch {
            incomingDataFlow.emit(frame)
        }
    }

    private fun emitSessionEvent(event: WebRtcSessionEvent) {
        callbackScope.launch {
            sessionEventFlow.emit(event)
        }
    }

    private fun emitAvSessionEvent(event: WebRtcAvSessionEvent) {
        callbackScope.launch {
            avSessionEventFlow.emit(event)
        }
    }

    private data class Session(
        val sessionId: String,
        val remotePeer: DeviceAddress,
        val peerConnection: RTCPeerConnection,
        var dataChannel: RTCDataChannel? = null,
    ) {
        private val disposed = AtomicBoolean(false)

        fun dispose() {
            if (!disposed.compareAndSet(false, true)) return
            runCatching {
                dataChannel?.unregisterObserver()
                dataChannel?.close()
                dataChannel?.dispose()
            }
            runCatching { peerConnection.close() }
        }
    }
}

private fun encodeAvSessionOptions(options: AvSessionOptions): ByteArray {
    val flags = (if (options.audioEnabled) 1 else 0) or
        (if (options.videoEnabled) 1 shl 1 else 0) or
        (if (options.screenShareEnabled) 1 shl 2 else 0)
    return byteArrayOf(flags.toByte(), options.qualityTier.ordinal.toByte())
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

