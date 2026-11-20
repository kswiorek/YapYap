package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.protocol.FileId
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * File request trigger (design §9): decides when this device asks for a file, beside the trigger
 * state (`pending_file_requests`), and starts routing's asking loop through [fileCarrier].
 *
 * Trigger (message ingest): a Text attachment referencing a file with no READY
 * local copy and no progressing transfer opens a PENDING row and starts the
 * router's asking loop. Small files wait out a grace period
 * (`fileRequestFirstAttemptDelay`) for the pushed OFFER; large files at or
 * below `autoDownloadMaxBytes` trigger at once (nothing is pushed); large
 * files above it land DECLINED locally until the user accepts. STALLED (hash
 * mismatch below the retry limit, or the gap trigger after `fileStallTimeout`
 * without prefix progress) and EXPIRED rows re-open; DECLINED / CANCELLED /
 * FAILED / EVICTED are never auto-resurrected — those need a new message or
 * a user re-open.
 */
internal class DefaultFileRequestCoordinator(
    private val pending: PendingFileRequestStore,
    private val receives: FileReceiveReader,
    private val control: FileReceiveControl,
    private val messages: MessageRepository,
    private val fileCarrier: FileCarrier,
    private val pipeline: InboundMessagePipeline,
    private val receiveEvents: Flow<FileReceiveEvent>,
    private val config: StateFlow<OrchestratorConfig>,
    private val clock: Clock = Clock.System,
) : FileRequestControl {
    private val jobs = mutableListOf<Job>()

    fun start(scope: CoroutineScope) {
        check(jobs.isEmpty()) { "FileRequestCoordinator already started" }
        jobs += scope.launch {
            pipeline.ingestResults.collect { result ->
                // One message failing must not end this collector: every later
                // ingest would go untriggered until restart.
                try {
                    onIngest(result.payload)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.ORCHESTRATOR,
                        event = LogEvent.SYNC_PROCESS_FAILED,
                        message = "File request trigger from ingest failed",
                        throwable = e,
                    )
                }
            }
        }
        jobs += scope.launch {
            fileCarrier.fileEvents.collect { event ->
                runCatching { onFileEvent(event) }
            }
        }
        jobs += scope.launch {
            receiveEvents.collect { event ->
                if (event !is FileReceiveEvent.Ready) return@collect
                try {
                    pending.drop(event.fileId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.ORCHESTRATOR,
                        event = LogEvent.SYNC_PROCESS_FAILED,
                        message = "Dropping the request of a ready file failed",
                        throwable = e,
                        fields = mapOf("fileId" to event.fileId),
                    )
                }
            }
        }
        jobs += scope.launch {
            while (true) {
                delay(config.value.fileSweepInterval)
                runCatching { sweep(clock.now()) }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    // -- Trigger ----------------------------------------------------------------

    private suspend fun onIngest(payload: MessagePayload) {
        val text = payload as? MessagePayload.Text ?: return
        if (text.attachments.isEmpty()) return
        for (attachment in text.attachments) {
            if (attachment.totalBytes <= SMALL_FILE_MAX_BYTES) {
                maybeRequest(attachment.fileId, immediate = false)
            } else if (attachment.totalBytes <= config.value.autoDownloadMaxBytes) {
                // Nothing is pushed for large files (§6.6), so there is no grace
                // period: fetch at once.
                maybeRequest(attachment.fileId, immediate = true)
            } else {
                // Above the auto-download limit: a local DECLINED until the user
                // accepts (re-openable through the runtime facade).
                markDeclined(attachment.fileId, text.messageId)
            }
        }
    }

    private suspend fun maybeRequest(fileId: FileId, immediate: Boolean) {
        val now = clock.now()
        val row = try {
            receives.find(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (row != null) {
            when (row.status) {
                FileReceiveStatus.READY -> {
                    pending.drop(fileId)
                    return
                }

                FileReceiveStatus.PENDING,
                FileReceiveStatus.IN_FLIGHT,
                    -> return // a push/fetch is progressing (or about to); small files also wait out the OFFER grace

                FileReceiveStatus.FAILED,
                FileReceiveStatus.DECLINED,
                FileReceiveStatus.CANCELLED,
                FileReceiveStatus.EVICTED,
                -> {
                    pending.drop(fileId)
                    return
                }

                // Re-requestable: a hash mismatch below the retry limit (the gap trigger
                // that reopens stalled transfers lands in step 19). Rows that never
                // progress expire by deletion (no row → trigger below).
                FileReceiveStatus.STALLED -> Unit
            }
        }
        val existing = try {
            pending.find(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (existing?.status == PendingFileRequestStatus.PENDING) return
        // New trigger, or a new message/sync re-arming an UNAVAILABLE row.
        // Small files wait out the OFFER grace period first; large auto-downloads
        // start at once.
        val at = if (immediate) now else now + config.value.fileRequestFirstAttemptDelay
        pending.upsertPending(fileId, at, now)
        fileCarrier.startFileRequest(fileId, at)
    }

    /**
     * Records a local DECLINED for a large file above the auto-download limit: never fetched until the
     * user accepts. An existing receive (any status) is left alone, so an accept in flight is kept.
     */
    private suspend fun markDeclined(fileId: FileId, originMessageId: Uuid) {
        val attachment = messages.findFileAttachment(fileId) ?: return
        control.declineUnseen(attachment, originMessageId, clock.now())
    }

    // -- FileRequestControl -----------------------------------------------------

    override suspend fun acceptFile(fileId: FileId) {
        val now = clock.now()
        if (!control.reopen(fileId)) return
        pending.upsertPending(fileId, now, now)
        fileCarrier.startFileRequest(fileId, now)
    }

    override suspend fun declineFile(fileId: FileId) {
        fileCarrier.abortFileFetch(fileId)
        pending.drop(fileId)
        control.decline(fileId, messages.findFileAttachment(fileId), clock.now())
    }

    private suspend fun sweep(now: Instant) {
        // Re-arm the router for due rows (restart recovery: routing rows persist, but a
        // dropped routing row must not strand a PENDING trigger), run the gap trigger,
        // and reopen stalled receives. startFileRequest is idempotent.
        for (row in pending.due(now)) {
            fileCarrier.startFileRequest(row.fileId, row.nextAttemptAt)
        }
        // Gap trigger (design §8, step 19): an IN_FLIGHT receive whose contiguous
        // prefix P has not advanced for fileStallTimeout goes STALLED — re-requestable,
        // never refused — and the STALLED branch below re-opens the REQUEST with held
        // coverage. Small transfers are left alone to merge whatever arrives; a live
        // large fetch is ended with an ABORT (§6.6) since a dead source cannot be
        // superseded. The timer counts only while this device runs (the sweep runs in
        // the started scope).
        val stallAfter = config.value.fileStallTimeout
        val idleSince = now - stallAfter
        for (row in receives.active()) {
            if (row.status != FileReceiveStatus.IN_FLIGHT || row.updatedAt >= idleSince) continue
            // The engine re-checks under its lock: a completion since this read wins.
            if (control.markStalled(row.fileId, idleSince, now) && row.totalBytes > SMALL_FILE_MAX_BYTES) {
                fileCarrier.abortFileFetch(row.fileId)
            }
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

    private suspend fun onFileEvent(event: FileEvent) {
        val now = clock.now()
        when (event) {
            is FileEvent.RequestSatisfied -> pending.drop(event.fileId)
            is FileEvent.RequestUnavailable -> pending.setUnavailable(event.fileId, now)
            is FileEvent.NoDirectConnection -> {
                // Online-but-unconnectable (design §6.6): the routing loop keeps the
                // request PENDING and retries when a candidate comes online, so there
                // is no state to change here — the event is for the GUI surface.
                AppLog.debug(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.SYNC_PROCESS_FAILED,
                    message = "Large fetch has no direct connection; request stays pending",
                    fields = mapOf("fileId" to event.fileId),
                )
            }
            else -> Unit
        }
    }


}
