package org.yapyap.transport.webrtc.transport

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.PeerId
import org.yapyap.transport.webrtc.backend.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.AvSessionOptions
import org.yapyap.transport.webrtc.types.WebRtcAvSessionState
import org.yapyap.transport.webrtc.types.WebRtcIncomingAvSessionRequest
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import org.yapyap.transport.webrtc.types.WebRtcSignal

interface WebRtcTransport {
    // Data plane
    val incomingEnvelopes: Flow<WebRtcIncomingEnvelope>
    val incomingAvFrames: Flow<WebRtcDataFrame>

    // Signaling plane (bootstrap only: OFFER/ANSWER/ICE/REJECT/CANCEL)
    val outgoingBootstrapSignals: Flow<WebRtcSignal>

    // Session lifecycle (peer connection)
    val sessionStates: Flow<WebRtcSessionState>

    // Call lifecycle (user-facing)
    val incomingCallInvites: Flow<WebRtcIncomingAvSessionRequest>
    val callStates: Flow<WebRtcAvSessionState>

    suspend fun start(deviceId: PeerId)
    suspend fun stop()

    // Session (transport)
    suspend fun openSession(target: PeerId, sessionId: String)
    suspend fun sendEnvelope(sessionId: String, targetId: PeerId, envelope: BinaryEnvelope)
    suspend fun closeSession(sessionId: String)
    suspend fun handleBootstrapSignal(signal: WebRtcSignal)

    suspend fun getSessionForPeer(target: PeerId): String?

    // Call (in-band over WebRTC data)
    suspend fun inviteCall(target: PeerId, sessionId: String, options: AvSessionOptions)
    suspend fun acceptCall(sessionId: String, options: AvSessionOptions)
    suspend fun rejectCall(sessionId: String, reason: String)
    suspend fun updateCallOptions(sessionId: String, options: AvSessionOptions)
    suspend fun endCall(sessionId: String, reason: String? = null)
}

data class WebRtcIncomingEnvelope(
    val sessionId: String,
    val source: PeerId,
    val envelope: BinaryEnvelope,
)
