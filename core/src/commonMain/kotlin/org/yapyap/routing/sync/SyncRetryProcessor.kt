package org.yapyap.routing.sync

import kotlinx.coroutines.*
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.persistence.sync.PendingSyncRow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.retry.RetryLoop
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RoutingContext
import org.yapyap.time.EpochSecondsProvider
import kotlin.coroutines.cancellation.CancellationException

internal class SyncRetryProcessor(
    private val ctx: RoutingContext,
    private val pendingSyncs: PendingSyncRepository,
    private val syncHandler: SyncHandler,
    private val peerPolicy: SyncPeerPolicy,
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
    private val time: EpochSecondsProvider,
    maxIdlePollSeconds: Long,
) {
    private val retryLoop = RetryLoop(
        earliestPendingRetryAt = { pendingSyncs.earliestDueAt() },
        time = time,
        processDue = { processDue() },
        maxIdlePollSeconds = maxIdlePollSeconds,
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
            peerAvailabilityRegistry.onlineEvents.collect { deviceId ->
                onPeerOnline(deviceId)
            }
        }
        return retryLoop.runIn(scope)
    }

    fun wake() {
        retryLoop.notifyChanged()
    }

    private suspend fun onPeerOnline(deviceId: PeerId) {
        val now = time.nowEpochSeconds()
        pendingSyncs.accelerateForOnlinePeer(deviceId, now)
        wake()
    }

    private suspend fun processDue() {
        val now = time.nowEpochSeconds()
        val dueRows = pendingSyncs.findDue(now, limit = 10)
        if (dueRows.isNotEmpty()) {
            AppLog.debug(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESSED,
                message = "Processing due sync requests",
                fields = mapOf("dueCount" to dueRows.size),
            )
        }
        if (dueRows.isNotEmpty()) {
            coroutineScope {
                dueRows.map { row ->
                    async { processDueRow(row, now) }
                }.awaitAll()
            }
        }
        wake()
        if (dueRows.isNotEmpty()) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESSED,
                message = "Processed due sync requests",
                fields = mapOf("dueCount" to dueRows.size),
            )
        }
    }

    private suspend fun processDueRow(row: PendingSyncRow, now: Long) {
        val candidateDevices = row.candidateAccounts.flatMap { accountId ->
            ctx.identityResolver.getAllPeerDevicesForAccount(accountId)
        }.filter { it != ctx.localDeviceId }.distinct() //TODO: batch query

        val attempted = pendingSyncs.getAttemptedDevices(row.syncId)  // NACKed only
        val nextDevice = peerPolicy.pickNextDevice(candidateDevices, attempted)

        if (nextDevice == null) {
            pendingSyncs.updateAttemptAt(row.syncId, now + 60) // TODO: config
            return
        }

        val request = SyncRequest.decode(row.requestPayload) as SyncRequest
        try {
            syncHandler.sendSyncRequest(nextDevice, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_PROCESS_FAILED,
                message = "Failed to send sync request",
                fields = mapOf("syncId" to row.syncId, "deviceId" to nextDevice, "error" to e.toString()),
            )
        }

        val nextAttemptAt = now + computeBackoff(row.attempts)
        pendingSyncs.recordAttempt(row.syncId, nextAttemptAt, now)
    }

    private fun computeBackoff(attempts: Int): Long {
        return (30L * (1L shl attempts)).coerceAtMost(3600L)
    }
}