package org.yapyap.transport.tor.backend

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runtime tuning values for Tor backend implementations.
 *
 * `socksRetryTimeout` bounds one [TorBackend.send] call, enforced per attempt:
 * each SOCKS dial gets at most the remaining budget, so a slow-but-working
 * rendezvous (up to [socksSocketTimeout]) is never cut off by the loop cadence
 * while a truly wedged Tor can't stall a dispatch past the budget. When the
 * budget lapses the send fails and the outbox retry loop owns the backoff.
 * Note a slow dispatch still occupies its outbox cycle slot (entries dispatch
 * concurrently and wake-ups coalesce, so fresh messages wait at most one slow
 * cycle) — the price of letting Tor finish instead of aborting winnable
 * rendezvous early.
 */
@Serializable
internal data class TorBackendConfig(
    /**
     * How long start() waits for the daemon's SOCKS listener. Bootstrap is not
     * part of it (progress is reported through `TorBackend.health`), so this
     * only fires when the daemon itself fails to come up.
     */
    val startupTimeout: Duration = 120_000L.milliseconds,
    val maxPayloadBytes: Int = 4 * 1024 * 1024,
    val socksRetryTimeout: Duration = 150.seconds,
    val socksRetryDelay: Duration = 5.seconds,
    /**
     * Backoff between retries when the target onion is not yet published
     * (SOCKS code 4, host-unreachable — the normal state of a fresh newcomer
     * onion for minutes after first boot). Deliberately slower than
     * [socksRetryDelay] so we don't hammer Tor's HSDir lookups in a tight loop.
     */
    val socksNotPublishedRetryDelay: Duration = 30.seconds,
    /**
     * Per-connection SOCKS TCP timeout (connect + handshake reads). Aligned
     * with Tor's own `SocksTimeout` default: Tor only answers the SOCKS
     * handshake once rendezvous is built or definitively failed, and on slow
     * paths that legitimately takes a minute or more. Cutting it shorter aborts
     * winnable rendezvous (observed as `closed-channel` with a
     * `SocketTimeoutException` cause). Fast-fail replies (codes 4/6) still
     * arrive immediately and are unaffected.
     */
    val socksSocketTimeout: Duration = 120.seconds,
    /**
     * Delay before re-probing after a self-dial that timed out (Tor still
     * working; keep its progress) or found no descriptor (after dropping Tor's
     * cached lookups with NEWNYM, which Tor rate-limits to one per 10 s).
     */
    val probeRefreshRetryDelay: Duration = 10.seconds,
    /**
     * Budget for a single self-dial probe (dial + rendezvous round trip).
     * Aligned with Tor's own `SocksTimeout`, like [socksSocketTimeout]: on slow
     * paths rendezvous legitimately takes over a minute, and a shorter budget
     * cut winnable probes off and restarted them from scratch (observed as
     * repeated 60 s timeouts followed by a quick success).
     */
    val probeTimeout: Duration = 120.seconds,
    /**
     * A fetch of our own descriptor that no directory relay has answered for
     * this long ends the self-dial probe early (lookups are then refreshed and
     * another relay asked). Normal fetches answer within a couple of seconds;
     * a hanging relay otherwise holds the probe for Tor's full two minutes.
     */
    val descriptorFetchStallTimeout: Duration = 20.seconds,
    /**
     * Minimum interval between onion-lookup refreshes (NEWNYM) requested from
     * outside the backend's own recovery — peer sends stuck on a fetch, the
     * router seeing an online peer as "not found". NEWNYM is global: every
     * onion's cached lookups are dropped and re-fetched.
     */
    val onionLookupRefreshMinInterval: Duration = 30.seconds,
    /** Give up verifying reachability after this long past the first upload. */
    val probeBudget: Duration = 10.minutes,
    /**
     * Grace period after bootstrap completes before verifying
     * reachability even when no `HS_DESC UPLOADED` was observed: the event is
     * only the fast path (it can be missed or late), the self-dial probe is
     * ground truth.
     */
    val uploadGracePeriod: Duration = 3.minutes,
    /**
     * Publish budget: how long after bootstrap completes we wait for a
     * matching `HS_DESC UPLOADED` before reporting DEGRADED. Tor circuit setup
     * plus HSDir upload normally takes a minute or two; the budget only fires
     * on a genuinely stuck network.
     */
    val publishTimeout: Duration = 15.minutes,
    /**
     * SOCKS reply codes worth retrying inside the send budget. Code 1 is
     * Tor's generic failure — almost always a transient daemon wobble for our
     * well-formed requests (never the slow not-published lane); 3/4/6 are the
     * usual transient set, with 4 (not-yet-published onion) on the slow lane.
     */
    val socksTransientFailureCodes: Set<Int> = setOf(1, 3, 4, 6),
    /**
     * Supervisor: no bootstrap progress for this long while online counts as
     * stuck. Generous on purpose — Tor recovers from many stalls on its own
     * (a minute or more is normal on a slow directory mirror), and every
     * recovery step throws its progress away.
     */
    val bootstrapStallTimeout: Duration = 2.minutes,
    /** Supervisor: minimum wait after a recovery step before judging again. */
    val recoveryCooldown: Duration = 30.seconds,
    /** Supervisor: cap for the stall timeout, which doubles per attempt once the ladder is exhausted. */
    val recoveryMaxBackoff: Duration = 30.minutes,
    /** Supervisor: guard state is reset at most this often (new guards weaken anonymity). */
    val guardResetMinInterval: Duration = 1.days,
    /** Supervisor: how often to silently re-prove our onion is dialable while healthy. */
    val reachabilityRecheckInterval: Duration = 15.minutes,
    val defaultTorPort: Int = 80,
    /**
     * Sessions (long-lived streams, see `TorTransport`): how long one session dial may keep
     * retrying before the session reports FAILED. Shorter than [socksRetryTimeout] on
     * purpose: whoever keeps sessions open retries later with its own backoff, and a dead
     * peer shouldn't hold a dial slot for minutes.
     */
    val sessionDialBudget: Duration = 60.seconds,
    /** Sessions: how long a dialed stream may wait for the far end's ACCEPT record. */
    val sessionAcceptTimeout: Duration = 30.seconds,
    /** Sessions: a keepalive goes out after this long without anything written. */
    val sessionKeepaliveInterval: Duration = 45.seconds,
    /**
     * Sessions: a stream with nothing received for this long is closed as dead. Tor gives no
     * timely signal when the far end vanishes (lid closed, network gone), so this bounds how
     * long writes can disappear into a dead stream. Must exceed [sessionKeepaliveInterval].
     */
    val sessionIdleTimeout: Duration = 2.minutes,
    /** Sessions: a single record write stuck this long (far end not reading) closes the stream. */
    val sessionWriteTimeout: Duration = 30.seconds,
    /** Sessions: accepted streams held at once; further inbound streams are refused. */
    val maxInboundSessions: Int = 128,
) {
    init {
        require(startupTimeout > 0L.milliseconds) { "startupTimeoutMillis must be > 0" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be > 0" }
        require(socksRetryTimeout > 0L.milliseconds) { "socksRetryTimeoutMillis must be > 0" }
        require(socksRetryDelay > 0L.milliseconds) { "socksRetryDelayMillis must be > 0" }
        require(socksNotPublishedRetryDelay > 0L.milliseconds) { "socksNotPublishedRetryDelayMillis must be > 0" }
        require(socksSocketTimeout > 0L.milliseconds) { "socksSocketTimeoutMillis must be > 0" }
        require(probeRefreshRetryDelay > 0L.milliseconds) { "probeRefreshRetryDelayMillis must be > 0" }
        require(probeTimeout > 0L.milliseconds) { "probeTimeoutMillis must be > 0" }
        require(descriptorFetchStallTimeout > 0L.milliseconds) { "descriptorFetchStallTimeout must be > 0" }
        require(onionLookupRefreshMinInterval > 0L.milliseconds) { "onionLookupRefreshMinInterval must be > 0" }
        require(probeBudget > 0L.milliseconds) { "probeBudgetMillis must be > 0" }
        require(sessionDialBudget > Duration.ZERO) { "sessionDialBudget must be > 0" }
        require(sessionAcceptTimeout > Duration.ZERO) { "sessionAcceptTimeout must be > 0" }
        require(sessionKeepaliveInterval > Duration.ZERO) { "sessionKeepaliveInterval must be > 0" }
        require(sessionIdleTimeout > sessionKeepaliveInterval) { "sessionIdleTimeout must exceed sessionKeepaliveInterval" }
        require(sessionWriteTimeout > Duration.ZERO) { "sessionWriteTimeout must be > 0" }
        require(maxInboundSessions > 0) { "maxInboundSessions must be > 0" }
        require(uploadGracePeriod > 0L.milliseconds) { "uploadGracePeriodMillis must be > 0" }
        require(publishTimeout > 0L.milliseconds) { "publishTimeoutMillis must be > 0" }
        require(bootstrapStallTimeout > 0L.milliseconds) { "bootstrapStallTimeout must be > 0" }
        require(recoveryCooldown > 0L.milliseconds) { "recoveryCooldown must be > 0" }
        require(recoveryMaxBackoff >= bootstrapStallTimeout) { "recoveryMaxBackoff must be >= bootstrapStallTimeout" }
        require(guardResetMinInterval > 0L.milliseconds) { "guardResetMinInterval must be > 0" }
        require(reachabilityRecheckInterval > 0L.milliseconds) { "reachabilityRecheckInterval must be > 0" }
        require(defaultTorPort in 1..65535) { "defaultTorPort must be in range 1..65535" }
    }
}

