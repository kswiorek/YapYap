package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.transport.webrtc.types.AvSessionOptions
import org.yapyap.backend.transport.webrtc.types.WebRtcAvSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcIncomingAvSessionRequest
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionState
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

interface WebRtcTransport {
    // Data plane
    val incomingEnvelopes: Flow<BinaryEnvelope>
    val incomingAvFrames: Flow<WebRtcDataFrame>

    // Signaling plane (bootstrap only: OFFER/ANSWER/ICE/REJECT/CANCEL)
    val outgoingBootstrapSignals: Flow<WebRtcSignal>

    // Session lifecycle (peer connection)
    val sessionStates: Flow<WebRtcSessionState>

    // Call lifecycle (user-facing)
    val incomingCallInvites: Flow<WebRtcIncomingAvSessionRequest>
    val callStates: Flow<WebRtcAvSessionState>

    suspend fun start(deviceId: String)
    suspend fun stop()

    // Session (transport)
    suspend fun openSession(target: String, sessionId: String)
    suspend fun sendEnvelope(sessionId: String, target: String, payload: ByteArray)
    suspend fun closeSession(sessionId: String)
    suspend fun handleBootstrapSignal(signal: WebRtcSignal, receivedAtEpochSeconds: Long)

    // Call (in-band over WebRTC data)
    suspend fun inviteCall(target: String, sessionId: String, options: AvSessionOptions)
    suspend fun acceptCall(sessionId: String, options: AvSessionOptions)
    suspend fun rejectCall(sessionId: String, reason: String)
    suspend fun updateCallOptions(sessionId: String, options: AvSessionOptions)
    suspend fun endCall(sessionId: String, reason: String? = null)
}

