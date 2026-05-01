package org.yapyap.backend.transport.webrtc.types

data class WebRtcSignal(
    val sessionId: String,
    val kind: WebRtcSignalKind,
    val source: String,
    val target: String,
    val payload: ByteArray,
)

enum class WebRtcSignalKind(val wireValue: Byte) {
    OFFER(1),
    ANSWER(2),
    ICE(3),
    REJECT(4),
    CANCEL(5);

    companion object {
        fun fromWireValue(value: Byte): WebRtcSignalKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported WebRTC signal kind wire value: $value")
    }
}
