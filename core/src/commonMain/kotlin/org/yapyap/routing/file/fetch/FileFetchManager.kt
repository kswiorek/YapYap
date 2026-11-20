package org.yapyap.routing.file.fetch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.FileFetchAbortReason
import org.yapyap.protocol.envelopes.FileFetchDeclineReason
import org.yapyap.protocol.envelopes.FileFetchFrame
import org.yapyap.routing.directory.DeviceDirectory
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReadResult
import org.yapyap.routing.file.port.FileServeGate
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private data class RequesterState(
    val fileId: FileId,
    val fromOffset: Long,
    var nextOffset: Long,
    var totalBytes: Long? = null,
    val answer: CompletableDeferred<Answer> = CompletableDeferred(),
    val completion: CompletableDeferred<FileFetchOutcome> = CompletableDeferred(),
)

private sealed interface Answer {
    data class Ok(val ok: FileFetchFrame.FetchOk) : Answer
    data class Declined(val reason: FileFetchDeclineReason) : Answer
}

private data class HolderState(
    val fileId: FileId,
    val totalBytes: Long,
    var offset: Long,
    var lastProgress: Long,
    val job: Job,
)

/**
 * Large-path fetch (design §6.6, step 21): plain fetch protocol on a dedicated
 * WebRTC data channel. No envelopes, no `transferId`, no key, no outbox row.
 *
 * The router passes frames straight between the transport's channel and this
 * code; nothing goes through the envelope pipeline. State is in memory on both
 * ends and dies with the channel.
 *
 * Holder side: on FETCH, serve-gate by the session's peer device, FETCH_OK or
 * FETCH_DECLINED, DATA from `fromOffset` through [FilePlaintextSource] within
 * `fileStreamWindowBytes` past the last PROGRESS and the channel's
 * `bufferedAmount` threshold; ABORT on a bad block at the source.
 *
 * Requester side: check the block list against the `fileId` (via
 * [FileIngestPort.checkOffer]) before accepting bytes; hand frames in order to
 * the ingest port; PROGRESS on committed checkpoints; close on READY; ABORT on
 * no space, the gap trigger or a user cancel.
 */
