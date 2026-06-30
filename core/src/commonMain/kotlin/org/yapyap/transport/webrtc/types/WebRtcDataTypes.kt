package org.yapyap.transport.webrtc.types

import org.yapyap.protocol.PeerId

enum class WebRtcDataType {
    ENVELOPE_BINARY,
    AV_DATA,
}

data class WebRtcDataFrame(
    val sessionId: String,
    val source: PeerId,
    val target: PeerId,
    val dataType: WebRtcDataType,
    val payload: ByteArray,
)