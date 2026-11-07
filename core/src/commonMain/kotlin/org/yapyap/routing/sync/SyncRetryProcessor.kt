package org.yapyap.routing.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.packet.OutboxTag
import org.yapyap.persistence.sync.SyncAttemptRow
import org.yapyap.persistence.sync.SyncAttemptStore
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.retry.RetryLoop
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Asks candidate devices for each pending sync, one device per attempt. What to ask for and
 * which accounts may hold it come from [syncs]; this decides which device and when.
 *
 * Only devices with an open link are asked; with none, the candidates' links are nudged and
 * the sync waits until one comes up ([PeerLinks.linkUps][org.yapyap.routing.link.PeerLinks.linkUps]
 * makes it due at once).
 *
 * Each attempt is one sync request handed to the outbox (tag [OutboxTag.Kind.SYNC]) with a
 * short lifetime (`syncAttemptTimeout`), and its outcome comes back through [onAttemptOutcome]:
 * - a decline ([PacketNackReason.DECLINED], or [PacketNackReason.UNSUPPORTED_TYPE] from a build
 *   that can't serve) rules that device out for this sync;
 * - expiry or any other NACK leaves the device askable but behind other candidates;
 * either way the next candidate is asked at once if there is one. An ACK leaves the backoff
 * to schedule the next attempt, since the answer is already on its way.
 */
internal class SyncRetryProcessor(
    private val ctx: RoutingContext,
    private val attempts: SyncAttemptStore,
    private val syncs: SyncPayloadProvider,
    private val systemSender: SystemSender,
    private val outboxProcessor: OutboxProcessor,
    private val peerPolicy: SyncPeerPolicy,
    maxIdlePoll: StateFlow<Duration>,
    /** No candidate has an open link: whoever keeps links open may want to try these now. */
    private val onNoLink: suspend (PeerId) -> Unit = {},
) {
    /**
     * Devices whose attempt for a sync went unanswered (expired, or a NACK that isn't a
     * decline), asked again only after the other candidates. In memory: a restart forgets it,
     * which costs at most one more attempt each.
     */
    private val unanswered = HashMap<Uuid, MutableSet<PeerId>>()
    private val unansweredLock = Mutex()

    private val retryLoop = RetryLoop(
        earliestPendingRetryAt = { attempts.earliestDueAt() },
        clock = ctx.clock,
        processDue = { processDue() },
        maxIdlePoll = maxIdlePoll,
        onProcessFailed = { error ->
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESS_FAILED,
                message = "Sync retry processing failed",
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

    /** Starts asking for [syncId] (idempotent). */
    suspend fun start(syncId: Uuid, firstAttemptAt: Instant) {
        attempts.start(syncId, firstAttemptAt)
        wake()
    }

    /**
     * [account] asserts it can serve [syncId] after all. When every candidate device has
     * declined (the sync is inert), its devices may be asked again; mid-round rotation is
     * left alone, so one candidate cannot hog the requests while others never get a turn.
     */
    suspend fun reopen(syncId: Uuid, account: AccountId) {
        val declined = attempts.declinedDevices(syncId)
        if (declined.isEmpty()) return
        val candidates = candidateDevices(syncs.syncCandidates(syncId))
        if (candidates.any { it !in declined }) return
        attempts.removeDeclined(syncId, ctx.identityResolver.getAllPeerDevicesForAccount(account))
        attempts.advanceToNow(listOf(syncId), ctx.clock.now())
        wake()
    }

    /** How one attempt's outbox entry ended. */
    suspend fun onAttemptOutcome(outcome: OutboxOutcome) {
        val syncId = outcome.tag.id
        when (outcome) {
            is OutboxOutcome.Acked -> Unit
            is OutboxOutcome.Nacked -> if (outcome.reason in DECLINES) {
                onDeclined(syncId, outcome.target)
            } else {
                onUnanswered(syncId, outcome.target)
            }

            is OutboxOutcome.Expired -> onUnanswered(syncId, outcome.target)
        }
    }

    /**
     * Asks the next candidate now if there is one that hasn't declined or gone unanswered;
     * otherwise the backoff decides, so a linked device that stays silent isn't asked every
     * `syncAttemptTimeout` forever.
     */
    private suspend fun onUnanswered(syncId: Uuid, deviceId: PeerId) {
        val silent = unansweredLock.withLock {
            unanswered.getOrPut(syncId) { mutableSetOf() }.apply { add(deviceId) }.toSet()
        }
        val declined = attempts.declinedDevices(syncId)
        val untried = candidateDevices(syncs.syncCandidates(syncId)).any { it !in declined && it !in silent }
        if (!untried) return
        attempts.advanceToNow(listOf(syncId), ctx.clock.now())
        wake()
    }

    /** [deviceId] won't serve [syncId]: never ask it again for this sync; ask the next one now. */
    suspend fun onDeclined(syncId: Uuid, deviceId: PeerId) {
        attempts.addDeclined(syncId, deviceId)
        attempts.advanceToNow(listOf(syncId), ctx.clock.now())
        wake()
    }

    /** A link to [deviceId] came up: syncs it can answer are asked now, whatever their backoff. */
    private suspend fun onLinkUp(deviceId: PeerId) {
        val account = ctx.identityResolver.getAccountIdForDevice(deviceId) ?: return
        attempts.advanceToNow(syncs.syncsWithCandidate(account), ctx.clock.now())
        wake()
    }

    private suspend fun processDue() {
        val now = ctx.clock.now()
        val dueRows = attempts.findDue(now, limit = 10)
        if (dueRows.isNotEmpty()) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESSED,
                message = "Processing due sync requests",
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

    private suspend fun processDueRow(row: SyncAttemptRow, now: Instant) {
        val tag = OutboxTag(OutboxTag.Kind.SYNC, row.syncId)
        val nextAttemptAt = now + computeBackoff(row.attempts).seconds
        if (outboxProcessor.hasQueued(tag)) {
            // The last attempt is still in the outbox (waiting for a link, or for its answer).
            attempts.postpone(row.syncId, nextAttemptAt)
            return
        }

        val request = syncs.syncRequest(row.syncId)
        if (request == null) {
            // Satisfied or gone while waiting: stop asking.
            attempts.drop(row.syncId)
            unansweredLock.withLock { unanswered.remove(row.syncId) }
            return
        }

        val candidateDevices = candidateDevices(syncs.syncCandidates(row.syncId))
        val silent = unansweredLock.withLock { unanswered[row.syncId]?.toSet().orEmpty() }
        val nextDevice = peerPolicy.pickNextDevice(candidateDevices, row.declinedDevices, silent)
        if (nextDevice == null) {
            // Ask again as soon as a candidate's link comes up (onLinkUp), not after the backoff.
            candidateDevices.filterNot { it in row.declinedDevices }.forEach { runCatching { onNoLink(it) } }
            // No sendable candidate (every device declined, or none linked):
            // the sync is inert, not dead. It ends only when its pending sync does
            // (the request above comes back null), never by age: an all-declined
            // sync is the durable chase for a genuinely lost message, cheaply
            // retried until someone holds it (docs/room events.md §6).
            // Exponential backoff keeps a dead sync at ~one query per interval,
            // capped at an hour.
            attempts.recordAttempt(row.syncId, nextAttemptAt)
            return
        }

        try {
            outboxProcessor.enqueueAndWake(
                systemSender.buildForOutbox(nextDevice, request, ctx.routerConfig.value.syncAttemptTimeout),
                nextRetryAt = now,
                tag = tag,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESS_FAILED,
                message = "Failed to queue sync request",
                fields = mapOf("syncId" to row.syncId, "deviceId" to nextDevice, "error" to e.toString()),
            )
        }
        attempts.recordAttempt(row.syncId, nextAttemptAt)
    }

    private suspend fun candidateDevices(accounts: List<AccountId>): List<PeerId> =
        ctx.identityResolver.getAllPeerDevicesForAccounts(accounts)
            .filter { it != ctx.localDeviceId }
            .distinct()

    private companion object {
        /** NACK reasons meaning "this device won't serve it", as opposed to "didn't get through". */
        val DECLINES = setOf(PacketNackReason.DECLINED, PacketNackReason.UNSUPPORTED_TYPE)
    }

    private fun computeBackoff(attempts: Int): Long {
        // Capped shift: an inert sync keeps counting attempts, and a shift past 63 wraps.
        return (30L * (1L shl attempts.coerceIn(0, 7))).coerceAtMost(3600L)
    }
}
