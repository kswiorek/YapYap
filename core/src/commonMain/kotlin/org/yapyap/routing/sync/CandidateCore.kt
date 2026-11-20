package org.yapyap.routing.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.retry.RetryLoop
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.OutboxTag
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One due row of a candidate-asking loop, keyed by the loop's own id
 * (a sync id, a file id — the core never interprets it).
 */
internal data class CandidateRow<K>(
    val id: K,
    val attempts: Int,
    val declined: Set<PeerId>,
)

/** Attempt + declined-device state for one asking loop, one row per asked-for id. */
internal interface CandidateAttemptStore<K> {
    /** Starts tracking [id] (idempotent: a known id keeps its state). */
    suspend fun start(id: K, firstAttemptAt: Instant)

    /** Stops tracking [id]; its declined devices go with it. */
    suspend fun drop(id: K)

    suspend fun earliestDueAt(): Instant?
    suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<K>>

    /** Counts an attempt and schedules the next one. */
    suspend fun recordAttempt(id: K, nextAttemptAt: Instant)

    /** Moves the next attempt to [nextAttemptAt] without counting one. */
    suspend fun postpone(id: K, nextAttemptAt: Instant)

    /** Brings [ids] forward to [now]; never pushes one back. */
    suspend fun advanceToNow(ids: Collection<K>, now: Instant)

    suspend fun declinedDevices(id: K): Set<PeerId>
    suspend fun addDeclined(id: K, device: PeerId)
    suspend fun removeDeclined(id: K, devices: Collection<PeerId>)
}

/** One built outbox attempt: the envelope plus the tag its outcome routes by. */
internal data class BuiltAttempt(
    val envelope: BinaryEnvelope,
    val tag: OutboxTag,
)

/**
 * What one asking loop plugs into the shared core: where its rows live, what to
 * ask for, and what an ACK means. The loop (due selection, link-filtered picking,
 * unanswered/declined bookkeeping, backoff) is shared; only the request bytes and
 * the success handoff differ (a sync ACK means "reply on the way", a file ACK means
 * "OFFER pending" — WAITING).
 */
internal interface CandidateStrategy<K> {
    val attempts: CandidateAttemptStore<K>

    /** Accounts that may hold [id]'s target; mapped to devices (link-filtered) by the core. */
    suspend fun candidateAccounts(id: K): List<AccountId>

    /** Ids [account] is a candidate for (link-up fast path). */
    suspend fun idsWithCandidate(account: AccountId): List<K>

    /**
     * The request to send [id] to [device] now, with the tag its outcome routes by.
     * Null once the id is satisfied or gone: the core drops the row and stops asking.
     */
    suspend fun buildAttempt(id: K, device: PeerId, now: Instant): BuiltAttempt?

    /** Bookkeeping after [built] left through the outbox (e.g. persisting a request ref). */
    suspend fun afterEnqueue(id: K, built: BuiltAttempt)

    /** Whether the last attempt for [id] is still out, and if not, whether its device went silent. */
    suspend fun attemptState(id: K): AttemptState

    /** Maps an outbox outcome back to the asked-for id, or null when it is not ours. */
    suspend fun resolveOutcome(outcome: OutboxOutcome): K?

    /** An ACK arrived from [device]: the handoff the plan's callback seam exists for. */
    suspend fun onSuccess(id: K, device: PeerId)

    /**
     * Every known candidate refused (none merely unreachable): true drops [id] as
     * terminal. Sync never terminates this way (durable chase); file requests go
     * UNAVAILABLE.
     */
    suspend fun onExhausted(id: K): Boolean = false
}

/** Where a candidate request's last attempt stands, as its strategy sees it. */
internal sealed interface AttemptState {
    /** Still out: queued for a link, or waiting for its answer. */
    data object Pending : AttemptState

    /** Nothing out: the next candidate may be asked. */
    data object None : AttemptState

    /** The answer [device] promised never came: ask it again only after the other candidates. */
    data class Unanswered(val device: PeerId) : AttemptState
}

