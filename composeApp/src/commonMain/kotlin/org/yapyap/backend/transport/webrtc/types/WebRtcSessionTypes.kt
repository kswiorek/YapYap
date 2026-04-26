package org.yapyap.backend.transport.webrtc.types

import org.yapyap.backend.protocol.DeviceAddress

data class WebRtcIncomingSessionRequest(
    val sessionId: String,
    val source: DeviceAddress,
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
    val peer: DeviceAddress,
    val phase: WebRtcSessionPhase,
    val reason: String? = null,
)
