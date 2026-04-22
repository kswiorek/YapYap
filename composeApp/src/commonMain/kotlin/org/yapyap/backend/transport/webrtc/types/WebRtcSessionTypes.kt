package org.yapyap.backend.transport.webrtc.types

import org.yapyap.backend.protocol.PeerId

data class WebRtcIncomingSessionRequest(
    val sessionId: String,
    val source: PeerId,
    val receivedAtEpochSeconds: Long,
)

enum class WebRtcSessionPhase {
    PENDING_DECISION,
    NEGOTIATING,
    CONNECTED,
    REJECTED,
    CLOSED,
    FAILED,
}

data class WebRtcSessionState(
    val sessionId: String,
    val peer: PeerId,
    val phase: WebRtcSessionPhase,
    val reason: String? = null,
)
