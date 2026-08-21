package org.yapyap.transport.webrtc.backend

import dev.onvoid.webrtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.transport.webrtc.types.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class JvmWebRtcBackend(
    private val config: WebRtcBackendConfig = WebRtcBackendConfig(),
) : WebRtcBackend {

    private val outgoingSignalFlow = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val incomingDataFlow = MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 64)
    private val sessionEventFlow = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)
    private val avChannelEventFlow = MutableSharedFlow<WebRtcAvChannelEvent>(extraBufferCapacity = 64)

    override val outgoingSignals: Flow<WebRtcSignal> = outgoingSignalFlow.asSharedFlow()
    override val incomingDataFrames: Flow<WebRtcDataFrame> = incomingDataFlow.asSharedFlow()
    override val sessionEvents: Flow<WebRtcSessionEvent> = sessionEventFlow.asSharedFlow()
    override val avChannelEvents: Flow<WebRtcAvChannelEvent> = avChannelEventFlow.asSharedFlow()

    private var localDevice: PeerId? = null
    private var factory: PeerConnectionFactory? = null
    private val sessions = ConcurrentHashMap<PeerId, Session>()
    private var scope: CoroutineScope? = null

    override suspend fun start(localDevice: PeerId) {
        check(this.localDevice == null) { "WebRTC backend is already started" }
        this.localDevice = localDevice
        this.factory = PeerConnectionFactory()
        this.scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AppLog.info(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.STARTED,
            message = "JVM WebRTC backend started",
            fields = mapOf("deviceId" to localDevice),
        )
    }

    override suspend fun stop() {
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        scope?.cancel()
        scope = null
        factory?.dispose()
        factory = null
        localDevice = null
        AppLog.info(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.STOPPED,
            message = "JVM WebRTC backend stopped",
        )
    }

    override suspend fun openSession(target: PeerId) {
        val local = requireNotNull(localDevice) { "WebRTC backend must be started before opening session" }
        if (sessions.containsKey(target)) return

        val sessionRef = AtomicReference<Session?>()
        val pc = createPeerConnection(target, sessionRef)
        val session = Session(remotePeer = target, peerConnection = pc)
        sessionRef.set(session)

        if (sessions.putIfAbsent(target, session) != null) {
            session.dispose()
            return
        }

        val channelInit = RTCDataChannelInit().also { init ->
            init.ordered = config.orderedDataChannel
            config.maxRetransmits?.let { init.maxRetransmits = it }
            config.maxPacketLifeTimeMs?.let { init.maxPacketLifeTime = it }
        }
        val channel = pc.createDataChannel(envelopeChannelLabel(local, target), channelInit)
        attachDataChannel(session, channel, WebRtcDataType.ENVELOPE_BINARY)
        emitSessionEvent(WebRtcSessionEvent.Connecting(peer = target))

        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "Opened outbound session (offerer); awaiting renegotiation callback",
            fields = mapOf("peer" to target),
        )
    }

    override suspend fun handleRemoteSignal(signal: WebRtcSignal) {
        val local = requireNotNull(localDevice) { "WebRTC backend must be started before applying remote signal" }
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Applying inbound WebRTC signal",
            fields = mapOf("kind" to signal.kind.name, "source" to signal.source),
        )
        when (signal.kind) {
            WebRtcSignalKind.OFFER -> handleRemoteOffer(local, signal)
            WebRtcSignalKind.ANSWER -> handleRemoteAnswer(signal)
            WebRtcSignalKind.ICE -> handleRemoteIce(signal)
            WebRtcSignalKind.REJECT,
            WebRtcSignalKind.CANCEL,
            -> teardownRemote(signal.source, "Remote ${signal.kind.name.lowercase()}")
        }
    }

    override fun hasSession(target: PeerId): Boolean = sessions.containsKey(target)

    override suspend fun closeSession(target: PeerId) {
        check(localDevice != null) { "WebRTC backend must be started before closing session" }
        val session = sessions.remove(target) ?: return
        session.signalMutex.withLock {
            session.dispose()
            emitSessionEvent(WebRtcSessionEvent.Closed(session.remotePeer))
        }
    }

    override suspend fun sendData(dataFrame: WebRtcDataFrame) {
        check(localDevice != null) { "WebRTC backend must be started before sending data" }
        val local = requireNotNull(localDevice)
        require(dataFrame.source == local) { "Frame source mismatch for local device ${dataFrame.source}" }
        val session = sessions[dataFrame.target] ?: error("Unknown session for target: ${dataFrame.target}")
        require(session.remotePeer == dataFrame.target) { "Session target mismatch for target ${dataFrame.target}" }

        require(dataFrame.payload.size <= config.maxPayloadBytes) {
            "Payload length ${dataFrame.payload.size} exceeds configured max ${config.maxPayloadBytes}"
        }

        val channel = session.channelFor(dataFrame.dataType)
        if (channel?.state != RTCDataChannelState.OPEN) {
            awaitChannelOpen(session, dataFrame.dataType)
        }
        val openChannel = session.channelFor(dataFrame.dataType)
            ?: error("No ${dataFrame.dataType} channel available for target: ${dataFrame.target}")
        check(openChannel.state == RTCDataChannelState.OPEN) {
            "Data channel is not open for target: ${dataFrame.target}"
        }
        openChannel.send(RTCDataChannelBuffer(ByteBuffer.wrap(dataFrame.payload), true))
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Sent WebRTC data frame",
            fields = mapOf(
                "target" to dataFrame.target,
                "dataType" to dataFrame.dataType.name,
                "payloadSize" to dataFrame.payload.size,
            ),
        )
    }

    override suspend fun addAvChannel(target: PeerId) {
        check(localDevice != null) { "WebRTC backend must be started before adding AV channel" }
        val local = requireNotNull(localDevice)
        val session = sessions[target] ?: error("Unknown target: $target")
        session.signalMutex.withLock {
            if (session.disposed.get()) error("Session disposed for target: $target")
            val existing = session.avDataChannel
            if (existing != null && existing.state != RTCDataChannelState.CLOSED) {
                emitAvChannelEvent(WebRtcAvChannelEvent.Active(peer = target))
                return@withLock
            }
            val channelInit = RTCDataChannelInit().also { init ->
                init.ordered = false
                init.maxRetransmits = 0
            }
            val channel = session.peerConnection.createDataChannel(avChannelLabel(local, target), channelInit)
            attachDataChannel(session, channel, WebRtcDataType.AV_DATA)
            emitAvChannelEvent(WebRtcAvChannelEvent.Adding(peer = target))
            AppLog.debug(
                component = LogComponent.WEBRTC_BACKEND,
                event = LogEvent.SESSION_STATE_CHANGED,
                message = "Added AV data channel; renegotiation pending",
                fields = mapOf("peer" to target),
            )
        }
    }

    override suspend fun removeAvChannel(target: PeerId) {
        check(localDevice != null) { "WebRTC backend must be started before removing AV channel" }
        val session = sessions[target] ?: return
        session.signalMutex.withLock {
            val channel = session.avDataChannel ?: return@withLock
            session.avDataChannel = null
            runCatching {
                channel.unregisterObserver()
                channel.close()
                channel.dispose()
            }
            session.avChannelOpen = CompletableDeferred()
            emitAvChannelEvent(WebRtcAvChannelEvent.Removed(peer = target))
            AppLog.debug(
                component = LogComponent.WEBRTC_BACKEND,
                event = LogEvent.SESSION_STATE_CHANGED,
                message = "AV data channel removed; renegotiation pending",
                fields = mapOf("peer" to target),
            )
        }
    }

    private suspend fun handleRemoteOffer(local: PeerId, signal: WebRtcSignal) {
        val remote = signal.source
        val polite = isPolite(local, remote)
        val isNew = !sessions.containsKey(remote)

        val sessionRef = AtomicReference<Session?>()
        val session = sessions.computeIfAbsent(remote) {
            val pc = createPeerConnection(remote, sessionRef)
            Session(remotePeer = remote, peerConnection = pc).also { s -> sessionRef.set(s) }
        }

        session.signalMutex.withLock {
            if (session.disposed.get()) return@withLock
            val state = session.peerConnection.signalingState

            if (state == RTCSignalingState.HAVE_LOCAL_OFFER) {
                if (!polite) {
                    AppLog.info(
                        component = LogComponent.WEBRTC_BACKEND,
                        event = LogEvent.SESSION_STATE_CHANGED,
                        message = "Glare: impolite, ignoring inbound offer",
                        fields = mapOf("peer" to remote, "local" to local, "state" to state.name),
                    )
                    return@withLock
                }
                AppLog.info(
                    component = LogComponent.WEBRTC_BACKEND,
                    event = LogEvent.SESSION_STATE_CHANGED,
                    message = "Glare: polite, rolling back local offer",
                    fields = mapOf("peer" to remote, "local" to local),
                )
                runCatching {
                    session.peerConnection.setLocalDescriptionSuspending(
                        RTCSessionDescription(RTCSdpType.ROLLBACK, "")
                    )
                }.onFailure { err ->
                    failSession(session, "Rollback failed: ${err.message ?: err}")
                    return@withLock
                }
                disposeEnvelopeChannel(session)
                session.envelopeChannelOpen = CompletableDeferred()
            } else if (isNew) {
                AppLog.debug(
                    component = LogComponent.WEBRTC_BACKEND,
                    event = LogEvent.SESSION_STATE_CHANGED,
                    message = "Accepting inbound offer as answerer",
                    fields = mapOf("peer" to remote),
                )
            }

            emitSessionEvent(WebRtcSessionEvent.Connecting(remote))
            val offer = RTCSessionDescription(RTCSdpType.OFFER, signal.payload.decodeToString())
            runCatching {
                session.peerConnection.setRemoteDescriptionSuspending(offer)
            }.onFailure { err ->
                failSession(session, "Failed to set remote offer: ${err.message ?: err}")
                return@withLock
            }
            session.remoteDescriptionApplied = true
            drainPendingIce(session)

            val answer = runCatching {
                session.peerConnection.createAnswerSuspending(RTCAnswerOptions())
            }.onFailure { err ->
                failSession(session, "Failed to create answer: ${err.message ?: err}")
                return@withLock
            }.getOrThrow()

            runCatching {
                session.peerConnection.setLocalDescriptionSuspending(answer)
            }.onFailure { err ->
                failSession(session, "Failed to set local answer: ${err.message ?: err}")
                return@withLock
            }

            emitSignal(
                WebRtcSignal(
                    kind = WebRtcSignalKind.ANSWER,
                    source = local,
                    target = remote,
                    payload = answer.sdp.encodeToByteArray(),
                )
            )
        }
    }

    private suspend fun handleRemoteAnswer(signal: WebRtcSignal) {
        val remote = signal.source
        val session = sessions[remote] ?: return
        session.signalMutex.withLock {
            if (session.disposed.get()) return@withLock
            val state = session.peerConnection.signalingState
            if (state != RTCSignalingState.HAVE_LOCAL_OFFER) {
                AppLog.warn(
                    component = LogComponent.WEBRTC_BACKEND,
                    event = LogEvent.SESSION_FAILED,
                    message = "Received ANSWER in unexpected signaling state; ignoring",
                    fields = mapOf("peer" to remote, "state" to state.name),
                )
                return@withLock
            }
            val answer = RTCSessionDescription(RTCSdpType.ANSWER, signal.payload.decodeToString())
            runCatching {
                session.peerConnection.setRemoteDescriptionSuspending(answer)
            }.onFailure { err ->
                failSession(session, "Failed to set remote answer: ${err.message ?: err}")
                return@withLock
            }
            session.remoteDescriptionApplied = true
            drainPendingIce(session)
            maybeRenegotiateLocked(session)
        }
    }

    private suspend fun handleRemoteIce(signal: WebRtcSignal) {
        val remote = signal.source
        val session = sessions[remote] ?: return
        val candidate = decodeIceCandidate(signal.payload) ?: return
        session.signalMutex.withLock {
            if (session.disposed.get()) return@withLock
            if (session.remoteDescriptionApplied) {
                runCatching { session.peerConnection.addIceCandidate(candidate) }
            } else {
                session.pendingIceCandidates.add(candidate)
                AppLog.debug(
                    component = LogComponent.WEBRTC_BACKEND,
                    event = LogEvent.SIGNAL_INBOUND_HANDLED,
                    message = "Buffered ICE candidate (no remote description yet)",
                    fields = mapOf("peer" to remote, "buffered" to session.pendingIceCandidates.size),
                )
            }
        }
    }

    private suspend fun teardownRemote(remote: PeerId, reason: String) {
        val session = sessions.remove(remote) ?: return
        session.signalMutex.withLock {
            session.dispose()
            emitSessionEvent(WebRtcSessionEvent.Closed(session.remotePeer))
        }
        AppLog.info(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "Tore down session",
            fields = mapOf("peer" to remote, "reason" to reason),
        )
    }

    private fun drainPendingIce(session: Session) {
        val pending = session.pendingIceCandidates
        if (pending.isEmpty()) return
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_INBOUND_HANDLED,
            message = "Draining buffered ICE candidates",
            fields = mapOf("peer" to session.remotePeer, "count" to pending.size),
        )
        pending.forEach { candidate ->
            runCatching { session.peerConnection.addIceCandidate(candidate) }
        }
        pending.clear()
    }

    private suspend fun maybeRenegotiateLocked(session: Session) {
        if (session.disposed.get()) return
        if (!session.renegotiationPending.compareAndSet(true, false)) return
        val state = session.peerConnection.signalingState
        if (state != RTCSignalingState.STABLE) {
            session.renegotiationPending.set(true)
            return
        }
        val local = requireNotNull(localDevice)
        val offer = runCatching {
            session.peerConnection.createOfferSuspending(RTCOfferOptions())
        }.onFailure { err ->
            failSession(session, "Renegotiation createOffer failed: ${err.message ?: err}")
            return
        }.getOrThrow()

        runCatching {
            session.peerConnection.setLocalDescriptionSuspending(offer)
        }.onFailure { err ->
            failSession(session, "Renegotiation setLocalDescription failed: ${err.message ?: err}")
            return
        }

        emitSignal(
            WebRtcSignal(
                kind = WebRtcSignalKind.OFFER,
                source = local,
                target = session.remotePeer,
                payload = offer.sdp.encodeToByteArray(),
            )
        )
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Emitted renegotiation offer",
            fields = mapOf("peer" to session.remotePeer),
        )
    }

    private fun failSession(session: Session, reason: String) {
        sessions.remove(session.remotePeer, session)
        session.dispose()
        emitSessionEvent(WebRtcSessionEvent.Failed(peer = session.remotePeer, reason = reason))
    }

    private fun disposeEnvelopeChannel(session: Session) {
        val channel = session.envelopeDataChannel ?: return
        session.envelopeDataChannel = null
        runCatching {
            channel.unregisterObserver()
            channel.close()
            channel.dispose()
        }
    }

    private fun isPolite(local: PeerId, remote: PeerId): Boolean = local.id > remote.id

    private fun createPeerConnection(
        targetId: PeerId,
        sessionRef: AtomicReference<Session?>,
    ): RTCPeerConnection {
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
                            kind = WebRtcSignalKind.ICE,
                            source = local,
                            target = targetId,
                            payload = encodeIceCandidate(candidate),
                        )
                    )
                }

                override fun onConnectionChange(state: RTCPeerConnectionState) {
                    when (state) {
                        RTCPeerConnectionState.CONNECTING ->
                            emitSessionEvent(WebRtcSessionEvent.Connecting(targetId))

                        RTCPeerConnectionState.CONNECTED ->
                            emitSessionEvent(WebRtcSessionEvent.Connected(targetId))

                        RTCPeerConnectionState.FAILED ->
                            emitSessionEvent(
                                WebRtcSessionEvent.Failed(
                                    peer = targetId,
                                    reason = "Peer connection entered FAILED state",
                                )
                            )

                        RTCPeerConnectionState.CLOSED ->
                            emitSessionEvent(WebRtcSessionEvent.Closed(targetId))

                        RTCPeerConnectionState.DISCONNECTED,
                        RTCPeerConnectionState.NEW,
                        -> Unit
                    }
                }

                override fun onDataChannel(channel: RTCDataChannel) {
                    val session = sessionRef.get() ?: return
                    val dataType = inferDataTypeFromLabel(channel.label)
                    AppLog.debug(
                        component = LogComponent.WEBRTC_BACKEND,
                        event = LogEvent.SESSION_STATE_CHANGED,
                        message = "Attached inbound data channel",
                        fields = mapOf("peer" to session.remotePeer, "dataType" to dataType.name, "label" to channel.label),
                    )
                    attachDataChannel(session, channel, dataType)
                }

                override fun onRenegotiationNeeded() {
                    val session = sessionRef.get() ?: return
                    session.renegotiationPending.set(true)
                    scope?.launch { session.signalMutex.withLock { maybeRenegotiateLocked(session) } }
                }
            }
        )
    }

    private fun attachDataChannel(session: Session, channel: RTCDataChannel, dataType: WebRtcDataType) {
        when (dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> session.envelopeDataChannel = channel
            WebRtcDataType.AV_DATA -> {
                session.avDataChannel = channel
                emitAvChannelEvent(WebRtcAvChannelEvent.Adding(peer = session.remotePeer))
            }
        }
        channel.registerObserver(
            object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    val state = channel.state
                    when (dataType) {
                        WebRtcDataType.ENVELOPE_BINARY -> {
                            if (state == RTCDataChannelState.OPEN) {
                                session.envelopeChannelOpen.complete(Unit)
                            } else if (state == RTCDataChannelState.CLOSED) {
                                session.envelopeChannelOpen = CompletableDeferred()
                            }
                        }

                        WebRtcDataType.AV_DATA -> {
                            when (state) {
                                RTCDataChannelState.OPEN -> {
                                    session.avChannelOpen.complete(Unit)
                                    emitAvChannelEvent(WebRtcAvChannelEvent.Active(peer = session.remotePeer))
                                }

                                RTCDataChannelState.CLOSED -> {
                                    emitAvChannelEvent(WebRtcAvChannelEvent.Removed(peer = session.remotePeer))
                                }

                                else -> Unit
                            }
                        }
                    }
                }

                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    AppLog.debug(
                        component = LogComponent.WEBRTC_BACKEND,
                        event = LogEvent.SIGNAL_INBOUND_HANDLED,
                        message = "Received WebRTC data frame",
                        fields = mapOf(
                            "peer" to session.remotePeer,
                            "dataType" to dataType.name,
                            "payloadSize" to bytes.size,
                        ),
                    )
                    emitIncomingData(
                        WebRtcDataFrame(
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

    private fun inferDataTypeFromLabel(label: String): WebRtcDataType {
        if (label.contains("-av-")) return WebRtcDataType.AV_DATA
        return WebRtcDataType.ENVELOPE_BINARY
    }

    private fun envelopeChannelLabel(local: PeerId, target: PeerId): String =
        "yapyap-env-${local.id}-${target.id}"

    private fun avChannelLabel(local: PeerId, target: PeerId): String =
        "yapyap-av-${local.id}-${target.id}"

    private suspend fun awaitChannelOpen(session: Session, dataType: WebRtcDataType) {
        val deferred = when (dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> session.envelopeChannelOpen
            WebRtcDataType.AV_DATA -> session.avChannelOpen
        }
        withTimeoutOrNull(CHANNEL_OPEN_TIMEOUT) {
            deferred.await()
        } ?: error("Channel did not open within $CHANNEL_OPEN_TIMEOUT for ${session.remotePeer}")
    }

    private fun emitSignal(signal: WebRtcSignal) {
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Emitting outbound WebRTC signal",
            fields = mapOf("kind" to signal.kind.name, "target" to signal.target),
        )
        scope?.launch { outgoingSignalFlow.emit(signal) }
    }

    private fun emitIncomingData(frame: WebRtcDataFrame) {
        scope?.launch { incomingDataFlow.emit(frame) }
    }

    private fun emitSessionEvent(event: WebRtcSessionEvent) {
        when (event) {
            is WebRtcSessionEvent.Failed -> AppLog.warn(
                component = LogComponent.WEBRTC_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "WebRTC backend session failed",
                fields = mapOf("peer" to event.peer, "reason" to event.reason),
            )

            else -> AppLog.debug(
                component = LogComponent.WEBRTC_BACKEND,
                event = LogEvent.SESSION_STATE_CHANGED,
                message = "WebRTC backend session event",
                fields = mapOf("type" to event::class.simpleName),
            )
        }
        scope?.launch { sessionEventFlow.emit(event) }
    }

    private fun emitAvChannelEvent(event: WebRtcAvChannelEvent) {
        scope?.launch { avChannelEventFlow.emit(event) }
    }

    private class Session(
        val remotePeer: PeerId,
        val peerConnection: RTCPeerConnection,
    ) {
        val signalMutex = Mutex()
        val disposed = AtomicBoolean(false)

        @Volatile var envelopeDataChannel: RTCDataChannel? = null
        @Volatile var avDataChannel: RTCDataChannel? = null

        @Volatile var remoteDescriptionApplied: Boolean = false
        val pendingIceCandidates = mutableListOf<RTCIceCandidate>()
        val renegotiationPending = AtomicBoolean(false)

        @Volatile var envelopeChannelOpen: CompletableDeferred<Unit> = CompletableDeferred()
        @Volatile var avChannelOpen: CompletableDeferred<Unit> = CompletableDeferred()

        fun channelFor(dataType: WebRtcDataType): RTCDataChannel? = when (dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> envelopeDataChannel
            WebRtcDataType.AV_DATA -> avDataChannel
        }

        fun dispose() {
            if (!disposed.compareAndSet(false, true)) return
            runCatching {
                envelopeDataChannel?.unregisterObserver()
                envelopeDataChannel?.close()
                envelopeDataChannel?.dispose()
            }
            runCatching {
                avDataChannel?.unregisterObserver()
                avDataChannel?.close()
                avDataChannel?.dispose()
            }
            runCatching { peerConnection.close() }
        }
    }

    private companion object {
        val CHANNEL_OPEN_TIMEOUT = 30.seconds
    }
}

private suspend fun RTCPeerConnection.createOfferSuspending(options: RTCOfferOptions): RTCSessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(
            options,
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    cont.resumeWith(Result.success(description))
                }

                override fun onFailure(error: String) {
                    cont.resumeWith(Result.failure(WebRtcBackendException("createOffer failed: $error")))
                }
            }
        )
    }

private suspend fun RTCPeerConnection.createAnswerSuspending(options: RTCAnswerOptions): RTCSessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(
            options,
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    cont.resumeWith(Result.success(description))
                }

                override fun onFailure(error: String) {
                    cont.resumeWith(Result.failure(WebRtcBackendException("createAnswer failed: $error")))
                }
            }
        )
    }

private suspend fun RTCPeerConnection.setLocalDescriptionSuspending(description: RTCSessionDescription) {
    suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(
            description,
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    cont.resumeWith(Result.success(Unit))
                }

                override fun onFailure(error: String) {
                    cont.resumeWith(Result.failure(WebRtcBackendException("setLocalDescription failed: $error")))
                }
            }
        )
    }
}

private suspend fun RTCPeerConnection.setRemoteDescriptionSuspending(description: RTCSessionDescription) {
    suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(
            description,
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    cont.resumeWith(Result.success(Unit))
                }

                override fun onFailure(error: String) {
                    cont.resumeWith(Result.failure(WebRtcBackendException("setRemoteDescription failed: $error")))
                }
            }
        )
    }
}

class WebRtcBackendException(message: String) : RuntimeException(message)

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
        if (midLen > buffer.remaining()) return null
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
