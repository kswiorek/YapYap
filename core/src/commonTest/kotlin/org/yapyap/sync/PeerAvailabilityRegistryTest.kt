package org.yapyap.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RouterConfig
import org.yapyap.time.FixedEpochProvider
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PeerAvailabilityRegistryTest {

    private fun peer(suffix: String) = PeerId("avail-$suffix")

    private fun registry(
        time: FixedEpochProvider,
        pingIntervalSeconds: Long = 300,
        sweepIntervalSeconds: Long = 300,
        halfLifeSeconds: Long = 86_400,
    ) = PeerAvailabilityRegistry(
        timeProvider = time,
        routerConfig = MutableStateFlow(
            RouterConfig().copy(
                pingInterval = pingIntervalSeconds.seconds,
                sweepInterval = sweepIntervalSeconds.seconds,
                reliabilityHalfLife = halfLifeSeconds.seconds,
            )
        ),
        store = FakePeerAvailabilityStore(),
    )

    @Test
    fun unknownPeer_isOffline() {
        val registry = registry(FixedEpochProvider(1_000L))
        assertFalse(registry.isOnline(peer("a")))
    }

    @Test
    fun markReachable_marksPeerOnlineAndAddsToOnlineDevices() = runTest {
        val registry = registry(FixedEpochProvider(1_000L))
        registry.markReachable(peer("a"), 1_000L)

        assertTrue(registry.isOnline(peer("a")))
        assertEquals(setOf(peer("a")), registry.onlineDevices.first())
    }

    @Test
    fun isOnline_expiresAfterTwoPingIntervals() = runTest {
        val time = FixedEpochProvider(1_000L)
        val registry = registry(time, pingIntervalSeconds = 60)
        registry.markReachable(peer("a"), 1_000L)
        assertTrue(registry.isOnline(peer("a")))

        // 2 * 60s interval = 120s offline-after threshold.
        time.advanceTo(1_119L)
        assertTrue(registry.isOnline(peer("a")))

        time.advanceTo(1_120L)
        assertFalse(registry.isOnline(peer("a")))
    }

    @Test
    fun onlineEvents_emitsOncePerOfflineToOnlineTransition() = runTest {
        val registry = registry(FixedEpochProvider(1_000L))
        val events = mutableListOf<PeerId>()
        val job = launch { registry.onlineEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        registry.markReachable(peer("a"), 1_000L)
        registry.markReachable(peer("a"), 1_010L)
        testScheduler.runCurrent()

        assertEquals(listOf(peer("a")), events)
        job.cancel()
    }

    @Test
    fun onlineEvents_emitsForDistinctNewPeers() = runTest {
        val registry = registry(FixedEpochProvider(1_000L))
        val events = mutableListOf<PeerId>()
        val job = launch { registry.onlineEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        registry.markReachable(peer("a"), 1_000L)
        registry.markReachable(peer("b"), 1_000L)
        testScheduler.runCurrent()

        assertEquals(setOf(peer("a"), peer("b")), events.toSet())
        job.cancel()
    }

    @Test
    fun sweep_boostsScoreWhenTrafficSeenWithinWindow() = runTest {
        val time = FixedEpochProvider(1_000L)
        val registry = registry(time, sweepIntervalSeconds = 60, halfLifeSeconds = 3_600)
        registry.markReachable(peer("a"), 1_000L)

        registry.sweep()

        val window = 60.0
        val halfLife = 3_600.0
        val expected = 0.5 + (1.0 - 2.0.pow(-window / halfLife)) * (1.0 - 0.5)
        assertEquals(expected, registry.reliabilityScore(peer("a"))!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun sweep_decaysScoreWhenPingSentButNoTraffic() = runTest {
        val time = FixedEpochProvider(1_000L)
        val registry = registry(time, sweepIntervalSeconds = 60, halfLifeSeconds = 3_600)
        registry.markReachable(peer("a"), 1_000L)

        // Silent: last traffic is outside the window, but we pinged the peer now.
        time.advanceTo(2_000L)
        registry.notePingSent(peer("a"))
        registry.sweep()

        val window = 60.0
        val halfLife = 3_600.0
        val expected = 0.5 * 2.0.pow(-window / halfLife)
        assertEquals(expected, registry.reliabilityScore(peer("a"))!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun sweep_leavesScoreAloneWhenNeitherSeenNorPinged() = runTest {
        val time = FixedEpochProvider(1_000L)
        val registry = registry(time, sweepIntervalSeconds = 60, halfLifeSeconds = 3_600)
        registry.markReachable(peer("a"), 1_000L)

        time.advanceTo(2_000L)
        registry.sweep()

        // No ping sent and no recent traffic: we cannot judge the peer, so keep the score untouched.
        assertNull(registry.reliabilityScore(peer("a")))
    }

    @Test
    fun sweep_marksPeerOfflineAfterTwoMissedIntervals() = runTest {
        val time = FixedEpochProvider(1_000L)
        val registry = registry(time, pingIntervalSeconds = 60)
        registry.markReachable(peer("a"), 1_000L)
        assertTrue(registry.onlineDevices.first().contains(peer("a")))

        time.advanceTo(1_121L)
        registry.sweep()

        assertFalse(registry.isOnline(peer("a")))
        assertTrue(registry.onlineDevices.first().isEmpty())
    }

    @Test
    fun sweep_persistsUpdatedScoreToStore() = runTest {
        val time = FixedEpochProvider(1_000L)
        val fake = FakePeerAvailabilityStore()
        val registry = PeerAvailabilityRegistry(
            timeProvider = time,
            routerConfig = MutableStateFlow(RouterConfig().copy(sweepInterval = 60.seconds)),
            store = fake,
        )
        registry.markReachable(peer("a"), 1_000L)
        registry.sweep()

        val stored = fake.reliability[peer("a")]
        assertTrue(stored != null && stored > 0.0 && stored <= 1.0)
    }

    @Test
    fun start_seedsStoredScoreAndOnlineStatusFromStore() = runTest {
        val time = FixedEpochProvider(1_000L)
        val fake = FakePeerAvailabilityStore()
        fake.updateReliability(peer("online"), 0.9, seenAtEpochSeconds = 1_000L)
        fake.updateReliability(peer("stale"), 0.2, seenAtEpochSeconds = 0L)

        val registry = PeerAvailabilityRegistry(
            timeProvider = time,
            routerConfig = MutableStateFlow(RouterConfig().copy(pingInterval = 60.seconds)),
            store = fake,
        )
        registry.start(backgroundScope, peer("self"))

        // Persisted scores are available before any traffic/sweep this session…
        assertEquals(0.9, registry.reliabilityScore(peer("online"))!!, absoluteTolerance = 1e-9)
        assertEquals(0.2, registry.reliabilityScore(peer("stale"))!!, absoluteTolerance = 1e-9)
        // …a recently-seen peer is online and visible immediately after restart…
        assertTrue(registry.isOnline(peer("online")))
        assertTrue(registry.onlineDevices.first().contains(peer("online")))
        // …while a stale peer is not.
        assertFalse(registry.isOnline(peer("stale")))
        assertFalse(registry.onlineDevices.first().contains(peer("stale")))
    }

    /**
     * Wall-clock dynamics are independent of the sweep cadence: splitting the same total observed
     * time into more, smaller windows must give the same score.
     */
    @Test
    fun score_isInvariantToSweepIntervalForSameObservedTime() = runTest {
        val halfLife = 3_600L
        val coarse = runAlwaysAlive(sweepSeconds = 100, steps = 3, halfLifeSeconds = halfLife)
        val fine = runAlwaysAlive(sweepSeconds = 50, steps = 6, halfLifeSeconds = halfLife)

        assertTrue(coarse > 0.5)
        assertEquals(coarse, fine, absoluteTolerance = 1e-9)
    }

    /**
     * With a single time constant the reliability score tracks a peer's long-run availability
     * fraction: a peer alive 75% of the time converges toward ~0.75 (the phone/pc duty-cycle case).
     */
    @Test
    fun score_convergesToDutyCycle() = runTest {
        val sweep = 60L
        // Half-life long relative to the sweep so the score smooths out the per-window ripple and
        // converges to the long-run mean (matching the real 24h-half-life / 15min-sweep config).
        val halfLife = sweep * 30
        val time = FixedEpochProvider(0L)
        val registry = registry(time, sweepIntervalSeconds = sweep, halfLifeSeconds = halfLife)

        var t = 0L
        repeat(40) {
            // 3 alive windows …
            repeat(3) {
                t += sweep
                time.advanceTo(t)
                registry.markReachable(peer("a"), t)
                registry.sweep()
            }
            // … then 1 genuinely-silent window: advance just past the evidence window so the prior
            // traffic falls out of it, but still probe so the silence counts against the peer.
            t += sweep + 1
            time.advanceTo(t)
            registry.notePingSent(peer("a"))
            registry.sweep()
        }

        val score = registry.reliabilityScore(peer("a"))!!
        assertTrue(abs(score - 0.75) < 0.05, "expected ~0.75 for 75% duty, got $score")
    }

    private suspend fun runAlwaysAlive(sweepSeconds: Long, steps: Int, halfLifeSeconds: Long): Double {
        val time = FixedEpochProvider(0L)
        val registry = registry(time, sweepIntervalSeconds = sweepSeconds, halfLifeSeconds = halfLifeSeconds)
        var t = 0L
        repeat(steps) {
            t += sweepSeconds
            time.advanceTo(t)
            registry.markReachable(peer("a"), t)
            registry.sweep()
        }
        return registry.reliabilityScore(peer("a"))!!
    }

    @Test
    fun reliabilityScore_geometricMeanOfMeasuredAndReported() = runTest {
        val time = FixedEpochProvider(1_000L)
        val registry = registry(time, sweepIntervalSeconds = 60, halfLifeSeconds = 3_600)
        registry.markReachable(peer("a"), 1_000L)
        registry.sweep()

        // No report yet → effective == measured.
        val measured = registry.reliabilityScore(peer("a"))!!
        assertEquals(sqrt(measured * measured), measured, absoluteTolerance = 1e-9)

        registry.noteSelfReported(peer("a"), 0.25)
        assertEquals(sqrt(measured * 0.25), registry.reliabilityScore(peer("a"))!!, absoluteTolerance = 1e-9)

        // A peer declining relay duty severs its effective score.
        registry.noteSelfReported(peer("b"), 0.0)
        registry.markReachable(peer("b"), 1_000L)
        registry.sweep()
        assertEquals(0.0, registry.reliabilityScore(peer("b"))!!)

        // Unknown peers are still unknown.
        assertNull(registry.reliabilityScore(peer("c")))
    }

    @Test
    fun start_billsSelfDowntimeGapFromPersistedStamp() = runTest {
        val startedAt = 10_000L
        val time = FixedEpochProvider(startedAt)
        val self = peer("self")
        val fake = FakePeerAvailabilityStore()
        // Last active 1h ago at the default reliability half-life of 1h → score halves from 0.8 to 0.4.
        fake.updateReliability(self, 0.8, seenAtEpochSeconds = startedAt - 3_600L)

        val registry = PeerAvailabilityRegistry(
            timeProvider = time,
            routerConfig = MutableStateFlow(RouterConfig().copy(reliabilityHalfLife = 3_600.seconds)),
            store = fake,
        )
        registry.start(backgroundScope, self)

        assertEquals(0.4, registry.currentSelfScore(), absoluteTolerance = 1e-9)
    }

    @Test
    fun start_freshInstallDoesNotBillDowntime() = runTest {
        val time = FixedEpochProvider(1_000L)
        val registry = registry(time, halfLifeSeconds = 3_600)
        registry.start(backgroundScope, peer("self"))

        assertEquals(0.5, registry.currentSelfScore(), absoluteTolerance = 1e-9)
    }

    @Test
    fun selfSweep_boostsWhenActivityStampedAndPersists() = runTest {
        val time = FixedEpochProvider(1_000L)
        val self = peer("self")
        val fake = FakePeerAvailabilityStore()
        val registry = PeerAvailabilityRegistry(
            timeProvider = time,
            routerConfig = MutableStateFlow(
                RouterConfig().copy(
                    sweepInterval = 60.seconds,
                    reliabilityHalfLife = 3_600.seconds
                )
            ),
            store = fake,
        )
        registry.start(backgroundScope, self) // fresh start: score 0.5, active stamp = now

        time.advanceTo(1_060L)
        registry.notePingSent(peer("other")) // a successful ping proves we are up
        registry.sweep()

        val factor = 2.0.pow(-60.0 / 3_600.0)
        val expected = 0.5 + (1 - factor) * (1 - 0.5)
        assertEquals(expected, registry.currentSelfScore(), absoluteTolerance = 1e-9)
        // Persisted to our own row.
        assertEquals(expected, fake.reliability[self]!!, absoluteTolerance = 1e-9)
    }
}
