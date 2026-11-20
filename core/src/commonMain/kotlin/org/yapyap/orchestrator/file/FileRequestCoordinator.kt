package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.RoomRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.RoomMemberStatus
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.FileCoverage
import org.yapyap.routing.file.FileEvent
import org.yapyap.routing.file.FileIngestPort
import org.yapyap.routing.file.FileIngestResult
import org.yapyap.routing.file.FileOfferInfo
import org.yapyap.routing.file.FileOfferVerdict
import org.yapyap.routing.router.Router
import org.yapyap.routing.router.RouterConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * File request trigger + serve gate + request-side port (design §9, step 16).
 *
 * Implements the routing-declared [FileIngestPort] extension the request loop needs:
 * ingest-side calls delegate to the receive engine; coverage, candidates and the serve
 * gate live here, beside the trigger state (`pending_file_requests`). The router never
 * addresses a device through this seam — candidates are accounts, serving is gated
 * per account like sync.
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
    private val receives: FileReceiveStore,
    private val transfers: FileTransferStore,
    private val messages: MessageRepository,
    private val rooms: RoomRepository,
    private val descriptors: FileDescriptorStore,
    private val localIdentity: LocalIdentity,
    private val router: Router,
    private val pipeline: InboundMessagePipeline,
    private val engine: FileIngestEngine,
    private val config: StateFlow<OrchestratorConfig>,
    private val routerConfig: StateFlow<RouterConfig>,
    private val fileConfig: FileServiceConfig,
    private val clock: Clock = Clock.System,
) : FileIngestPort {
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
            router.fileCarrier.fileEvents.collect { event ->
                runCatching { onFileEvent(event) }
            }
        }
        jobs += scope.launch {
            while (true) {
                delay(config.value.fileRequestFirstAttemptDelay)
                runCatching { sweep(clock.now()) }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    // -- FileIngestPort ---------------------------------------------------------

    override suspend fun checkOffer(offer: FileOfferInfo): FileOfferVerdict = engine.checkOffer(offer)

    override suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult =
        engine.ingest(fileId, byteOffset, plaintext)

    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = engine.heldCoverage(fileId)

    override suspend fun requestCandidates(fileId: FileId): List<AccountId> {
        val messageIds = try {
            messages.findMessageIdsByFileId(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return emptyList()
        }
        val senders = LinkedHashSet<AccountId>()
        for (messageId in messageIds) {
            val stored = try {
                messages.findById(messageId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: continue
            val text = stored.payload as? MessagePayload.Text ?: continue
            senders += text.senderAccountId
        }
        val own = try {
            localIdentity.getLocalAccountId()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        // Senders' devices first, then own-account siblings (room members later).
        val ordered = senders.toMutableList()
        if (own != null) {
            ordered.remove(own)
            ordered.add(own)
        }
        return ordered
    }

    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean {
        val descriptor = try {
            descriptors.findById(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return false
        if (descriptor.status != FileDescriptorStatus.HELD) return false
        // No size gate: small REQUESTs for large files are DECLINED downstream by
        // the pusher (never served by transfer), while fetch serves any size here.
        val messageIds = try {
            messages.findMessageIdsByFileId(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return false
        }
        for (messageId in messageIds) {
            if (isVisibleTo(messageId, requester)) return true
        }
        return false
    }

    override suspend fun requestTotalBytes(fileId: FileId): Long? {
        // Attachments first (works before any receive row exists), then the row.
        findAttachment(fileId)?.let { return it.totalBytes }
        return try {
            receives.find(fileId)?.totalBytes
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    // -- Trigger ----------------------------------------------------------------

    private suspend fun onIngest(payload: MessagePayload) {
        val text = payload as? MessagePayload.Text ?: return
        if (text.attachments.isEmpty()) return
        for (attachment in text.attachments) {
            if (attachment.totalBytes <= SMALL_FILE_MAX_BYTES) {
                maybeRequest(attachment.fileId, immediate = false)
            } else if (attachment.totalBytes <= fileConfig.autoDownloadMaxBytes) {
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
        router.startFileRequest(fileId, at)
    }

    /**
     * Records a local DECLINED for a large file above the auto-download limit:
     * never fetched until the user accepts. Idempotent: an existing row (any
     * status but READY) is left alone so an accept in flight is not clobbered.
     */
    private suspend fun markDeclined(fileId: FileId, originMessageId: Uuid) {
        val now = clock.now()
        val row = try {
            receives.find(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (row != null) return
        val attachment = findAttachment(fileId) ?: return
        runCatching {
            receives.upsert(
                FileReceiveRow(
                    fileId = fileId,
                    status = FileReceiveStatus.DECLINED,
                    totalBytes = attachment.totalBytes,
                    contiguousBytes = 0L,
                    blockSize = null,
                    blockHashes = null,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    hashMismatches = 0,
                    // No bytes on disk yet: paths are placeholders until the fetch's
                    // FETCH_OK checkOffer upserts the real row. DECLINED rows are
                    // inert (refused before any path is touched).
                    partPath = "",
                    finalPath = "",
                    originMessageId = originMessageId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    /** First attachment referencing [fileId] across its messages, or null. */
    private suspend fun findAttachment(fileId: FileId): MessagePayload.Attachment? {
        val messageIds = try {
            messages.findMessageIdsByFileId(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        for (messageId in messageIds) {
            val stored = try {
                messages.findById(messageId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: continue
            val text = stored.payload as? MessagePayload.Text ?: continue
            text.attachments.firstOrNull { it.fileId == fileId }?.let { return it }
        }
        return null
    }

    private suspend fun sweep(now: Instant) {
        // Re-arm the router for due rows (restart recovery: routing rows persist, but a
        // dropped routing row must not strand a PENDING trigger), run the gap trigger,
        // and reopen stalled receives. startFileRequest is idempotent.
        for (row in pending.due(now)) {
            router.startFileRequest(row.fileId, row.nextAttemptAt)
        }
        // Gap trigger (design §8, step 19): an IN_FLIGHT receive whose contiguous
        // prefix P has not advanced for fileStallTimeout goes STALLED — re-requestable,
        // never refused — and the STALLED branch below re-opens the REQUEST with held
        // coverage. Small transfers are left alone to merge whatever arrives; a live
        // large fetch is ended with an ABORT (§6.6) since a dead source cannot be
        // superseded. The timer counts only while this device runs (this sweep
        // is the only writer, and it runs in the started scope).
        val stallAfter = routerConfig.value.fileStallTimeout
        for (row in receives.active()) {
            if (row.status == FileReceiveStatus.IN_FLIGHT &&
                now - row.updatedAt > stallAfter
            ) {
                if (row.totalBytes > SMALL_FILE_MAX_BYTES) {
                    runCatching { router.abortFileFetch(row.fileId) }
                }
                runCatching {
                    receives.updateStatus(row.fileId, FileReceiveStatus.STALLED, now)
                }
            }
        }
        for (row in receives.active()) {
            if (row.status != FileReceiveStatus.STALLED) continue
            if (pending.find(row.fileId)?.status == PendingFileRequestStatus.PENDING) {
                router.startFileRequest(row.fileId, now)
                continue
            }
            pending.upsertPending(row.fileId, now, now)
            router.startFileRequest(row.fileId, now)
        }
    }

    private suspend fun onFileEvent(event: FileEvent) {
        val now = clock.now()
        when (event) {
            is FileEvent.Ready -> pending.drop(event.fileId)
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
            is FileEvent.ServeStarted -> {
                if (transfers.find(event.fileId, event.requester) == null) {
                    val total = requestTotalBytes(event.fileId)
                    transfers.upsert(
                        FileTransfer(
                            fileId = event.fileId,
                            peerAccountId = event.requester,
                            direction = FileTransferDirection.SERVE,
                            klass = if (total != null && total > SMALL_FILE_MAX_BYTES) {
                                FileTransferClass.LARGE
                            } else {
                                FileTransferClass.SMALL
                            },
                            status = FileTransferStatus.IN_FLIGHT,
                            createdAt = now,
                            updatedAt = now,
                            expiresAt = now + routerConfig.value.fileTransferTtl,
                        ),
                    )
                }
            }

            is FileEvent.OutgoingComplete -> {
                val row = transfers.find(event.fileId, event.target)
                if (row != null && row.status == FileTransferStatus.IN_FLIGHT) {
                    transfers.setStatus(event.fileId, event.target, FileTransferStatus.COMPLETED, now)
                }
            }

            else -> Unit
        }
    }

    /** Serve gate, same rule as sync's membership gate: ACTIVE sees everything in the room. */
    private suspend fun isVisibleTo(messageId: Uuid, requester: AccountId): Boolean {
        val stored = try {
            messages.findById(messageId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return false
        val text = stored.payload as? MessagePayload.Text ?: return false
        val member = try {
            rooms.memberRowOf(text.roomId, requester)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return false
        if (member.status == RoomMemberStatus.ACTIVE) return true
        val removal = member.removalNodeId ?: return false
        if (member.status != RoomMemberStatus.REMOVED) return false
        // Member-era history only: the message must be the removal node or one of its ancestors.
        if (messageId == removal) return true
        val visited = HashSet<Uuid>()
        val queue = ArrayDeque<Uuid>()
        queue.add(removal)
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 512) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            if (id == messageId) return true
            val parents = try {
                messages.findParents(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return false
            }
            queue.addAll(parents)
        }
        return false
    }
}
