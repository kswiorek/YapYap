package org.yapyap.transport.webrtc.backend

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
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalKind
import org.yapyap.transport.TransportException
import org.yapyap.transport.webrtc.types.FILE_CHANNEL_PREFIX
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcAvChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcDataType
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import org.yapyap.transport.webrtc.types.WebRtcSessionEvent
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

internal class JvmWebRtcBackend(
    private val config: StateFlow<WebRtcBackendConfig>,
) : WebRtcBackend {

    private val outgoingSignalFlow = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val incomingDataFlow = MutableSharedFlow<WebRtcDataFrame>(extraBufferCapacity = 256)
    private val incomingFileFlow = MutableSharedFlow<WebRtcFileFrame>(extraBufferCapacity = 256)
    private val sessionEventFlow = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)
    private val avChannelEventFlow = MutableSharedFlow<WebRtcAvChannelEvent>(extraBufferCapacity = 64)
    private val fileChannelEventFlow = MutableSharedFlow<WebRtcFileChannelEvent>(extraBufferCapacity = 256)

    override val outgoingSignals: Flow<WebRtcSignal> = outgoingSignalFlow.asSharedFlow()
    override val incomingDataFrames: Flow<WebRtcDataFrame> = incomingDataFlow.asSharedFlow()
    override val incomingFileFrames: Flow<WebRtcFileFrame> = incomingFileFlow.asSharedFlow()
    override val sessionEvents: Flow<WebRtcSessionEvent> = sessionEventFlow.asSharedFlow()
    override val avChannelEvents: Flow<WebRtcAvChannelEvent> = avChannelEventFlow.asSharedFlow()
    override val fileChannelEvents: Flow<WebRtcFileChannelEvent> = fileChannelEventFlow.asSharedFlow()

    private var localDevice: PeerId? = null
    private var factory: PeerConnectionFactory? = null
    private val sessions = ConcurrentHashMap<PeerId, Session>()
    private var scope: CoroutineScope? = null

    // Ordered delivery: every native callback trySends into a queue drained by one
    // coroutine, so frames from one channel land in order without a launch per message.
    // File channels have their own queues and flow ([FileChannelInbox]), so a stalled file
    // consumer never delays envelopes.
    private val signalQueue = Channel<WebRtcSignal>(Channel.UNLIMITED)
    private val sessionEventQueue = Channel<WebRtcSessionEvent>(Channel.UNLIMITED)
    private val avEventQueue = Channel<WebRtcAvChannelEvent>(Channel.UNLIMITED)
    private val fileEventQueue = Channel<WebRtcFileChannelEvent>(Channel.UNLIMITED)
    private val envelopeQueues = ConcurrentHashMap<PeerId, Channel<WebRtcDataFrame>>()
    private val envelopeDrainJobs = ConcurrentHashMap<PeerId, Job>()
    private var fileInbox: FileChannelInbox? = null

    //TODO: [Finishing touches] renegotiate without removing session
    override suspend fun start(localDevice: PeerId) {
        check(this.localDevice == null) { "WebRTC backend is already started" }
        this.localDevice = localDevice
        this.factory = PeerConnectionFactory()
        val startedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        this.scope = startedScope
        this.fileInbox = FileChannelInbox(startedScope) { frame -> incomingFileFlow.emit(frame) }
        // One drainer per queue: callbacks only trySend, never launch.
        startedScope.launch {
            for (signal in signalQueue) outgoingSignalFlow.emit(signal)
        }
        startedScope.launch {
            for (event in sessionEventQueue) sessionEventFlow.emit(event)
        }
        startedScope.launch {
            for (event in avEventQueue) avChannelEventFlow.emit(event)
        }
        startedScope.launch {
            for (event in fileEventQueue) fileChannelEventFlow.emit(event)
        }
        AppLog.info(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.STARTED,
            message = "JVM WebRTC backend started",
            fields = mapOf("deviceId" to localDevice),
        )
    }

    override suspend fun stop() {
        val drainTimeout = config.value.drainChannelTimeout

        // Drain envelope channels so buffered LogOff/outbound data hits the wire first.
        sessions.values.forEach { it.drainEnvelopeChannel(drainTimeout) }

        sessions.values.forEach { it.dispose() }
        sessions.clear()
        envelopeDrainJobs.values.forEach { it.cancel() }
        envelopeDrainJobs.clear()
        envelopeQueues.values.forEach { it.close() }
        envelopeQueues.clear()
        // A stop while backpressured releases the holders waiting in awaitBufferedBelow.
        fileInbox?.closeAll()
        fileInbox = null
        // Join the drainers first: their last events must not outlive the purge below.
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        // Drop stale events so a restart does not replay pre-stop signals or channel events.
        while (signalQueue.tryReceive().isSuccess) Unit
        while (sessionEventQueue.tryReceive().isSuccess) Unit
        while (avEventQueue.tryReceive().isSuccess) Unit
        while (fileEventQueue.tryReceive().isSuccess) Unit
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

        val channel = pc.createDataChannel(
            envelopeChannelLabel(local, target), dataChannelInit(config.value, WebRtcDataType.ENVELOPE_BINARY),
        )
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
            WebRtcSignalKind.REJECT -> {
                val reason = signal.payload.decodeToString().ifEmpty { "Remote reject" }
                teardownRemote(signal.source, reason, rejected = true)
            }

            WebRtcSignalKind.CANCEL ->
                teardownRemote(signal.source, "Remote ${signal.kind.name.lowercase()}")
        }
    }

    override fun hasSession(target: PeerId): Boolean {
        val session = sessions[target] ?: return false
        val channel = session.envelopeDataChannel ?: return false
        return channel.state == RTCDataChannelState.OPEN
    }

    override suspend fun closeSession(target: PeerId) {
        check(localDevice != null) { "WebRTC backend must be started before closing session" }
        val session = sessions.remove(target) ?: return
        session.signalMutex.withLock {
            closeFileChannels(session, session.dispose())
            envelopeQueues.remove(target)?.close()
            envelopeDrainJobs.remove(target)?.cancel()
            emitSessionEvent(WebRtcSessionEvent.Closed(session.remotePeer))
        }
    }

    override suspend fun sendData(dataFrame: WebRtcDataFrame) {
        check(localDevice != null) { "WebRTC backend must be started before sending data" }
        val local = requireNotNull(localDevice)
        require(dataFrame.source == local) { "Frame source mismatch for local device ${dataFrame.source}" }
        require(dataFrame.dataType != WebRtcDataType.FILE_DATA) { "File bytes go through sendFileFrame" }
        val session = sessions[dataFrame.target] ?: error("Unknown session for target: ${dataFrame.target}")
        require(session.remotePeer == dataFrame.target) { "Session target mismatch for target ${dataFrame.target}" }

        val maxPayloadBytes = config.value.maxPayloadBytes
        if (dataFrame.payload.size > maxPayloadBytes) {
            throw TransportException.PayloadTooLarge(
                sizeBytes = dataFrame.payload.size,
                limitBytes = maxPayloadBytes,
            )
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

    override suspend fun openFileChannel(target: PeerId): FileChannelId {
        check(localDevice != null) { "WebRTC backend must be started before opening file channel" }
        val session = sessions[target] ?: error("Unknown target: $target")
        val id = FileChannelId(target, "$FILE_CHANNEL_PREFIX${Uuid.random()}")
        session.signalMutex.withLock {
            if (session.disposed.get()) error("Session disposed for target: $target")
            val channel = session.peerConnection.createDataChannel(
                id.label, dataChannelInit(config.value, WebRtcDataType.FILE_DATA),
            )
            attachFileChannel(session, channel, id.label)
            emitFileChannelEvent(WebRtcFileChannelEvent.Adding(id))
            AppLog.debug(
                component = LogComponent.WEBRTC_BACKEND,
                event = LogEvent.SESSION_STATE_CHANGED,
                message = "Added file data channel; renegotiation pending",
                fields = mapOf("peer" to target, "channelLabel" to id.label),
            )
        }
        return id
    }

    override suspend fun closeFileChannel(channel: FileChannelId) {
        check(localDevice != null) { "WebRTC backend must be started before closing file channel" }
        val session = sessions[channel.peer]
        if (session != null) {
            session.signalMutex.withLock {
                val rtc = session.takeFileChannel(channel.label)
                if (rtc != null) {
                    closeNative("file channel ${channel.label}") {
                        rtc.unregisterObserver()
                        rtc.close()
                        rtc.dispose()
                    }
                }
                session.fileChannelOpens.remove(channel.label)
            }
        }
        closeInbound(channel)
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "File data channel removed",
            fields = mapOf("peer" to channel.peer, "channelLabel" to channel.label),
        )
    }

    override suspend fun sendFileFrame(channel: FileChannelId, payload: ByteArray) {
        check(localDevice != null) { "WebRTC backend must be started before sending file frame" }
        val maxPayloadBytes = config.value.maxPayloadBytes
        if (payload.size > maxPayloadBytes) {
            throw TransportException.PayloadTooLarge(
                sizeBytes = payload.size,
                limitBytes = maxPayloadBytes,
            )
        }
        val session = sessions[channel.peer] ?: error("Unknown target: ${channel.peer}")
        val rtc = session.fileChannel(channel.label)
        if (rtc?.state != RTCDataChannelState.OPEN) {
            awaitFileChannelOpen(session, channel.label)
        }
        val openChannel = session.fileChannel(channel.label)
            ?: error("No file channel ${channel.label} available for target: ${channel.peer}")
        check(openChannel.state == RTCDataChannelState.OPEN) {
            "File data channel is not open for target: ${channel.peer}"
        }
        openChannel.send(RTCDataChannelBuffer(ByteBuffer.wrap(payload), true))
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Sent WebRTC file frame",
            fields = mapOf(
                "target" to channel.peer,
                "channelLabel" to channel.label,
                "payloadSize" to payload.size,
            ),
        )
    }

    override suspend fun awaitBufferedBelow(channel: FileChannelId, thresholdBytes: Long) {
        require(thresholdBytes >= 0) { "threshold must be >= 0" }
        val session = sessions[channel.peer] ?: return
        fileInbox?.awaitBufferedBelow(channel, thresholdBytes) {
            // Read under the session lock: closes and teardown dispose the native channel under it.
            session.signalMutex.withLock {
                if (session.disposed.get()) null else session.fileChannel(channel.label)?.bufferedAmount
            }
        }
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
            val channel = session.peerConnection.createDataChannel(
                avChannelLabel(local, target), dataChannelInit(config.value, WebRtcDataType.AV_DATA),
            )
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

    private suspend fun teardownRemote(remote: PeerId, reason: String, rejected: Boolean = false) {
        val session = sessions.remove(remote) ?: return
        session.signalMutex.withLock {
            closeFileChannels(session, session.dispose())
            envelopeQueues.remove(remote)?.close()
            envelopeDrainJobs.remove(remote)?.cancel()
            if (rejected) {
                emitSessionEvent(WebRtcSessionEvent.Rejected(session.remotePeer, reason))
            } else {
                emitSessionEvent(WebRtcSessionEvent.Closed(session.remotePeer))
            }
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
        closeFileChannels(session, session.dispose())
        envelopeQueues.remove(session.remotePeer)?.close()
        envelopeDrainJobs.remove(session.remotePeer)?.cancel()
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
            configuration.iceServers = config.value.iceServers.map { serverConfig ->
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

                        // Deliberately no Connected here: peer-connection CONNECTED precedes
                        // the envelope data channel opening, and Connected means the channel
                        // can carry data (see WebRtcSessionState). Usability is signaled by
                        // the channel observer below, which is the single Connected source.
                        RTCPeerConnectionState.CONNECTED -> Unit

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
                        fields = mapOf(
                            "peer" to session.remotePeer,
                            "dataType" to dataType.name,
                            "label" to channel.label
                        ),
                    )
                    if (dataType == WebRtcDataType.FILE_DATA && !(channel.isOrdered && channel.isReliable)) {
                        // File channels are reliable on both ends: a lossy one could drop fetch bytes.
                        AppLog.warn(
                            component = LogComponent.WEBRTC_BACKEND,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "Rejected unreliable inbound file channel",
                            fields = mapOf("peer" to session.remotePeer, "label" to channel.label),
                        )
                        channel.close()
                        channel.dispose()
                        return
                    }
                    attachDataChannel(session, channel, dataType)
                    if (dataType == WebRtcDataType.FILE_DATA) {
                        emitFileChannelEvent(
                            WebRtcFileChannelEvent.Adding(FileChannelId(session.remotePeer, channel.label))
                        )
                    }
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
        // File channels carry their label (one fetch = one channel); envelope/AV are singletons.
        if (dataType == WebRtcDataType.FILE_DATA) {
            attachFileChannel(session, channel, channel.label)
            return
        }
        when (dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> session.envelopeDataChannel = channel
            WebRtcDataType.AV_DATA -> {
                session.avDataChannel = channel
                emitAvChannelEvent(WebRtcAvChannelEvent.Adding(peer = session.remotePeer))
            }
            WebRtcDataType.FILE_DATA -> error("unreachable")
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
                                // Align session events with the hasSession contract: Connected
                                // means the envelope channel can carry data, not just that the
                                // peer connection reached CONNECTED.
                                emitSessionEvent(WebRtcSessionEvent.Connected(session.remotePeer))
                            } else if (state == RTCDataChannelState.CLOSED) {
                                session.envelopeChannelOpen = CompletableDeferred()
                                // The envelope channel is the session's usability predicate
                                // (hasSession reads this same state), so an abrupt close must
                                // surface as a transition rather than go silent and leave a
                                // stale CONNECTED behind. Explicit teardowns unregister this
                                // observer before closing, so they cannot double-emit.
                                emitSessionEvent(WebRtcSessionEvent.Closed(session.remotePeer))
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
                    // Ordered per peer: the callback only trySends into the peer's queue.
                    envelopeQueueFor(session.remotePeer).trySend(
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
        if (label.startsWith(FILE_CHANNEL_PREFIX)) return WebRtcDataType.FILE_DATA
        if (label.contains("-av-")) return WebRtcDataType.AV_DATA
        return WebRtcDataType.ENVELOPE_BINARY
    }

    private fun attachFileChannel(session: Session, channel: RTCDataChannel, channelLabel: String) {
        val id = FileChannelId(session.remotePeer, channelLabel)
        val inbox = fileInbox
        if (inbox == null || !session.putFileChannel(channelLabel, channel)) {
            // The session (or the backend) is already torn down: nothing will ever read it.
            channel.close()
            channel.dispose()
            return
        }
        inbox.open(id) { emitFileChannelEvent(WebRtcFileChannelEvent.Removed(id)) }
        channel.registerObserver(
            object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) {
                    inbox.bufferedAmountChanged(id, channel.bufferedAmount)
                }

                override fun onStateChange() {
                    when (channel.state) {
                        RTCDataChannelState.OPEN -> {
                            session.fileChannelOpens[channelLabel]?.complete(Unit)
                            emitFileChannelEvent(WebRtcFileChannelEvent.Active(id))
                        }
                        RTCDataChannelState.CLOSED -> {
                            session.takeFileChannel(channelLabel)
                            session.fileChannelOpens.remove(channelLabel)
                            inbox.close(id)
                        }
                        else -> Unit
                    }
                }

                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    // Ordered per channel: the callback only trySends, one drainer emits.
                    if (!inbox.deliver(id, bytes)) {
                        AppLog.debug(
                            component = LogComponent.WEBRTC_BACKEND,
                            event = LogEvent.SESSION_STATE_CHANGED,
                            message = "Dropped a frame on a closed file channel",
                            fields = mapOf("peer" to id.peer, "channelLabel" to id.label),
                        )
                    }
                }
            }
        )
    }

    /** The channels a disposed session held: stop their inbound side; each reports Removed once drained. */
    private fun closeFileChannels(session: Session, labels: List<String>) {
        for (label in labels) closeInbound(FileChannelId(session.remotePeer, label))
    }

    /** Stop [id]'s inbound side; Removed follows its last queued frame (or now, if it had none). */
    private fun closeInbound(id: FileChannelId) {
        val inbox = fileInbox
        if (inbox != null && inbox.isOpen(id)) inbox.close(id) else emitFileChannelEvent(
            WebRtcFileChannelEvent.Removed(
                id
            )
        )
    }

    private fun envelopeChannelLabel(local: PeerId, target: PeerId): String =
        "yapyap-env-${local.id}-${target.id}"

    private fun avChannelLabel(local: PeerId, target: PeerId): String =
        "yapyap-av-${local.id}-${target.id}"

    private suspend fun awaitChannelOpen(session: Session, dataType: WebRtcDataType) {
        val deferred = when (dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> session.envelopeChannelOpen
            WebRtcDataType.AV_DATA -> session.avChannelOpen
            WebRtcDataType.FILE_DATA -> error("Use awaitFileChannelOpen for file channels")
        }
        withTimeoutOrNull(config.value.channelOpenTimeout) {
            deferred.await()
        } ?: error("Channel did not open within ${config.value.channelOpenTimeout} for ${session.remotePeer}")
    }

    private suspend fun awaitFileChannelOpen(session: Session, channelLabel: String) {
        val deferred = session.fileChannelOpens.getOrPut(channelLabel) { CompletableDeferred() }
        withTimeoutOrNull(config.value.channelOpenTimeout) {
            deferred.await()
        } ?: error("File channel $channelLabel did not open within ${config.value.channelOpenTimeout} for ${session.remotePeer}")
    }

    private fun emitSignal(signal: WebRtcSignal) {
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Emitting outbound WebRTC signal",
            fields = mapOf("kind" to signal.kind.name, "target" to signal.target),
        )
        signalQueue.trySend(signal)
    }

    private fun envelopeQueueFor(peer: PeerId): Channel<WebRtcDataFrame> =
        envelopeQueues.getOrPut(peer) {
            Channel<WebRtcDataFrame>(Channel.UNLIMITED).also { queue ->
                val job = scope?.launch {
                    for (frame in queue) incomingDataFlow.emit(frame)
                }
                if (job != null) envelopeDrainJobs[peer] = job else queue.close()
            }
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
        sessionEventQueue.trySend(event)
    }

    private fun emitAvChannelEvent(event: WebRtcAvChannelEvent) {
        avEventQueue.trySend(event)
    }

    private fun emitFileChannelEvent(event: WebRtcFileChannelEvent) {
        fileEventQueue.trySend(event)
    }

    private class Session(
        val remotePeer: PeerId,
        val peerConnection: RTCPeerConnection,
    ) {
        val signalMutex = Mutex()
        val disposed = AtomicBoolean(false)

        @Volatile
        var envelopeDataChannel: RTCDataChannel? = null

        @Volatile
        var avDataChannel: RTCDataChannel? = null

        val fileChannels = ConcurrentHashMap<String, RTCDataChannel>()
        val fileChannelOpens = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        @Volatile
        var remoteDescriptionApplied: Boolean = false
        val pendingIceCandidates = mutableListOf<RTCIceCandidate>()
        val renegotiationPending = AtomicBoolean(false)

        @Volatile
        var envelopeChannelOpen: CompletableDeferred<Unit> = CompletableDeferred()

        @Volatile
        var avChannelOpen: CompletableDeferred<Unit> = CompletableDeferred()

        fun channelFor(dataType: WebRtcDataType): RTCDataChannel? = when (dataType) {
            WebRtcDataType.ENVELOPE_BINARY -> envelopeDataChannel
            WebRtcDataType.AV_DATA -> avDataChannel
            WebRtcDataType.FILE_DATA -> null
        }

        fun fileChannel(label: String): RTCDataChannel? = fileChannels[label]

        /** False once the session is disposed: a channel attached after that is never tracked. */
        @Synchronized
        fun putFileChannel(label: String, channel: RTCDataChannel): Boolean {
            if (disposed.get()) return false
            fileChannels[label] = channel
            fileChannelOpens.getOrPut(label) { CompletableDeferred() }
            return true
        }

        fun takeFileChannel(label: String): RTCDataChannel? = fileChannels.remove(label)

        /** Closes everything and returns the labels of the file channels it held (empty if already disposed). */
        fun dispose(): List<String> {
            val fileLabels = synchronized(this) {
                if (!disposed.compareAndSet(false, true)) return emptyList()
                fileChannels.keys.toList()
            }
            closeNative("envelope channel") {
                envelopeDataChannel?.unregisterObserver()
                envelopeDataChannel?.close()
                envelopeDataChannel?.dispose()
            }
            closeNative("AV channel") {
                avDataChannel?.unregisterObserver()
                avDataChannel?.close()
                avDataChannel?.dispose()
            }
            for ((label, channel) in fileChannels) {
                closeNative("file channel $label") {
                    channel.unregisterObserver()
                    channel.close()
                    channel.dispose()
                }
            }
            fileChannels.clear()
            closeNative("peer connection") { peerConnection.close() }
            return fileLabels
        }

        suspend fun drainEnvelopeChannel(timeout: Duration) {
            val channel = envelopeDataChannel ?: return
            withTimeoutOrNull(timeout) {
                while (channel.bufferedAmount > 0) {
                    // rely on onBufferedAmountChange to signal progress; or poll
                    delay(10.milliseconds)
                }
            }
        }
    }
}

