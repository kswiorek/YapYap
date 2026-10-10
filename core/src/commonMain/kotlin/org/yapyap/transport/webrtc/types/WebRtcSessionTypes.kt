package org.yapyap.transport.webrtc.types

import org.yapyap.protocol.PeerId

internal enum class WebRtcSessionPhase {
    NEGOTIATING,
    CONNECTED,
    REJECTED,
    CLOSED,
    FAILED,
}

/**
 * Last-known session lifecycle state for one remote peer.
 *
 * Contract: [phase] is `CONNECTED` if and only if the envelope data channel to [peerId] is
 * open and can carry data immediately — the same predicate as
 * [hasSession][org.yapyap.transport.webrtc.transport.WebRtcTransport.hasSession]. In
 * particular `CONNECTED` is emitted on envelope-channel OPEN, never on peer-connection
 * CONNECTED (the channel opens strictly after the connection), and every channel close —
 * orderly or abrupt — lands here as `CLOSED`/`FAILED`/`REJECTED`, so a stored state can
 * never go stale relative to the channel. Subscribers may treat a replayed `CONNECTED`
 * as ground truth, not as a hint.
 */
internal data class WebRtcSessionState(
    val peerId: PeerId,
    val phase: WebRtcSessionPhase,
    val reason: String? = null,
)

internal sealed interface WebRtcSessionEvent {
    val peer: PeerId

    data class Connecting(override val peer: PeerId) : WebRtcSessionEvent

    /**
     * The envelope data channel to [peer] reached OPEN. Never emitted for peer-connection
     * CONNECTED alone — see the [WebRtcSessionState] contract.
     */
    data class Connected(override val peer: PeerId) : WebRtcSessionEvent
    data class Closed(override val peer: PeerId) : WebRtcSessionEvent
    data class Rejected(override val peer: PeerId, val reason: String) : WebRtcSessionEvent
    data class Failed(override val peer: PeerId, val reason: String) : WebRtcSessionEvent
}

internal sealed interface WebRtcAvChannelEvent {
    val peer: PeerId

    data class Adding(override val peer: PeerId) : WebRtcAvChannelEvent
    data class Active(override val peer: PeerId) : WebRtcAvChannelEvent
    data class Removed(override val peer: PeerId) : WebRtcAvChannelEvent
    data class Failed(override val peer: PeerId, val reason: String) : WebRtcAvChannelEvent
}