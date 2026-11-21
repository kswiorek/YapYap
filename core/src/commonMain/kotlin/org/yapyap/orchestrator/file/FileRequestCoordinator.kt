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
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.envelopes.FileFetchAbortReason
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * File request trigger (design §9): decides when this device asks for a file, beside the trigger
 * state (`pending_file_requests`), and starts routing's asking loop through [fileCarrier].
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
                    }

                    is FileReceiveEvent.Failed -> {
                        progressGuardLocked {
                            lastProgress.remove(event.fileId)
                            lastContiguous.remove(event.fileId)
                        }
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
                delay(config.value.fileSweepInterval)
                try {
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
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun onIngest(payload: MessagePayload) {
        val text = payload as? MessagePayload.Text ?: return
        if (text.attachments.isEmpty()) return
        for (attachment in text.attachments) {
            if (attachment.totalBytes <= SMALL_FILE_MAX_BYTES) {
                maybeRequest(attachment.fileId, immediate = false)
            } else if (attachment.totalBytes <= config.value.autoDownloadMaxBytes) {
                maybeRequest(attachment.fileId, immediate = true)
            } else {
                markDeclined(attachment.fileId, text.messageId)
            }
        }
    }

    private suspend fun maybeRequest(fileId: FileId, immediate: Boolean) {
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
                FileReceiveStatus.PENDING,
                FileReceiveStatus.IN_FLIGHT,
                    -> return
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
        val at = if (immediate) now else now + config.value.fileRequestFirstAttemptDelay
        pending.upsertPending(fileId, at, now)
        fileCarrier.startFileRequest(fileId, at)
    }

    private suspend fun markDeclined(fileId: FileId, originMessageId: Uuid) {
        val attachment = messages.findFileAttachment(fileId) ?: return
        control.declineUnseen(attachment, originMessageId, clock.now())
    }

    override suspend fun acceptFile(fileId: FileId) {
        val now = clock.now()
        if (!control.reopen(fileId)) return
        pending.upsertPending(fileId, now, now)
        fileCarrier.startFileRequest(fileId, now)
    }

    override suspend fun declineFile(fileId: FileId) {
        fileCarrier.abortFileFetch(fileId, FileFetchAbortReason.CANCELLED)
        pending.drop(fileId)
        control.decline(fileId, messages.findFileAttachment(fileId), clock.now())
    }

    private suspend fun sweep(now: Instant) {
        // An accepted OFFER covers the grace period: a progressing transfer needs no REQUEST.
        // Drop pending rows for IN_FLIGHT receives instead of re-arming the router.
        for (row in pending.due(now)) {
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

                    FileReceiveStatus.PENDING -> Unit
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
            fileCarrier.startFileRequest(row.fileId, row.nextAttemptAt)
        }
        // Gap trigger: an IN_FLIGHT receive whose prefix has not advanced for fileStallTimeout
        // (measured on the monotonic clock while this device runs) goes STALLED and re-opens
        // the REQUEST with held coverage. Live transfers are left alone; a live large fetch
        // is ended with an ABORT since a dead source cannot be superseded.
        val stallAfter = config.value.fileStallTimeout
        for (row in receives.active()) {
            if (row.status != FileReceiveStatus.IN_FLIGHT) continue
            // A receive with no mark yet (an accepted OFFER that never sent a byte) starts its
            // gap window now.
            val mark = progressGuardLocked { lastProgress.getOrPut(row.fileId) { timeSource.markNow() } }
            if (mark.elapsedNow() < stallAfter) continue
            if (control.markStalled(row.fileId, now) && row.totalBytes > SMALL_FILE_MAX_BYTES) {
                fileCarrier.abortFileFetch(row.fileId, FileFetchAbortReason.STALLED)
            }
            // A STALLED file re-opens the pending request below.
            progressGuardLocked { lastProgress[row.fileId] = timeSource.markNow() }
        }
        for (row in receives.active()) {
            if (row.status != FileReceiveStatus.STALLED) continue
            if (pending.find(row.fileId)?.status == PendingFileRequestStatus.PENDING) {
                fileCarrier.startFileRequest(row.fileId, now)
                continue
            }
            pending.upsertPending(row.fileId, now, now)
            fileCarrier.startFileRequest(row.fileId, now)
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
}
