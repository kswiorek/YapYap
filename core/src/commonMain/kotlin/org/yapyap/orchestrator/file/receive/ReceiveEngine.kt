package org.yapyap.orchestrator.file.receive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.FileCoverageReader
import org.yapyap.orchestrator.file.FileDescriptor
import org.yapyap.orchestrator.file.FileDescriptorReader
import org.yapyap.orchestrator.file.FileDescriptorStatus
import org.yapyap.orchestrator.file.FileIds
import org.yapyap.orchestrator.file.FilePathKind
import org.yapyap.orchestrator.file.FileReceiveControl
import org.yapyap.orchestrator.file.FileReceiveEvent
import org.yapyap.orchestrator.file.FileReceiveRow
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.FileReceiveStore
import org.yapyap.orchestrator.file.FileStorage
import org.yapyap.orchestrator.file.FileStore
import org.yapyap.orchestrator.file.StagedRange
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.protocol.FileId
import org.yapyap.protocol.blockSizeFor
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.file.port.FileReceiveEnds
import org.yapyap.routing.file.port.FileRefusalReason
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What a verb decided to announce. Sent after the file lock is released. */
private class Announcements {
    val events = mutableListOf<FileReceiveEvent>()
    val ends = mutableListOf<FileReceiveEnd>()
}

private sealed interface DrainOutcome {
    data object NothingDrainable : DrainOutcome
    data class Advanced(val newP: Long) : DrainOutcome
    data object Completed : DrainOutcome
    data class Mismatched(val blockStart: Long) : DrainOutcome
    data object Failed : DrainOutcome
}

private val ACTIVE = setOf(FileReceiveStatus.IN_FLIGHT, FileReceiveStatus.STALLED)

private val TERMINAL = setOf(
    FileReceiveStatus.FAILED,
    FileReceiveStatus.DECLINED,
    FileReceiveStatus.CANCELLED,
    FileReceiveStatus.EVICTED,
)

/**
 * The receive engine (design §8): one engine for both classes. The small/large distinction is
 * send-side path policy; the receiver never branches on it.
 *
 * - Byte coverage is the whole model. Chunks from any number of transfers, with any chunkings,
 *   land their byte ranges; fully covered ranges are duplicates and are dropped.
 * - Durable before ACK, checkpointed. Staged ranges are recorded in one DB transaction before
 *   the chunk is ACKed. P advances only at checkpoints (when `fileCheckpointBytes` are drainable,
 *   every `fileCheckpointInterval`, and at completion): append what is drainable, fsync the
 *   `.part` once, verify the completed blocks from disk, then advance P and drop the drained
 *   staged rows in one DB transaction.
 * - P is never committed past an unverified block. A mismatch truncates to the block's start
 *   and CANCELs the live transfers.
 *
 * Every verb runs under its file's lock; only the free-space reservation takes a global one.
 * Events and receive ends are sent after the lock is released.
 */
