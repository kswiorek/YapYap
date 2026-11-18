package org.yapyap.orchestrator.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.Sha256Hasher
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.protocol.FileId
import org.yapyap.protocol.blockSizeFor
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.routing.file.FileCarrier
import org.yapyap.routing.file.FileEvent
import org.yapyap.routing.file.FileFailureReason
import org.yapyap.routing.file.FileIngestPort
import org.yapyap.routing.file.FileIngestResult
import org.yapyap.routing.file.FileOfferInfo
import org.yapyap.routing.file.FileOfferVerdict
import org.yapyap.routing.file.FileRefusalReason
import org.yapyap.routing.router.RouterConfig
import kotlin.time.Clock
import kotlin.time.Instant

private data class RunningHash(val blockIndex: Int, val hasher: Sha256Hasher)

/**
 * The receive engine (design §8): one engine for both classes — the small/large distinction
 * is send-side path policy, the receiver never branches on it.
 *
 * - **Byte coverage is the whole model.** Chunks from any number of transfers, with any
 *   chunkings, land their byte ranges; fully-covered ranges are duplicates and are dropped.
 * - **Durable-before-ACK, checkpointed.** Staged ranges are recorded in one DB transaction
 *   before the chunk is ACKed; P advances only at checkpoints (every `fileCheckpointBytes`,
 *   every `fileCheckpointInterval`, and at completion): append what is drainable, fsync the
 *   `.part` once, then advance `contiguous_bytes` and delete the drained staged rows in one
 *   DB transaction.
 * - **P is never committed past an unverified block**: every block below P has matched its
 *   hash. A mismatch truncates to the block's start and CANCELs the live transfers.
 */
