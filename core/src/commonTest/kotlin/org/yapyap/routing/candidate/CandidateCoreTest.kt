package org.yapyap.routing.candidate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.sync.FixedSyncPeerPolicy
import org.yapyap.sync.buildSyncRoutingStack
import org.yapyap.sync.testDeviceIdentity
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Shared candidate core with a Call executor: concurrent fetches run bounded beside the
 * due loop, a running row is not processed again, and WAITING expiry hands the next
 * candidate the turn with the first tried last.
 */
class CandidateCoreTest {

    private val localDevice = PeerId("candidate-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val holderDevice = PeerId("candidate-holder-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val holderAccount = AccountId("candidate-holder-account")
    private val now = epochSeconds(10_000L)

    private class InMemoryAttempts : CandidateAttemptStore<Uuid> {
        private data class Row(
            var attempts: Int = 0,
            var nextAttemptAt: Instant = epochSeconds(0L),
            var waitingDevice: PeerId? = null,
            var waitingUntil: Instant? = null,
            val declined: MutableSet<PeerId> = mutableSetOf(),
        )

        private val rows = mutableMapOf<Uuid, Row>()
        var findDueCalls = 0

        override suspend fun start(id: Uuid, firstAttemptAt: Instant) {
            rows.getOrPut(id) { Row(nextAttemptAt = firstAttemptAt) }
        }

        override suspend fun drop(id: Uuid) {
            rows.remove(id)
        }

        override suspend fun earliestDueAt(): Instant? = rows.values.minOfOrNull { it.nextAttemptAt }

        override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<Uuid>> =
            also { findDueCalls++ }.rows.entries.filter { it.value.nextAttemptAt <= now }.take(limit)
                .map { (id, row) -> CandidateRow(id, row.attempts, row.declined.toSet()) }

        override suspend fun recordAttempt(id: Uuid, nextAttemptAt: Instant) {
            rows[id]?.let { it.attempts += 1; it.nextAttemptAt = nextAttemptAt }
        }

        override suspend fun postpone(id: Uuid, nextAttemptAt: Instant) {
            rows[id]?.nextAttemptAt = nextAttemptAt
        }

        override suspend fun advanceToNow(ids: Collection<Uuid>, now: Instant) {
            ids.forEach { id -> rows[id]?.let { if (it.nextAttemptAt > now) it.nextAttemptAt = now } }
        }

        override suspend fun declinedDevices(id: Uuid): Set<PeerId> = rows[id]?.declined?.toSet().orEmpty()

        override suspend fun addDeclined(id: Uuid, device: PeerId) {
            rows[id]?.declined?.add(device)
        }

        override suspend fun removeDeclined(id: Uuid, devices: Collection<PeerId>) {
            rows[id]?.declined?.removeAll(devices.toSet())
        }

        override suspend fun setWaiting(id: Uuid, device: PeerId, until: Instant) {
            rows[id]?.let { it.waitingDevice = device; it.waitingUntil = until }
        }

        override suspend fun clearWaiting(id: Uuid) {
            rows[id]?.let { it.waitingDevice = null; it.waitingUntil = null }
        }

        fun contains(id: Uuid): Boolean = rows.containsKey(id)
    }

    private class FakeStrategy(
        val holder: AccountId,
        override val attempts: InMemoryAttempts,
        var wanted: Set<Uuid> = emptySet(),
    ) : CandidateStrategy<Uuid> {
        override suspend fun candidateAccounts(id: Uuid): List<AccountId> = listOf(holder)
        override suspend fun idsWithCandidate(account: AccountId): List<Uuid> = emptyList()
        override suspend fun isWanted(id: Uuid): Boolean = id in wanted
        override suspend fun buildAttempt(id: Uuid, device: PeerId, now: Instant): BuiltAttempt? = null
        override suspend fun afterEnqueue(id: Uuid, built: BuiltAttempt) = Unit
        override suspend fun attemptState(id: Uuid): AttemptState = AttemptState.None
        override suspend fun resolveOutcome(outcome: org.yapyap.routing.router.OutboxOutcome): Uuid? = null
        override suspend fun onSuccess(id: Uuid, device: PeerId): SuccessAction = SuccessAction.Done
    }

    private data class CoreHarness(
        val scope: CoroutineScope,
        val core: CandidateRequestCore<Uuid>,
        val attempts: InMemoryAttempts,
        val strategy: FakeStrategy,
        val clock: FakeClock,
        val stack: org.yapyap.sync.SyncRoutingStack,
    ) {
        private var job: Job? = null

        fun start() {
            job = core.runIn(scope)
        }

        fun stop() {
            job?.cancel()
            scope.cancel()
        }
    }

    private fun coreHarness(
        maxConcurrent: Int = 2,
        run: suspend (id: Uuid, device: PeerId) -> CallOutcome,
    ): CoreHarness {
        val clock = FakeClock(now)
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(holderAccount to listOf(holderDevice)),
            torByPeer = mutableMapOf(),
            clock = clock,
        )
        val attempts = InMemoryAttempts()
        val strategy = FakeStrategy(holderAccount, attempts)
        val core = CandidateRequestCore(
            ctx = stack.ctx,
            strategy = strategy,
            peerPolicy = FixedSyncPeerPolicy(nextDevice = holderDevice),
            executor = AttemptExecutor.Call(MutableStateFlow(maxConcurrent), run),
            maxIdlePoll = MutableStateFlow(50.milliseconds),
        )
        val scope = CoroutineScope(SupervisorJob())
        return CoreHarness(scope, core, attempts, strategy, clock, stack)
    }

