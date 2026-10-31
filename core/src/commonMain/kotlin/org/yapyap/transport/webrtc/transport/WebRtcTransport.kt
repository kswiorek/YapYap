package org.yapyap.transport.webrtc.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.session.SessionTransport
import org.yapyap.transport.webrtc.types.*


internal interface WebRtcTransport : SessionTransport<PeerId> {
    // Data plane
    val incomingEnvelopes: Flow<WebRtcIncomingEnvelope>
    val incomingAvFrames: Flow<WebRtcDataFrame>

    // Signaling plane (bootstrap only: OFFER/ANSWER/ICE/REJECT/CANCEL)
    val outgoingBootstrapSignals: Flow<WebRtcSignal>

    // Session lifecycle (peer connection), keyed by remote peer; the shared
    // SessionTransport contract (CONNECTED iff hasSession(peer), replayed values are
    // ground truth). Absence means no session was ever negotiated with that peer (or the
    // transport was stopped). Link-up reactions (outbox acceleration) are the router's,
    // made uniformly for every session transport.
    override val sessionStates: StateFlow<Map<PeerId, WebRtcSessionState>>

    /**
     * Current-state-then-updates for one peer: replays the stored state on collection
     * (when present) and follows its transitions. Race-free way to await usability —
     * a CONNECTED that lands before subscription is delivered immediately.
     */
    override fun sessionStatesOf(key: PeerId): Flow<WebRtcSessionState> =
        sessionStates.mapNotNull { it[key] }

    // Call lifecycle (user-facing)
    val incomingCallInvites: Flow<WebRtcIncomingAvSessionRequest>
    val callStates: Flow<WebRtcAvSessionState>

    suspend fun start(deviceId: PeerId)
    suspend fun stop()

    // Session (transport)
    override suspend fun openSession(key: PeerId)
    suspend fun sendEnvelope(targetId: PeerId, envelope: BinaryEnvelope)
    override suspend fun closeSession(key: PeerId)
    suspend fun handleBootstrapSignal(signal: WebRtcSignal)

    /**
     * True iff the envelope data channel to [peerId] is open and can carry data immediately.
     * False while a session is still negotiating or after it failed/closed. This is the
     * predicate transport-selection policy and WebRTC-only senders (typing indicators) rely on.
     */
    override fun hasSession(key: PeerId): Boolean

    // Call (in-band over WebRTC data)
    suspend fun inviteCall(peer: PeerId, options: AvSessionOptions)
    suspend fun acceptCall(peer: PeerId, options: AvSessionOptions)
    suspend fun rejectCall(peer: PeerId, reason: String)
    suspend fun updateCallOptions(peer: PeerId, options: AvSessionOptions)
    suspend fun endCall(peer: PeerId, reason: String? = null)
}

internal data class WebRtcIncomingEnvelope(
    val source: PeerId,
    val envelope: BinaryEnvelope,
)
