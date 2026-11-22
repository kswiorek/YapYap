package org.yapyap.routing.file.fetch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.FileFetchAbortReason
import org.yapyap.protocol.FileFetchDeclineReason
import org.yapyap.protocol.FileFetchFrame
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.routing.directory.DeviceDirectory
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.file.port.FileServeGate
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private sealed interface RequesterInput {
    data class Frame(val frame: FileFetchFrame) : RequesterInput
    data object ChannelClosed : RequesterInput
}

private sealed interface Answer {
    data class Ok(val ok: FileFetchFrame.FetchOk) : Answer

    /** The holder's own FETCH_DECLINED: the only answer that counts as a decline. */
    data class Declined(val reason: FileFetchDeclineReason) : Answer

    /**
     * Settled on our side or by the holder giving up, never a decline by the holder: our own
     * refusal or Defer at FETCH_OK, a holder ABORT. [fetch] returns [result] as it is.
     */
    data class Settled(val result: FetchResult) : Answer

    /** The channel closed before any answer: backoff, never a decline. */
    data object Closed : Answer
}

private class RequesterActor(
    val fileId: FileId,
    val device: PeerId,
    val channel: FileChannelId,
    val fromOffset: Long,
) {
    val mailbox = Channel<RequesterInput>(Channel.UNLIMITED)
    val answer = CompletableDeferred<Answer>()
    val result = CompletableDeferred<FetchResult>()
    var nextOffset: Long = fromOffset
    var totalBytes: Long? = null

    /** The last P sent as PROGRESS; read by the frame router to enforce the holder's window. */
    @Volatile
    var lastSentProgress: Long = fromOffset

    /** End of the DATA routed to this actor so far (frame router only). */
    var routedEnd: Long = fromOffset

    /** The channel is gone or the fetch was aborted: a Defer retry stops. */
    @Volatile
    var closed: Boolean = false
}

/**
 * One incoming FETCH channel (design §6.6): serves the file holder side.
 * Sends FETCH_OK then DATA from a [PlaintextStream], staying within the credit window
 * past the last PROGRESS and the transport's buffered threshold. Ends on channel close
 * or after an idle timeout with no PROGRESS.
 */
private class HolderActor(
    val channel: FileChannelId,
    val fileId: FileId,
    val totalBytes: Long,
    @Volatile var lastProgress: Long,
    @Volatile var lastProgressAt: Long,
)

/**
 * Large-path fetch (design §6.6): plain fetch protocol on dedicated WebRTC data channels.
 * No envelopes, no `transferId`, no key, no outbox row.
 *
 * One fetch runs on one channel. The requester ([fetch]) pulls from one holder; each incoming
 * FETCH channel gets a [HolderActor] serving it. State is in memory on both ends and dies with
 * the channel; what is durable is the requester's receive row and request row.
 */
