package org.yapyap.transport.webrtc.transport

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.webrtc.types.*


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
    suspend fun openSession(target: PeerId)
    suspend fun sendEnvelope(targetId: PeerId, envelope: BinaryEnvelope)
    suspend fun closeSession(targetId: PeerId)
    suspend fun handleBootstrapSignal(signal: WebRtcSignal)

    /**
     * True iff the envelope data channel to [peerId] is open and can carry data immediately.
     * False while a session is still negotiating or after it failed/closed. This is the
     * predicate transport-selection policy and WebRTC-only senders (typing indicators) rely on.
     */
    fun hasSession(peerId: PeerId): Boolean

    // Call (in-band over WebRTC data)
    suspend fun inviteCall(peer: PeerId, options: AvSessionOptions)
    suspend fun acceptCall(peer: PeerId, options: AvSessionOptions)
    suspend fun rejectCall(peer: PeerId, reason: String)
    suspend fun updateCallOptions(peer: PeerId, options: AvSessionOptions)
    suspend fun endCall(peer: PeerId, reason: String? = null)
}

data class WebRtcIncomingEnvelope(
    val source: PeerId,
    val envelope: BinaryEnvelope,
)