/**
 * Closes a native object during teardown. The native side may already have torn it down, so a
 * failure here is logged and the teardown goes on with the next object.
 */
private inline fun closeNative(what: String, close: () -> Unit) {
    try {
        close()
    } catch (e: Exception) {
        AppLog.debug(
            component = LogComponent.WEBRTC_BACKEND,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "Native close failed during teardown",
            fields = mapOf("what" to what, "error" to e.toString()),
        )
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
    suspendCancellableCoroutine { cont ->
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
    suspendCancellableCoroutine { cont ->
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

internal class WebRtcBackendException(message: String) : RuntimeException(message)

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

/**
 * Channel parameters per kind. File channels are always reliable (ordered, no retransmit or
 * lifetime limit) whatever [config] says; the envelope channel follows [config]; AV is lossy.
 */
internal fun dataChannelInit(config: WebRtcBackendConfig, dataType: WebRtcDataType): RTCDataChannelInit =
    RTCDataChannelInit().also { init ->
        when (dataType) {
            WebRtcDataType.FILE_DATA -> {
                init.ordered = true
                init.maxRetransmits = -1
                init.maxPacketLifeTime = -1
            }

            WebRtcDataType.ENVELOPE_BINARY -> {
                init.ordered = config.orderedDataChannel
                config.maxRetransmits?.let { init.maxRetransmits = it }
                config.maxPacketLifeTimeMs?.let { init.maxPacketLifeTime = it }
            }

            WebRtcDataType.AV_DATA -> {
                init.ordered = false
                init.maxRetransmits = 0
            }
        }
    }
