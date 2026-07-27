package org.yapyap.transport.webrtc.types

import org.yapyap.protocol.PeerId

enum class WebRtcSessionPhase {
    NEGOTIATING,
    CONNECTED,
    REJECTED,
    CLOSED,
    FAILED,
}

data class WebRtcSessionState(
    val peerId: PeerId,
    val phase: WebRtcSessionPhase,
    val reason: String? = null,
)

sealed interface WebRtcSessionEvent {
    val peer: PeerId
    data class Connecting(override val peer: PeerId) : WebRtcSessionEvent
    data class Connected(override val peer: PeerId) : WebRtcSessionEvent
    data class Closed(override val peer: PeerId) : WebRtcSessionEvent
    data class Failed(override val peer: PeerId, val reason: String) : WebRtcSessionEvent
}
sealed interface WebRtcAvChannelEvent {
    val peer: PeerId
    data class Adding(override val peer: PeerId) : WebRtcAvChannelEvent
    data class Active(override val peer: PeerId) : WebRtcAvChannelEvent
    data class Removed(override val peer: PeerId) : WebRtcAvChannelEvent
    data class Failed(override val peer: PeerId, val reason: String) : WebRtcAvChannelEvent
}