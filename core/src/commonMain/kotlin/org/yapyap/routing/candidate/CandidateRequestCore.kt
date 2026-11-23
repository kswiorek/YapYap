package org.yapyap.routing.candidate

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.outbound.RetryLoop
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Shared candidate-asking loop (design §9): one candidate device per attempt, link-filtered
 * like every other send. Only devices with an open link are asked; with none, the candidates'
 * links are nudged and the id waits until one comes up.
 *
 * Outbox attempts go through the outbox with a short lifetime, call attempts run bounded
 * beside the due loop, and each outcome comes back through `onAttemptOutcome` (outbox) or
 * the call's own [CallOutcome]:
 * - a decline (`DECLINED`, or `UNSUPPORTED_TYPE` from a build that can't serve) rules that
 *   device out for this id;
 * - expiry or any other NACK leaves the device askable but behind the other candidates;
 * either way the next candidate is asked at once if there is one. An ACK leaves the
 * backoff to schedule the next attempt, and hands off to [CandidateStrategy.onSuccess].
 */
internal class CandidateRequestCore<K>(
    private val ctx: RoutingContext,
    private val strategy: CandidateStrategy<K>,
    private val peerPolicy: SyncPeerPolicy,
    private val executor: AttemptExecutor<K>,
    maxIdlePoll: StateFlow<Duration>,
    /** No candidate has an open link: whoever keeps links open may want to try these now. */
    private val linkKeeper: LinkKeeper,
) {
    /**
     * Devices whose attempt went unanswered (expired, or a NACK that isn't a
     * decline), asked again only after the other candidates. In memory: a restart forgets it,
     * which costs at most one more attempt each.
     */
    private val unanswered = HashMap<K, MutableSet<PeerId>>()
    private val unansweredLock = Mutex()

    /** Rows with a running (or slot-waiting) Call: postponed by the due loop until the call ends. */
    private val running = mutableSetOf<K>()
    private val runningLock = Mutex()

    /** Calls holding a slot right now, bounded by [AttemptExecutor.Call.maxConcurrent]. */
    private val activeCalls = MutableStateFlow(0)

    private val retryLoop = RetryLoop(
        earliestPendingRetryAt = { strategy.attempts.earliestDueAt() },
        clock = ctx.clock,
        // Call attempts launch as children of the due loop's own job, so stopping the loop
        // stops them; the due pass itself never waits for them.
        processDue = { processDue(CoroutineScope(currentCoroutineContext())) },
        maxIdlePoll = maxIdlePoll,
        onProcessFailed = { error ->
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESS_FAILED,
                message = "Failed to process candidate requests",
                throwable = error,
            )
        },
    )

    fun runIn(scope: CoroutineScope): Job {
        scope.launch {
            ctx.peerLinks.linkUps.collect { up -> onLinkUp(up.peer) }
        }
        return retryLoop.runIn(scope)
    }

    fun wake() {
        retryLoop.notifyChanged()
    }

    /** Starts asking for [id] (idempotent). */
    suspend fun start(id: K, firstAttemptAt: Instant) {
        strategy.attempts.start(id, firstAttemptAt)
        wake()
    }

    /**
     * [account] asserts it can serve [id] after all. When every candidate device has
     * declined (the id is inert), its devices may be asked again; mid-round rotation
     * is left alone, so one candidate cannot hog the requests while others never get a turn.
     */
    suspend fun reopen(id: K, account: AccountId) {
        val declined = strategy.attempts.declinedDevices(id)
        if (declined.isEmpty()) return
        val candidates = candidateDevices(strategy.candidateAccounts(id))
        if (candidates.any { it !in declined }) return
        strategy.attempts.removeDeclined(id, ctx.devices.getAllPeerDevicesForAccount(account))
        strategy.attempts.advanceToNow(listOf(id), ctx.clock.now())
        wake()
    }

    /** How one attempt's outbox entry ended (Outbox executor only; Call maps its own outcomes). */
    suspend fun onAttemptOutcome(outcome: OutboxOutcome) {
        if (executor !is AttemptExecutor.Outbox<K>) return
        val id = strategy.resolveOutcome(outcome) ?: return
        when (outcome) {
            is OutboxOutcome.Acked -> when (val action = strategy.onSuccess(id, outcome.target)) {
                is SuccessAction.Done -> forget(id)
                is SuccessAction.AwaitUntil -> strategy.attempts.setWaiting(id, outcome.target, action.until)
            }

            is OutboxOutcome.Nacked -> if (outcome.reason in DECLINES) {
                onDeclined(id, outcome.target)
            } else {
                onUnanswered(id, outcome.target)
            }

            is OutboxOutcome.Expired -> onUnanswered(id, outcome.target)
        }
    }

    /** [deviceId] won't serve [id]: never ask it again for this id; ask the next one now. */
    suspend fun onDeclined(id: K, deviceId: PeerId) {
        strategy.attempts.addDeclined(id, deviceId)
        strategy.attempts.clearWaiting(id)
        strategy.attempts.advanceToNow(listOf(id), ctx.clock.now())
        wake()
    }

    /**
     * [deviceId]'s attempt went unanswered without ruling it out: deprioritize it behind
     * the other candidates (a WAITING timeout lands here too).
     */
    private suspend fun noteSilent(id: K, deviceId: PeerId) {
        unansweredLock.withLock {
            unanswered.getOrPut(id) { mutableSetOf() }.add(deviceId)
        }
    }

    /**
     * Asks the next candidate now if there is one that hasn't declined or gone unanswered;
     * otherwise the backoff decides, so a linked device that stays silent isn't asked every
     * attempt timeout forever.
     */
    private suspend fun onUnanswered(id: K, deviceId: PeerId) {
        noteSilent(id, deviceId)
        val silent = unansweredLock.withLock { unanswered[id]?.toSet().orEmpty() }
        val declined = strategy.attempts.declinedDevices(id)
        val untried = candidateDevices(strategy.candidateAccounts(id)).any { it !in declined && it !in silent }
        if (!untried) return
        strategy.attempts.advanceToNow(listOf(id), ctx.clock.now())
        wake()
    }

    /** Drops the core's state for [id] from outside the due loop (e.g. an OFFER arrived). */
    suspend fun forgetExternal(id: K) {
        forget(id)
    }

    /** Drops all of the core's state for [id]: attempts row (WAITING with it) and silence. */
    private suspend fun forget(id: K) {
        strategy.attempts.drop(id)
        unansweredLock.withLock { unanswered.remove(id) }
    }

    /** A link to [deviceId] came up: ids it can answer are asked now, whatever their backoff. */
    private suspend fun onLinkUp(deviceId: PeerId) {
        try {
            val account = ctx.devices.getAccountIdForDevice(deviceId) ?: return
            strategy.attempts.advanceToNow(strategy.idsWithCandidate(account), ctx.clock.now())
            wake()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESS_FAILED,
                message = "Link-up handling failed",
                fields = mapOf("peerId" to deviceId, "error" to e.toString()),
            )
        }
    }

    private suspend fun processDue(loopScope: CoroutineScope) {
        val now = ctx.clock.now()
        val dueRows = strategy.attempts.findDue(now, limit = DUE_PAGE)
        if (dueRows.isNotEmpty()) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESSED,
                message = "Processing due candidate requests",
                fields = mapOf("dueCount" to dueRows.size),
            )
            coroutineScope {
                dueRows.map { row ->
                    async { processDueRow(row, now, loopScope) }
                }.awaitAll()
            }
        }
        // Every processed row was moved forward (attempted, postponed or dropped), so only a
        // full page can leave more due rows behind. Waking unconditionally made the loop spin.
        if (dueRows.size == DUE_PAGE) wake()
    }

    private suspend fun processDueRow(row: CandidateRow<K>, now: Instant, loopScope: CoroutineScope) {
        // Satisfied or gone while waiting: stop asking before choosing a candidate.
        if (!strategy.isWanted(row.id)) {
            forget(row.id)
            return
        }
        val nextAttemptAt = now + computeBackoff(row.attempts).seconds
        // A running Call owns its row until the call ends. Postpone rather than skip: a row
        // left due would make the loop wake at once, over and over, for the whole call.
        if (executor is AttemptExecutor.Call<K> && runningLock.withLock { row.id in running }) {
            strategy.attempts.postpone(row.id, nextAttemptAt)
            return
        }
        // WAITING (stored on the row): still held → sleep until it ends; expired with no
        // drop → the holder goes silent and the next candidate is asked in this same pass.
        row.waiting?.let { waiting ->
            if (now < waiting.until) {
                strategy.attempts.postpone(row.id, waiting.until)
                return
            }
            strategy.attempts.clearWaiting(row.id)
            noteSilent(row.id, waiting.device)
        }

        when (strategy.attemptState(row.id)) {
            AttemptState.Pending -> {
                // The last attempt is still queued (waiting for a link).
                strategy.attempts.postpone(row.id, nextAttemptAt)
                return
            }

            AttemptState.None -> Unit
        }

        val candidateDevices = candidateDevices(strategy.candidateAccounts(row.id))
        val silent = unansweredLock.withLock { unanswered[row.id]?.toSet().orEmpty() }
        val nextDevice = peerPolicy.pickNextDevice(candidateDevices, row.declined, silent)
        if (nextDevice == null) {
            // Every known candidate refused (none merely unreachable): terminal for loops
            // that say so (file requests go UNAVAILABLE); sync never terminates this way.
            val declined = strategy.attempts.declinedDevices(row.id)
            if (candidateDevices.isNotEmpty() && candidateDevices.all { it in declined } &&
                strategy.onExhausted(row.id)
            ) {
                forget(row.id)
                return
            }
            // Ask again as soon as a candidate's link comes up (onLinkUp), not after the backoff.
            candidateDevices.filterNot { it in row.declined }.forEach {
                try {
                    linkKeeper.nudge(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best-effort nudge; the recheck delay is the backstop.
                }
            }
            // No sendable candidate (every device declined, or none linked):
            // the id is inert, not dead. Exponential backoff keeps a dead id at ~one
            // query per interval, capped at an hour.
            strategy.attempts.recordAttempt(row.id, nextAttemptAt)
            return
        }

        when (val exec = executor) {
            is AttemptExecutor.Outbox<K> -> runOutboxAttempt(row, nextDevice, now, nextAttemptAt, exec)
            is AttemptExecutor.Call<K> -> runCallAttempt(row, nextDevice, nextAttemptAt, exec, loopScope)
        }
    }

    private suspend fun runOutboxAttempt(
        row: CandidateRow<K>,
        nextDevice: PeerId,
        now: Instant,
        nextAttemptAt: Instant,
        exec: AttemptExecutor.Outbox<K>,
    ) {
        val built = try {
            strategy.buildAttempt(row.id, nextDevice, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESS_FAILED,
                message = "Failed to queue candidate request",
                fields = mapOf("id" to row.id, "deviceId" to nextDevice, "error" to e.toString()),
            )
            strategy.attempts.recordAttempt(row.id, nextAttemptAt)
            return
        } ?: run {
            // Satisfied or gone while waiting: stop asking.
            forget(row.id)
            return
        }

        try {
            exec.outbox.enqueueAndWake(
                built.envelope,
                nextRetryAt = now,
                tag = built.tag,
            )
            strategy.afterEnqueue(row.id, built)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESS_FAILED,
                message = "Failed to queue candidate request",
                fields = mapOf("id" to row.id, "deviceId" to nextDevice, "error" to e.toString()),
            )
        }
        strategy.attempts.recordAttempt(row.id, nextAttemptAt)
    }

    private suspend fun runCallAttempt(
        row: CandidateRow<K>,
        nextDevice: PeerId,
        nextAttemptAt: Instant,
        exec: AttemptExecutor.Call<K>,
        loopScope: CoroutineScope,
    ) {
        // The call owns its row while running; record first so a crash mid-call never re-asks at once.
        strategy.attempts.recordAttempt(row.id, nextAttemptAt)
        runningLock.withLock { running.add(row.id) }
        // The due loop never blocks on a call: each attempt runs as a child job, bounded by
        // the call slots, and the row is postponed until that job ends.
        loopScope.launch {
            try {
                val outcome = withCallSlot(exec.maxConcurrent) { runCall(exec, row, nextDevice) }
                // A drop meanwhile wins: never act on a stale outcome.
                if (!strategy.isWanted(row.id)) {
                    forget(row.id)
                    return@launch
                }
                handleCallOutcome(row, nextDevice, outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(
                    component = LogComponent.ROUTER,
                    event = LogEvent.SYNC_PROCESS_FAILED,
                    message = "Call attempt failed",
                    fields = mapOf("id" to row.id, "deviceId" to nextDevice, "error" to e.toString()),
                )
            } finally {
                runningLock.withLock { running.remove(row.id) }
            }
        }
    }

    /** Runs [block] once fewer than [limit] calls hold a slot; the limit is read live. */
    private suspend fun <T> withCallSlot(limit: Flow<Int>, block: suspend () -> T): T {
        while (true) {
            val max = combine(activeCalls, limit) { active, max -> if (active < max) max else null }
                .filterNotNull()
                .first()
            val current = activeCalls.value
            if (current < max && activeCalls.compareAndSet(current, current + 1)) break
        }
        try {
            return block()
        } finally {
            activeCalls.update { it - 1 }
        }
    }

    /** One call; an unexpected failure counts as an unanswered attempt (logged here, once). */
    private suspend fun runCall(
        exec: AttemptExecutor.Call<K>,
        row: CandidateRow<K>,
        nextDevice: PeerId,
    ): CallOutcome {
        return try {
            exec.run(row.id, nextDevice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESS_FAILED,
                message = "Call attempt failed",
                fields = mapOf("id" to row.id, "deviceId" to nextDevice, "error" to e.toString()),
            )
            CallOutcome.Unanswered
        }
    }

    private suspend fun handleCallOutcome(row: CandidateRow<K>, nextDevice: PeerId, outcome: CallOutcome) {
        when (outcome) {
            is CallOutcome.Satisfied -> forget(row.id)
            is CallOutcome.Declined -> onDeclined(row.id, nextDevice)
            is CallOutcome.Unanswered -> onUnanswered(row.id, nextDevice)
            // Unreachable directly: like silence, it moves on to a device not yet tried instead
            // of picking the same first-in-order device every attempt (backoff already recorded).
            is CallOutcome.NoDirectConnection -> onUnanswered(row.id, nextDevice)
        }
    }

    private suspend fun candidateDevices(accounts: List<AccountId>): List<PeerId> =
        ctx.devices.getAllPeerDevicesForAccounts(accounts)
            .filter { it != ctx.localDeviceId }
            .distinct()

    private companion object {
        /** Due rows per pass; a full page means more may be due right away. */
        const val DUE_PAGE = 10

        /** NACK reasons meaning "this device won't serve it", as opposed to "didn't get through". */
        val DECLINES = setOf(PacketNackReason.DECLINED, PacketNackReason.UNSUPPORTED_TYPE)

        fun computeBackoff(attempts: Int): Long {
            // Capped shift: an inert id keeps counting attempts, and a shift past 63 wraps.
            return (30L * (1L shl attempts.coerceIn(0, 7))).coerceAtMost(3600L)
        }
    }
}
