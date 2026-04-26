package org.yapyap.backend.transport.webrtc.types

import org.yapyap.backend.protocol.DeviceAddress

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

data class AvControlUpdate(
    val audioEnabled: Boolean? = null,
    val videoEnabled: Boolean? = null,
    val screenShareEnabled: Boolean? = null,
)

data class WebRtcIncomingAvSessionRequest(
    val sessionId: String,
    val source: DeviceAddress,
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
    val peer: DeviceAddress,
    val phase: WebRtcAvSessionPhase,
    val options: AvSessionOptions? = null,
    val reason: String? = null,
)