internal class DefaultFileReceiveEngine(
    private val receives: FileReceiveStore,
    private val descriptors: FileDescriptorStore,
    private val messages: MessageRepository,
    private val crypto: CryptoProvider,
    private val files: FileAccess,
    private val storeDirectory: Path,
    private val incomingDirectory: Path,
    private val config: FileServiceConfig,
    private val fileStore: FileStore,
    private val carrier: FileCarrier,
    private val routerConfig: StateFlow<RouterConfig>,
    private val clock: Clock = Clock.System,
) : FileIngestPort {
    private val lock = Mutex()
    private val runningHashes = mutableMapOf<FileId, RunningHash>()

    fun start(scope: CoroutineScope) {
        scope.launch { recover(clock.now()) }
        scope.launch {
            while (true) {
                delay(config.fileCheckpointInterval)
                runCatching { checkpoint() }
                runCatching { sweep() }
            }
        }
    }

    // -- FileIngestPort ------------------------------------------------------------

    override suspend fun checkOffer(offer: FileOfferInfo): FileOfferVerdict = lock.withLock {
        val now = clock.now()
        val existing = try {
            receives.find(offer.fileId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE)
        }
        if (existing != null) {
            return when (existing.status) {
                FileReceiveStatus.READY -> FileOfferVerdict.AlreadyReady
                FileReceiveStatus.FAILED,
                FileReceiveStatus.DECLINED,
                FileReceiveStatus.CANCELLED,
                FileReceiveStatus.EVICTED,
                -> FileOfferVerdict.Refuse(FileRefusalReason.NOT_RETRYABLE)

                FileReceiveStatus.PENDING,
                FileReceiveStatus.IN_FLIGHT,
                FileReceiveStatus.STALLED,
                -> {
                    if (existing.totalBytes != offer.totalBytes ||
                        !FileIds.checkBlockList(
                            crypto, offer.fileId, offer.totalBytes, offer.blockSize, offer.blockHashes,
                        )
                    ) {
                        return FileOfferVerdict.Refuse(FileRefusalReason.INVALID_OFFER)
                    }
                    ensurePart(existing)
                    FileOfferVerdict.Accept
                }
            }
        }
        if (!FileIds.checkBlockList(crypto, offer.fileId, offer.totalBytes, offer.blockSize, offer.blockHashes)) {
            return FileOfferVerdict.Refuse(FileRefusalReason.INVALID_OFFER)
        }
        if (files.freeSpaceBytes(incomingDirectory) < offer.totalBytes) {
            return FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE)
        }
        val partPath = partPathFor(offer.fileId)
        val finalPath = finalPathFor(offer.fileId)
        try {
            files.createDirectories(incomingDirectory)
            files.createDirectories(storeDirectory)
            // Reserve the .part (empty; the free-space check above is the reservation).
            files.openAppendWriter(partPath, null).use { it.flushAndSync() }
            receives.upsert(
                FileReceiveRow(
                    fileId = offer.fileId,
                    status = FileReceiveStatus.IN_FLIGHT,
                    totalBytes = offer.totalBytes,
                    contiguousBytes = 0L,
                    blockSize = offer.blockSize,
                    blockHashes = offer.blockHashes,
                    fileName = offer.fileName,
                    mimeType = offer.mimeType,
                    hashMismatches = 0,
                    partPath = partPath.toString(),
                    finalPath = finalPath.toString(),
                    originMessageId = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            runningHashes[offer.fileId] = RunningHash(0, crypto.sha256Hasher())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.error(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.MESSAGE_DECODE_FAILED,
                message = "Failed to reserve receive state; refusing OFFER",
                throwable = e,
                fields = mapOf("fileId" to offer.fileId),
            )
            return FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE)
        }
        FileOfferVerdict.Accept
    }

    override suspend fun ingest(fileId: FileId, byteOffset: Long, plaintext: ByteArray): FileIngestResult {
        if (plaintext.isEmpty()) return FileIngestResult.Duplicate
        return lock.withLock {
            val now = clock.now()
            val row = try {
                receives.find(fileId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return FileIngestResult.Refused
            } ?: return FileIngestResult.Refused
            when (row.status) {
                FileReceiveStatus.READY -> return FileIngestResult.Duplicate
                FileReceiveStatus.FAILED,
                FileReceiveStatus.DECLINED,
                FileReceiveStatus.CANCELLED,
                FileReceiveStatus.EVICTED,
                -> return FileIngestResult.Refused

                FileReceiveStatus.PENDING,
                FileReceiveStatus.IN_FLIGHT,
                FileReceiveStatus.STALLED,
                -> Unit
            }
            if (byteOffset < 0 || byteOffset >= row.totalBytes) return FileIngestResult.Refused
            val end = minOf(byteOffset + plaintext.size, row.totalBytes)
            if (end <= row.contiguousBytes) return FileIngestResult.Duplicate
            val staged: List<StagedRange> = try {
                receives.stagedByFile(fileId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return FileIngestResult.Refused
            }
            val uncovered = subtractUncovered(maxOf(byteOffset, row.contiguousBytes), end, byteOffset, plaintext, staged)
            if (uncovered.isEmpty()) return FileIngestResult.Duplicate
            if (!extendsPrefix(row.contiguousBytes, staged, uncovered)) {
                val stagedTotal = try {
                    receives.stagedBytes(fileId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return FileIngestResult.Refused
                }
                if (stagedTotal + uncovered.sumOf { it.second.size.toLong() } > config.maxStagedBytes) {
                    return FileIngestResult.Defer
                }
            }
            try {
                for ((start, bytes) in uncovered) {
                    receives.stageChunk(fileId, StagedRange(start, bytes.size.toLong(), bytes))
                }
                if (row.status != FileReceiveStatus.IN_FLIGHT) {
                    receives.updateStatus(fileId, FileReceiveStatus.IN_FLIGHT, now)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return FileIngestResult.Refused
            }
            if (staged.sumOf { it.length } + uncovered.sumOf { it.second.size.toLong() } >= config.fileCheckpointBytes) {
                checkpointLocked(now)
            }
            val current = try {
                receives.find(fileId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } ?: row
            FileIngestResult.Staged(current.contiguousBytes, current.totalBytes)
        }
    }

    // -- Checkpoints, restart, retention --------------------------------------------

    /** Append what is drainable, fsync once, advance P and drop drained rows in one DB txn. */
    suspend fun checkpoint(now: Instant = clock.now()) {
        lock.withLock { checkpointLocked(now) }
    }

    private suspend fun checkpointLocked(now: Instant) {
        val rows = try {
            receives.active()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.error(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.MESSAGE_DECODE_FAILED,
                message = "Checkpoint listing failed; will retry",
                throwable = e,
            )
            return
        }
        for (row in rows) {
            try {
                drainRow(row, now)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.error(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.MESSAGE_DECODE_FAILED,
                    message = "Checkpoint drain failed; will retry",
                    throwable = e,
                    fields = mapOf("fileId" to row.fileId),
                )
            }
            val fresh = try {
                receives.find(row.fileId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } ?: continue
            if (fresh.status != FileReceiveStatus.READY && fresh.contiguousBytes >= fresh.totalBytes) {
                completeRow(fresh, now)
            }
        }
    }

    /** Delete READY rows no message references past the transfer TTL, with their files. */
    suspend fun sweep(now: Instant = clock.now()) {
        lock.withLock {
            val ready = try {
                receives.ready()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return
            }
            for (row in ready) {
                if (row.originMessageId != null) continue
                val referencing = try {
                    messages.findMessageIdsByFileId(row.fileId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    continue
                }
                if (referencing.isNotEmpty()) {
                    runCatching { receives.updateOrigin(row.fileId, referencing.first(), now) }
                    continue
                }
                if (row.createdAt + routerConfig.value.fileTransferTtl <= now) {
                    runCatching { files.delete(Path(row.finalPath)) }
                    runCatching { files.delete(Path(row.partPath)) }
                    runCatching { descriptors.deleteById(row.fileId) }
                    runCatching { receives.delete(row.fileId) }
                    AppLog.info(
                        component = LogComponent.ORCHESTRATOR,
                        event = LogEvent.MESSAGE_FETCHED,
                        message = "Deleted unreferenced receive past TTL",
                        fields = mapOf("fileId" to row.fileId),
                    )
                }
            }
        }
    }

    // -- Internals -------------------------------------------------------------------

    private suspend fun drainRow(row: FileReceiveRow, now: Instant) {
        val blockSize = row.blockSize ?: return
        val blockHashes = row.blockHashes ?: return
        val staged = try {
            receives.stagedByFile(row.fileId).sortedBy { it.startOffset }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return
        }
        // Contiguous drainable prefix from P.
        var cursor = row.contiguousBytes
        val drain = mutableListOf<Pair<Long, ByteArray>>()
        for (range in staged) {
            if (range.startOffset > cursor) break
            if (range.startOffset + range.length <= cursor) continue
            val skip = (cursor - range.startOffset).toInt()
            drain += cursor to range.plaintext.copyOfRange(skip, range.plaintext.size)
            cursor = range.startOffset + range.length
        }
        if (cursor > row.totalBytes) cursor = row.totalBytes
        // Nothing new since P — but a zero-byte file (or a fully-covered P) still needs
        // its completion pass; the caller decides that from the fresh row.
        if (cursor == row.contiguousBytes && (row.totalBytes > 0 || staged.isNotEmpty())) return

        val partPath = Path(row.partPath)
        var mismatchBlock: Int? = null
        try {
            files.openAppendWriter(partPath, null).use { writer ->
                if (writer.size() != row.contiguousBytes) {
                    if (writer.size() > row.contiguousBytes) {
                        writer.truncate(row.contiguousBytes)
                    } else {
                        throw IllegalStateException("torn .part shorter than P for ${row.fileId}")
                    }
                }
                var pos = row.contiguousBytes
                var running = runningHashes[row.fileId]
                    ?.takeIf { it.blockIndex == (pos / blockSize).toInt() }
                    ?: freshRunningHash(partPath, pos, blockSize)
                for ((_, bytes) in drain) {
                    var i = 0
                    while (i < bytes.size) {
                        val blockEnd = (pos / blockSize + 1) * blockSize.toLong()
                        val take = minOf(bytes.size - i, (blockEnd - pos).toInt())
                        running.hasher.update(bytes, i, take)
                        writer.write(bytes, i, take)
                        pos += take
                        i += take
                        if (pos == blockEnd || pos == row.totalBytes) {
                            val blockIndex = ((pos - 1) / blockSize).toInt()
                            if (!running.hasher.digest().contentEquals(blockHashes[blockIndex])) {
                                mismatchBlock = blockIndex
                                break
                            }
                            if (pos < row.totalBytes) {
                                running = RunningHash(blockIndex + 1, crypto.sha256Hasher())
                            }
                        }
                    }
                    if (mismatchBlock != null) break
                }
                if (mismatchBlock == null) {
                    writer.flushAndSync()
                    // Carry the partial-block state forward when P stopped mid-block.
                    runningHashes[row.fileId] =
                        if (pos % blockSize == 0L) RunningHash((pos / blockSize).toInt(), crypto.sha256Hasher())
                        else running
                } else {
                    val blockStart = mismatchBlock!! * blockSize.toLong()
                    writer.truncate(blockStart)
                    writer.flushAndSync()
                    runningHashes.remove(row.fileId)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.error(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.MESSAGE_DECODE_FAILED,
                message = "Checkpoint append failed; failing receive cleanly",
                throwable = e,
                fields = mapOf("fileId" to row.fileId),
            )
            runningHashes.remove(row.fileId)
            failReceive(row, FileFailureReason.NO_SPACE, now)
            return
        }

        val mismatchAt = mismatchBlock
        if (mismatchAt != null) {
            val blockStart = mismatchAt * blockSize.toLong()
            runCatching { receives.deleteStagedFrom(row.fileId, blockStart) }
            val mismatches = row.hashMismatches + 1
            if (mismatches > config.fileHashRetryLimit) {
                runCatching {
                    receives.updateProgress(row.fileId, blockStart, mismatches, FileReceiveStatus.FAILED, now)
                }
                failReceive(row, FileFailureReason.FAILED, now)
            } else {
                runCatching {
                    receives.updateProgress(row.fileId, blockStart, mismatches, FileReceiveStatus.STALLED, now)
                }
                runCatching { carrier.cancelIncoming(row.fileId, FileCancelReason.ABORT) }
            }
            return
        }

        try {
            // Drop fully-drained rows and trim a row straddling the new P.
            receives.deleteStagedBelow(row.fileId, cursor)
            val straddling = receives.stagedByFile(row.fileId)
                .firstOrNull { it.startOffset < cursor && it.startOffset + it.length > cursor }
            if (straddling != null) {
                val skip = (cursor - straddling.startOffset).toInt()
                receives.trimStagedRow(
                    row.fileId,
                    straddling.startOffset,
                    cursor,
                    straddling.length - skip,
                    straddling.plaintext.copyOfRange(skip, straddling.plaintext.size),
                )
            }
            receives.updateProgress(row.fileId, cursor, row.hashMismatches, row.status, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.error(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.MESSAGE_DECODE_FAILED,
                message = "Checkpoint commit failed after a durable append; will re-drain",
                throwable = e,
                fields = mapOf("fileId" to row.fileId),
            )
        }
    }

    /** Running hash for the block containing [pos], re-reading the committed prefix. */
    private suspend fun freshRunningHash(partPath: Path, pos: Long, blockSize: Int): RunningHash {
        val hasher = crypto.sha256Hasher()
        val blockStart = pos / blockSize * blockSize
        if (pos > blockStart) {
            files.openPositionedReader(partPath).use { reader ->
                val prefix = ByteArray((pos - blockStart).toInt())
                var off = 0
                while (off < prefix.size) {
                    val n = reader.readAt(blockStart + off, prefix, off, prefix.size - off)
                    if (n <= 0) break
                    off += n
                }
                hasher.update(prefix, 0, off)
            }
        }
        return RunningHash((pos / blockSize).toInt(), hasher)
    }

    private suspend fun completeRow(row: FileReceiveRow, now: Instant) {
        // Idempotent: a crash between the atomic rename and the READY commit restarts here
        // with the final file already in place.
        if (files.fileSize(Path(row.finalPath)) == null) {
            try {
                files.move(Path(row.partPath), Path(row.finalPath))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.error(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.MESSAGE_DECODE_FAILED,
                    message = "READY rename failed; failing receive cleanly",
                    throwable = e,
                    fields = mapOf("fileId" to row.fileId),
                )
                failReceive(row, FileFailureReason.FAILED, now)
                return
            }
        }
        val descriptor = FileDescriptor(
            fileId = row.fileId,
            fileName = row.fileName ?: row.fileId.value,
            mimeType = row.mimeType,
            totalBytes = row.totalBytes,
            blockSize = row.blockSize ?: blockSizeFor(row.totalBytes),
            blockHashes = row.blockHashes ?: emptyList(),
            storage = FileStorage.COPY,
            path = row.finalPath,
            status = FileDescriptorStatus.HELD,
            createdAt = now,
        )
        try {
            descriptors.upsert(descriptor)
            receives.deleteStagedByFile(row.fileId)
            receives.updateStatus(row.fileId, FileReceiveStatus.READY, now)
            runningHashes.remove(row.fileId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.error(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.MESSAGE_DECODE_FAILED,
                message = "READY commit failed after atomic rename; will retry",
                throwable = e,
                fields = mapOf("fileId" to row.fileId),
            )
            return
        }
        runCatching { fileStore.enforceQuota() }
        try {
            carrier.fileReady(row.fileId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.error(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.MESSAGE_DECODE_FAILED,
                message = "COMPLETE fan-out failed after READY; will retry on restart",
                throwable = e,
                fields = mapOf("fileId" to row.fileId),
            )
        }
    }

    private suspend fun failReceive(row: FileReceiveRow, reason: FileFailureReason, now: Instant) {
        runCatching { receives.updateStatus(row.fileId, FileReceiveStatus.FAILED, now) }
        runCatching { carrier.cancelIncoming(row.fileId, FileCancelReason.ABORT) }
        runCatching { carrier.emit(FileEvent.Failed(row.fileId, target = null, reason = reason)) }
    }

    private suspend fun ensurePart(row: FileReceiveRow) {
        val partPath = Path(row.partPath)
        if (files.fileSize(partPath) == null) {
            runCatching { files.openAppendWriter(partPath, null).use { } }
            runCatching { receives.deleteStagedByFile(row.fileId) }
            runCatching {
                receives.updateProgress(row.fileId, 0L, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, clock.now())
            }
            runningHashes[row.fileId] = RunningHash(0, crypto.sha256Hasher())
        }
    }

    private suspend fun recover(now: Instant) {
        for (row in activeRows()) {
            val partPath = Path(row.partPath)
            val size = try {
                files.fileSize(partPath)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (size == null) {
                // .part gone: prefix bytes are lost; restart from zero (the pull path recovers).
                AppLog.warn(
                    component = LogComponent.ORCHESTRATOR,
                    event = LogEvent.MESSAGE_FETCHED,
                    message = "Receive .part missing at restart; resetting coverage",
                    fields = mapOf("fileId" to row.fileId),
                )
                runningHashes.remove(row.fileId)
                runCatching { receives.deleteStagedByFile(row.fileId) }
                runCatching { files.openAppendWriter(partPath, null).use { } }
                runCatching {
                    receives.updateProgress(row.fileId, 0L, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now)
                }
                runningHashes[row.fileId] = RunningHash(0, crypto.sha256Hasher())
                continue
            }
            if (size > row.contiguousBytes) {
                // Torn append is cut away; staged rows drain again.
                runCatching { files.openAppendWriter(partPath, row.contiguousBytes).use { } }
            }
            if (size < row.contiguousBytes) {
                runningHashes.remove(row.fileId)
                runCatching { receives.deleteStagedByFile(row.fileId) }
                runCatching {
                    receives.updateProgress(row.fileId, 0L, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now)
                }
                runningHashes[row.fileId] = RunningHash(0, crypto.sha256Hasher())
                continue
            }
            // Re-hash the partial block at P.
            val blockSize = row.blockSize
            if (blockSize == null) {
                runningHashes[row.fileId] = RunningHash(0, crypto.sha256Hasher())
                continue
            }
            try {
                runningHashes[row.fileId] = freshRunningHash(partPath, row.contiguousBytes, blockSize)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                runningHashes.remove(row.fileId)
                runCatching { receives.deleteStagedByFile(row.fileId) }
                runCatching {
                    receives.updateProgress(row.fileId, 0L, row.hashMismatches, FileReceiveStatus.IN_FLIGHT, now)
                }
                runningHashes[row.fileId] = RunningHash(0, crypto.sha256Hasher())
            }
        }
        // A READY row restarts with COMPLETE already due: re-run the fan-out (idempotent —
        // incoming rows are gone, so nothing is re-sent) and re-emit Ready.
        val ready = try {
            receives.ready()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
        for (row in ready) {
            runCatching { carrier.fileReady(row.fileId) }
        }
    }

    private suspend fun activeRows(): List<FileReceiveRow> = try {
        receives.active()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AppLog.error(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.MESSAGE_DECODE_FAILED,
            message = "Failed to list active receives",
            throwable = e,
        )
        emptyList()
    }

    private fun partPathFor(fileId: FileId): Path =
        Path(incomingDirectory.toString(), "${fileId.value}.part")

    private fun finalPathFor(fileId: FileId): Path =
        Path(storeDirectory.toString(), fileId.value)

    private fun stagedBytesAfter(
        staged: List<StagedRange>,
        uncovered: List<Pair<Long, ByteArray>>,
    ): Long = staged.sumOf { it.length } + uncovered.sumOf { it.second.size.toLong() }

    /** Slices of [bytes] (covering [offset, offset + bytes.size)) neither below [floor] nor staged. */
    private fun subtractUncovered(
        floor: Long,
        end: Long,
        offset: Long,
        bytes: ByteArray,
        staged: List<StagedRange>,
    ): List<Pair<Long, ByteArray>> {
        val out = mutableListOf<Pair<Long, ByteArray>>()
        var cursor = maxOf(offset, floor)
        for (range in staged.sortedBy { it.startOffset }) {
            if (range.startOffset + range.length <= cursor) continue
            if (range.startOffset >= end) break
            if (range.startOffset > cursor) {
                val gapEnd = minOf(range.startOffset, end)
                val srcOff = (cursor - offset).toInt()
                out += cursor to bytes.copyOfRange(srcOff, srcOff + (gapEnd - cursor).toInt())
                cursor = gapEnd
            }
            if (range.startOffset <= cursor) {
                cursor = maxOf(cursor, minOf(range.startOffset + range.length, end))
            }
            if (cursor >= end) break
        }
        if (cursor < end) {
            val srcOff = (cursor - offset).toInt()
            out += cursor to bytes.copyOfRange(srcOff, srcOff + (end - cursor).toInt())
        }
        return out
    }

    /** Would adding [uncovered] to [staged] extend the contiguous prefix at [prefix]? */
    private fun extendsPrefix(
        prefix: Long,
        staged: List<StagedRange>,
        uncovered: List<Pair<Long, ByteArray>>,
    ): Boolean {
        var cursor = prefix
        val all = (staged.map { it.startOffset to it.length } + uncovered.map { it.first to it.second.size.toLong() })
            .sortedBy { it.first }
        for ((start, length) in all) {
            if (start > cursor) break
            cursor = maxOf(cursor, start + length)
            if (cursor > prefix) return true
        }
        return cursor > prefix
    }
}
