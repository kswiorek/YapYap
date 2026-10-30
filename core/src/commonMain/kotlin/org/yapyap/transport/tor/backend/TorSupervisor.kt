package org.yapyap.transport.tor.backend

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Recovery actions, cheapest first. None of them touches the onion key, so the address never changes. */
internal enum class TorRecoveryStep {
    /** Cycle DisableNetwork (drop every connection, retry guards now instead of after backoff). */
    CYCLE_NETWORK,

    /** Stop and start the daemon, keeping all its data. */
    RESTART_DAEMON,

    /** Restart after deleting Tor's directory cache (consensus, relay descriptors): stale or corrupt dir info. */
    PURGE_DIRECTORY_CACHE,

    /**
     * Restart after deleting Tor's `state` file (guard selection). Last resort and
     * rate-limited: picking new entry guards often weakens anonymity.
     */
    RESET_GUARD_STATE,
}

/** What the supervisor needs from a Tor backend. */
internal interface TorRecoveryControl {
    val health: StateFlow<TorHealth>
    val publishState: StateFlow<TorPublishState>

    suspend fun perform(step: TorRecoveryStep)

    /**
     * Silently re-proves our onion is dialable (publish state untouched on
     * success). On repeated failure the backend marks publication DEGRADED.
     */
    suspend fun recheckReachability()
}

/** Remembers when guard state was last reset, so the reset stays rare across restarts. */
internal interface GuardResetRecord {
    fun lastReset(): Instant?
    fun recordReset(at: Instant)
}

/**
 * Recovers a Tor that is stuck while the device network is fine — without the
 * user restarting the app. Network changes are handled elsewhere (the backend's
 * network watch); this covers what is left:
 *
 * - **Bootstrap stall**: no bootstrap progress for [TorBackendConfig.bootstrapStallTimeout]
 *   while online. Progress, not Tor warnings, is the signal: Tor can stall
 *   silently, and it also recovers from loud warnings on its own. The timeout is
 *   generous because every recovery step throws away progress Tor may be about
 *   to make.
 * - **Unreachable onion**: publication DEGRADED while Tor is up.
 *
 * Each detected problem escalates one [TorRecoveryStep]; the ladder starts over
 * once Tor is up and published. Past the last step it keeps restarting, with
 * the stall timeout doubling up to [TorBackendConfig.recoveryMaxBackoff].
 * While up and published it re-checks reachability every
 * [TorBackendConfig.reachabilityRecheckInterval].
 */
internal class TorSupervisor(
    private val control: TorRecoveryControl,
    private val networkOnline: StateFlow<Boolean>,
    private val config: StateFlow<TorBackendConfig>,
    private val guardResets: GuardResetRecord,
    private val clock: Clock = Clock.System,
) {
    private val _recovery = MutableStateFlow<TorRecoveryStep?>(null)

    /** The step currently being worked through, null when healthy. Cleared once up and published. */
    val recovery: StateFlow<TorRecoveryStep?> = _recovery.asStateFlow()

    private var attempts = 0

    /** Runs until cancelled. */
    suspend fun run() {
        while (true) {
            when (awaitTrigger()) {
                Trigger.HEALTHY_RECHECK -> control.recheckReachability()
                Trigger.STALLED, Trigger.DEGRADED -> recover()
            }
        }
    }

    private suspend fun recover() {
        val step = nextStep(attempts, guardResetAllowed())
        attempts++
        _recovery.value = step
        AppLog.warn(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.TOR_HEALTH_CHANGED,
            message = "Tor is stuck; recovering",
            fields = mapOf(
                "step" to step,
                "attempt" to attempts,
                "health" to control.health.value,
                "publishState" to control.publishState.value,
            ),
        )
        if (step == TorRecoveryStep.RESET_GUARD_STATE) guardResets.recordReset(clock.now())
        control.perform(step)
        // Give the step time to show an effect before judging again; also keeps a
        // step that changes nothing (failed command, DEGRADED unchanged) from
        // looping tightly.
        delay(config.value.recoveryCooldown)
    }

    private fun guardResetAllowed(): Boolean {
        val last = guardResets.lastReset() ?: return true
        return clock.now() - last >= config.value.guardResetMinInterval
    }

    private enum class Trigger { STALLED, DEGRADED, HEALTHY_RECHECK }

    /** What the supervisor is waiting on, derived from the current inputs. */
    private sealed interface Watch {
        /** Not ours to fix (stopped, offline): wait. */
        data object Idle : Watch

        /** Tor not up: fires when this progress key stays unchanged too long. */
        data class Progress(val key: String) : Watch

        data object Degraded : Watch

        data object Healthy : Watch
    }

    private fun watchFor(health: TorHealth, publish: TorPublishState, online: Boolean): Watch = when {
        health == TorHealth.Stopped || health == TorHealth.NoNetwork || !online -> Watch.Idle
        health == TorHealth.Ready && publish == TorPublishState.DEGRADED -> Watch.Degraded
        health == TorHealth.Ready && publish == TorPublishState.PUBLISHED -> Watch.Healthy
        // Ready but still proving publication: the backend's own verifier and its budget own that.
        health == TorHealth.Ready -> Watch.Idle
        // Only progress resets the stall timer: a new warning at the same percentage is not progress.
        health is TorHealth.Bootstrapping -> Watch.Progress("bootstrap:${health.progress}")
        else -> Watch.Progress(health::class.simpleName.orEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun awaitTrigger(): Trigger =
        combine(control.health, control.publishState, networkOnline, ::watchFor)
            .distinctUntilChanged()
            .transformLatest { watch ->
                when (watch) {
                    Watch.Idle -> Unit
                    Watch.Healthy -> {
                        // Up and published: the episode is over.
                        attempts = 0
                        _recovery.value = null
                        delay(config.value.reachabilityRecheckInterval)
                        emit(Trigger.HEALTHY_RECHECK)
                    }

                    Watch.Degraded -> emit(Trigger.DEGRADED)
                    is Watch.Progress -> {
                        delay(stallTimeout(attempts))
                        emit(Trigger.STALLED)
                    }
                }
            }
            .first()

    private fun stallTimeout(attempts: Int): Duration {
        val base = config.value.bootstrapStallTimeout
        val beyondLadder = (attempts - TorRecoveryStep.entries.size + 1).coerceAtLeast(0)
        var timeout = base
        repeat(beyondLadder) { timeout = minOf(timeout * 2, config.value.recoveryMaxBackoff) }
        return timeout
    }

    internal companion object {
        /**
         * The step for the [attempt]-th consecutive recovery (0-based). Walks the
         * ladder once, then keeps restarting. A guard reset that isn't allowed yet
         * is replaced by a restart.
         */
        fun nextStep(attempt: Int, guardResetAllowed: Boolean): TorRecoveryStep {
            val ladder = TorRecoveryStep.entries
            val step = if (attempt < ladder.size) ladder[attempt] else TorRecoveryStep.RESTART_DAEMON
            return if (step == TorRecoveryStep.RESET_GUARD_STATE && !guardResetAllowed) {
                TorRecoveryStep.RESTART_DAEMON
            } else {
                step
            }
        }
    }
}