/**
 * Shared candidate-asking loop (design §9): one candidate device per attempt, link-filtered
 * like every other send. Only devices with an open link are asked; with none, the candidates'
 * links are nudged and the id waits until one comes up.
 *
 * Each attempt goes through the outbox with a short lifetime, and its outcome comes back
 * through `onAttemptOutcome`:
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
    private val outboxProcessor: OutboxProcessor,
    maxIdlePoll: StateFlow<Duration>,
    /** No candidate has an open link: whoever keeps links open may want to try these now. */
    private val onNoLink: suspend (PeerId) -> Unit = {},
) {
    /**
     * Devices whose attempt went unanswered (expired, or a NACK that isn't a
     * decline), asked again only after the other candidates. In memory: a restart forgets it,
     * which costs at most one more attempt each.
     */
    private val unanswered = HashMap<K, MutableSet<PeerId>>()
    private val unansweredLock = Mutex()

    private val retryLoop = RetryLoop(
        earliestPendingRetryAt = { strategy.attempts.earliestDueAt() },
        clock = ctx.clock,
        processDue = { processDue() },
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

    /** How one attempt's outbox entry ended. */
    suspend fun onAttemptOutcome(outcome: OutboxOutcome) {
        val id = strategy.resolveOutcome(outcome) ?: return
        when (outcome) {
            is OutboxOutcome.Acked -> strategy.onSuccess(id, outcome.target)
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
        strategy.attempts.advanceToNow(listOf(id), ctx.clock.now())
        wake()
    }

    /**
     * [deviceId]'s attempt went unanswered without ruling it out: deprioritize it behind
     * the other candidates (a WAITING timeout lands here too). Visible to strategies that
     * re-arm from a non-outcome path.
     */
    suspend fun noteSilent(id: K, deviceId: PeerId) {
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

    /** A link to [deviceId] came up: ids it can answer are asked now, whatever their backoff. */
    private suspend fun onLinkUp(deviceId: PeerId) {
        val account = ctx.devices.getAccountIdForDevice(deviceId) ?: return
        strategy.attempts.advanceToNow(strategy.idsWithCandidate(account), ctx.clock.now())
        wake()
    }

    private suspend fun processDue() {
        val now = ctx.clock.now()
        val dueRows = strategy.attempts.findDue(now, limit = 10)
        if (dueRows.isNotEmpty()) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESSED,
                message = "Processing due candidate requests",
                fields = mapOf("dueCount" to dueRows.size),
            )
            coroutineScope {
                dueRows.map { row ->
                    async { processDueRow(row, now) }
                }.awaitAll()
            }
        }
        wake()
    }

    private suspend fun processDueRow(row: CandidateRow<K>, now: Instant) {
        val nextAttemptAt = now + computeBackoff(row.attempts).seconds
        when (val state = strategy.attemptState(row.id)) {
            AttemptState.Pending -> {
                // The last attempt is still out (waiting for a link, or for its answer).
                strategy.attempts.postpone(row.id, nextAttemptAt)
                return
            }

            is AttemptState.Unanswered -> noteSilent(row.id, state.device)
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
                strategy.attempts.drop(row.id)
                unansweredLock.withLock { unanswered.remove(row.id) }
                return
            }
            // Ask again as soon as a candidate's link comes up (onLinkUp), not after the backoff.
            candidateDevices.filterNot { it in row.declined }.forEach { runCatching { onNoLink(it) } }
            // No sendable candidate (every device declined, or none linked):
            // the id is inert, not dead. Exponential backoff keeps a dead id at ~one
            // query per interval, capped at an hour.
            strategy.attempts.recordAttempt(row.id, nextAttemptAt)
            return
        }

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
            strategy.attempts.drop(row.id)
            unansweredLock.withLock { unanswered.remove(row.id) }
            return
        }

        try {
            outboxProcessor.enqueueAndWake(
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

    private suspend fun candidateDevices(accounts: List<AccountId>): List<PeerId> =
        ctx.devices.getAllPeerDevicesForAccounts(accounts)
            .filter { it != ctx.localDeviceId }
            .distinct()

    private companion object {
        /** NACK reasons meaning "this device won't serve it", as opposed to "didn't get through". */
        val DECLINES = setOf(PacketNackReason.DECLINED, PacketNackReason.UNSUPPORTED_TYPE)

        fun computeBackoff(attempts: Int): Long {
            // Capped shift: an inert id keeps counting attempts, and a shift past 63 wraps.
            return (30L * (1L shl attempts.coerceIn(0, 7))).coerceAtMost(3600L)
        }
    }
}
