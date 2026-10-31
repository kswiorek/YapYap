package org.yapyap.routing.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.PeerId
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.session.SessionStatus
import org.yapyap.transport.session.SessionTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LinkKeeperTest {

    private val alice = PeerId("a".repeat(64))
    private val bob = PeerId("b".repeat(64))
    private val carol = PeerId("c".repeat(64))

    private data class State(override val phase: SessionPhase, override val reason: String? = null) : SessionStatus

    /** Opens take [openTime]; peers in [reachable] connect, the rest fail. */
    private class FakeSessions(private val scope: CoroutineScope) : SessionTransport<PeerId> {
        val states = MutableStateFlow<Map<PeerId, SessionStatus>>(emptyMap())
        override val sessionStates = states
        val reachable = mutableSetOf<PeerId>()
        val opens = mutableListOf<PeerId>()
        var openTime: Duration = 1.seconds
        var inFlight = 0
        var maxInFlight = 0

        override fun hasSession(key: PeerId) = states.value[key]?.phase == SessionPhase.CONNECTED

        override suspend fun openSession(key: PeerId) {
            opens += key
            set(key, SessionPhase.NEGOTIATING)
            scope.launch {
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                delay(openTime)
                inFlight--
                set(key, if (key in reachable) SessionPhase.CONNECTED else SessionPhase.FAILED)
            }
        }

        override suspend fun closeSession(key: PeerId) = set(key, SessionPhase.CLOSED)

        fun set(key: PeerId, phase: SessionPhase) {
            states.value += key to State(phase)
        }
    }

    private class Harness(
        val sessions: FakeSessions,
        val keeper: LinkKeeper,
        val ready: MutableStateFlow<Boolean>,
        val targets: MutableList<PeerId>,
        val opened: MutableList<PeerId>,
        val failed: MutableList<PeerId>,
    )

    private val config = LinkKeeperConfig(
        retryDelay = 10.seconds,
        maxRetryDelay = 80.seconds,
        maxConcurrentOpens = 2,
        openTimeout = 30.seconds,
        recheckInterval = 60.seconds,
    )

    private fun TestScope.harness(
        targets: List<PeerId>,
        reachable: Set<PeerId> = targets.toSet(),
        ready: Boolean = true,
        keeperConfig: LinkKeeperConfig = config,
    ): Harness {
        val sessions = FakeSessions(backgroundScope).also { it.reachable += reachable }
        val readyFlow = MutableStateFlow(ready)
        val targetList = targets.toMutableList()
        val opened = mutableListOf<PeerId>()
        val failed = mutableListOf<PeerId>()
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        }
        val keeper = LinkKeeper(
            name = "test",
            sessions = sessions,
            targets = { targetList.toList() },
            ready = readyFlow,
            config = MutableStateFlow(keeperConfig),
            clock = clock,
            onOpened = { opened += it },
            onFailed = { peer, _ -> failed += peer; null },
            random = Random(42),
        )
        keeper.runIn(backgroundScope)
        runCurrent()
        return Harness(sessions, keeper, readyFlow, targetList, opened, failed)
    }

    @Test
    fun opensALinkToEveryTarget() = runTest {
        val h = harness(listOf(alice, bob, carol))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(setOf(alice, bob, carol), h.sessions.opens.toSet())
        assertEquals(setOf(alice, bob, carol), h.opened.toSet())
        assertTrue(listOf(alice, bob, carol).all { h.sessions.hasSession(it) })
    }

    @Test
    fun opensAtMostMaxConcurrentAtOnce() = runTest {
        val h = harness(listOf(alice, bob, carol))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(2, h.sessions.maxInFlight)
        assertEquals(3, h.opened.size, "the third opens once a slot frees")
    }

    @Test
    fun failedOpen_backsOffExponentially() = runTest {
        val h = harness(listOf(bob), reachable = emptySet())
        // Open #1 at 0 s fails at 1 s; the next is due 7.5–12.5 s later (10 s ± 25%).
        advanceTimeBy(8.seconds)
        runCurrent()
        assertEquals(1, h.sessions.opens.size, "no retry inside the first backoff")
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(2, h.sessions.opens.size, "retried after the first backoff")
        // Second failure: 20 s ± 25%, so nothing within the next 14 s.
        advanceTimeBy(14.seconds)
        runCurrent()
        assertEquals(2, h.sessions.opens.size, "the backoff doubled")
        assertEquals(listOf(bob, bob), h.failed)
    }

    @Test
    fun noOpensWhileNotReady_andBecomingReadyRetriesAtOnce() = runTest {
        val h = harness(listOf(alice), ready = false)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(0, h.sessions.opens.size)

        h.ready.value = true
        runCurrent()
        assertEquals(1, h.sessions.opens.size)
    }

    @Test
    fun droppedLink_isReopenedRightAway() = runTest {
        val h = harness(listOf(alice))
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(h.sessions.hasSession(alice))

        h.sessions.set(alice, SessionPhase.CLOSED)
        runCurrent()
        assertEquals(2, h.sessions.opens.size, "re-opened without waiting for the recheck interval")
    }

    @Test
    fun nudgeWithReset_skipsTheBackoff() = runTest {
        val h = harness(listOf(bob), reachable = emptySet())
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, h.sessions.opens.size)

        h.sessions.reachable += bob
        h.keeper.nudge(bob, resetBackoff = true)
        runCurrent()
        assertEquals(2, h.sessions.opens.size, "fresh evidence the peer is up: try now")
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(h.sessions.hasSession(bob))
    }

    @Test
    fun linkThatCameUpOnItsOwn_isNotReportedAsOpened() = runTest {
        val h = harness(listOf(alice), ready = false)
        h.sessions.set(alice, SessionPhase.CONNECTED)
        h.ready.value = true
        runCurrent()
        assertEquals(0, h.sessions.opens.size)
        assertTrue(h.opened.isEmpty(), "onOpened is for opens the keeper made")
    }

    @Test
    fun newTarget_isPickedUpOnTheNextPass() = runTest {
        val h = harness(listOf(alice))
        advanceTimeBy(2.seconds)
        runCurrent()
        h.targets += carol
        h.keeper.nudgeAll(resetBackoff = false)
        runCurrent()
        assertTrue(carol in h.sessions.opens)
    }
}
