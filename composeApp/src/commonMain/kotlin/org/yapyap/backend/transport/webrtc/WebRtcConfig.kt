package org.yapyap.backend.transport.webrtc

data class WebRtcIceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val password: String? = null,
) {
    init {
        require(urls.isNotEmpty()) { "WebRTC ICE server urls must not be empty" }
    }
}

data class WebRtcConfig(
    val iceServers: List<WebRtcIceServerConfig> = listOf(
        WebRtcIceServerConfig(urls = listOf("stun:stun.l.google.com:19302")),
        WebRtcIceServerConfig(urls = listOf("stun:stun1.l.google.com:19302")),
    ),
    val dataChannelLabelPrefix: String = "yapyap",
    val orderedDataChannel: Boolean = true,
    val maxRetransmits: Int? = null,
    val maxPacketLifeTimeMs: Int? = null,
) {
    init {
        require(dataChannelLabelPrefix.isNotBlank()) { "dataChannelLabelPrefix must not be blank" }
    }
}
