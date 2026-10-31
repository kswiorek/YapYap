package org.yapyap.routing.router

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
internal data class RouterConfig(
    val binaryEnvelopeLifetime: Duration = 2.days,
    val ackLifetime: Duration = 1.hours,
    val fastRetryBudget: Int = 3,
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
    /** Default budget for `ProactiveSessionOpener.awaitSession` (best-effort REQUIRED mode). */
    val sessionAwaitTimeout: Duration = 45.seconds,
    /**
     * Cadence at which `ProactiveSessionOpener.awaitSession` re-issues the idempotent
     * open while waiting. The wait itself is event-driven; this is only the safety net
     * for silently lost signaling (no event ever arrives) and the rate limit for
     * re-opening after terminal states. Signaling round-trips take seconds, so this is
     * deliberately coarser than the old poll quantum.
     */
    val sessionAwaitReopenDelay: Duration = 5.seconds,
    /**
     * After Tor's cached "not found" for an online peer was dropped, retry that
     * peer's outbox this soon instead of after the normal backoff.
     */
    val onionRefreshRetryDelay: Duration = 10.seconds,
    /** Outbox: sends in progress at once, across all peers (each holds a Tor stream). */
    val outboxMaxInFlight: Int = 16,
    /**
     * Outbox: sends in progress at once to one peer. Small on purpose: ten queued
     * messages to an offline peer shouldn't all spend their budgets against the
     * same dead address in parallel.
     */
    val outboxMaxInFlightPerTarget: Int = 2,
    /**
     * Transports in order of preference for any send to a peer with more than one open link.
     * A send never opens a link itself; with none open, outbox entries wait for one
     * (see [noLinkRecheckDelay]) and system traffic falls back to a one-shot Tor dial.
     */
    val linkPreference: List<RouterTransport> = listOf(RouterTransport.WEBRTC, RouterTransport.TOR),
    /**
     * Outbox entries for a peer with no open link wait this long before being looked at
     * again. Only a safety net: a link coming up wakes the peer's entries at once.
     */
    val noLinkRecheckDelay: Duration = 10.minutes,
    /** Tor links (kept open to every active peer): wait after the first failed open, doubling per failure. */
    val torLinkRetryDelay: Duration = 15.seconds,
    /** Tor links: cap on the wait between opens to a peer that keeps failing. */
    val torLinkMaxRetryDelay: Duration = 10.minutes,
    /** Tor links: opens (dials) in progress at once. */
    val torLinkMaxConcurrentOpens: Int = 8,
    /**
     * Tor links: how long one open may take before it counts as failed. Covers the
     * transport's dial budget plus the far end's accept (`TorBackendConfig.sessionDialBudget`,
     * `sessionAcceptTimeout`).
     */
    val torLinkOpenTimeout: Duration = 2.minutes,
    /** Link keepers re-check their peers at least this often (new devices, due retries). */
    val linkKeeperRecheckInterval: Duration = 60.seconds,
    /** ACK/NACK sends in progress at once (each is a Tor dial back to the sender). */
    val dispositionMaxInFlight: Int = 8,
    /**
     * Give up on one ACK/NACK send after this long. Best-effort: the sender resends
     * an unacknowledged packet, and its duplicate is answered again.
     */
    val dispositionSendBudget: Duration = 30.seconds,
    /** Max messages returned per sync response (responder-side page size). */
    val syncMaxMessages: Int = 20,
    /**
     * Max requester frontier IDs honored per sync response (responder-side bound).
     * Policy: truncate, never refuse (docs/room events.md §6) — the bound is a
     * DoS guard against forged requests, not a paging mechanism (appends
     * reference the whole frontier, so the covering antichain stays tiny).
     * Truncation is merely wasteful, never incorrect: see SyncPayloadProvider.
     */
    val syncMaxKnownIds: Int = 64,
    /** Backoff when a pending sync has no candidate device that looks reachable. */
    val syncOfflineRetryDelay: Duration = 60.seconds,
    /**
     * Backoff between removal-node re-pushes to the same device
     * (ping-contradiction recovery, docs/room events.md §6): the ping is the
     * proof, the backoff is the rate governor.
     */
    val removalRePushBackoff: Duration = 1.hours,

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
     * Outbound bootstrap envelope lifetime (wire TTL): how long a queued intro /
     * recovery-request ciphertext stays deliverable in the outbox. Short enough
     * that late duplicates can't append chain entries for failed onboardings or
     * reply into burned secrets.
     */
    val bootstrapIntroLifetime: Duration = 8.minutes,
    /**
     * Newcomer onboarding session budget (AWAITING_INTRO + SYNCING wall-clock):
     * how long the newcomer waits for the sponsor intro and the follow-up
     * global-room sync before burning the secret (TIMED_OUT). Deliberately much
     * longer than [bootstrapIntroLifetime] — Tor circuit setup, onion
     * descriptor publication/HSDir propagation, and a human paste-and-scan round
     * trip routinely exceed a few minutes on real networks, while the envelope
     * TTL above still bounds replayability. The outbox keeps re-sending the
     * intro until its own TTL lapses; the session budget covers the whole
     * human + network round trip.
     */
    val bootstrapSessionLifetime: Duration = 40.minutes,
    /** Desired chance that at least one selected relay is online when a message needs relaying. */
    val relayTargetSuccessProbability: Double = 0.9,
    /** Hard cap on the number of relays a message is deposited with. */
    val maxRelays: Int = 3,
    /** Relays below this reliability score are never used (score 0 = opted out). */
    val minRelayScore: Double = 0.1,
) {
    init {
        require(binaryEnvelopeLifetime > Duration.ZERO) { "binaryEnvelopeLifetime must be > 0" }
        require(fastRetryBudget > 0) { "fastRetryBudget must be > 0" }
        require(torRetryDelay > Duration.ZERO) { "torRetryDelay must be > 0" }
        require(webRtcRetryDelay > Duration.ZERO) { "webRtcRetryDelay must be > 0" }
        require(standbyRetryDelay > Duration.ZERO) { "standbyRetryDelay must be > 0" }
        require(retryLoopMaxIdlePoll > Duration.ZERO) { "retryLoopMaxIdlePoll must be > 0" }
        require(ackLifetime > Duration.ZERO) { "ackLifetime must be > 0" }
        require(outboxMaxSizeBytes > 0) { "outboxMaxSizeBytes must be > 0" }
        require(proactiveSessionFreshness > Duration.ZERO) { "proactiveSessionFreshness must be > 0" }
        require(proactiveSessionRetryDelay > Duration.ZERO) { "proactiveSessionRetryDelay must be > 0" }
        require(onionRefreshRetryDelay > Duration.ZERO) { "onionRefreshRetryDelay must be > 0" }
        require(outboxMaxInFlight > 0) { "outboxMaxInFlight must be > 0" }
        require(outboxMaxInFlightPerTarget > 0) { "outboxMaxInFlightPerTarget must be > 0" }
        require(linkPreference.isNotEmpty()) { "linkPreference must not be empty" }
        require(noLinkRecheckDelay > Duration.ZERO) { "noLinkRecheckDelay must be > 0" }
        require(torLinkRetryDelay > Duration.ZERO) { "torLinkRetryDelay must be > 0" }
        require(torLinkMaxRetryDelay >= torLinkRetryDelay) { "torLinkMaxRetryDelay must be >= torLinkRetryDelay" }
        require(torLinkMaxConcurrentOpens > 0) { "torLinkMaxConcurrentOpens must be > 0" }
        require(torLinkOpenTimeout > Duration.ZERO) { "torLinkOpenTimeout must be > 0" }
        require(linkKeeperRecheckInterval > Duration.ZERO) { "linkKeeperRecheckInterval must be > 0" }
        require(dispositionMaxInFlight > 0) { "dispositionMaxInFlight must be > 0" }
        require(dispositionSendBudget > Duration.ZERO) { "dispositionSendBudget must be > 0" }
        require(sessionAwaitTimeout > Duration.ZERO) { "sessionAwaitTimeout must be > 0" }
        require(sessionAwaitReopenDelay > Duration.ZERO) { "sessionAwaitReopenDelay must be > 0" }
        require(syncMaxMessages > 0) { "syncMaxMessages must be > 0" }
        require(syncMaxKnownIds > 0) { "syncMaxKnownIds must be > 0" }
        require(syncOfflineRetryDelay > Duration.ZERO) { "syncOfflineRetryDelay must be > 0" }
        require(removalRePushBackoff > Duration.ZERO) { "removalRePushBackoff must be > 0" }
        require(pingInterval > Duration.ZERO) { "pingInterval must be > 0" }
        require(sweepInterval > Duration.ZERO) { "sweepInterval must be > 0" }
        require(reliabilityHalfLife > Duration.ZERO) { "reliabilityHalfLife must be > 0" }
        require(maxBillableGap > Duration.ZERO) { "maxBillableGap must be > 0" }
        require(bootstrapIntroLifetime > Duration.ZERO) { "bootstrapIntroLifetime must be > 0" }
        require(bootstrapSessionLifetime > Duration.ZERO) { "bootstrapSessionLifetime must be > 0" }
        require(relayTargetSuccessProbability in 0.0..1.0) { "relayTargetSuccessProbability must be in [0,1]" }
        require(maxRelays > 0) { "maxRelays must be > 0" }
        require(minRelayScore in 0.0..1.0) { "minRelayScore must be in [0,1]" }
    }

    fun getRetryDelay(transport: RouterTransport): Duration = when (transport) {
        RouterTransport.WEBRTC -> webRtcRetryDelay
        RouterTransport.TOR -> torRetryDelay
    }
}