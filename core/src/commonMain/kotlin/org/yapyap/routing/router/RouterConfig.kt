package org.yapyap.routing.router

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
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
     * (see [noLinkRecheckDelay]) and system traffic has Tor dial a stream on demand.
     */
    val linkPreference: List<RouterTransport> = listOf(RouterTransport.WEBRTC, RouterTransport.TOR),
    /**
     * Outbox entries for a peer with no open link wait this long before being looked at
     * again. Only a safety net: a link coming up wakes the peer's entries at once.
     */
    val noLinkRecheckDelay: Duration = 10.minutes,
    /**
     * One sync request's lifetime: unanswered (no ACK or NACK) by then, the next candidate is
     * asked. Over an open link a request is answered within seconds.
     */
    val syncAttemptTimeout: Duration = 90.seconds,
    /**
     * A message sent over a link but not ACKed within this long gets its relay copies (the
     * link may only look open). Healthy links ACK in about a second, so this rarely fires
     * while the peer is really there.
     */
    val relayBackupAfter: Duration = 30.seconds,
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
    /** Link keeper: failed-open backoffs vary by ± this fraction, so peers that failed together don't retry together. */
    val linkKeeperRetryJitter: Double = 0.25,
    /** Link keeper: shortest sleep between passes (a due time in the past never makes it spin). */
    val linkKeeperMinWait: Duration = 100.milliseconds,
    /** ACK/NACK sends in progress at once (each is a Tor dial back to the sender). */
    val dispositionMaxInFlight: Int = 8,
    /**
     * Give up on one ACK/NACK send after this long. Best-effort: the sender resends
     * an unacknowledged packet, and its duplicate is answered again.
     */
    val dispositionSendBudget: Duration = 30.seconds,
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
     * Outbound bootstrap envelope lifetime (wire TTL): how long a queued intro /
     * recovery-request ciphertext stays deliverable in the outbox. Short enough
     * that late duplicates can't append chain entries for failed onboardings or
     * reply into burned secrets.
     */
    val bootstrapIntroLifetime: Duration = 8.minutes,
    /** Desired chance that at least one selected relay is online when a message needs relaying. */
    val relayTargetSuccessProbability: Double = 0.9,
    /** Hard cap on the number of relays a message is deposited with. */
    val maxRelays: Int = 3,
    /** Relays below this reliability score are never used (score 0 = opted out). */
    val minRelayScore: Double = 0.1,
    /**
     * File-transfer lifetime (design §15): OFFER markers, relay reservations and transfer
     * rows live this long. Must stay below the ratchet superseded-session retention so a
     * sealed OFFER half replayed at the TTL edge still decrypts
     * (`fileTransferTtl < supersededSessionRetention`, 2 days < 3 days).
     */
    val fileTransferTtl: Duration = 2.days,
    /** The pusher's in-flight window: pending chunk rows per transfer (≈3.5 MB of outbox). */
    val maxInFlightChunks: Int = 16,
    /**
     * Flat retry delay for FILE chunk rows (design §6.2), outside the message
     * `fastRetryBudget` / `standbyRetryDelay` policy.
     */
    val fileChunkRetryDelay: Duration = 10.seconds,
    /**
     * One file REQUEST's lifetime: unanswered (no ACK or NACK) by then, the next candidate
     * is asked. Over an open link a request is answered within seconds.
     */
    val fileRequestAttemptTimeout: Duration = 90.seconds,
    /**
     * How long a REQUEST waits for its OFFER after the ACK ("I hold it, serving") before
     * the next candidate is asked.
     */
    val fileRequestWaitTimeout: Duration = 90.seconds,
    /**
     * Chunk-phase deposit signal (design §6.3, plan step 19): a transfer with rows in
     * flight and no chunk ACK at all for this long deposits its remainder at the
     * policy-selected relays. Only the *absence* of every ACK counts — one slow or
     * retried chunk never triggers it. Must stay well below [fileStallTimeout] so the
     * push machinery acts before the receiver's gap trigger re-requests.
     */
    val chunkAckTimeout: Duration = 45.seconds,
    /**
     * Receiver gap trigger (design §8, plan step 19): an IN_FLIGHT receive whose
     * contiguous prefix P does not advance for this long goes STALLED and the REQUEST
     * path re-opens. Owned by the orchestrator's request coordinator, configured here
     * per design §15 (same layer-split TBD as the router-service split).
     */
    val fileStallTimeout: Duration = 10.minutes,
    /**
     * This device's store-and-forward pledge for file relay ciphertext (design §15).
     * Admission and whole-transfer LRU eviction in the file relay store are accounted
     * against it; our own outbox rows are never part of it.
     *
     * TODO (with the router-service split): the config layering here is still a mess —
     * `FileServiceConfig` is not part of `RuntimeConfig` (so it never reaches the
     * settings file) while the orchestrator already carries two sub-configs, and the
     * message relay-cache budget (`outboxMaxSizeBytes`) vs this file share are split
     * ad hoc instead of from one pledge in wiring. Unify the pledge split with the
     * router-service refactor; see the docs note with that TODO.
     */
    val relayStoragePledgeBytes: Long = 100L * 1024L * 1024L,
) {
    init {
        require(binaryEnvelopeLifetime > Duration.ZERO) { "binaryEnvelopeLifetime must be > 0" }
        require(relayBackupAfter > Duration.ZERO) { "relayBackupAfter must be > 0" }
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
        require(syncAttemptTimeout > Duration.ZERO) { "syncAttemptTimeout must be > 0" }
        require(torLinkRetryDelay > Duration.ZERO) { "torLinkRetryDelay must be > 0" }
        require(torLinkMaxRetryDelay >= torLinkRetryDelay) { "torLinkMaxRetryDelay must be >= torLinkRetryDelay" }
        require(torLinkMaxConcurrentOpens > 0) { "torLinkMaxConcurrentOpens must be > 0" }
        require(torLinkOpenTimeout > Duration.ZERO) { "torLinkOpenTimeout must be > 0" }
        require(linkKeeperRecheckInterval > Duration.ZERO) { "linkKeeperRecheckInterval must be > 0" }
        require(linkKeeperRetryJitter in 0.0..<1.0) { "linkKeeperRetryJitter must be in [0, 1)" }
        require(linkKeeperMinWait > Duration.ZERO) { "linkKeeperMinWait must be > 0" }
        require(dispositionMaxInFlight > 0) { "dispositionMaxInFlight must be > 0" }
        require(dispositionSendBudget > Duration.ZERO) { "dispositionSendBudget must be > 0" }
        require(sessionAwaitTimeout > Duration.ZERO) { "sessionAwaitTimeout must be > 0" }
        require(sessionAwaitReopenDelay > Duration.ZERO) { "sessionAwaitReopenDelay must be > 0" }
        require(syncOfflineRetryDelay > Duration.ZERO) { "syncOfflineRetryDelay must be > 0" }
        require(pingInterval > Duration.ZERO) { "pingInterval must be > 0" }
        require(sweepInterval > Duration.ZERO) { "sweepInterval must be > 0" }
        require(reliabilityHalfLife > Duration.ZERO) { "reliabilityHalfLife must be > 0" }
        require(maxBillableGap > Duration.ZERO) { "maxBillableGap must be > 0" }
        require(bootstrapIntroLifetime > Duration.ZERO) { "bootstrapIntroLifetime must be > 0" }
        require(relayTargetSuccessProbability in 0.0..1.0) { "relayTargetSuccessProbability must be in [0,1]" }
        require(maxRelays > 0) { "maxRelays must be > 0" }
        require(minRelayScore in 0.0..1.0) { "minRelayScore must be in [0,1]" }
        require(fileTransferTtl > Duration.ZERO) { "fileTransferTtl must be > 0" }
        require(maxInFlightChunks > 0) { "maxInFlightChunks must be > 0" }
        require(fileChunkRetryDelay > Duration.ZERO) { "fileChunkRetryDelay must be > 0" }
        require(fileRequestAttemptTimeout > Duration.ZERO) { "fileRequestAttemptTimeout must be > 0" }
        require(fileRequestWaitTimeout > Duration.ZERO) { "fileRequestWaitTimeout must be > 0" }
        require(chunkAckTimeout > Duration.ZERO) { "chunkAckTimeout must be > 0" }
        require(fileStallTimeout > chunkAckTimeout) { "fileStallTimeout must be > chunkAckTimeout" }
        require(relayStoragePledgeBytes > 0) { "relayStoragePledgeBytes must be > 0" }
    }

    fun getRetryDelay(transport: RouterTransport): Duration = when (transport) {
        RouterTransport.WEBRTC -> webRtcRetryDelay
        RouterTransport.TOR -> torRetryDelay
    }
}