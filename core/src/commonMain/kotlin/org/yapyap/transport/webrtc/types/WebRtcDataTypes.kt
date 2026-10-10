package org.yapyap.transport.webrtc.types

import org.yapyap.protocol.PeerId

internal enum class WebRtcDataType {
    ENVELOPE_BINARY,
    AV_DATA,
}

internal data class WebRtcDataFrame(
    val source: PeerId,
    val target: PeerId,
    val dataType: WebRtcDataType,
    val payload: ByteArray,
)