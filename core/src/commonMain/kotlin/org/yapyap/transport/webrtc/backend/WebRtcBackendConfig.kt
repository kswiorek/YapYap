package org.yapyap.transport.webrtc.backend

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class WebRtcIceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val password: String? = null,
) {
    init {
        require(urls.isNotEmpty()) { "WebRTC ICE server urls must not be empty" }
    }
}

@Serializable
data class WebRtcBackendConfig(
    val iceServers: List<WebRtcIceServerConfig> = listOf(
        WebRtcIceServerConfig(urls = listOf("stun:stun.l.google.com:19302")),
        WebRtcIceServerConfig(urls = listOf("stun:stun1.l.google.com:19302")),
    ),
    val orderedDataChannel: Boolean = true,
    val maxRetransmits: Int? = null,
    val maxPacketLifeTimeMs: Int? = null,
    val maxPayloadBytes: Int = 1024 * 1024 * 1,
    val channelOpenTimeout: Duration = 30.seconds,
    val drainChannelTimeout: Duration = 5.seconds,
) {
}
