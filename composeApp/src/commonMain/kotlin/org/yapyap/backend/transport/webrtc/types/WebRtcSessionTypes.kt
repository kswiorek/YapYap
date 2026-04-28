package org.yapyap.backend.transport.webrtc.types

data class WebRtcIncomingSessionRequest(
    val sessionId: String,
    val source: String,
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
    val peer: String,
    val phase: WebRtcSessionPhase,
    val reason: String? = null,
)
