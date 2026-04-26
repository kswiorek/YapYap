package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.routing.PlaceholderRouter
import org.yapyap.backend.transport.tor.TorTransport
import org.yapyap.backend.transport.webrtc.types.AvControlUpdate
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcAvSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcIncomingAvSessionRequest
import org.yapyap.backend.transport.webrtc.types.WebRtcIncomingSessionRequest
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionState

/**
 * Router-like transport that wires WebRTC signaling through Tor transport.
 *
 * This keeps [DefaultWebRtcTransport] focused on WebRTC session behavior while signaling
 * transport/security concerns are handled here.
 */
class TorRoutedWebRtcTransport(
    private val delegate: DefaultWebRtcTransport,
    private val torTransport: TorTransport,
    private val protection: WebRtcSignalProtection,
    private val protectionContext: WebRtcSignalProtectionContext,
    private val packetIdGenerator: () -> PacketId = { PacketId.random() },
) : WebRtcTransport {
    private val placeholderRouter = PlaceholderRouter(protectionContext.peerDirectory)

    override val incomingData: Flow<WebRtcIncomingDataFrame> = delegate.incomingData
    override val incomingSessionRequests: Flow<WebRtcIncomingSessionRequest> = delegate.incomingSessionRequests
    override val sessionStates: Flow<WebRtcSessionState> = delegate.sessionStates
    override val incomingAvSessionRequests: Flow<WebRtcIncomingAvSessionRequest> = delegate.incomingAvSessionRequests
    override val avSessionStates: Flow<WebRtcAvSessionState> = delegate.avSessionStates

    private var started = false
    private var scope: CoroutineScope? = null
    private var outgoingSignalsJob: Job? = null
    private var torIncomingJob: Job? = null

    override suspend fun start(localPeer: PeerDescriptor) {
        check(!started) { "WebRTC router transport is already started" }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = localScope

        torTransport.start(localPeer)
        delegate.start(localPeer)

        outgoingSignalsJob = localScope.launch(start = CoroutineStart.UNDISPATCHED) {
            delegate.outgoingSignals.collect { signal ->
                val createdAt = protectionContext.nowEpochSeconds()
                val signalEnvelope = protection.protect(
                    signal = signal,
                    createdAtEpochSeconds = createdAt,
                    nonce = protectionContext.nonceGenerator(),
                )
                val envelope = BinaryEnvelope(
                    packetId = packetIdGenerator(),
                    packetType = PacketType.SIGNAL,
                    createdAtEpochSeconds = createdAt,
                    expiresAtEpochSeconds = createdAt + protectionContext.signalTtlSeconds,
                    hopCount = 0,
                    route = placeholderRouter.routeForTarget(signal.target),
                    payload = signalEnvelope.encode(),
                )
                torTransport.send(
                    target = protectionContext.peerDirectory.resolveTorEndpoint(signal.target),
                    envelope = envelope,
                )
            }
        }

        torIncomingJob = localScope.launch(start = CoroutineStart.UNDISPATCHED) {
            torTransport.incoming.collect { inbound ->
                if (inbound.envelope.packetType != PacketType.SIGNAL) return@collect
                val signalEnvelope = runCatching { WebRtcSignalEnvelope.decode(inbound.envelope.payload) }
                    .getOrNull() ?: return@collect
                val signal = runCatching { protection.open(signalEnvelope) }.getOrNull() ?: return@collect
                delegate.handleInboundSignal(signal, receivedAtEpochSeconds = inbound.receivedAtEpochSeconds)
            }
        }

        started = true
    }

    override suspend fun stop() {
        if (!started) return
        outgoingSignalsJob?.cancel()
        torIncomingJob?.cancel()
        outgoingSignalsJob = null
        torIncomingJob = null
        scope?.cancel()
        scope = null

        runCatching { delegate.stop() }
        runCatching { torTransport.stop() }
        started = false
    }

    override suspend fun initiateSession(target: PeerId): String = delegate.initiateSession(target)

    override suspend fun acceptSession(sessionId: String) = delegate.acceptSession(sessionId)

    override suspend fun rejectSession(sessionId: String, reason: String) = delegate.rejectSession(sessionId, reason)

    override suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray) {
        delegate.sendData(sessionId, target, payload)
    }

    override suspend fun closeSession(sessionId: String) = delegate.closeSession(sessionId)

    override suspend fun initiateAvSession(target: PeerId, options: AvSessionOptions): String {
        return delegate.initiateAvSession(target = target, options = options)
    }

    override suspend fun acceptAvSession(sessionId: String, options: AvSessionOptions) {
        delegate.acceptAvSession(sessionId = sessionId, options = options)
    }

    override suspend fun rejectAvSession(sessionId: String, reason: String) {
        delegate.rejectAvSession(sessionId = sessionId, reason = reason)
    }

    override suspend fun updateAvControls(sessionId: String, update: AvControlUpdate) {
        delegate.updateAvControls(sessionId = sessionId, update = update)
    }

    override suspend fun endAvSession(sessionId: String) {
        delegate.endAvSession(sessionId = sessionId)
    }
}
