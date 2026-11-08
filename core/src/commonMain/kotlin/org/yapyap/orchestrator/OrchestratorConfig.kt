package org.yapyap.orchestrator

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
internal data class OrchestratorConfig(
    val maintenanceInterval: Duration = 1.hours,
    /** Send cadence for typing indicators while the user is composing; receivers idle-timeout at ~2x this. */
    val typingIndicatorInterval: Duration = 3.seconds,
    /** Delay before a freshly created pending sync is first attempted (lets in-flight messages land). */
    val syncGracePeriod: Duration = 60.seconds,
    /** Roster re-read cadence for the identity service (silent lastSeen aging emits nothing). */
    val identityRefreshInterval: Duration = 60.seconds,
    /** Max messages returned per sync response (responder-side page size). */
    val syncMaxMessages: Int = 20,
    /**
     * Max requester frontier IDs honored per sync response (responder-side bound).
     * Policy: truncate, never refuse (docs/room events.md §6) — the bound is a
     * DoS guard against forged requests, not a paging mechanism (appends
     * reference the whole frontier, so the covering antichain stays tiny).
     * Truncation is merely wasteful, never incorrect: see DefaultSyncPayloadProvider.
     */
    val syncMaxKnownIds: Int = 64,
    /**
     * Backoff between removal-node re-pushes to the same account and room
     * (ping-contradiction recovery, docs/room events.md §6): the ping is the
     * proof, the backoff is the rate governor.
     */
    val removalRePushBackoff: Duration = 1.hours,
    /**
     * Newcomer onboarding session budget (AWAITING_INTRO + SYNCING wall-clock):
     * how long the newcomer waits for the sponsor intro and the follow-up
     * global-room sync before burning the secret (TIMED_OUT). Deliberately much
     * longer than the router's bootstrap envelope TTL
     * ([org.yapyap.routing.router.RouterConfig.bootstrapIntroLifetime]) — Tor circuit setup, onion
     * descriptor publication/HSDir propagation, and a human paste-and-scan round
     * trip routinely exceed a few minutes on real networks, while the envelope
     * TTL still bounds replayability. The outbox keeps re-sending the
     * intro until its own TTL lapses; the session budget covers the whole
     * human + network round trip.
     */
    val onboardingSessionLifetime: Duration = 40.minutes,
) {
    init {
        require(maintenanceInterval > Duration.ZERO) { "maintenanceIntervalSeconds must be > 0" }
        require(typingIndicatorInterval > Duration.ZERO) { "typingIndicatorInterval must be > 0" }
        require(syncGracePeriod >= Duration.ZERO) { "syncGracePeriodSeconds must be >= 0" }
        require(identityRefreshInterval > Duration.ZERO) { "identityRefreshInterval must be > 0" }
        require(syncMaxMessages > 0) { "syncMaxMessages must be > 0" }
        require(syncMaxKnownIds > 0) { "syncMaxKnownIds must be > 0" }
        require(removalRePushBackoff > Duration.ZERO) { "removalRePushBackoff must be > 0" }
        require(onboardingSessionLifetime > Duration.ZERO) { "onboardingSessionLifetime must be > 0" }
    }
}
