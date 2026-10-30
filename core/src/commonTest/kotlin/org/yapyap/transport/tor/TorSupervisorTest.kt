package org.yapyap.transport.tor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.transport.tor.backend.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TorSupervisorTest {

    private class FakeControl : TorRecoveryControl {
        override val health = MutableStateFlow<TorHealth>(TorHealth.Bootstrapping(5, null))
        override val publishState = MutableStateFlow(TorPublishState.PUBLISHING)
        val performed = mutableListOf<TorRecoveryStep>()
        var rechecks = 0

        override suspend fun perform(step: TorRecoveryStep) {
            performed += step
        }

        override suspend fun recheckReachability() {
            rechecks++
        }
    }

    private class MemoryGuardResets(var last: Instant? = null) : GuardResetRecord {
        override fun lastReset(): Instant? = last
        override fun recordReset(at: Instant) {
            last = at
        }
    }

    private val config = TorBackendConfig(
        bootstrapStallTimeout = 2.minutes,
        recoveryCooldown = 30.seconds,
        recoveryMaxBackoff = 30.minutes,
        guardResetMinInterval = 24.hours,
        reachabilityRecheckInterval = 15.minutes,
    )

    private class Harness(
        val control: FakeControl,
        val online: MutableStateFlow<Boolean>,
        val guardResets: MemoryGuardResets,
        val supervisor: TorSupervisor,
    )

    private fun TestScope.harness(guardResets: MemoryGuardResets = MemoryGuardResets()): Harness {
        val control = FakeControl()
        val online = MutableStateFlow(true)
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_000_000_000_000L + testScheduler.currentTime)
        }
        val supervisor = TorSupervisor(control, online, MutableStateFlow(config), guardResets, clock)
        backgroundScope.launch { supervisor.run() }
        runCurrent()
        return Harness(control, online, guardResets, supervisor)
    }

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun nextStep_walksLadderThenKeepsRestarting() {
        assertEquals(
            listOf(
                TorRecoveryStep.CYCLE_NETWORK,
                TorRecoveryStep.RESTART_DAEMON,
                TorRecoveryStep.PURGE_DIRECTORY_CACHE,
                TorRecoveryStep.RESET_GUARD_STATE,
                TorRecoveryStep.RESTART_DAEMON,
                TorRecoveryStep.RESTART_DAEMON,
            ),
            (0..5).map { TorSupervisor.nextStep(it, guardResetAllowed = true) },
        )
        assertEquals(TorRecoveryStep.RESTART_DAEMON, TorSupervisor.nextStep(3, guardResetAllowed = false))
    }

    @Test
    fun stalledBootstrap_escalatesOneStepPerStall() = runTest {
        val h = harness()
        advance(119.seconds)
        assertEquals(emptyList(), h.control.performed)
        advance(1.seconds)
        assertEquals(listOf(TorRecoveryStep.CYCLE_NETWORK), h.control.performed)
        assertEquals(TorRecoveryStep.CYCLE_NETWORK, h.supervisor.recovery.value)
        // Cooldown, then another full stall timeout with no progress.
        advance(30.seconds + 2.minutes)
        assertEquals(listOf(TorRecoveryStep.CYCLE_NETWORK, TorRecoveryStep.RESTART_DAEMON), h.control.performed)
    }

    @Test
    fun progress_resetsTheStallTimer_butNewWarningsDoNot() = runTest {
        val h = harness()
        advance(90.seconds)
        h.control.health.value = TorHealth.Bootstrapping(10, null)
        runCurrent()
        advance(90.seconds)
        assertEquals(emptyList(), h.control.performed, "progress at 1:30 must restart the 2 min timer")
        // Same percentage with a warning attached is not progress.
        h.control.health.value = TorHealth.Bootstrapping(10, null).copy(
            problem = org.yapyap.transport.tor.backend.TorBootstrapProblem("NOROUTE", null, 1, null),
        )
        runCurrent()
        advance(30.seconds)
        assertEquals(listOf(TorRecoveryStep.CYCLE_NETWORK), h.control.performed)
    }

    @Test
    fun offlineOrNoNetwork_isNeverTreatedAsStuck() = runTest {
        val h = harness()
        h.online.value = false
        runCurrent()
        advance(30.minutes)
        h.online.value = true
        h.control.health.value = TorHealth.NoNetwork
        runCurrent()
        advance(30.minutes)
        assertEquals(emptyList(), h.control.performed)
    }

    @Test
    fun degradedPublication_recoversAtOnce_andHealthyEndsTheEpisode() = runTest {
        val h = harness()
        h.control.health.value = TorHealth.Ready
        h.control.publishState.value = TorPublishState.DEGRADED
        runCurrent()
        assertEquals(listOf(TorRecoveryStep.CYCLE_NETWORK), h.control.performed)

        h.control.publishState.value = TorPublishState.PUBLISHED
        advance(30.seconds)
        assertNull(h.supervisor.recovery.value)

        // A new episode starts from the bottom of the ladder again.
        h.control.publishState.value = TorPublishState.DEGRADED
        runCurrent()
        assertEquals(listOf(TorRecoveryStep.CYCLE_NETWORK, TorRecoveryStep.CYCLE_NETWORK), h.control.performed)
    }

    @Test
    fun healthy_rechecksReachabilityPeriodically() = runTest {
        val h = harness()
        h.control.health.value = TorHealth.Ready
        h.control.publishState.value = TorPublishState.PUBLISHED
        runCurrent()
        advance(15.minutes)
        assertEquals(1, h.control.rechecks)
        advance(15.minutes)
        assertEquals(2, h.control.rechecks)
        assertEquals(emptyList(), h.control.performed)
    }

    @Test
    fun guardReset_isRecorded_andNotRepeatedWithinTheInterval() = runTest {
        val resets = MemoryGuardResets()
        val h = harness(resets)
        // Walk the whole ladder: each step needs cooldown + a stall timeout.
        advance(2.minutes)
        repeat(3) { advance(30.seconds + 2.minutes) }
        assertEquals(TorRecoveryStep.RESET_GUARD_STATE, h.control.performed.last())
        val firstReset = resets.last

        // Next episode, same day: the guard step becomes a plain restart.
        h.control.health.value = TorHealth.Ready
        h.control.publishState.value = TorPublishState.PUBLISHED
        advance(30.seconds)
        h.control.health.value = TorHealth.Bootstrapping(5, null)
        h.control.publishState.value = TorPublishState.PUBLISHING
        runCurrent()
        val before = h.control.performed.size
        advance(2.minutes)
        repeat(3) { advance(30.seconds + 2.minutes) }
        assertEquals(
            listOf(
                TorRecoveryStep.CYCLE_NETWORK,
                TorRecoveryStep.RESTART_DAEMON,
                TorRecoveryStep.PURGE_DIRECTORY_CACHE,
                TorRecoveryStep.RESTART_DAEMON,
            ),
            h.control.performed.drop(before),
        )
        assertEquals(firstReset, resets.last)
    }

    @Test
    fun pastTheLadder_stallTimeoutBacksOff() = runTest {
        val h = harness(MemoryGuardResets())
        advance(2.minutes)
        repeat(3) { advance(30.seconds + 2.minutes) }
        assertEquals(4, h.control.performed.size)
        // Fifth attempt waits 4 min (doubled once), not 2.
        advance(30.seconds + 2.minutes)
        assertEquals(4, h.control.performed.size)
        advance(2.minutes)
        assertEquals(5, h.control.performed.size)
    }
}
