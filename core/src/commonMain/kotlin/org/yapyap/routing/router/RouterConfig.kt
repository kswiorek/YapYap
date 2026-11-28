package org.yapyap.routing.router

import kotlinx.serialization.Serializable
import org.yapyap.routing.link.LinkKeeperConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
internal data class RouterConfig(
    /**
     * How long a message's content lives, from its first hop; relay copies and forwards keep the
     * same expiry. Below crypto's superseded-session retention (`wiring/ConfigInvariants`).
     */
    val binaryEnvelopeLifetime: Duration = 2.days,
    val ackLifetime: Duration = 1.hours,
    val fastRetryBudget: Int = 3,
    val torRetryDelay: Duration = 60.seconds,
    val webRtcRetryDelay: Duration = 10.seconds,
    val standbyRetryDelay: Duration = 1.hours,
    val retryLoopMaxIdlePoll: Duration = 60.seconds,
    val dedupRetention: Duration = 30.days,
    /**
     * The WebRTC link keeper's "sure online and expected traffic" gate: a peer becomes a target
     * only after it sent us traffic within this window. A WebRTC open costs Tor signaling
     * round-trips plus ICE, so it is never spent on a peer we have no recent evidence for.
     */
    val proactiveSessionFreshness: Duration = 60.seconds,
    /** WebRTC links: initial backoff after a failed open, doubling per consecutive failure. */
    val proactiveSessionRetryDelay: Duration = 30.seconds,
    /** WebRTC links: cap on the exponential backoff between open attempts to a peer that keeps failing. */
    val webrtcLinkMaxRetryDelay: Duration = 10.minutes,
    /** WebRTC links: opens (signaling round-trips + ICE) in progress at once. */
    val webrtcLinkMaxConcurrentOpens: Int = 2,
    /**
     * Default budget for a budgeted link await (`LinkKeeper.await`, REQUIRED mode), and the
     * WebRTC keeper's per-attempt open budget. Signaling round-trips take seconds.
     */
    val sessionAwaitTimeout: Duration = 45.seconds,
    /**
     * Cadence at which a budgeted link await (`LinkKeeper.await`) re-issues the idempotent
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
     * A message sent over a link but not ACKed within this long gets its relay copies, and a
     * file OFFER its deposit (the link may only look open). Healthy links ACK in about a second,
     * so this rarely fires while the peer is really there.
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
     * How often [org.yapyap.routing.presence.PeerAvailabilityRegistry]'s sweep samples peer availability: for each peer it
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
     * The network's small/large boundary: at or below it a file may be pushed directly (and
     * relays may store it); above it the receiver pulls it over WebRTC. A network value, not a
     * user setting — every device must settle on the same number; receivers enforce their own.
     */
    val smallFileMaxBytes: Long = 10L * 1024L * 1024L,
    /**
     * The push window before a small file's first automatic pull attempt: the OFFER may be in
     * flight, or may never come. Large files start at once (nothing is ever pushed).
     */
    val filePullDelay: Duration = 60.seconds,
    /**
     * Cadence of the routing file sweeps (the seal registry's, the relay forwards' and the target
     * side's); each transfer session ticks three times per interval. Distinct from the orchestrator's
     * `fileSweepInterval`, which paces the request-trigger sweep.
     */
    val fileSweepInterval: Duration = 45.seconds,
    /**
     * A seal's lifetime: every transfer of it, at every hop, and every relay copy expire with it.
     * Must stay below crypto's superseded-session retention (checked in `wiring/ConfigInvariants`),
     * so a seal forwarded late still opens.
     */
    val fileTransferTtl: Duration = 2.days,
    /** A transfer's in-flight window: pending chunk rows per transfer (≈4.1 MB of outbox at 16 chunks). */
    val maxInFlightChunks: Int = 16,
    /**
     * Flat retry delay for FILE chunk rows (design §6.2), outside the message
     * `fastRetryBudget` / `standbyRetryDelay` policy.
     */
    val fileChunkRetryDelay: Duration = 10.seconds,
    /**
     * One file pull attempt's answer budget, both classes: a small REQUEST unanswered (no ACK or
     * NACK) by then, or a large PULL with no PULL_OK/DECLINED, moves to the next candidate. It also
     * bounds a large-file holder's wait for a requester that stopped making progress. Over an open
     * link an attempt is answered within seconds.
     */
    val filePullAttemptTimeout: Duration = 90.seconds,
    /**
     * How long a REQUEST waits for the serve's OFFER after the ACK ("I hold it, serving") before
     * the next candidate is asked.
     */
    val fileRequestWaitTimeout: Duration = 90.seconds,
    /**
     * Chunk-phase gone signal: a transfer with chunks in flight and no ACK at all for this long
     * reports its peer gone, and a seal's direct transfer deposits its remainder at the
     * policy-selected relays. Only the
     * absence of every ACK counts; one slow or retried chunk never triggers it. Must stay below
     * the orchestrator's stall timeout (checked in `wiring/ConfigInvariants`), so the push acts
     * before the receiver re-requests.
     */
    val chunkAckTimeout: Duration = 45.seconds,
    /**
     * Large-file session budget (design §15): a large pull opens a
     * WebRTC link through `LinkKeeper.await` with this timeout.
     * Matches the generic session-await budget: signaling round-trips take seconds.
     */
    val largeFileSessionOpenTimeout: Duration = 45.seconds,
    /**
     * Large-file credit window (design §6.6, §15): the holder may run at
     * most this far past the last PROGRESS before pausing for the receiver. Bounds
     * a slow disk so frames never pile up in memory; the channel's `bufferedAmount`
     * low threshold bounds the sender side.
     */
    val largeFileStreamWindowBytes: Long = 32L * 1024L * 1024L,
    /** Large-file pulls running at once per node (design §9, one candidate at a time per file). */
    val maxConcurrentLargeFiles: Int = 2,
    /** Large-file serves one peer may hold open at once (each holds a plaintext stream and a channel). */
    val maxLargeFileServesPerPeer: Int = 4,
    /**
     * The close-race grace before a requester treats a channel close as the outcome: a real
     * DECLINED/PULL_OK arriving just after the close still wins (design §6.6).
     */
    val largeFileAnswerGrace: Duration = 300.milliseconds,
    /** The holder's poll while past the credit window, and after an empty read (design §6.6). */
    val largeFilePacingDelay: Duration = 50.milliseconds,
    /** The requester's retry after a deferred append (the engine is still recovering, design §8). */
    val largeFileDeferRetry: Duration = 100.milliseconds,
    /** How often a holder that sent every byte rechecks whether its requester is still there. */
    val largeFileParkedPoll: Duration = 1.seconds,
    /** Buffered-amount resume watermark for the holder's backpressure wait (design §6.6). */
    val largeFileBufferedResumeBytes: Long = 1L * 1024L * 1024L,
    /** Idle reservation TTL (design §7.2): partial deposits older than this free their quota. */
    val fileRelayIdleTtl: Duration = 1.hours,
    /**
     * This device's store-and-forward pledge (design §15): the bytes it holds for other
     * devices' traffic, split between relayed file ciphertext and the message relay cache by
     * [fileRelayShare]. Our own outbox rows are never part of it.
     */
    val relayStoragePledgeBytes: Long = 100L * 1024L * 1024L,
    /** Fraction of [relayStoragePledgeBytes] that relayed files may use; relayed messages get the rest. */
    val fileRelayShare: Double = 0.8,
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
        require(proactiveSessionFreshness > Duration.ZERO) { "proactiveSessionFreshness must be > 0" }
        require(proactiveSessionRetryDelay > Duration.ZERO) { "proactiveSessionRetryDelay must be > 0" }
        require(webrtcLinkMaxRetryDelay >= proactiveSessionRetryDelay) {
            "webrtcLinkMaxRetryDelay must be >= proactiveSessionRetryDelay"
        }
        require(webrtcLinkMaxConcurrentOpens > 0) { "webrtcLinkMaxConcurrentOpens must be > 0" }
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
        require(smallFileMaxBytes > 0) { "smallFileMaxBytes must be > 0" }
        require(filePullDelay >= Duration.ZERO) { "filePullDelay must be >= 0" }
        require(fileSweepInterval > Duration.ZERO) { "fileSweepInterval must be > 0" }
        require(fileTransferTtl > Duration.ZERO) { "fileTransferTtl must be > 0" }
        require(maxInFlightChunks > 0) { "maxInFlightChunks must be > 0" }
        require(fileChunkRetryDelay > Duration.ZERO) { "fileChunkRetryDelay must be > 0" }
        require(filePullAttemptTimeout > Duration.ZERO) { "filePullAttemptTimeout must be > 0" }
        require(fileRequestWaitTimeout > Duration.ZERO) { "fileRequestWaitTimeout must be > 0" }
        require(chunkAckTimeout > Duration.ZERO) { "chunkAckTimeout must be > 0" }
        require(largeFileSessionOpenTimeout > Duration.ZERO) { "largeFileSessionOpenTimeout must be > 0" }
        require(largeFileStreamWindowBytes > 0) { "largeFileStreamWindowBytes must be > 0" }
        require(maxConcurrentLargeFiles > 0) { "maxConcurrentLargeFiles must be > 0" }
        require(maxLargeFileServesPerPeer > 0) { "maxLargeFileServesPerPeer must be > 0" }
        require(largeFileAnswerGrace > Duration.ZERO) { "largeFileAnswerGrace must be > 0" }
        require(largeFilePacingDelay > Duration.ZERO) { "largeFilePacingDelay must be > 0" }
        require(largeFileDeferRetry > Duration.ZERO) { "largeFileDeferRetry must be > 0" }
        require(largeFileParkedPoll > Duration.ZERO) { "largeFileParkedPoll must be > 0" }
        require(largeFileBufferedResumeBytes > 0) { "largeFileBufferedResumeBytes must be > 0" }
        require(fileRelayIdleTtl > Duration.ZERO) { "fileRelayIdleTtl must be > 0" }
        require(fileRelayIdleTtl < fileTransferTtl) { "fileRelayIdleTtl must be shorter than the transfer TTL" }
        require(relayStoragePledgeBytes > 0) { "relayStoragePledgeBytes must be > 0" }
        require(fileRelayShare > 0.0 && fileRelayShare < 1.0) { "fileRelayShare must be in (0, 1)" }
    }

    /** Bytes of relayed file ciphertext this device admits ([fileRelayShare] of the pledge). */
    val fileRelayBudgetBytes: Long
        get() = (relayStoragePledgeBytes * fileRelayShare).toLong()

    /** Bytes of relayed messages this device keeps in its outbox (the rest of the pledge). */
    val messageRelayBudgetBytes: Long
        get() = relayStoragePledgeBytes - fileRelayBudgetBytes

    /** The Tor link keeper's piece of these rules: it keeps a link open to every active peer. */
    val torLinkKeeperConfig: LinkKeeperConfig
        get() = LinkKeeperConfig(
            retryDelay = torLinkRetryDelay,
            maxRetryDelay = torLinkMaxRetryDelay,
            maxConcurrentOpens = torLinkMaxConcurrentOpens,
            openTimeout = torLinkOpenTimeout,
            recheckInterval = linkKeeperRecheckInterval,
            retryJitter = linkKeeperRetryJitter,
            minWait = linkKeeperMinWait,
            reopenCadence = sessionAwaitReopenDelay,
        )

    /** The WebRTC link keeper's piece: same mechanism, guarded opens (sure online and expected traffic). */
    val webrtcLinkKeeperConfig: LinkKeeperConfig
        get() = LinkKeeperConfig(
            retryDelay = proactiveSessionRetryDelay,
            maxRetryDelay = webrtcLinkMaxRetryDelay,
            maxConcurrentOpens = webrtcLinkMaxConcurrentOpens,
            openTimeout = sessionAwaitTimeout,
            recheckInterval = linkKeeperRecheckInterval,
            retryJitter = linkKeeperRetryJitter,
            minWait = linkKeeperMinWait,
            reopenCadence = sessionAwaitReopenDelay,
        )

    fun getRetryDelay(transport: RouterTransport): Duration = when (transport) {
        RouterTransport.WEBRTC -> webRtcRetryDelay
        RouterTransport.TOR -> torRetryDelay
    }
}