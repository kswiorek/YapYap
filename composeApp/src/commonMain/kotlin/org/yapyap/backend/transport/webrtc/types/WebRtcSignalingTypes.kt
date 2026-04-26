package org.yapyap.backend.transport.webrtc.types

import org.yapyap.backend.protocol.DeviceAddress

data class WebRtcSignal(
    val sessionId: String,
    val kind: WebRtcSignalKind,
    val source: DeviceAddress,
    val target: DeviceAddress,
    val payload: ByteArray,
)

enum class WebRtcSignalKind(val wireValue: Byte) {
    OFFER(1),
    ANSWER(2),
    ICE(3),
    REJECT(4),
    CANCEL(5),
    AV_OFFER(6),
    AV_ANSWER(7),
    AV_UPDATE(8),
    AV_END(9),
    AV_REJECT(10);

    companion object {
        fun fromWireValue(value: Byte): WebRtcSignalKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported WebRTC signal kind wire value: $value")
    }
}
