package org.yapyap.routing.router

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
data class RouterConfig(
    val binaryEnvelopeLifetime: Duration = 2.days,
    val ackLifetime: Duration = 1.hours,
    val messageMaxRetries: Int = 3,
    val torRetryDelay: Duration = 60.seconds,
    val webRtcRetryDelay: Duration = 10.seconds,
    val standbyRetryDelay: Duration = 1.hours,
    val retryLoopMaxIdlePoll: Duration = 60.seconds,
    val outboxMaxSizeBytes: Long = 1024 * 1024 * 10,
    val dedupRetention: Duration = 30.days,
    val onlineThreshold: Duration = 2.minutes,
    /** A peer must have sent us traffic within this window for proactive session pre-warming. */
    val proactiveSessionFreshness: Duration = 60.seconds,
    /** Minimum interval between proactive open attempts to the same peer after a failed/closed session. */
    val proactiveSessionRetryDelay: Duration = 30.seconds,
    /** Default budget for [ProactiveSessionOpener.awaitSession] (best-effort REQUIRED mode). */
    val sessionAwaitTimeout: Duration = 45.seconds,
    /** Max messages returned per sync response (responder-side page size). */
    val syncMaxMessages: Int = 20,
    /** Backoff when a pending sync has no candidate device that looks reachable. */
    val syncOfflineRetryDelay: Duration = 60.seconds,

    val pingInterval: Duration = 5.minutes,
) {
    init {
        require(binaryEnvelopeLifetime > Duration.ZERO) { "messageLifetimeSeconds must be > 0" }
        require(messageMaxRetries > 0) { "messageMaxRetries must be > 0" }
        require(torRetryDelay > Duration.ZERO) { "torRetryDelaySeconds must be > 0" }
        require(webRtcRetryDelay > Duration.ZERO) { "webRtcRetryDelaySeconds must be > 0" }
        require(standbyRetryDelay > Duration.ZERO) { "standbyRetryDelaySeconds must be > 0" }
        require(retryLoopMaxIdlePoll > Duration.ZERO) { "outboxMaxIdlePollSeconds must be > 0" }
        require(ackLifetime > Duration.ZERO) { "ackLifetimeSeconds must be > 0" }
        require(outboxMaxSizeBytes > 0) { "outboxMaxSizeBytes must be > 0" }
        require(proactiveSessionFreshness > Duration.ZERO) { "proactiveSessionFreshnessSeconds must be > 0" }
        require(proactiveSessionRetryDelay > Duration.ZERO) { "proactiveSessionRetryDelaySeconds must be > 0" }
        require(sessionAwaitTimeout > Duration.ZERO) { "sessionAwaitTimeoutSeconds must be > 0" }
        require(syncMaxMessages > 0) { "syncMaxMessages must be > 0" }
        require(syncOfflineRetryDelay > Duration.ZERO) { "syncOfflineRetryDelaySeconds must be > 0" }
    }
    fun getRetryDelaySeconds(transport: RouterTransport): Duration = when (transport) {
        RouterTransport.WEBRTC -> webRtcRetryDelay
        RouterTransport.TOR -> torRetryDelay
    }
}