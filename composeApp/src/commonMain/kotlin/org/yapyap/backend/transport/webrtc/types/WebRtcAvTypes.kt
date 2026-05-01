package org.yapyap.backend.transport.webrtc.types


enum class AvQualityTier {
    LOW,
    MEDIUM,
    HIGH,
}

data class AvSessionOptions(
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val screenShareEnabled: Boolean = false,
    val qualityTier: AvQualityTier = AvQualityTier.MEDIUM,
)

data class WebRtcIncomingAvSessionRequest(
    val sessionId: String,
    val source: String,
    val options: AvSessionOptions? = null,
    val receivedAtEpochSeconds: Long,
)

enum class WebRtcAvSessionPhase {
    PENDING_DECISION,
    NEGOTIATING,
    ACTIVE,
    REJECTED,
    ENDED,
    FAILED,
}

data class WebRtcAvSessionState(
    val sessionId: String,
    val peer: String,
    val phase: WebRtcAvSessionPhase,
    val options: AvSessionOptions? = null,
    val reason: String? = null,
)
