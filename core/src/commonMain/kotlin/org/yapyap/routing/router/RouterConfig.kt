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
    /**
     * How often [PeerAvailabilityRegistry]'s sweep samples peer availability: for each peer it
     * looks at whether we saw any traffic from them within the last [sweepInterval] (boost) or,
     * failing that, whether one of our pings went unanswered (decay). Independent of [pingInterval];
     * should be a few times larger so a ping early in a window has time to draw a reply.
     */
    val sweepInterval: Duration = 15.minutes,
    /**
     * Time constant for the complementary filter on a peer's reliability score: the deficit to 1
     * (when traffic is seen) and the score itself (when our ping goes unanswered) halve each
     * [reliabilityHalfLife] of observed time. Expressed in wall-clock time (not per-sweep) so the
     * score's dynamics are unaffected by [sweepInterval] or how long the app is awake for.
     */
    val reliabilityHalfLife: Duration = 24.hours,
    val maxBillableGap: Duration = 30.days,
    /**
     * Lifetime of an outbound bootstrap intro envelope. Deliberately short: the QR secret is
     * one-time and the intro must either arrive and be ACKed while the newcomer is on-boarding,
     * or die — a stale intro must not keep circling the outbox.
     */
    val bootstrapIntroLifetime: Duration = 2.hours,
    /** Desired chance that at least one selected relay is online when a message needs relaying. */
    val relayTargetSuccessProbability: Double = 0.9,
    /** Hard cap on the number of relays a message is deposited with. */
    val maxRelays: Int = 3,
    /** Relays below this reliability score are never used (score 0 = opted out). */
    val minRelayScore: Double = 0.1,
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
        require(pingInterval > Duration.ZERO) { "pingInterval must be > 0" }
        require(sweepInterval > Duration.ZERO) { "sweepInterval must be > 0" }
        require(reliabilityHalfLife > Duration.ZERO) { "reliabilityHalfLife must be > 0" }
        require(maxBillableGap > Duration.ZERO) { "maxBillableGap must be > 0" }
        require(bootstrapIntroLifetime > Duration.ZERO) { "bootstrapIntroLifetime must be > 0" }
        require(relayTargetSuccessProbability in 0.0..1.0) { "relayTargetSuccessProbability must be in [0,1]" }
        require(maxRelays > 0) { "maxRelays must be > 0" }
        require(minRelayScore in 0.0..1.0) { "minRelayScore must be in [0,1]" }
    }
    fun getRetryDelaySeconds(transport: RouterTransport): Duration = when (transport) {
        RouterTransport.WEBRTC -> webRtcRetryDelay
        RouterTransport.TOR -> torRetryDelay
    }
}