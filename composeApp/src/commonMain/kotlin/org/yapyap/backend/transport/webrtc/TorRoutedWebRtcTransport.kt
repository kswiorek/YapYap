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
import org.yapyap.backend.protocol.EnvelopeRoute
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.transport.tor.TorTransport

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

    override val incomingData: Flow<WebRtcIncomingDataFrame> = delegate.incomingData
    override val incomingSessionRequests: Flow<WebRtcIncomingSessionRequest> = delegate.incomingSessionRequests
    override val sessionStates: Flow<WebRtcSessionState> = delegate.sessionStates

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
                    route = EnvelopeRoute(
                        destinationAccount = signal.target.accountName,
                        destinationDevice = signal.target.deviceId,
                        nextHopDevice = null,
                    ),
                    payload = signalEnvelope.encode(),
                )
                torTransport.send(
                    target = protectionContext.resolveTorEndpoint(signal.target),
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
}
