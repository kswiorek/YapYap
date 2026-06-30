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
    val sessionId: String,
    val peerId: PeerId,
    val phase: WebRtcSessionPhase,
    val reason: String? = null,
)