    @Test
    fun concurrentCalls_bothStartBeforeEitherCompletes() = runBlocking {
        val entered = Channel<Uuid>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val h = coreHarness { id, _ ->
            entered.send(id)
            release.await()
            CallOutcome.Satisfied
        }
        try {
            val first = Uuid.random()
            val second = Uuid.random()
            h.strategy.wanted = setOf(first, second)
            h.core.start(first, now)
            h.core.start(second, now)
            h.start()
            withTimeout(10.seconds) {
                val started = mutableSetOf<Uuid>()
                repeat(2) { started += entered.receive() }
                assertEquals(setOf(first, second), started, "both fetches start before either completes")
            }
            release.complete(Unit)
            withTimeout(10.seconds) {
                while (h.attempts.contains(first) || h.attempts.contains(second)) delay(20)
            }
        } finally {
            h.stop()
        }
    }

    @Test
    fun runningCall_rowIsNotProcessedAgain() = runBlocking {
        var runs = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = coreHarness { _, _ ->
            runs += 1
            entered.complete(Unit)
            release.await()
            CallOutcome.Satisfied
        }
        try {
            val id = Uuid.random()
            h.strategy.wanted = setOf(id)
            h.core.start(id, now)
            h.start()
            withTimeout(10.seconds) { entered.await() }
            // Force more due passes past the backoff while the call is still running.
            repeat(3) {
                h.clock.advanceBy(61.seconds)
                h.core.wake()
                delay(200)
            }
            assertEquals(1, runs, "a running row is skipped by the due loop")
            release.complete(Unit)
            withTimeout(10.seconds) {
                while (h.attempts.contains(id)) delay(20)
            }
        } finally {
            h.stop()
        }
    }

    @Test
    fun idleLoop_sleepsBetweenPasses() = runBlocking {
        val h = coreHarness { _, _ -> CallOutcome.Satisfied }
        try {
            h.start()
            delay(500)
            // maxIdlePoll is 50 ms: about ten passes. A loop that re-wakes itself runs thousands.
            assertTrue(h.attempts.findDueCalls < 50, "idle loop ran ${h.attempts.findDueCalls} passes")
        } finally {
            h.stop()
        }
    }
}
