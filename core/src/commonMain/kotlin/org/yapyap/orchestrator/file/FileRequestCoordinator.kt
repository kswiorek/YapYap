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
 * Trigger (message ingest): a Text attachment referencing a small file with no READY
 * local copy and no progressing transfer opens a PENDING row (grace
 * `fileRequestFirstAttemptDelay`, deduped by fileId) and starts the router's asking
 * loop. STALLED (hash mismatch below the retry limit, or the gap trigger after
 * `fileStallTimeout` without prefix progress) and EXPIRED rows re-open;
 * DECLINED / CANCELLED / FAILED / EVICTED are never auto-resurrected — those need a
 * new message or a user re-open. Large files never trigger: they are pull-only (Phase F).
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
        if (descriptor.totalBytes > SMALL_FILE_MAX_BYTES) return false
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

    // -- Trigger ----------------------------------------------------------------

    private suspend fun onIngest(payload: MessagePayload) {
        val text = payload as? MessagePayload.Text ?: return
        if (text.attachments.isEmpty()) return
        for (attachment in text.attachments) {
            if (attachment.totalBytes > SMALL_FILE_MAX_BYTES) continue
            maybeRequest(attachment.fileId)
        }
    }

    private suspend fun maybeRequest(fileId: FileId) {
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
                -> return // a push is progressing (or about to); the grace period covers late OFFERs

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
        val at = now + config.value.fileRequestFirstAttemptDelay
        pending.upsertPending(fileId, at, now)
        router.startFileRequest(fileId, at)
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
        // coverage. It cancels nothing: live incoming transfers keep merging whatever
        // arrives, and a serve from an already-pushing device supersedes that push at
        // the serving device. The timer counts only while this device runs (this sweep
        // is the only writer, and it runs in the started scope).
        val stallAfter = routerConfig.value.fileStallTimeout
        for (row in receives.active()) {
            if (row.status == FileReceiveStatus.IN_FLIGHT &&
                row.totalBytes <= SMALL_FILE_MAX_BYTES &&
                now - row.updatedAt > stallAfter
            ) {
                runCatching {
                    receives.updateStatus(row.fileId, FileReceiveStatus.STALLED, now)
                }
            }
        }
        for (row in receives.active()) {
            if (row.status != FileReceiveStatus.STALLED) continue
            if (row.totalBytes > SMALL_FILE_MAX_BYTES) continue
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
            is FileEvent.ServeStarted -> {
                if (transfers.find(event.fileId, event.requester) == null) {
                    transfers.upsert(
                        FileTransfer(
                            fileId = event.fileId,
                            peerAccountId = event.requester,
                            direction = FileTransferDirection.SERVE,
                            klass = FileTransferClass.SMALL,
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
