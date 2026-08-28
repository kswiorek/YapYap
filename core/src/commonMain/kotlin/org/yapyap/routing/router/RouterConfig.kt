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
    /** A peer must have sent us traffic within this window for proactive session pre-warming. */
    val proactiveSessionFreshnessSeconds: Long = 60,
    /** Minimum interval between proactive open attempts to the same peer after a failed/closed session. */
    val proactiveSessionRetryDelaySeconds: Long = 30,
    /** Default budget for [ProactiveSessionOpener.awaitSession] (best-effort REQUIRED mode). */
    val sessionAwaitTimeoutSeconds: Long = 45,
    /** Max messages returned per sync response (responder-side page size). */
    val syncMaxMessages: Int = 20,
    /** Backoff when a pending sync has no candidate device that looks reachable. */
    val syncOfflineRetryDelaySeconds: Long = 60,
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
        require(proactiveSessionFreshnessSeconds > 0) { "proactiveSessionFreshnessSeconds must be > 0" }
        require(proactiveSessionRetryDelaySeconds > 0) { "proactiveSessionRetryDelaySeconds must be > 0" }
        require(sessionAwaitTimeoutSeconds > 0) { "sessionAwaitTimeoutSeconds must be > 0" }
        require(syncMaxMessages > 0) { "syncMaxMessages must be > 0" }
        require(syncOfflineRetryDelaySeconds > 0) { "syncOfflineRetryDelaySeconds must be > 0" }
    }
    fun getRetryDelaySeconds(transport: RouterTransport): Long = when (transport) {
        RouterTransport.WEBRTC -> webRtcRetryDelaySeconds
        RouterTransport.TOR -> torRetryDelaySeconds
    }
}