internal class FileFetchService(
    private val devices: DeviceDirectory,
    private val transport: WebRtcTransport,
    private val plaintext: FilePlaintextSource,
    private val ingest: FileIngestPort,
    private val serveGate: FileServeGate,
    private val routerConfig: StateFlow<RouterConfig>,
    /** File bytes per DATA frame. */
    private val frameBytes: StateFlow<Int>,
    private val events: MutableSharedFlow<FileEvent>,
    private val clock: Clock = Clock.System,
) {
    private val lock = Mutex()
    private val requestersByFile = mutableMapOf<FileId, RequesterActor>()
    private val requestersByChannel = mutableMapOf<FileChannelId, RequesterActor>()
    private val holders = mutableMapOf<FileChannelId, HolderActor>()

    /** Channels with a FETCH being gated or served (guarded by [lock]); see [handleFetchRequest]. */
    private val serveClaims = mutableSetOf<FileChannelId>()

    private enum class Claim { CLAIMED, ALREADY, BUSY }
    private var scope: CoroutineScope? = null

    fun runIn(scope: CoroutineScope): Job {
        this.scope = scope
        return scope.launch {
            launch {
                transport.incomingFileFrames.collect { frame ->
                    try {
                        routeFrame(frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.error(
                            component = LogComponent.FILE,
                            event = LogEvent.FILE_TASK_FAILED,
                            message = "Failed to route fetch frame",
                            throwable = e,
                        )
                    }
                }
            }
            launch {
                transport.fileChannelEvents.collect { event ->
                    try {
                        routeEvent(event)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.error(
                            component = LogComponent.FILE,
                            event = LogEvent.FILE_TASK_FAILED,
                            message = "Failed to route fetch channel event",
                            throwable = e,
                        )
                    }
                }
            }
        }
    }

    suspend fun activeCount(): Int = lock.withLock { requestersByFile.size + holders.size }

    /**
     * Pull [fileId] from [peer]'s holder, starting at [fromOffset] (the checkpointed P).
     * Bounded: ends when the channel ends. One fetch per file at a time (the request loop
     * enforces it).
     */
    suspend fun fetch(peer: PeerId, fileId: FileId, fromOffset: Long): FetchResult {
        val channel = try {
            transport.openFileChannel(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Fetch channel open failed",
                fields = mapOf("peer" to peer, "fileId" to fileId),
            )
            return FetchResult.UNANSWERED
        }
        val actor = RequesterActor(fileId, peer, channel, fromOffset)
        val duplicate = lock.withLock {
            // One fetch per file at a time (the request loop enforces it): a racing second
            // fetch backs off, leaving the running one alone.
            if (requestersByFile.containsKey(fileId)) true else {
                requestersByFile[fileId] = actor
                requestersByChannel[channel] = actor
                false
            }
        }
        if (duplicate) {
            closeQuietly(channel)
            return FetchResult.UNANSWERED
        }
        val job = checkNotNull(scope) { "FileFetchService is not running" }.launch { requesterLoop(actor) }
        try {
            try {
                transport.sendFileFrame(channel, FileFetchFrame.Fetch(fileId, fromOffset).encode())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_FETCH_FAILED,
                    message = "Fetch request send failed",
                    fields = mapOf("peer" to peer, "fileId" to fileId),
                )
                finishRequester(actor, FetchResult.UNANSWERED)
                return FetchResult.UNANSWERED
            }
            val answer = withTimeoutOrNull(routerConfig.value.fileRequestAttemptTimeout) {
                actor.answer.await()
            }
            if (answer == null) {
                sendAbort(actor.channel, FileFetchAbortReason.CANCELLED, "fetch answer timeout")
                finishRequester(actor, FetchResult.UNANSWERED)
                return FetchResult.UNANSWERED
            }
            when (answer) {
                is Answer.Declined -> {
                    finishRequester(actor, FetchResult.DECLINED)
                    return FetchResult.DECLINED
                }

                is Answer.Settled -> {
                    finishRequester(actor, answer.result)
                    return answer.result
                }

                is Answer.Closed -> {
                    finishRequester(actor, FetchResult.UNANSWERED)
                    return FetchResult.UNANSWERED
                }

                is Answer.Ok -> Unit
            }
            return actor.result.await()
        } finally {
            job.cancel()
            closeQuietly(channel)
            lock.withLock {
                if (requestersByFile[fileId] === actor) requestersByFile.remove(fileId)
                requestersByChannel.remove(channel)
            }
        }
    }

    /**
     * Abort our live fetch of [fileId] with the real reason (STALLED for the gap trigger,
     * CANCELLED for the user). Aborts only the requester, never our serves of the same file.
     */
    suspend fun abortFetch(fileId: FileId, reason: FileFetchAbortReason) {
        val actor = lock.withLock { requestersByFile[fileId] }
        if (actor != null) {
            sendAbort(actor.channel, reason, null)
            closeRequester(actor)
        }
    }

    // -- Routing ---------------------------------------------------------------

    private suspend fun routeFrame(frame: WebRtcFileFrame) {
        val payload = try {
            FileFetchFrame.decode(frame.payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Ignoring undecodable fetch frame",
            )
            return
        }
        when (payload) {
            is FileFetchFrame.Fetch -> {
                // Our own requester channel never serves: a holder sending FETCH back is ignored.
                if (lock.withLock { requestersByChannel.containsKey(frame.channel) }) return
                // The serve gate does several lookups: off the shared frame collector, so DATA for
                // every fetch keeps flowing meanwhile (the channel claim stops a duplicate FETCH).
                checkNotNull(scope) { "FileFetchService is not running" }.launch {
                    try {
                        handleFetchRequest(frame.channel, payload)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.error(
                            component = LogComponent.FILE,
                            event = LogEvent.FILE_FETCH_FAILED,
                            message = "Fetch serve request failed",
                            throwable = e,
                            fields = mapOf("fileId" to payload.fileId),
                        )
                        closeQuietly(frame.channel)
                    }
                }
            }
            else -> {
                val actor = lock.withLock { requestersByChannel[frame.channel] }
                if (actor != null) {
                    if (payload is FileFetchFrame.Data && overrunsWindow(actor, payload)) return
                    actor.mailbox.trySend(RequesterInput.Frame(payload))
                } else {
                    val holder = lock.withLock { holders[frame.channel] }
                    if (holder != null) {
                        when (payload) {
                            is FileFetchFrame.Progress -> {
                                // Only real progress keeps a holder alive: a requester repeating
                                // its P cannot pin our stream and channel forever.
                                if (payload.contiguousBytes > holder.lastProgress) {
                                    holder.lastProgress = payload.contiguousBytes
                                    holder.lastProgressAt = clock.now().toEpochMilliseconds()
                                }
                            }

                            is FileFetchFrame.Abort -> cleanupHolder(frame.channel)
                            else -> Unit // DATA on a holder channel: not ours, ignore.
                        }
                    }
                    // DATA/ABORT on an unknown channel: the fetch is gone; ignore.
                }
            }
        }
    }

    private suspend fun routeEvent(event: WebRtcFileChannelEvent) {
        when (event) {
            is WebRtcFileChannelEvent.Adding, is WebRtcFileChannelEvent.Active -> Unit
            is WebRtcFileChannelEvent.Removed, is WebRtcFileChannelEvent.Failed -> {
                lock.withLock { requestersByChannel[event.channel] }?.let { closeRequester(it) }
                cleanupHolder(event.channel)
            }
        }
    }

    private fun closeRequester(actor: RequesterActor) {
        actor.closed = true
        actor.mailbox.trySend(RequesterInput.ChannelClosed)
    }

    /**
     * The holder may start a frame at most `fileStreamWindowBytes` past the last PROGRESS
     * (design §6.6). One that starts further out ignores the window: its frames would pile up
     * here without bound, so the channel is closed and the fetch ends like a drop.
     */
    private suspend fun overrunsWindow(actor: RequesterActor, data: FileFetchFrame.Data): Boolean {
        val start = actor.routedEnd
        actor.routedEnd += data.bytes.size
        if (start <= actor.lastSentProgress + routerConfig.value.fileStreamWindowBytes) return false
        AppLog.warn(
            component = LogComponent.FILE,
            event = LogEvent.FILE_FETCH_FAILED,
            message = "Holder overran the fetch window; closing the channel",
            fields = mapOf("peer" to actor.device, "fileId" to actor.fileId, "offset" to start),
        )
        closeRequester(actor)
        closeQuietly(actor.channel)
        return true
    }

    // -- Requester ---------------------------------------------------------------

    /**
     * Safety net around [runRequester]: whatever ends it, [fetch] gets a result, so a failure here
     * never leaves it waiting (or holding a request slot) forever.
     */
    private suspend fun requesterLoop(actor: RequesterActor) {
        try {
            runRequester(actor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Fetch requester failed; ending as unanswered",
                throwable = e,
                fields = mapOf("peer" to actor.device, "fileId" to actor.fileId),
            )
        } finally {
            settle(actor, FetchResult.UNANSWERED)
        }
    }

    private suspend fun runRequester(actor: RequesterActor) {
        for (input in actor.mailbox) {
            when (input) {
                is RequesterInput.ChannelClosed -> {
                    // The close may have raced an in-flight answer frame (separate flows):
                    // give it a short grace window before treating the close as the outcome.
                    // A real Declined/FETCH_OK then still wins; otherwise the drop is UNANSWERED.
                    var answered = false
                    val grace = withTimeoutOrNull(300.milliseconds) {
                        for (queued in actor.mailbox) {
                            when (queued) {
                                is RequesterInput.ChannelClosed -> Unit // extra close, ignore
                                is RequesterInput.Frame -> when (val frame = queued.frame) {
                                    is FileFetchFrame.FetchOk -> {
                                        handleFetchOk(actor, frame)
                                        answered = true
                                        return@withTimeoutOrNull
                                    }

                                    is FileFetchFrame.FetchDeclined -> {
                                        actor.answer.complete(Answer.Declined(frame.reason))
                                        actor.result.complete(FetchResult.DECLINED)
                                        answered = true
                                        return@withTimeoutOrNull
                                    }

                                    is FileFetchFrame.Abort -> {
                                        settle(actor, FetchResult.UNANSWERED)
                                        answered = true
                                        return@withTimeoutOrNull
                                    }

                                    else -> Unit // DATA/PROGRESS/FETCH after close: ignore
                                }
                            }
                            if (actor.result.isCompleted) {
                                answered = true
                                return@withTimeoutOrNull
                            }
                        }
                    }
                    if (!answered) {
                        actor.answer.complete(Answer.Closed)
                        actor.result.complete(FetchResult.UNANSWERED)
                    }
                    return
                }

                is RequesterInput.Frame -> when (val frame = input.frame) {
                    is FileFetchFrame.FetchOk -> handleFetchOk(actor, frame)
                    is FileFetchFrame.FetchDeclined -> {
                        actor.answer.complete(Answer.Declined(frame.reason))
                        actor.result.complete(FetchResult.DECLINED)
                        return
                    }

                    is FileFetchFrame.Data -> handleData(actor, frame)
                    is FileFetchFrame.Abort -> {
                        // The holder gave up (a bad block at its source): back to PENDING.
                        settle(actor, FetchResult.UNANSWERED)
                        return
                    }

                    is FileFetchFrame.Progress -> Unit
                    is FileFetchFrame.Fetch -> Unit
                }
            }
            if (actor.result.isCompleted) return
        }
    }

    private fun settle(actor: RequesterActor, result: FetchResult) {
        actor.answer.complete(Answer.Settled(result))
        actor.result.complete(result)
    }

    private suspend fun handleFetchOk(actor: RequesterActor, frame: FileFetchFrame.FetchOk) {
        // FETCH_OK carries no fileId (one fetch = one channel = one file); the block list is
        // checked against our fileId before a byte is accepted (design §2).
        val verdict = try {
            ingest.checkOffer(
                FileManifest(
                    fileId = actor.fileId,
                    totalBytes = frame.totalBytes,
                    blockSize = frame.blockSize,
                    blockHashes = frame.blockHashes,
                    fileName = frame.fileName,
                    mimeType = frame.mimeType,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch OFFER check failed; ending as unanswered",
                throwable = e,
                fields = mapOf("fileId" to actor.fileId),
            )
            settle(actor, FetchResult.UNANSWERED)
            return
        }
        when (verdict) {
            is FileOfferVerdict.Accept -> {
                actor.totalBytes = frame.totalBytes
                if (!actor.answer.complete(Answer.Ok(frame))) return
                if (frame.totalBytes == 0L || actor.nextOffset >= frame.totalBytes) {
                    actor.result.complete(FetchResult.SATISFIED)
                }
            }

            is FileOfferVerdict.AlreadyReady -> {
                actor.answer.complete(Answer.Ok(frame))
                actor.result.complete(FetchResult.SATISFIED)
            }

            is FileOfferVerdict.Refuse -> when (verdict.reason) {
                // Only a bad block list counts against the holder; a local refusal never does.
                FileRefusalReason.INVALID_OFFER -> {
                    sendAbort(actor.channel, FileFetchAbortReason.CANCELLED, "refused")
                    settle(actor, FetchResult.DECLINED)
                }

                FileRefusalReason.NO_SPACE -> {
                    sendAbort(actor.channel, FileFetchAbortReason.NO_SPACE, null)
                    settle(actor, FetchResult.NO_SPACE)
                }

                FileRefusalReason.NOT_RETRYABLE -> {
                    sendAbort(actor.channel, FileFetchAbortReason.CANCELLED, "refused")
                    settle(actor, FetchResult.ENDED)
                }
            }

            is FileOfferVerdict.Defer -> {
                sendAbort(actor.channel, FileFetchAbortReason.CANCELLED, "deferred")
                settle(actor, FetchResult.UNANSWERED)
            }
        }
    }

    private suspend fun handleData(actor: RequesterActor, frame: FileFetchFrame.Data) {
        if (!actor.answer.isCompleted) return
        if (actor.result.isCompleted) return
        if (frame.bytes.isEmpty()) return
        val total = actor.totalBytes ?: return
        if (actor.nextOffset + frame.bytes.size > total) {
            // More bytes than FETCH_OK announced: the holder is broken; end like a drop.
            sendAbort(actor.channel, FileFetchAbortReason.CANCELLED, "overrun")
            actor.result.complete(FetchResult.UNANSWERED)
            return
        }
        // Defer suspends: retry the same bytes until the engine takes them or the channel goes.
        // The window holds the holder back meanwhile, and no frame is dropped.
        while (!actor.closed) {
            val result = try {
                ingest.append(actor.fileId, actor.nextOffset, frame.bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // P did not move: the next attempt resumes from it.
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Fetch append failed; the next attempt resumes from the checkpoint",
                    throwable = e,
                    fields = mapOf("fileId" to actor.fileId),
                )
                sendAbort(actor.channel, FileFetchAbortReason.CANCELLED, "append failed")
                actor.result.complete(FetchResult.UNANSWERED)
                return
            }
            when (result) {
                is FileAppendResult.Appended -> {
                    actor.nextOffset += frame.bytes.size
                    // PROGRESS names the durable checkpoint P (design §8): only when P moves.
                    if (result.committedBytes > actor.lastSentProgress) {
                        actor.lastSentProgress = result.committedBytes
                        sendProgress(actor.channel, result.committedBytes)
                    }
                    return
                }

                is FileAppendResult.Ready -> {
                    actor.nextOffset += frame.bytes.size
                    sendProgress(actor.channel, total)
                    actor.result.complete(FetchResult.SATISFIED)
                    return
                }

                is FileAppendResult.Mismatch -> {
                    sendAbort(actor.channel, FileFetchAbortReason.CANCELLED, "mismatch")
                    actor.result.complete(FetchResult.MISMATCH)
                    return
                }

                is FileAppendResult.NoSpace -> {
                    sendAbort(actor.channel, FileFetchAbortReason.NO_SPACE, null)
                    // The engine declined the receive; drop the row, never blacklist.
                    actor.result.complete(FetchResult.NO_SPACE)
                    return
                }

                is FileAppendResult.Ended -> {
                    sendAbort(actor.channel, FileFetchAbortReason.CANCELLED, null)
                    actor.result.complete(FetchResult.ENDED)
                    return
                }

                is FileAppendResult.Defer -> delay(DEFER_RETRY)
            }
        }
        actor.result.complete(FetchResult.UNANSWERED)
    }

    private suspend fun finishRequester(actor: RequesterActor, result: FetchResult) {
        settle(actor, result)
        actor.mailbox.close()
        closeQuietly(actor.channel)
        lock.withLock {
            if (requestersByFile[actor.fileId] === actor) requestersByFile.remove(actor.fileId)
            requestersByChannel.remove(actor.channel)
        }
    }

    // -- Holder --------------------------------------------------------------------

    /**
     * A FETCH claims its channel before the gate runs: a repeated FETCH on the same channel is
     * ignored at once, and one peer holds at most [MAX_SERVES_PER_PEER] serves (each an open
     * stream and channel); past that it is told to come back later, never declined.
     */
    private suspend fun handleFetchRequest(channel: FileChannelId, frame: FileFetchFrame.Fetch) {
        val claim = lock.withLock {
            when {
                channel in serveClaims -> Claim.ALREADY
                serveClaims.count { it.peer == channel.peer } >= MAX_SERVES_PER_PEER -> Claim.BUSY
                else -> {
                    serveClaims += channel
                    Claim.CLAIMED
                }
            }
        }
        when (claim) {
            Claim.ALREADY -> return
            Claim.BUSY -> {
                sendAbort(channel, FileFetchAbortReason.CANCELLED, "busy")
                closeQuietly(channel)
                return
            }

            Claim.CLAIMED -> Unit
        }
        var serving = false
        try {
            serving = gateAndServe(channel, frame)
        } finally {
            if (!serving) lock.withLock { serveClaims.remove(channel) }
        }
    }

    /** Runs the serve gate and starts the serve; false when no serve started. */
    private suspend fun gateAndServe(channel: FileChannelId, frame: FileFetchFrame.Fetch): Boolean {
        val peer = channel.peer
        val account = try {
            devices.getAccountIdForDevice(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve gate lookup failed",
                throwable = e,
            )
            null
        }
        if (account == null) {
            sendDeclined(channel, FileFetchDeclineReason.NOT_ALLOWED)
            return false
        }
        // Removed or revoked devices never serve, even when the visibility rule would allow them.
        // Fail closed: a directory failure declines rather than serving an unknown peer.
        val deviceStatus = try {
            devices.getDeviceStatus(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve device-status lookup failed; declining",
                throwable = e,
                fields = mapOf("fileId" to frame.fileId),
            )
            sendDeclined(channel, FileFetchDeclineReason.NOT_ALLOWED)
            return false
        }
        val accountStatus = try {
            devices.getAccountStatus(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve account-status lookup failed; declining",
                throwable = e,
                fields = mapOf("fileId" to frame.fileId),
            )
            sendDeclined(channel, FileFetchDeclineReason.NOT_ALLOWED)
            return false
        }
        if (deviceStatus != IdentityStatus.ACTIVE || accountStatus == IdentityStatus.BANNED) {
            sendDeclined(channel, FileFetchDeclineReason.NOT_ALLOWED)
            return false
        }
        val allowed = try {
            serveGate.canServe(frame.fileId, account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve gate failed",
                throwable = e,
                fields = mapOf("fileId" to frame.fileId),
            )
            false
        }
        if (!allowed) {
            sendDeclined(channel, FileFetchDeclineReason.NOT_ALLOWED)
            return false
        }
        val info = try {
            plaintext.describe(frame.fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch describe failed",
                throwable = e,
                fields = mapOf("fileId" to frame.fileId),
            )
            null
        }
        if (info == null) {
            sendDeclined(channel, FileFetchDeclineReason.NOT_HELD)
            return false
        }
        if (frame.fromOffset > info.totalBytes) {
            sendAbort(channel, FileFetchAbortReason.SOURCE_ERROR, "bad offset")
            closeQuietly(channel)
            return false
        }
        if (!sendFetchOk(channel, info)) {
            closeQuietly(channel)
            return false
        }
        val holder = HolderActor(
            channel = channel,
            fileId = frame.fileId,
            totalBytes = info.totalBytes,
            lastProgress = frame.fromOffset,
            lastProgressAt = clock.now().toEpochMilliseconds(),
        )
        lock.withLock { holders[channel] = holder }
        checkNotNull(scope) { "FileFetchService is not running" }.launch { serveLoop(holder, info, frame.fromOffset) }
        try {
            events.emit(FileEvent.ServeStarted(frame.fileId, account))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Failed to emit fetch serve event",
                throwable = e,
            )
        }
        return true
    }

    private suspend fun serveLoop(holder: HolderActor, info: FileManifest, fromOffset: Long) {
        val stream = try {
            plaintext.open(info.fileId, fromOffset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve open failed",
                throwable = e,
                fields = mapOf("fileId" to info.fileId),
            )
            sendAbort(holder.channel, FileFetchAbortReason.SOURCE_ERROR, null)
            cleanupHolder(holder.channel)
            return
        }
        if (stream == null) {
            sendAbort(holder.channel, FileFetchAbortReason.SOURCE_ERROR, null)
            cleanupHolder(holder.channel)
            return
        }
        try {
            val window = routerConfig.value.fileStreamWindowBytes
            var offset = fromOffset
            while (offset < info.totalBytes) {
                // Idle timeout with no PROGRESS: the requester is gone; end the serve so a
                // parked holder never strands the requester (which is bounded by its channel).
                // Uses the attempt scope (not the receiver gap trigger, which aborts the
                // requester side): a legitimate long Defer keeps PROGRESS flowing via byte
                // checkpoints, so only a dead requester trips this.
                if (clock.now().toEpochMilliseconds() - holder.lastProgressAt >
                    routerConfig.value.fileRequestAttemptTimeout.inWholeMilliseconds
                ) {
                    break
                }
                if (offset > holder.lastProgress + window) {
                    delay(50.milliseconds)
                    continue
                }
                // Bounded like the idle check: a buffer that never drains is a requester that stopped
                // reading, and the idle check above does not run while we wait here.
                val drained = try {
                    withTimeoutOrNull(routerConfig.value.fileRequestAttemptTimeout) {
                        transport.awaitBufferedBelow(holder.channel, FILE_FETCH_BUFFERED_RESUME_BYTES)
                    } != null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Fetch backpressure wait failed; ending serve",
                        throwable = e,
                    )
                    break
                }
                if (!drained) break
                val maxBytes = minOf(frameBytes.value.toLong(), info.totalBytes - offset).toInt().coerceAtLeast(1)
                val read = try {
                    stream.read(maxBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Fetch serve read failed",
                        throwable = e,
                        fields = mapOf("fileId" to info.fileId),
                    )
                    sendAbort(holder.channel, FileFetchAbortReason.SOURCE_ERROR, null)
                    cleanupHolder(holder.channel)
                    return
                }
                when (read) {
                    is PlaintextRead.Bytes -> {
                        if (read.bytes.isEmpty()) {
                            delay(20.milliseconds)
                            continue
                        }
                        val sent = try {
                            transport.sendFileFrame(holder.channel, FileFetchFrame.Data(read.bytes).encode())
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.error(
                                component = LogComponent.FILE,
                                event = LogEvent.FILE_FETCH_FAILED,
                                message = "Failed to send fetch DATA frame; ending serve",
                                throwable = e,
                                fields = mapOf("fileId" to info.fileId),
                            )
                            false
                        }
                        if (!sent) {
                            cleanupHolder(holder.channel)
                            return
                        }
                        offset += read.bytes.size
                    }

                    // The source ended short of totalBytes: the serve ends (cleaned up below).
                    is PlaintextRead.Eof -> break
                    is PlaintextRead.SourceLost -> {
                        sendAbort(holder.channel, FileFetchAbortReason.SOURCE_ERROR, null)
                        cleanupHolder(holder.channel)
                        return
                    }
                }
            }
            // All bytes sent: the requester closes on READY, and the holder parks until then. A
            // requester that stops making progress (or never closes) is reaped by the same idle
            // timeout, so a parked holder never keeps its stream and channel forever. An early
            // break (idle timeout, backpressure failure) ends the serve at once.
            if (offset >= info.totalBytes) {
                while (lock.withLock { holders.containsKey(holder.channel) } && holder.lastProgress < info.totalBytes &&
                    clock.now().toEpochMilliseconds() - holder.lastProgressAt <=
                    routerConfig.value.fileRequestAttemptTimeout.inWholeMilliseconds
                ) {
                    delay(PARKED_CHECK)
                }
            }
            cleanupHolder(holder.channel)
        } finally {
            stream.close()
        }
    }

    /** False when FETCH_OK could not be sent: no serve starts on that channel. */
    private suspend fun sendFetchOk(channel: FileChannelId, info: FileManifest): Boolean {
        return try {
            transport.sendFileFrame(
                channel,
                FileFetchFrame.FetchOk(
                    totalBytes = info.totalBytes,
                    blockSize = info.blockSize,
                    blockHashes = info.blockHashes,
                    fileName = info.fileName,
                    mimeType = info.mimeType,
                ).encode(),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Failed to send FETCH_OK",
                fields = mapOf("fileId" to info.fileId, "error" to e.toString()),
            )
            false
        }
    }

    /** A lost DECLINED still ends the fetch: the channel closes right after. */
    private suspend fun sendDeclined(channel: FileChannelId, reason: FileFetchDeclineReason) {
        sendBestEffort(channel, FileFetchFrame.FetchDeclined(reason).encode(), "FETCH_DECLINED")
        closeQuietly(channel)
    }

    /** A lost ABORT still ends the fetch: every abort is followed by the channel's close. */
    private suspend fun sendAbort(channel: FileChannelId, reason: FileFetchAbortReason, text: String?) {
        sendBestEffort(channel, FileFetchFrame.Abort(reason, text).encode(), "ABORT")
    }

    private suspend fun sendBestEffort(channel: FileChannelId, frame: ByteArray, kind: String) {
        try {
            transport.sendFileFrame(channel, frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Failed to send fetch $kind; the channel is closing anyway",
                fields = mapOf("peer" to channel.peer, "error" to e.message),
            )
        }
    }

    /** The channel may already be gone (the peer closed it, the session dropped). */
    private suspend fun closeQuietly(channel: FileChannelId) {
        try {
            transport.closeFileChannel(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Fetch channel close failed; it is already gone",
                fields = mapOf("peer" to channel.peer, "error" to e.message),
            )
        }
    }

    private suspend fun cleanupHolder(channel: FileChannelId) {
        val removed = lock.withLock {
            serveClaims.remove(channel)
            holders.remove(channel)
        }
        if (removed != null) closeQuietly(channel)
    }

    /** A lost PROGRESS frame only delays the sender's window; the next one carries a later P. */
    private suspend fun sendProgress(channel: FileChannelId, contiguousBytes: Long) {
        try {
            transport.sendFileFrame(channel, FileFetchFrame.Progress(contiguousBytes).encode())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Failed to send fetch PROGRESS",
                fields = mapOf("peer" to channel.peer, "error" to e.message),
            )
        }
    }

    private companion object {
        /** How soon a deferred append (engine still recovering) is retried. */
        val DEFER_RETRY = 100.milliseconds

        /** Serves one peer may hold open at once (each holds a plaintext stream and a channel). */
        const val MAX_SERVES_PER_PEER = 4

        /** How often a holder that sent every byte checks whether its requester is still there. */
        val PARKED_CHECK = 1.seconds
    }
}
