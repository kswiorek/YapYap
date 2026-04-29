package org.yapyap.backend.transport.webrtc.types
enum class WebRtcSessionPhase {
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