internal class FileFetchManager(
    private val localDeviceId: PeerId,
    private val devices: DeviceDirectory,
    private val transport: WebRtcTransport,
    private val plaintext: FilePlaintextSource,
    private val ingest: FileIngestPort,
    private val serveGate: FileServeGate,
    private val routerConfig: StateFlow<RouterConfig>,
    /** File bytes per DATA frame. */
    private val frameBytes: StateFlow<Int>,
    private val events: MutableSharedFlow<FileEvent>,
) {
    private val lock = Mutex()
    private val requesters = mutableMapOf<FetchKey, RequesterState>()
    private val holders = mutableMapOf<FetchKey, HolderState>()
    private var scope: CoroutineScope? = null
    private var incomingJob: Job? = null
    private var channelEventJob: Job? = null

    fun runIn(scope: CoroutineScope): Job {
        this.scope = scope
        val job = scope.launch {
            launch {
                transport.incomingFileFrames.collect { frame ->
                    val label = frame.channelLabel ?: return@collect
                    runCatching { handleFrame(frame.source, label, frame.payload) }
                }
            }
            launch {
                transport.fileChannelEvents.collect { event ->
                    runCatching { handleChannelEvent(event) }
                }
            }
        }
        incomingJob = job
        return job
    }

    fun activeCount(): Int = requesters.size + holders.size

    /**
     * Requester entry point (Step 22 calls this after `awaitSession`): open a
     * fetch channel to [peer] and pull [fileId] from [fromOffset]. One fetch per
     * file at a time (the request loop enforces it).
     */
    suspend fun fetchFile(peer: PeerId, fileId: FileId, fromOffset: Long): FileFetchOutcome {
        val label = newFetchLabel()
        val key = FetchKey(peer, label)
        val state = RequesterState(fileId = fileId, fromOffset = fromOffset, nextOffset = fromOffset)
        lock.withLock { requesters[key] = state }
        try {
            runCatching { transport.openFileChannel(peer, label) }.onFailure { e ->
                if (e is CancellationException) throw e
                lock.withLock { requesters.remove(key) }
                return FileFetchOutcome.Interrupted
            }
            runCatching {
                transport.sendFileFrame(peer, label, FileFetchFrame.Fetch(fileId, fromOffset).encode())
            }.onFailure { e ->
                if (e is CancellationException) throw e
                closeQuietly(peer, label)
                lock.withLock { requesters.remove(key) }
                return FileFetchOutcome.Interrupted
            }
            val answer = withTimeoutOrNull(routerConfig.value.fileRequestAttemptTimeout) {
                state.answer.await()
            }
            if (answer == null) {
                sendAbortQuietly(peer, label, FileFetchAbortReason.CANCELLED, "fetch answer timeout")
                closeQuietly(peer, label)
                lock.withLock { requesters.remove(key) }
                return FileFetchOutcome.Interrupted
            }
            when (answer) {
                is Answer.Declined -> {
                    closeQuietly(peer, label)
                    lock.withLock { requesters.remove(key) }
                    return FileFetchOutcome.Declined(answer.reason.name)
                }
                is Answer.Ok -> {
                    // Fall through to the DATA loop below; completion is driven by
                    // inbound frames through handleFrame.
                }
            }
            val outcome = try {
                state.completion.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FileFetchOutcome.Interrupted
            }
            closeQuietly(peer, label)
            lock.withLock { requesters.remove(key) }
            return outcome
        } catch (e: CancellationException) {
            closeQuietly(peer, label)
            lock.withLock { requesters.remove(key) }
            throw e
        }
    }

    /** Receiver gap trigger / user cancel (Step 22): ABORT the live fetch of [fileId]. */
    suspend fun abortFetch(fileId: FileId, reason: FileFetchAbortReason) {
        val targets = lock.withLock {
            requesters.entries.filter { it.value.fileId == fileId }.map { it.key }
        }
        for (key in targets) {
            sendAbortQuietly(key.peer, key.channelLabel, reason, null)
            closeQuietly(key.peer, key.channelLabel)
            lock.withLock {
                requesters[key]?.completion?.complete(FileFetchOutcome.Failed(reason.name))
                requesters.remove(key)
            }
        }
        val holderKeys = lock.withLock {
            holders.entries.filter { it.value.fileId == fileId }.map { it.key }
        }
        for (key in holderKeys) {
            sendAbortQuietly(key.peer, key.channelLabel, reason, null)
            closeQuietly(key.peer, key.channelLabel)
            cleanupHolder(key)
        }
    }

    // -- Inbound ---------------------------------------------------------------

    private suspend fun handleFrame(peer: PeerId, label: String, payload: ByteArray) {
        val frame = try {
            FileFetchFrame.decode(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val key = FetchKey(peer, label)
        when (frame) {
            is FileFetchFrame.Fetch -> handleFetchRequest(key, frame)
            is FileFetchFrame.FetchOk -> handleFetchOk(key, frame)
            is FileFetchFrame.FetchDeclined -> handleFetchDeclined(key, frame)
            is FileFetchFrame.Progress -> handleProgress(key, frame)
            is FileFetchFrame.Abort -> handleAbortFrame(key, frame)
            is FileFetchFrame.Data -> handleData(key, frame)
        }
    }

    private suspend fun handleFetchRequest(key: FetchKey, frame: FileFetchFrame.Fetch) {
        // Holder side: serve gate for the session's peer device, then FETCH_OK
        // or FETCH_DECLINED and a DATA stream on this same channel.
        val account = try {
            devices.getAccountIdForDevice(key.peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (account == null) {
            sendDeclined(key, FileFetchDeclineReason.NOT_ALLOWED)
            return
        }
        val allowed = try {
            serveGate.canServe(frame.fileId, account)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!allowed) {
            sendDeclined(key, FileFetchDeclineReason.NOT_ALLOWED)
            return
        }
        val info = try {
            plaintext.describe(frame.fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (info == null) {
            sendDeclined(key, FileFetchDeclineReason.NOT_HELD)
            return
        }
        if (frame.fromOffset > info.totalBytes) {
            sendAbortQuietly(key.peer, key.channelLabel, FileFetchAbortReason.SOURCE_ERROR, "bad offset")
            closeQuietly(key.peer, key.channelLabel)
            return
        }
        val already = lock.withLock { holders.containsKey(key) }
        if (already) return
        val window = routerConfig.value.fileStreamWindowBytes
        val holderJob = scope?.launch {
            runCatching { serveLoop(key, info, frame.fromOffset, window) }
        } ?: return
        lock.withLock {
            holders[key] = HolderState(
                fileId = frame.fileId,
                totalBytes = info.totalBytes,
                offset = frame.fromOffset,
                lastProgress = frame.fromOffset,
                job = holderJob,
            )
        }
        runCatching {
            transport.sendFileFrame(
                key.peer,
                key.channelLabel,
                FileFetchFrame.FetchOk(
                    totalBytes = info.totalBytes,
                    blockSize = info.blockSize,
                    blockHashes = info.blockHashes,
                    fileName = info.fileName,
                    mimeType = info.mimeType,
                ).encode(),
            )
        }.onFailure {
            cleanupHolder(key)
            return
        }
        runCatching { transport.setFileChannelBufferedThreshold(key.peer, key.channelLabel, FILE_FETCH_BUFFERED_RESUME_BYTES) }
        runCatching { events.emit(FileEvent.ServeStarted(frame.fileId, account)) }
    }

    private suspend fun serveLoop(
        key: FetchKey,
        info: FileManifest,
        fromOffset: Long,
        window: Long,
    ) {
        var offset = fromOffset
        while (offset < info.totalBytes) {
            val holder = lock.withLock { holders[key] } ?: return
            val lastProgress = holder.lastProgress
            if (offset > lastProgress + window) {
                delay(50.milliseconds)
                continue
            }
            val buffered = try {
                transport.fileChannelBufferedAmount(key.peer, key.channelLabel) ?: 0L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0L
            }
            if (buffered > FILE_FETCH_BUFFERED_PAUSE_BYTES) {
                delay(50.milliseconds)
                continue
            }
            val maxBytes = minOf(frameBytes.value.toLong(), info.totalBytes - offset).toInt().coerceAtLeast(1)
            when (val read = readAt(info.fileId, offset, maxBytes)) {
                is FileReadResult.Ok -> {
                    if (read.bytes.isEmpty()) {
                        delay(20.milliseconds)
                        continue
                    }
                    val sent = try {
                        transport.sendFileFrame(key.peer, key.channelLabel, FileFetchFrame.Data(read.bytes).encode())
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    if (!sent) return
                    offset += read.bytes.size
                    lock.withLock { holders[key]?.offset = offset }
                    if (read.eof) return
                }
                is FileReadResult.NotHeld, is FileReadResult.SourceLost -> {
                    sendAbortQuietly(key.peer, key.channelLabel, FileFetchAbortReason.SOURCE_ERROR, null)
                    cleanupHolder(key)
                    return
                }
            }
        }
        // All bytes sent: the requester closes on READY. Holder just parks until
        // the close (or a PROGRESS/ABORT) cleans it up.
    }

    private suspend fun readAt(fileId: FileId, offset: Long, maxBytes: Int): FileReadResult {
        return try {
            plaintext.read(fileId, offset, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FileReadResult.NotHeld
        }
    }

    private suspend fun handleFetchOk(key: FetchKey, frame: FileFetchFrame.FetchOk) {
        val state = lock.withLock { requesters[key] } ?: return
        // FETCH_OK carries no fileId (one fetch = one channel = one file);
        // the block list is checked against our fileId before a byte is accepted
        // (design §2): checkOffer refuses INVALID_OFFER when it does not hash.
        val verdict = try {
            ingest.checkOffer(
                FileManifest(
                    fileId = state.fileId,
                    totalBytes = frame.totalBytes,
                    blockSize = frame.blockSize,
                    blockHashes = frame.blockHashes,
                    fileName = frame.fileName,
                    mimeType = frame.mimeType,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FileOfferVerdict.Refuse(org.yapyap.routing.file.port.FileRefusalReason.INVALID_OFFER)
        }
        when (verdict) {
            is FileOfferVerdict.Accept -> {
                lock.withLock { state.totalBytes = frame.totalBytes }
                if (!state.answer.complete(Answer.Ok(frame))) {
                    // Already answered (duplicate FETCH_OK): ignore.
                }
                if (frame.totalBytes == 0L || state.nextOffset >= frame.totalBytes) {
                    state.completion.complete(FileFetchOutcome.Satisfied)
                }
            }
            is FileOfferVerdict.AlreadyReady -> {
                state.answer.complete(Answer.Ok(frame))
                state.completion.complete(FileFetchOutcome.Satisfied)
                closeQuietly(key.peer, key.channelLabel)
            }
            is FileOfferVerdict.Refuse -> {
                sendAbortQuietly(key.peer, key.channelLabel, FileFetchAbortReason.CANCELLED, "refused")
                state.answer.complete(Answer.Declined(FileFetchDeclineReason.NOT_ALLOWED))
                state.completion.complete(FileFetchOutcome.Declined(verdict.reason.name))
            }
            is FileOfferVerdict.Defer -> {
                sendAbortQuietly(key.peer, key.channelLabel, FileFetchAbortReason.CANCELLED, "deferred")
                state.answer.complete(Answer.Declined(FileFetchDeclineReason.NOT_ALLOWED))
                state.completion.complete(FileFetchOutcome.Declined("DEFER"))
            }
        }
    }

    private suspend fun handleFetchDeclined(key: FetchKey, frame: FileFetchFrame.FetchDeclined) {
        val state = lock.withLock { requesters[key] } ?: run {
            closeQuietly(key.peer, key.channelLabel)
            return
        }
        state.answer.complete(Answer.Declined(frame.reason))
        state.completion.complete(FileFetchOutcome.Declined(frame.reason.name))
    }

    private suspend fun handleProgress(key: FetchKey, frame: FileFetchFrame.Progress) {
        lock.withLock {
            holders[key]?.lastProgress = maxOf(holders[key]?.lastProgress ?: 0L, frame.contiguousBytes)
        }
        // PROGRESS on a requester channel is unexpected; ignore.
    }

    private suspend fun handleAbortFrame(key: FetchKey, frame: FileFetchFrame.Abort) {
        lock.withLock {
            requesters[key]?.completion?.complete(FileFetchOutcome.Failed(frame.reason.name))
        }
        cleanupHolder(key)
        closeQuietly(key.peer, key.channelLabel)
    }

    private suspend fun handleData(key: FetchKey, frame: FileFetchFrame.Data) {
        val state = lock.withLock { requesters[key] } ?: return
        if (!state.answer.isCompleted) {
            // DATA before FETCH_OK: sender violated §3.5 ordering; drop it.
            return
        }
        if (state.completion.isCompleted) return
        if (frame.bytes.isEmpty()) return
        val total = state.totalBytes ?: return
        if (state.nextOffset >= total) return
        val result = try {
            ingest.ingest(state.fileId, state.nextOffset, frame.bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FileIngestResult.Refused
        }
        when (result) {
            is FileIngestResult.Staged -> {
                state.nextOffset = minOf(state.nextOffset + frame.bytes.size, total)
                // PROGRESS names the durable checkpoint P (design §8): the ingest
                // result carries the new P, sent once the fsync + commit are done.
                runCatching {
                    transport.sendFileFrame(
                        key.peer,
                        key.channelLabel,
                        FileFetchFrame.Progress(result.contiguousBytes).encode(),
                    )
                }
                if (result.contiguousBytes >= result.totalBytes) {
                    state.completion.complete(FileFetchOutcome.Satisfied)
                }
            }
            is FileIngestResult.Duplicate -> {
                state.nextOffset = minOf(state.nextOffset + frame.bytes.size, total)
            }
            is FileIngestResult.Defer -> {
                // Staging bound: silent; the holder's window closes and its retry
                // resumes once P advances (same backpressure as §6.2/§8).
            }
            is FileIngestResult.Refused -> {
                sendAbortQuietly(key.peer, key.channelLabel, FileFetchAbortReason.NO_SPACE, null)
                state.completion.complete(FileFetchOutcome.Failed("REFUSED"))
            }
        }
    }

    private suspend fun handleChannelEvent(event: WebRtcFileChannelEvent) {
        val key = FetchKey(event.peer, event.channelLabel)
        when (event) {
            is WebRtcFileChannelEvent.Removed, is WebRtcFileChannelEvent.Failed -> {
                lock.withLock {
                    requesters[key]?.completion?.complete(FileFetchOutcome.Interrupted)
                    requesters.remove(key)
                }
                cleanupHolder(key)
            }
            is WebRtcFileChannelEvent.BufferedLow -> {
                // Holder poll loop observes the new bufferedAmount on its next pass.
            }
            is WebRtcFileChannelEvent.Adding, is WebRtcFileChannelEvent.Active -> Unit
        }
    }

    private suspend fun sendDeclined(key: FetchKey, reason: FileFetchDeclineReason) {
        runCatching {
            transport.sendFileFrame(key.peer, key.channelLabel, FileFetchFrame.FetchDeclined(reason).encode())
        }
        closeQuietly(key.peer, key.channelLabel)
    }

    private suspend fun sendAbortQuietly(peer: PeerId, label: String, reason: FileFetchAbortReason, text: String?) {
        runCatching {
            transport.sendFileFrame(peer, label, FileFetchFrame.Abort(reason, text).encode())
        }
    }

    private suspend fun closeQuietly(peer: PeerId, label: String) {
        runCatching { transport.closeFileChannel(peer, label) }
    }

    private suspend fun cleanupHolder(key: FetchKey) {
        val job = lock.withLock {
            holders.remove(key)?.job
        }
        runCatching { job?.cancel() }
    }
}
