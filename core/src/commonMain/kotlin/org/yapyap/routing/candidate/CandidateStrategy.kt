package org.yapyap.routing.candidate

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.store.OutboxTag
import kotlin.time.Instant

/**
 * One due row of a candidate-asking loop, keyed by the loop's own id
 * (a sync id, a file id — the core never interprets it).
 */
internal data class CandidateRow<K>(
    val id: K,
    val attempts: Int,
    val declined: Set<PeerId>,
    /** The stored WAITING state, or null (loops without WAITING never set one). */
    val waiting: CandidateWaiting? = null,
)

/** WAITING for [device]'s answer until [until] (a file REQUEST ACK waits for the OFFER). */
internal data class CandidateWaiting(val device: PeerId, val until: Instant)

/**
 * The asking loop's row-state port — not a plain CRUD table store. It persists exactly what the
 * loop needs to resume: the next-attempt time, the attempt count, the declined children, and the
 * WAITING state, written through the same port as the scheduling calls. A store that owns the
 * candidate tables composes the parent row and its declined children into a view of this
 * interface; consumers never assemble the join themselves.
 *
 * TODO: reconsider the name; it should say "the asking loop's row state", not "database rows".
 */
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

    /**
     * Enters WAITING for the OFFER from [device] until [until]. Persisted, and returned in
     * [CandidateRow.waiting]: the stored row is the only record of WAITING, across restarts too.
     */
    suspend fun setWaiting(id: K, device: PeerId, until: Instant)

    /** Leaves WAITING (satisfied, timed out, or declined). */
    suspend fun clearWaiting(id: K)
}

/** One built outbox attempt: the envelope plus the tag its outcome routes by. */
internal data class BuiltAttempt(
    val envelope: BinaryEnvelope,
    val tag: OutboxTag,
)

/** How one attempt leaves: queued through the outbox, or run as a suspending call. */
internal sealed interface AttemptExecutor<K> {
    /** Queue [BuiltAttempt] through the outbox; the outcome comes back via onAttemptOutcome. */
    class Outbox<K>(val outbox: OutboxProcessor) : AttemptExecutor<K>

    /**
     * Run the attempt as a call (a large fetch). At most [maxConcurrent] run at once (read live,
     * so a settings change applies to the next call); the core records the attempt
     * (recordAttempt) before calling, so a crash mid-call never re-asks at once.
     */
    class Call<K>(val maxConcurrent: Flow<Int>, val run: suspend (id: K, device: PeerId) -> CallOutcome) :
        AttemptExecutor<K>
}

internal sealed interface CallOutcome {
    data object Satisfied : CallOutcome          // drop the row
    data object Declined : CallOutcome           // addDeclined(device), ask the next one
    data object Unanswered : CallOutcome         // noteSilent(device), backoff
    data object NoDirectConnection : CallOutcome // noteSilent(device), next device or backoff (design §6.6)
}

internal sealed interface SuccessAction {
    data object Done : SuccessAction                       // drop the row (sync)
    data class AwaitUntil(val until: Instant) : SuccessAction // file REQUEST ACK: WAITING for the OFFER
}

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

    /** False once [id] is satisfied or gone: the core drops the row and stops asking. */
    suspend fun isWanted(id: K): Boolean

    /**
     * The request to send [id] to [device] now, with the tag its outcome routes by.
     * Only called for [AttemptExecutor.Outbox]; the Call executor runs its own function instead.
     */
    suspend fun buildAttempt(id: K, device: PeerId, now: Instant): BuiltAttempt?

    /** Bookkeeping after [built] left through the outbox (e.g. persisting a request ref). */
    suspend fun afterEnqueue(id: K, built: BuiltAttempt)

    /**
     * Whether the last attempt for [id] is still queued in the outbox. WAITING is not this
     * method's concern: the core reads it from [CandidateRow.waiting].
     */
    suspend fun attemptState(id: K): AttemptState

    /** Maps an outbox outcome back to the asked-for id, or null when it is not ours. */
    suspend fun resolveOutcome(outcome: OutboxOutcome): K?

    /** An ACK arrived from [device]: the handoff the plan's callback seam exists for. */
    suspend fun onSuccess(id: K, device: PeerId): SuccessAction

    /**
     * Every known candidate refused (none merely unreachable): true drops [id] as
     * terminal. Sync never terminates this way (durable chase); file requests go
     * UNAVAILABLE.
     */
    suspend fun onExhausted(id: K): Boolean = false
}

/** Where a candidate request's last attempt stands, as its strategy sees it. */
internal sealed interface AttemptState {
    /** Still queued in the outbox (waiting for a link). */
    data object Pending : AttemptState

    /** Nothing out: the next candidate may be asked. */
    data object None : AttemptState
}
