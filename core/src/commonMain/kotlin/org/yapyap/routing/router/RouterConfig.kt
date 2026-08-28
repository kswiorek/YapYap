package org.yapyap.routing.router

import kotlinx.serialization.Serializable

@Serializable
data class RouterConfig(
    val binaryEnvelopeLifetimeSeconds: Long = 60 * 60 * 24 * 2,
    val ackLifetimeSeconds: Long = 60 * 60,
    val messageMaxRetries: Int = 3,
    val torRetryDelaySeconds: Long = 60,
    val webRtcRetryDelaySeconds: Long = 10,
    val standbyRetryDelaySeconds: Long = 3600,
    val retryLoopMaxIdlePollSeconds: Long = 60,
    val outboxMaxSizeBytes: Long = 1024 * 1024 * 10,
    val dedupRetentionSeconds: Long = 60 * 60 * 24 * 30,
    val onlineThresholdSeconds: Long = 2 * 60,
    /** Send cadence for typing indicators while the user is composing; receivers idle-timeout at ~2x this. */
    val typingIndicatorIntervalSeconds: Int = 3,
    /** A peer must have sent us traffic within this window for proactive session pre-warming. */
    val proactiveSessionFreshnessSeconds: Long = 60,
    /** Minimum interval between proactive open attempts to the same peer after a failed/closed session. */
    val proactiveSessionRetryDelaySeconds: Long = 30,
    /** Default budget for [ProactiveSessionOpener.awaitSession] (best-effort REQUIRED mode). */
    val sessionAwaitTimeoutSeconds: Long = 45,
) {
    init {
        require(binaryEnvelopeLifetimeSeconds > 0) { "messageLifetimeSeconds must be > 0" }
        require(messageMaxRetries > 0) { "messageMaxRetries must be > 0" }
        require(torRetryDelaySeconds > 0) { "torRetryDelaySeconds must be > 0" }
        require(webRtcRetryDelaySeconds > 0) { "webRtcRetryDelaySeconds must be > 0" }
        require(standbyRetryDelaySeconds > 0) { "standbyRetryDelaySeconds must be > 0" }
        require(retryLoopMaxIdlePollSeconds > 0) { "outboxMaxIdlePollSeconds must be > 0" }
        require(ackLifetimeSeconds > 0) { "ackLifetimeSeconds must be > 0" }
        require(outboxMaxSizeBytes > 0) { "outboxMaxSizeBytes must be > 0" }
        require(typingIndicatorIntervalSeconds > 0) { "typingIndicatorIntervalSeconds must be > 0" }
        require(proactiveSessionFreshnessSeconds > 0) { "proactiveSessionFreshnessSeconds must be > 0" }
        require(proactiveSessionRetryDelaySeconds > 0) { "proactiveSessionRetryDelaySeconds must be > 0" }
        require(sessionAwaitTimeoutSeconds > 0) { "sessionAwaitTimeoutSeconds must be > 0" }
    }
    fun getRetryDelaySeconds(transport: RouterTransport): Long = when (transport) {
        RouterTransport.WEBRTC -> webRtcRetryDelaySeconds
        RouterTransport.TOR -> torRetryDelaySeconds
    }
}