package org.yapyap.transport.webrtc.types

import org.yapyap.protocol.PeerId

internal enum class WebRtcDataType {
    ENVELOPE_BINARY,
    AV_DATA,
    FILE_DATA,
}

internal data class WebRtcDataFrame(
    val source: PeerId,
    val target: PeerId,
    val dataType: WebRtcDataType,
    val payload: ByteArray,
    /** File-channel label for FILE_DATA frames (one fetch = one channel); null otherwise. */
    val channelLabel: String? = null,
)