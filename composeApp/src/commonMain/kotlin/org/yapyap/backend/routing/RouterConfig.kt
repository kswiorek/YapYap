package org.yapyap.backend.routing

data class RouterConfig(
    val messageLifetimeSeconds: Long = 60 * 60 * 24 * 2,
    val ackLifetimeSeconds: Long = 60 * 60,
    val messageMaxRetries: Int = 3,
    val torRetryDelaySeconds: Long = 60,
    val webRtcRetryDelaySeconds: Long = 10,
) {
    init {
        require(messageLifetimeSeconds > 0) { "messageLifetimeSeconds must be > 0" }
        require(messageMaxRetries > 0) { "messageMaxRetries must be > 0" }
        require(torRetryDelaySeconds > 0) { "torRetryDelaySeconds must be > 0" }
        require(webRtcRetryDelaySeconds > 0) { "webRtcRetryDelaySeconds must be > 0" }
    }
}