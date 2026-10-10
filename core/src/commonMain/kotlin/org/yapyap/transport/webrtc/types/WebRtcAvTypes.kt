package org.yapyap.transport.webrtc.types

import org.yapyap.protocol.PeerId


internal enum class AvQualityTier {
    LOW,
    MEDIUM,
    HIGH,
}

internal data class AvSessionOptions(
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val screenShareEnabled: Boolean = false,
    val qualityTier: AvQualityTier = AvQualityTier.MEDIUM,
)

internal data class WebRtcIncomingAvSessionRequest(
    val source: PeerId,
    val options: AvSessionOptions? = null,
)

internal enum class WebRtcAvSessionPhase {
    PENDING_DECISION,
    NEGOTIATING,
    ACTIVE,
    REJECTED,
    ENDED,
    FAILED,
}

internal data class WebRtcAvSessionState(
    val peer: PeerId,
    val phase: WebRtcAvSessionPhase,
    val options: AvSessionOptions? = null,
    val reason: String? = null,
)