internal class ReceiveEngine(
    private val receives: FileReceiveStore,
    private val descriptors: FileDescriptorReader,
    private val crypto: CryptoProvider,
    private val files: FileAccess,
    private val paths: ReceivePaths,
    private val config: StateFlow<OrchestratorConfig>,
    private val fileStore: FileStore,
    private val clock: Clock = Clock.System,
) : FileIngestPort, FileCoverageReader, FileReceiveEnds, FileReceiveControl {
    private val writer = PartWriter(files, crypto)

    private val events = MutableSharedFlow<FileReceiveEvent>(extraBufferCapacity = 64)

    /** Progress, readiness and failures of the files this engine receives. */
    val receiveEvents: Flow<FileReceiveEvent> = events.asSharedFlow()

    private val ends = Channel<FileReceiveEnd>(Channel.UNLIMITED)

    override val receiveEnds: Flow<FileReceiveEnd> = ends.receiveAsFlow()

    private val locksGuard = Mutex()
    private val locks = mutableMapOf<FileId, FileLock>()
    private val spaceMutex = Mutex()

    @Volatile
    private var recovered = false

    private var job: Job? = null

    /** Recovers, then checkpoints on the timer. Ingest answers `Defer` until recovery succeeds. */
    fun start(scope: CoroutineScope) {
        check(job == null) { "ReceiveEngine already started" }
        job = scope.launch {
            while (!recovered) {
                try {
                    recover(clock.now())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Receive recovery failed; retrying",
                        throwable = e,
                    )
                    delay(config.value.fileCheckpointInterval)
                }
            }
            while (true) {
                delay(config.value.fileCheckpointInterval)
                try {
                    checkpoint()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Checkpoint sweep failed; will retry",
                        throwable = e,
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Runs recovery inline, for tests that do not call [start]. */
    internal suspend fun recoverNow(now: Instant = clock.now()) = recover(now)

    // -- FileIngestPort -----------------------------------------------------------

    /** A store or disk failure while checking is transient: the sender retries the OFFER. */
    override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict {
        if (!recovered) return FileOfferVerdict.Defer
        return try {
            offerVerdict(offer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "OFFER check failed; deferring",
                throwable = e,
                fields = mapOf("fileId" to offer.fileId),
            )
            FileOfferVerdict.Defer
        }
    }

    /** A store or disk failure before the chunk is staged is transient: the sender resends it. */
    override suspend fun ingest(
        fileId: FileId,
        byteOffset: Long,
        plaintext: ByteArray,
    ): FileIngestResult {
        if (!recovered) return FileIngestResult.Defer
        return try {
            ingestChunk(fileId, byteOffset, plaintext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_CHUNK_DEFERRED,
                message = "File chunk deferred: staging failed",
                throwable = e,
                fields = mapOf("fileId" to fileId),
            )
            FileIngestResult.Defer
        }
    }

    /**
     * Fetch append (design §6.6, §8). Only a not-yet-recovered engine defers; a failed write,
     * fsync or verify propagates, leaving P where it was, and the fetch resumes from P.
     */
    override suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult {
        if (!recovered) return FileAppendResult.Defer
        return appendBytes(fileId, offset, bytes)
    }

    /**
     * Durable held coverage for building a REQUEST (design §3.2, §9): the contiguous prefix plus
     * the capped missing ranges beyond it. Null when the file needs no request: held, READY, or
     * ended (DECLINED, FAILED, CANCELLED, EVICTED wait for the user to re-open them).
     */
    override suspend fun heldCoverage(fileId: FileId): FileCoverage? {
        if (descriptors.findById(fileId)?.status == FileDescriptorStatus.HELD) return null
        return withFile(fileId) { _ ->
            val row =
                receives.find(fileId)?.let { forgetLostReady(it) } ?: return@withFile FileCoverage(0L, emptyList())
            if (row.status !in ACTIVE) return@withFile null
            Coverage.heldCoverage(
                row.contiguousBytes,
                receives.stagedRefsByFile(fileId),
                FilePayload.Request.MAX_MISSING_RANGES,
                row.totalBytes,
            )
        }
    }

    // -- FileReceiveControl ---------------------------------------------------------

    override suspend fun markStalled(fileId: FileId, now: Instant): Boolean = withFile(fileId) { _ ->
        val row = receives.find(fileId) ?: return@withFile false
        if (row.status != FileReceiveStatus.IN_FLIGHT) return@withFile false
        receives.updateStatus(fileId, FileReceiveStatus.STALLED, now)
        true
    }

    override suspend fun declineUnseen(attachment: MessagePayload.Attachment, originMessageId: Uuid, now: Instant) {
        withFile(attachment.fileId) { _ ->
            if (receives.find(attachment.fileId) == null) {
                receives.upsert(declinedRow(attachment, originMessageId, now))
            }
        }
    }

    override suspend fun decline(fileId: FileId, attachment: MessagePayload.Attachment?, now: Instant) {
        withFile(fileId) { out ->
            val row = receives.find(fileId)
            when {
                row == null -> attachment?.let { receives.upsert(declinedRow(it, null, now)) }
                row.status == FileReceiveStatus.READY -> Unit
                else -> declineRow(row, now, out)
            }
        }
    }

    override suspend fun reopen(fileId: FileId): Boolean = withFile(fileId) { _ ->
        val row = receives.find(fileId)?.let { forgetLostReady(it) } ?: return@withFile true
        when (row.status) {
            FileReceiveStatus.READY -> false
            in ACTIVE -> true
            else -> {
                receives.deleteWithStaged(fileId)
                true
            }
        }
    }

    override suspend fun evicted(fileId: FileId, now: Instant): Boolean = withFile(fileId) { _ ->
        val row = receives.find(fileId) ?: return@withFile false
        if (row.status != FileReceiveStatus.READY) return@withFile false
        // EVICTED first, then the descriptor stops serving (SOURCE_LOST), then the bytes, then the
        // descriptor: a failure part-way never leaves a READY row or a HELD descriptor pointing at a
        // deleted file, and a failed delete keeps the copy in the quota's LRU, which retries it.
        receives.updateStatus(fileId, FileReceiveStatus.EVICTED, now)
        fileStore.markSourceLost(fileId)
        files.delete(paths.final(fileId))
        fileStore.forget(fileId)
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_EVICTED,
            message = "Received file evicted by quota",
            fields = mapOf("fileId" to fileId),
        )
        true
    }

    override suspend fun forgetUnreferenced(fileId: FileId) {
        withFile(fileId) { _ ->
            // Bytes first: a failed delete keeps the row, so the next sweep retries it instead of
            // leaving bytes no row refers to.
            files.delete(paths.part(fileId))
            files.delete(paths.final(fileId))
            receives.deleteWithStaged(fileId)
        }
    }

    override suspend fun updateOrigin(fileId: FileId, messageId: Uuid, now: Instant) {
        withFile(fileId) { _ ->
            val row = receives.find(fileId) ?: return@withFile
            if (row.originMessageId == null) {
                receives.updateOrigin(fileId, messageId, now)
            }
        }
    }

    // -- Checkpoints and restart -----------------------------------------------------

    /** Drains every active receive. A failing file is logged and retried on the next pass. */
    suspend fun checkpoint(now: Instant = clock.now()) {
        if (!recovered) return
        for (listed in receives.active()) {
            try {
                withFile(listed.fileId) { out ->
                    val row = receives.find(listed.fileId) ?: return@withFile
                    if (row.status !in ACTIVE) return@withFile
                    if (row.contiguousBytes >= row.totalBytes) {
                        completeRow(row, now, out)
                    } else if (receives.stagedBytes(row.fileId) > 0) {
                        drainRow(row, now, out)
                    } else {
                        // No staged rows: a fetch file whose `.part` grew past P.
                        commitFetchPrefix(row, now, out)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Checkpoint failed; will retry",
                    throwable = e,
                    fields = mapOf("fileId" to listed.fileId),
                )
            }
        }
    }

    /**
     * Restart repair, under each file's lock, before ingest accepts calls:
     * - an active `.part` longer than P is truncated to P; a shorter one rewinds P to what
     *   survived; a missing one is recreated and P rewinds to 0. Staged rows are kept (ACKed,
     *   durable) and drain once the prefix is back;
     * - an ended receive drops any `.part` and staged rows a failed cleanup left;
     * - a READY row whose descriptor is missing (crash between READY and adopt) is re-adopted.
     */
    private suspend fun recover(now: Instant) {
        for (listed in receives.active()) {
            withFile(listed.fileId) { _ ->
                val row = receives.find(listed.fileId) ?: return@withFile
                if (row.status in ACTIVE) recoverPart(row, now)
            }
        }
        for (listed in receives.all().filter { it.status in TERMINAL }) {
            withFile(listed.fileId) { _ ->
                val part = paths.part(listed.fileId)
                if (files.exists(part)) files.delete(part)
                if (receives.stagedBytes(listed.fileId) > 0) receives.deleteStagedByFile(listed.fileId)
            }
        }
        deleteOrphanParts()
        for (listed in receives.ready()) {
            withFile(listed.fileId) { out ->
                if (descriptors.findById(listed.fileId) == null && files.exists(paths.final(listed.fileId))) {
                    fileStore.adopt(receivedDescriptor(listed, createdAt = listed.createdAt, now = now))
                }
                out.ends += FileReceiveEnd.Ready(listed.fileId)
                out.events += FileReceiveEvent.Ready(listed.fileId)
            }
        }
        recovered = true
    }

    /**
     * `.part` files with no active receive (a delete that failed, or a crash between a row's end
     * and its file's delete): nothing refers to them, so they would hold space forever. Only the
     * engine writes `incoming/`, so this never races a receive.
     */
    private suspend fun deleteOrphanParts() {
        val active = receives.active().map { it.fileId.value }.toSet()
        for (name in files.listFileNames(paths.incomingDir)) {
            if (!name.endsWith(PART_SUFFIX)) continue
            if (name.removeSuffix(PART_SUFFIX) in active) continue
            files.delete(Path(paths.incomingDir.toString(), name))
        }
    }

    private suspend fun recoverPart(row: FileReceiveRow, now: Instant) {
        // Crash after the READY rename: the final file holds every byte; the checkpoint completes it.
        if (renamedToFinal(row)) return
        val part = paths.part(row.fileId)
        val size = files.fileSize(part)
        when {
            size == null -> {
                AppLog.warn(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Receive .part missing at restart; re-fetching its prefix",
                    fields = mapOf("fileId" to row.fileId),
                )
                createEmptyPart(row, now)
            }

            size > row.contiguousBytes -> writer.truncateTo(part, row.contiguousBytes)
            size < row.contiguousBytes -> receives.updateProgress(
                row.fileId, size, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now,
            )
        }
    }

    // -- Verbs' bodies ---------------------------------------------------------------

    private suspend fun offerVerdict(offer: FileManifest): FileOfferVerdict {
        if (descriptors.findById(offer.fileId)?.status == FileDescriptorStatus.HELD) {
            return FileOfferVerdict.AlreadyReady
        }
        return withFile(offer.fileId) { _ ->
            // READY with no HELD descriptor (checked above): the stored bytes were lost
            // (SOURCE_LOST, deleted), so the receive starts over.
            val existing = receives.find(offer.fileId)?.let { forgetLostReady(it) }
            val validBlockList = FileIds.checkBlockList(
                crypto, offer.fileId, offer.totalBytes, offer.blockSize, offer.blockHashes,
            )
            when {
                existing == null && !validBlockList -> FileOfferVerdict.Refuse(FileRefusalReason.INVALID_OFFER)
                existing == null -> spaceMutex.withLock { reserve(offer) }
                existing.status in TERMINAL -> FileOfferVerdict.Refuse(FileRefusalReason.NOT_RETRYABLE)
                existing.totalBytes != offer.totalBytes || !validBlockList ->
                    FileOfferVerdict.Refuse(FileRefusalReason.INVALID_OFFER)

                else -> {
                    if (!renamedToFinal(existing) && !files.exists(paths.part(offer.fileId))) {
                        createEmptyPart(existing, clock.now())
                    }
                    FileOfferVerdict.Accept
                }
            }
        }
    }

    /**
     * Free-space reservation (design §14): the OFFER fits only if free space minus the
     * outstanding `total − P` of every active receive holds it. Runs under the reservation
     * mutex, so two OFFERs never both claim the same space. No space is DECLINED, so the
     * message trigger stops re-requesting; it is never retried automatically.
     */
    private suspend fun reserve(offer: FileManifest): FileOfferVerdict {
        val now = clock.now()
        val reserved = receives.active()
            .filter { it.status == FileReceiveStatus.IN_FLIGHT || it.status == FileReceiveStatus.STALLED }
            .sumOf { maxOf(0L, it.totalBytes - it.contiguousBytes) }
        files.createDirectories(paths.incomingDir)
        files.createDirectories(paths.storeDir)
        if (files.freeSpaceBytes(paths.incomingDir) - reserved < offer.totalBytes) {
            receives.upsert(offeredRow(offer, FileReceiveStatus.DECLINED, now))
            AppLog.info(
                component = LogComponent.FILE,
                event = LogEvent.FILE_RECEIVE_FAILED,
                message = "File OFFER declined: no space",
                fields = mapOf("fileId" to offer.fileId, "totalBytes" to offer.totalBytes),
            )
            return FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE)
        }
        files.openAppendWriter(paths.part(offer.fileId), null).use { it.flushAndSync() }
        receives.upsert(offeredRow(offer, FileReceiveStatus.IN_FLIGHT, now))
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_OFFER_ACCEPTED,
            message = "File OFFER accepted",
            fields = mapOf("fileId" to offer.fileId),
        )
        return FileOfferVerdict.Accept
    }

    private suspend fun ingestChunk(
        fileId: FileId,
        byteOffset: Long,
        plaintext: ByteArray,
    ): FileIngestResult = withFile(fileId) { out ->
        val now = clock.now()
        val row = receives.find(fileId) ?: return@withFile FileIngestResult.Refused
        val p = row.contiguousBytes
        val total = row.totalBytes
        when (row.status) {
            FileReceiveStatus.READY -> return@withFile FileIngestResult.Duplicate(p, total)
            in TERMINAL -> return@withFile FileIngestResult.Refused
            else -> Unit
        }
        if (plaintext.isEmpty()) return@withFile FileIngestResult.Duplicate(p, total)
        if (byteOffset < 0 || (total > 0 && byteOffset >= total)) return@withFile FileIngestResult.Refused
        if (total == 0L) {
            completeRow(row, now, out)
            return@withFile FileIngestResult.Ready(0L, 0L)
        }
        val end = minOf(byteOffset + plaintext.size, total)
        if (end <= p) return@withFile FileIngestResult.Duplicate(p, total)
        val staged = receives.stagedRefsByFile(fileId)
        val uncovered = Coverage.uncovered(byteOffset, end, p, staged)
        if (uncovered.isEmpty()) return@withFile FileIngestResult.Duplicate(p, total)
        val toStage = uncovered.map { (off, len) ->
            val srcOff = (off - byteOffset).toInt()
            StagedRange(off, len, plaintext.copyOfRange(srcOff, srcOff + len.toInt()))
        }
        val added = toStage.map { StagedSpan(it.startOffset, it.length) }
        // Chunks that extend the prefix are never held back by the bound (they drain at the next
        // checkpoint), but a drain that keeps failing would let them stage without limit: past
        // one more checkpoint's worth they wait too.
        val stagedAfter = staged.sumOf { it.length } + added.sumOf { it.length }
        val bound = if (Coverage.extendsPrefix(p, staged, added)) {
            config.value.maxStagedBytes + config.value.fileCheckpointBytes
        } else {
            config.value.maxStagedBytes
        }
        if (stagedAfter > bound) {
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_CHUNK_DEFERRED,
                message = "File chunk deferred: staging bound",
                fields = mapOf("fileId" to fileId),
            )
            return@withFile FileIngestResult.Defer
        }
        receives.stageChunks(fileId, toStage, FileReceiveStatus.IN_FLIGHT, now)
        val drainable = Coverage.drainPlan(p, staged + added).newP
        if (drainable < total && drainable - p < config.value.fileCheckpointBytes) {
            return@withFile FileIngestResult.Staged(p, total)
        }
        // Completion or the byte threshold: drain now. The chunk is already durable, so a
        // failed drain still answers Staged and the checkpoint timer retries the drain.
        val outcome = try {
            drainRow(row.copy(status = FileReceiveStatus.IN_FLIGHT), now, out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Drain after staging failed; the checkpoint timer retries",
                throwable = e,
                fields = mapOf("fileId" to fileId),
            )
            return@withFile FileIngestResult.Staged(p, total)
        }
        when (outcome) {
            DrainOutcome.NothingDrainable -> FileIngestResult.Staged(p, total)
            is DrainOutcome.Advanced -> FileIngestResult.Staged(outcome.newP, total)
            DrainOutcome.Completed -> FileIngestResult.Ready(total, total)
            is DrainOutcome.Mismatched -> FileIngestResult.Staged(outcome.blockStart, total)
            DrainOutcome.Failed -> FileIngestResult.Refused
        }
    }

    /** DECLINED first: a failure in the cleanup below leaves leftovers recovery drops. Caller holds the lock. */
    private suspend fun declineRow(row: FileReceiveRow, now: Instant, out: Announcements) {
        receives.updateStatus(row.fileId, FileReceiveStatus.DECLINED, now)
        receives.deleteStagedByFile(row.fileId)
        files.delete(paths.part(row.fileId))
        if (row.status in ACTIVE) {
            out.ends += FileReceiveEnd.Cancelled(row.fileId, FileCancelReason.DECLINED)
            out.events += FileReceiveEvent.Failed(row.fileId, FileFailureReason.DECLINED)
        }
    }

    /**
     * Fetch append body: writes [bytes] at [offset] into the `.part` without staging or an fsync.
     * A checkpoint (a full `fileCheckpointBytes` past P, or the file's end) fsyncs, verifies the
     * blocks it completes and commits P. Time checkpoints run in [checkpoint].
     */
    private suspend fun appendBytes(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult =
        withFile(fileId) { out ->
            require(offset >= 0) { "offset must be >= 0 but was $offset" }
            val now = clock.now()
            val row = receives.find(fileId) ?: return@withFile FileAppendResult.Ended
            when (row.status) {
                FileReceiveStatus.READY -> return@withFile FileAppendResult.Ready
                !in ACTIVE -> return@withFile FileAppendResult.Ended
                else -> Unit
            }
            val total = row.totalBytes
            if (total == 0L) {
                require(offset == 0L) { "fetch offset $offset != uncommitted end 0" }
                completeRow(row, now, out)
                return@withFile FileAppendResult.Ready
            }
            val blockSize = checkNotNull(row.blockSize) { "missing block info for $fileId" }
            val blockHashes = checkNotNull(row.blockHashes) { "missing block info for $fileId" }
            val part = paths.part(fileId)
            val size = files.fileSize(part) ?: run {
                createEmptyPart(row, now)
                0L
            }
            // A resume after a drop without a restart: the `.part` still holds the previous fetch's
            // unverified suffix past P. The write below truncates it.
            require(offset == size || (offset == row.contiguousBytes && size > offset)) {
                "fetch offset $offset != uncommitted end $size"
            }
            if (bytes.isEmpty()) return@withFile FileAppendResult.Appended(row.contiguousBytes)
            val end = offset + bytes.size
            require(end <= total) { "fetch bytes [$offset, $end) exceed total $total" }
            // The reservation at FETCH_OK counted this file; the disk can still fill from elsewhere.
            if (files.freeSpaceBytes(paths.incomingDir) < bytes.size) {
                declineRow(row, now, out)
                AppLog.info(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_RECEIVE_FAILED,
                    message = "Fetch declined: no space",
                    fields = mapOf("fileId" to fileId),
                )
                return@withFile FileAppendResult.NoSpace
            }
            writer.writeAt(part, offset, bytes)
            // A source resumed delivery (design §14: STALLED → IN_FLIGHT), so the gap trigger
            // watches this receive again.
            if (row.status == FileReceiveStatus.STALLED) {
                receives.updateStatus(fileId, FileReceiveStatus.IN_FLIGHT, now)
            }
            if (end < total && end - row.contiguousBytes < config.value.fileCheckpointBytes) {
                return@withFile FileAppendResult.Appended(row.contiguousBytes)
            }
            writer.sync(part)
            val bad = firstBadBlock(part, row, end, blockSize, blockHashes)
            if (bad != null) {
                mismatch(row, bad, blockSize, now, out)
                return@withFile FileAppendResult.Mismatch(bad)
            }
            if (end >= total) {
                // P first, then the rename: a crash in between finds P = total and the checkpoint
                // finishes the rename, instead of finding P short with no `.part` and re-fetching.
                receives.updateProgress(fileId, total, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now)
                completeRow(row.copy(contiguousBytes = total), now, out)
                return@withFile FileAppendResult.Ready
            }
            receives.updateProgress(fileId, end, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now)
            // Staged small-path rows the fetch overtook: their bytes are below P now. Left in place
            // they would count against the staging bound and keep the time checkpoint on the
            // drain branch, which has nothing to drain.
            receives.deleteStagedBelow(fileId, end)
            out.events += FileReceiveEvent.Progress(fileId, end, total)
            FileAppendResult.Appended(end)
        }

    /**
     * Time checkpoint for a fetch file: fsyncs and commits the `.part` suffix past P that byte
     * checkpoints have not moved yet. Caller holds the file lock.
     */
    private suspend fun commitFetchPrefix(row: FileReceiveRow, now: Instant, out: Announcements) {
        val part = paths.part(row.fileId)
        val size = files.fileSize(part) ?: return
        if (size <= row.contiguousBytes) return
        val blockSize = row.blockSize ?: return
        val blockHashes = row.blockHashes ?: return
        val newP = minOf(size, row.totalBytes)
        writer.sync(part)
        val bad = firstBadBlock(part, row, newP, blockSize, blockHashes)
        if (bad != null) {
            mismatch(row, bad, blockSize, now, out)
            return
        }
        if (newP >= row.totalBytes) {
            receives.updateProgress(row.fileId, newP, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now)
            completeRow(row.copy(contiguousBytes = newP), now, out)
            return
        }
        receives.updateProgress(row.fileId, newP, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now)
        receives.deleteStagedBelow(row.fileId, newP)
        out.events += FileReceiveEvent.Progress(row.fileId, newP, row.totalBytes)
    }

    /**
     * Drains the contiguous run from P: append it, verify the blocks it completes, then commit
     * P and the staged cleanup in one transaction. Caller holds the file lock.
     */
    private suspend fun drainRow(row: FileReceiveRow, now: Instant, out: Announcements): DrainOutcome {
        val blockSize = row.blockSize ?: return DrainOutcome.NothingDrainable
        val blockHashes = row.blockHashes ?: return DrainOutcome.NothingDrainable
        val p = row.contiguousBytes
        val plan = Coverage.drainPlan(p, receives.stagedRefsByFile(row.fileId))
        if (plan.newP == p) return DrainOutcome.NothingDrainable
        val staged = receives.stagedOverlapping(row.fileId, p, plan.newP)
        val bytes = ByteArray((plan.newP - p).toInt())
        var cursor = p
        for (range in staged) {
            check(range.startOffset <= cursor) { "staged rows changed under the file lock" }
            val skip = (cursor - range.startOffset).toInt()
            val take = minOf(range.plaintext.size - skip, (plan.newP - cursor).toInt())
            if (take <= 0) continue
            range.plaintext.copyInto(bytes, (cursor - p).toInt(), skip, skip + take)
            cursor += take
        }
        check(cursor == plan.newP) { "staged rows do not cover the drain plan" }

        val part = paths.part(row.fileId)
        // The OFFER's reservation counted this file, but the disk can still fill from elsewhere:
        // a drain that cannot fit ends the receive as no space, like the fetch path, instead of
        // failing on every checkpoint while chunks keep arriving.
        if (files.freeSpaceBytes(paths.incomingDir) < bytes.size) {
            declineRow(row, now, out)
            AppLog.info(
                component = LogComponent.FILE,
                event = LogEvent.FILE_RECEIVE_FAILED,
                message = "Receive declined: no space for its drain",
                fields = mapOf("fileId" to row.fileId),
            )
            return DrainOutcome.Failed
        }
        writer.appendAt(part, p, bytes)
        val bad = firstBadBlock(part, row, plan.newP, blockSize, blockHashes)
        if (bad != null) return mismatch(row, bad, blockSize, now, out)

        receives.commitDrain(row.fileId, plan.newP, row.hashMismatches, row.status, now)
        out.events += FileReceiveEvent.Progress(row.fileId, plan.newP, row.totalBytes)
        if (plan.newP >= row.totalBytes) {
            completeRow(row.copy(contiguousBytes = plan.newP), now, out)
            return DrainOutcome.Completed
        }
        return DrainOutcome.Advanced(plan.newP)
    }

    /** The first block from the one holding P that [newP] completes and that fails its hash. */
    private suspend fun firstBadBlock(
        part: Path,
        row: FileReceiveRow,
        newP: Long,
        blockSize: Int,
        blockHashes: List<ByteArray>,
    ): Int? {
        val first = (row.contiguousBytes / blockSize).toInt()
        val lastComplete = if (newP >= row.totalBytes) {
            ((row.totalBytes - 1) / blockSize).toInt()
        } else {
            (newP / blockSize).toInt() - 1
        }
        for (blockIndex in first..lastComplete) {
            val expected = blockHashes.getOrNull(blockIndex) ?: return blockIndex
            val check = writer.verifyBlock(part, blockIndex, blockSize, row.totalBytes, expected)
            if (check is BlockCheck.Mismatch) return blockIndex
        }
        return null
    }

    /**
     * A block failed its hash (design §8): truncate to its start, drop every
     * staged row and rewind P in one transaction, CANCEL the live transfers. STALLED
     * re-requests it; FAILED at `fileHashRetryLimit` mismatches.
     */
    private suspend fun mismatch(
        row: FileReceiveRow,
        blockIndex: Int,
        blockSize: Int,
        now: Instant,
        out: Announcements,
    ): DrainOutcome {
        val blockStart = blockIndex.toLong() * blockSize
        // Truncate before the commit: if it fails nothing is committed and the next drain redoes it.
        writer.truncateTo(paths.part(row.fileId), blockStart)
        val mismatches = row.hashMismatches + 1
        val failed = mismatches >= config.value.fileHashRetryLimit
        val status = if (failed) FileReceiveStatus.FAILED else FileReceiveStatus.STALLED
        receives.commitMismatch(row.fileId, blockStart, mismatches, status, now)
        out.ends += FileReceiveEnd.Cancelled(row.fileId, FileCancelReason.ABORT)
        AppLog.warn(
            component = LogComponent.FILE,
            event = LogEvent.FILE_BLOCK_MISMATCH,
            message = "File block failed its hash",
            fields = mapOf("fileId" to row.fileId, "block" to blockIndex, "mismatches" to mismatches),
        )
        if (!failed) return DrainOutcome.Mismatched(blockStart)
        files.delete(paths.part(row.fileId))
        out.events += FileReceiveEvent.Failed(row.fileId, FileFailureReason.FAILED)
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_RECEIVE_FAILED,
            message = "File receive failed: hash retry limit",
            fields = mapOf("fileId" to row.fileId),
        )
        return DrainOutcome.Failed
    }

    /**
     * Rename the `.part` to the final path, fsync the directory, mark READY, adopt the descriptor.
     * P is already committed at total, so this is idempotent: a crash after the rename restarts here
     * with no `.part` and the final file in place; a crash between READY and adopt is repaired by
     * [recover]. The verified `.part` always wins over a file already at the final path (a stale
     * copy whose descriptor went SOURCE_LOST): the atomic rename replaces it. Quota runs
     * elsewhere, never under this lock.
     */
    private suspend fun completeRow(row: FileReceiveRow, now: Instant, out: Announcements) {
        val final = paths.final(row.fileId)
        val part = paths.part(row.fileId)
        if (files.exists(part)) {
            files.move(part, final)
        } else {
            check(files.exists(final)) { "receive ${row.fileId} has neither its .part nor its final file" }
        }
        files.syncDirectory(final)
        receives.markReady(row.fileId, row.totalBytes, row.hashMismatches, now)
        fileStore.adopt(receivedDescriptor(row, createdAt = now, now = now))
        out.events += FileReceiveEvent.Ready(row.fileId)
        out.ends += FileReceiveEnd.Ready(row.fileId)
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_RECEIVE_READY,
            message = "File receive READY",
            fields = mapOf("fileId" to row.fileId),
        )
    }

    /** P reached the end and the `.part` was already renamed: only the READY commit is missing. */
    /**
     * A READY row whose descriptor is no longer HELD lost its stored bytes: the row is dropped so
     * the file can be received again, and null returned. Any other row is returned as it is, and so
     * is every row before recovery ran (a READY row may still be waiting for [recover] to adopt its
     * descriptor). Caller holds the file lock.
     */
    private suspend fun forgetLostReady(row: FileReceiveRow): FileReceiveRow? {
        if (row.status != FileReceiveStatus.READY || !recovered) return row
        if (descriptors.findById(row.fileId)?.status == FileDescriptorStatus.HELD) return row
        receives.deleteWithStaged(row.fileId)
        AppLog.info(
            component = LogComponent.FILE,
            event = LogEvent.FILE_RECEIVE_FAILED,
            message = "Received file no longer held; receiving it again",
            fields = mapOf("fileId" to row.fileId),
        )
        return null
    }

    private suspend fun renamedToFinal(row: FileReceiveRow): Boolean =
        row.contiguousBytes >= row.totalBytes && files.exists(paths.final(row.fileId))

    /** A missing `.part` of an active receive: recreate it and rewind P to 0, keeping staged rows. */
    private suspend fun createEmptyPart(row: FileReceiveRow, now: Instant) {
        files.createDirectories(paths.incomingDir)
        files.openAppendWriter(paths.part(row.fileId), null).use { it.flushAndSync() }
        receives.updateProgress(row.fileId, 0L, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now)
    }

    // -- Locking --------------------------------------------------------------------

    /** One file's lock and how many callers hold or wait for it ([locksGuard] guards both). */
    private class FileLock {
        val mutex = Mutex()
        var users = 0
    }

    /** Takes a use of [fileId]'s lock; every call is paired with [releaseLock]. */
    private suspend fun acquireLock(fileId: FileId): FileLock = locksGuard.withLock {
        locks.getOrPut(fileId) { FileLock() }.also { it.users += 1 }
    }

    /** Drops a use; the last one removes the entry, so remote fileIds never accumulate locks. */
    private suspend fun releaseLock(fileId: FileId, lock: FileLock) = locksGuard.withLock {
        lock.users -= 1
        if (lock.users == 0 && locks[fileId] === lock) locks.remove(fileId)
    }

    /** Runs [block] under [fileId]'s lock, then sends what it announced, also when it threw part-way. */
    private suspend inline fun <T> withFile(fileId: FileId, block: (Announcements) -> T): T {
        val lock = acquireLock(fileId)
        val out = Announcements()
        try {
            return lock.mutex.withLock { block(out) }
        } finally {
            withContext(NonCancellable) {
                releaseLock(fileId, lock)
                for (end in out.ends) ends.send(end)
                for (event in out.events) events.emit(event)
            }
        }
    }

    // -- Rows -----------------------------------------------------------------------

    private fun offeredRow(offer: FileManifest, status: FileReceiveStatus, now: Instant) = FileReceiveRow(
        fileId = offer.fileId,
        status = status,
        totalBytes = offer.totalBytes,
        contiguousBytes = 0L,
        blockSize = offer.blockSize,
        blockHashes = offer.blockHashes,
        fileName = offer.fileName,
        mimeType = offer.mimeType,
        hashMismatches = 0,
        originMessageId = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun declinedRow(attachment: MessagePayload.Attachment, originMessageId: Uuid?, now: Instant) =
        FileReceiveRow(
            fileId = attachment.fileId,
            status = FileReceiveStatus.DECLINED,
            totalBytes = attachment.totalBytes,
            contiguousBytes = 0L,
            blockSize = null,
            blockHashes = null,
            fileName = attachment.fileName,
            mimeType = attachment.mimeType,
            hashMismatches = 0,
            originMessageId = originMessageId,
            createdAt = now,
            updatedAt = now,
        )

    private fun receivedDescriptor(row: FileReceiveRow, createdAt: Instant, now: Instant) = FileDescriptor(
        fileId = row.fileId,
        fileName = row.fileName ?: row.fileId.value,
        mimeType = row.mimeType,
        totalBytes = row.totalBytes,
        blockSize = row.blockSize ?: blockSizeFor(row.totalBytes),
        blockHashes = row.blockHashes ?: emptyList(),
        storage = FileStorage.COPY,
        path = paths.final(row.fileId).toString(),
        pathKind = FilePathKind.PATH,
        status = FileDescriptorStatus.HELD,
        createdAt = createdAt,
        lastAccessAt = now,
    )
}
