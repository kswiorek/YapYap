package org.yapyap.transport.webrtc.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.webrtc.types.*


internal interface WebRtcTransport {
    // Data plane
    val incomingEnvelopes: Flow<WebRtcIncomingEnvelope>
    val incomingAvFrames: Flow<WebRtcDataFrame>

    // Signaling plane (bootstrap only: OFFER/ANSWER/ICE/REJECT/CANCEL)
    val outgoingBootstrapSignals: Flow<WebRtcSignal>

    // Session lifecycle (peer connection), keyed by remote peer. Absence means no
    // session was ever negotiated with that peer (or the transport was stopped).
    //
    // The stored state is the last-known backend transition for that peer and obeys the
    // WebRtcSessionState contract: CONNECTED if and only if hasSession(peer) is true.
    // A new subscriber replays the current per-peer states, and replayed values are
    // ground truth — a stored CONNECTED can never go stale relative to the channel.
    //
    // Deliberately no session-connected side effects are wired in the router (no outbox
    // acceleration on CONNECTED): session establishment implies prior authenticated
    // signal traffic, which already fired the peer-availability transition
    // (InboundEnvelopeProcessor -> PeerAvailabilityRegistry.markReachable). The residual
    // value of such a trigger is bounded by one outbox retry delay on already-failed
    // sends, so it was removed rather than kept as semantic noise.
    val sessionStates: StateFlow<Map<PeerId, WebRtcSessionState>>

    /**
     * Current-state-then-updates for one peer: replays the stored state on collection
     * (when present) and follows its transitions. Race-free way to await usability —
     * a CONNECTED that lands before subscription is delivered immediately.
     */
    fun sessionStatesOf(peerId: PeerId): Flow<WebRtcSessionState> =
        sessionStates.mapNotNull { it[peerId] }

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

internal data class WebRtcIncomingEnvelope(
    val source: PeerId,
    val envelope: BinaryEnvelope,
)
