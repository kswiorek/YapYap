package org.yapyap.orchestrator

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
    /**
     * How often the file request coordinator sweeps pending requests and stalled receives. This is
     * the trigger-state sweep; routing runs its own file sweeps on `RouterConfig.fileSweepInterval`.
     */
    val fileSweepInterval: Duration = 30.seconds,
    /**
     * Receiver gap trigger (design §8): an IN_FLIGHT receive whose contiguous prefix does not
     * advance for this long goes STALLED and the request path re-opens.
     */
    val fileStallTimeout: Duration = 10.minutes,
    /**
     * How long a receive no message references is kept before it is deleted (design invariant 25):
     * a pushed or relayed file can arrive before the message that references it.
     */
    val unreferencedFileRetention: Duration = 2.days,
    /** Quota over the store's copies; references are never deleted by retention. */
    val storeQuotaBytes: Long = 1024L * 1024L * 1024L,
    /** Staging bound (design §8): chunks beyond the prefix that cannot extend it are deferred past this. */
    val maxStagedBytes: Long = 32L * 1024L * 1024L,
    /** Block mismatches before a receive fails (design §8). */
    val fileHashRetryLimit: Int = 3,
    /**
     * Every file at or below this is fetched automatically, small included; above it only a user
     * click does (design §8). It is about the user's attention and disk, orthogonal to the router's
     * small/large boundary.
     */
    val autoDownloadMaxBytes: Long = 250L * 1024L * 1024L,
    /** Checkpoint cadence (design §8): one append, fsync and prefix commit per this many bytes, */
    val fileCheckpointBytes: Long = 8L * 1024L * 1024L,
    /** or per this long, whichever comes first. */
    val fileCheckpointInterval: Duration = 2.seconds,
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
     * global-room sync before burning the secret (TIMED_OUT). Must be longer than
     * the router's bootstrap envelope TTL (checked in `wiring/ConfigInvariants`).
     */
    val onboardingSessionLifetime: Duration = 40.minutes,
) {
    init {
        require(maintenanceInterval > Duration.ZERO) { "maintenanceIntervalSeconds must be > 0" }
        require(typingIndicatorInterval > Duration.ZERO) { "typingIndicatorInterval must be > 0" }
        require(syncGracePeriod >= Duration.ZERO) { "syncGracePeriodSeconds must be >= 0" }
        require(fileSweepInterval > Duration.ZERO) { "fileSweepInterval must be > 0" }
        require(fileStallTimeout > Duration.ZERO) { "fileStallTimeout must be > 0" }
        require(unreferencedFileRetention > Duration.ZERO) { "unreferencedFileRetention must be > 0" }
        require(storeQuotaBytes > 0) { "storeQuotaBytes must be > 0" }
        require(maxStagedBytes > 0) { "maxStagedBytes must be > 0" }
        require(fileHashRetryLimit > 0) { "fileHashRetryLimit must be > 0" }
        require(autoDownloadMaxBytes >= 0) { "autoDownloadMaxBytes must be >= 0" }
        // A file fetched without asking must fit the quota, or the next quota run evicts it
        // right after it arrived (design §10).
        require(storeQuotaBytes >= autoDownloadMaxBytes) { "storeQuotaBytes must hold an auto-downloaded file" }
        require(fileCheckpointBytes > 0) { "fileCheckpointBytes must be > 0" }
        require(fileCheckpointInterval > Duration.ZERO) { "fileCheckpointInterval must be > 0" }
        require(maxStagedBytes >= fileCheckpointBytes) { "maxStagedBytes must hold a checkpoint's worth" }
        require(identityRefreshInterval > Duration.ZERO) { "identityRefreshInterval must be > 0" }
        require(syncMaxMessages > 0) { "syncMaxMessages must be > 0" }
        require(syncMaxKnownIds > 0) { "syncMaxKnownIds must be > 0" }
        require(removalRePushBackoff > Duration.ZERO) { "removalRePushBackoff must be > 0" }
        require(onboardingSessionLifetime > Duration.ZERO) { "onboardingSessionLifetime must be > 0" }
    }
}
