package org.yapyap.backend.routing

data class RouterConfig(
    val messageLifetimeSeconds: Long = 60 * 60 * 24 * 2,
    val ackLifetimeSeconds: Long = 60 * 60,
    val messageMaxRetries: Int = 3,
    val torRetryDelaySeconds: Long = 60,
    val webRtcRetryDelaySeconds: Long = 10,
    val standbyRetryDelaySeconds: Long = 3600,
    val outboxMaxIdlePollSeconds: Long = 60,
    val outboxMaxSizeBytes: Long = 1024 * 1024 * 10,
) {
    init {
        require(messageLifetimeSeconds > 0) { "messageLifetimeSeconds must be > 0" }
        require(messageMaxRetries > 0) { "messageMaxRetries must be > 0" }
        require(torRetryDelaySeconds > 0) { "torRetryDelaySeconds must be > 0" }
        require(webRtcRetryDelaySeconds > 0) { "webRtcRetryDelaySeconds must be > 0" }
        require(standbyRetryDelaySeconds > 0) { "standbyRetryDelaySeconds must be > 0" }
        require(outboxMaxIdlePollSeconds > 0) { "outboxMaxIdlePollSeconds must be > 0" }
        require(ackLifetimeSeconds > 0) { "ackLifetimeSeconds must be > 0" }
        require(outboxMaxSizeBytes > 0) { "outboxMaxSizeBytes must be > 0" }
    }
    fun getRetryDelaySeconds(transport: RouterTransport): Long = when (transport) {
        RouterTransport.WEBRTC -> webRtcRetryDelaySeconds
        RouterTransport.TOR -> torRetryDelaySeconds
    }
}