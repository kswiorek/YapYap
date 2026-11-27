package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileReceiveEnd
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * File request trigger (design §9): decides whether this device wants a file, keeps the user-facing
 * trigger state (`pending_file_requests`), and hands the trigger's origin to routing through
 * [fileCarrier]. When the ask actually runs is routing's (`file_pull_attempts`).
 *
 * The gap trigger counts time while this device runs (design §8): it tracks the last P advance
 * per file in memory on a monotonic clock started at [start], not against `updatedAt`. A long
 * sleep never fires the trigger at once.
 */
internal class DefaultFileRequestCoordinator(
    private val pending: PendingFileRequestStore,
    private val receives: FileReceiveReader,
    private val descriptors: FileDescriptorReader,
    private val control: FileReceiveControl,
    private val messages: MessageRepository,
    private val fileCarrier: FileCarrier,
    private val pipeline: InboundMessagePipeline,
    private val receiveEvents: Flow<FileReceiveEvent>,
    private val config: StateFlow<OrchestratorConfig>,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : FileRequestControl {
    private val jobs = mutableListOf<Job>()
    private val lastProgress = mutableMapOf<FileId, TimeMark>()
    private val lastContiguous = mutableMapOf<FileId, Long>()
    private val progressGuard = Mutex()

    fun start(scope: CoroutineScope) {
        check(jobs.isEmpty()) { "FileRequestCoordinator already started" }
        jobs += scope.launch {
            pipeline.ingestResults.collect { result ->
                try {
                    onIngest(result.payload)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "File request trigger from ingest failed",
                        throwable = e,
                    )
                }
            }
        }
        jobs += scope.launch {
            fileCarrier.fileEvents.collect { event ->
                try {
                    onFileEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "File request event failed",
                        throwable = e,
                    )
                }
            }
        }
        jobs += scope.launch {
            receiveEvents.collect { event ->
                when (event) {
                    is FileReceiveEvent.Progress -> {
                        // Reset the gap mark only when P advances: suffix chunks staging behind a
                        // stuck gap must not suppress STALLED.
                        val advanced = progressGuardLocked {
                            val prev = lastContiguous[event.fileId]
                            (prev == null || event.contiguousBytes > prev).also { advanced ->
                                if (advanced) {
                                    lastProgress[event.fileId] = timeSource.markNow()
                                    lastContiguous[event.fileId] = event.contiguousBytes
                                }
                            }
                        }
                        // A progressing transfer covers the grace period: drop a pending
                        // REQUEST while bytes arrive (design §9 trigger condition).
                        if (advanced) try {
                            val row = receives.find(event.fileId)
                            if (row != null && row.status == FileReceiveStatus.IN_FLIGHT) {
                                pending.drop(event.fileId)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.error(
                                component = LogComponent.FILE,
                                event = LogEvent.FILE_TASK_FAILED,
                                message = "Dropping covered pending request failed",
                                throwable = e,
                                fields = mapOf("fileId" to event.fileId),
                            )
                        }
                    }

                    is FileReceiveEvent.Ready -> {
                        progressGuardLocked {
                            lastProgress.remove(event.fileId)
                            lastContiguous.remove(event.fileId)
                        }
                        try {
                            pending.drop(event.fileId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.error(
                                component = LogComponent.FILE,
                                event = LogEvent.FILE_TASK_FAILED,
                                message = "Dropping the request of a ready file failed",
                                throwable = e,
                                fields = mapOf("fileId" to event.fileId),
                            )
                        }
                        answerTransferAuthors(event.fileId, FileReceiveEnd.Ready)
                    }

                    is FileReceiveEvent.Failed -> {
                        progressGuardLocked {
                            lastProgress.remove(event.fileId)
                            lastContiguous.remove(event.fileId)
                        }
                        answerTransferAuthors(
                            event.fileId,
                            FileReceiveEnd.Cancelled(event.reason.toCancelReason()),
                        )
                    }
                }
            }
        }
        jobs += scope.launch {
            // Every active receive gets a fresh mark at start, so a long sleep never
            // fires the gap trigger at once.
            try {
                val active = receives.active()
                progressGuard.lock()
                try {
                    for (row in active) {
                        lastProgress[row.fileId] = timeSource.markNow()
                        lastContiguous[row.fileId] = row.contiguousBytes
                    }
                } finally {
                    progressGuard.unlock()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Seeding gap-trigger marks failed",
                    throwable = e,
                )
            }
            while (true) {
                try {
                    // Also the startup re-fire: every pending row is re-armed unconditionally;
                    // routing's attempt row keeps its schedule, so this costs at most a wake.
                    sweep(clock.now())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "File request sweep failed; will retry",
                        throwable = e,
                    )
                }
                delay(config.value.fileSweepInterval)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun onIngest(payload: DagPayload) {
        val text = payload as? DagPayload.Text ?: return
        if (text.attachments.isEmpty()) return
        // The orchestrator's only size decision: fetch automatically or wait for the user.
        // It is about the user's attention and disk, not the router's small/large boundary.
        for (attachment in text.attachments) {
            if (attachment.totalBytes <= config.value.autoDownloadMaxBytes) {
                maybeRequest(attachment.fileId)
            } else {
                markDeclined(attachment.fileId, text.messageId)
            }
        }
    }

    private suspend fun maybeRequest(fileId: FileId) {
        val now = clock.now()
        if (descriptors.findById(fileId)?.status == FileDescriptorStatus.HELD) {
            pending.drop(fileId)
            return
        }
        val row = receives.find(fileId)
        if (row != null) {
            when (row.status) {
                FileReceiveStatus.READY -> {
                    pending.drop(fileId)
                    return
                }
                FileReceiveStatus.IN_FLIGHT -> return
                FileReceiveStatus.FAILED,
                FileReceiveStatus.DECLINED,
                FileReceiveStatus.CANCELLED,
                FileReceiveStatus.EVICTED,
                -> {
                    pending.drop(fileId)
                    return
                }
                FileReceiveStatus.STALLED -> Unit
            }
        }
        val existing = pending.find(fileId)
        if (existing?.status == PendingFileRequestStatus.PENDING) return
        // Only the trigger state lives here; routing owns the attempt schedule.
        pending.upsertPending(fileId, now)
        fileCarrier.startFilePull(fileId, immediate = false)
    }

    private suspend fun markDeclined(fileId: FileId, originMessageId: Uuid) {
        val attachment = messages.findFileAttachment(fileId) ?: return
        control.declineUnseen(attachment, originMessageId, clock.now())
    }

    override suspend fun acceptFile(fileId: FileId) {
        val now = clock.now()
        if (!control.reopen(fileId)) return
        pending.upsertPending(fileId, now)
        fileCarrier.startFilePull(fileId, immediate = true)
    }

    override suspend fun declineFile(fileId: FileId) {
        fileCarrier.abortLargeFile(fileId, LargeFileAbortReason.CANCELLED)
        pending.drop(fileId)
        control.decline(fileId, messages.findFileAttachment(fileId), clock.now())
    }

    private suspend fun sweep(now: Instant) {
        // An accepted OFFER covers the push window: a progressing transfer needs no REQUEST.
        // Drop pending rows for IN_FLIGHT receives instead of re-arming the router.
        for (row in pending.pending()) {
            val receive = receives.find(row.fileId)
            if (receive != null) {
                when (receive.status) {
                    FileReceiveStatus.READY -> {
                        pending.drop(row.fileId)
                        continue
                    }

                    FileReceiveStatus.IN_FLIGHT -> {
                        pending.drop(row.fileId)
                        continue
                    }

                    FileReceiveStatus.FAILED, FileReceiveStatus.DECLINED,
                    FileReceiveStatus.CANCELLED, FileReceiveStatus.EVICTED,
                        -> {
                        pending.drop(row.fileId)
                        continue
                    }

                    FileReceiveStatus.STALLED -> Unit
                }
            } else if (descriptors.findById(row.fileId)?.status == FileDescriptorStatus.HELD) {
                pending.drop(row.fileId)
                continue
            }
            fileCarrier.startFilePull(row.fileId, immediate = false)
        }
        // Gap trigger: an IN_FLIGHT receive whose prefix has not advanced for fileStallTimeout
        // (measured on the monotonic clock while this device runs) goes STALLED and re-opens
        // the REQUEST with held coverage. Live transfers are left alone; a live large fetch
        // is ended with an ABORT since a dead source cannot be superseded.
        val stallAfter = config.value.fileStallTimeout
        val active = receives.active()
        // Receives that ended without a Ready or Failed event (declined, cancelled, evicted) leave
        // their marks behind: only active receives keep one.
        val activeIds = active.map { it.fileId }.toSet()
        progressGuardLocked {
            lastProgress.keys.retainAll(activeIds)
            lastContiguous.keys.retainAll(activeIds)
        }
        for (row in active) {
            if (row.status != FileReceiveStatus.IN_FLIGHT) continue
            // A receive with no mark yet (an accepted OFFER that never sent a byte) starts its
            // gap window now.
            val mark = progressGuardLocked { lastProgress.getOrPut(row.fileId) { timeSource.markNow() } }
            if (mark.elapsedNow() < stallAfter) continue
            if (control.markStalled(row.fileId, now)) {
                // A large pull ends; for a small file there is no large fetch and this is a no-op.
                fileCarrier.abortLargeFile(row.fileId, LargeFileAbortReason.STALLED)
                // A fresh stall re-opens the request at once, even one that ran out of holders
                // before. Later sweeps re-arm it like any pending row, so routing's backoff holds.
                pending.upsertPending(row.fileId, now)
                fileCarrier.startFilePull(row.fileId, immediate = true)
            }
            progressGuardLocked { lastProgress[row.fileId] = timeSource.markNow() }
        }
        // Stalled by a block mismatch: nothing asked for it yet. A PENDING row is re-armed by the
        // first loop; an UNAVAILABLE one (every holder declined) stays terminal until a new
        // message or the user re-opens it.
        for (row in receives.active()) {
            if (row.status != FileReceiveStatus.STALLED || pending.find(row.fileId) != null) continue
            pending.upsertPending(row.fileId, now)
            fileCarrier.startFilePull(row.fileId, immediate = true)
        }
    }

    private suspend fun <T> progressGuardLocked(block: () -> T): T {
        progressGuard.lock()
        try {
            return block()
        } finally {
            progressGuard.unlock()
        }
    }

    private suspend fun onFileEvent(event: FileEvent) {
        val now = clock.now()
        when (event) {
            is FileEvent.RequestSatisfied -> pending.drop(event.fileId)
            is FileEvent.RequestUnavailable -> pending.setUnavailable(event.fileId, now)
            is FileEvent.NoDirectConnection -> {
                AppLog.debug(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Large fetch has no direct connection; request stays pending",
                    fields = mapOf("fileId" to event.fileId),
                )
            }
            else -> Unit
        }
    }

    /**
     * The receive ended: routing answers the transfer authors. The orchestrator decides
     * *when* (it owns receive lifecycle); routing owns the signaling and its rows.
     */
    private suspend fun answerTransferAuthors(fileId: FileId, end: FileReceiveEnd) {
        try {
            fileCarrier.endIncoming(fileId, end)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Politeness only: the authors' transfers end on their own TTL.
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Answering the transfer authors of an ended receive failed",
                throwable = e,
                fields = mapOf("fileId" to fileId),
            )
        }
    }
}

private fun FileFailureReason.toCancelReason(): FileCancelReason = when (this) {
    FileFailureReason.DECLINED -> FileCancelReason.DECLINED
    FileFailureReason.NO_SPACE -> FileCancelReason.NO_SPACE
    FileFailureReason.EXPIRED,
    FileFailureReason.CANCELLED,
    FileFailureReason.ENDED,
    FileFailureReason.FAILED,
    -> FileCancelReason.ABORT
}
