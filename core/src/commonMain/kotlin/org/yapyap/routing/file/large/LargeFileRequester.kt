package org.yapyap.routing.file.large

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileDeclineReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.LargeFileResult
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId
import kotlin.concurrent.Volatile

/** What the mux hands one requester session. */
internal sealed interface RequesterInput {
    data class Frame(val frame: LargeFileFrame) : RequesterInput
    data object ChannelClosed : RequesterInput
}

/** The holder's answer to our PULL. */
internal sealed interface RequesterAnswer {
    data class Ok(val ok: LargeFileFrame.PullOk) : RequesterAnswer

    /** The holder's own DECLINED: the only answer that counts as a decline. */
    data class Declined(val reason: LargeFileDeclineReason) : RequesterAnswer

    /**
     * Settled on our side or by the holder giving up, never a decline by the holder: our own
     * refusal or Defer at PULL_OK, a holder ABORT. `fetch` returns [result] as it is.
     */
    data class Settled(val result: LargeFileResult) : RequesterAnswer

    /** The channel closed before any answer: backoff, never a decline. */
    data object Closed : RequesterAnswer
}

/**
 * One outgoing pull's state, owned by [LargeFileRequester]: the mailbox the mux feeds, the answer
 * and result, the checkpointed offsets, and the credit-window bookkeeping (design §6.6).
 */
internal class RequesterSession(
    val fileId: FileId,
    val device: PeerId,
    val channel: FileChannelId,
    fromOffset: Long,
    private val routerConfig: StateFlow<RouterConfig>,
    private val io: LargeFileChannelIo,
) {
    val mailbox = Channel<RequesterInput>(Channel.UNLIMITED)
    val answer = CompletableDeferred<RequesterAnswer>()
    val result = CompletableDeferred<LargeFileResult>()
    var nextOffset: Long = fromOffset
    var totalBytes: Long? = null

    /** The last P sent as PROGRESS; read by [onFrame] to enforce the holder's window. */
    @Volatile
    var lastSentProgress: Long = fromOffset

    /** End of the DATA routed to this session so far (the mux's frame collector only). */
    var routedEnd: Long = fromOffset

    /** The channel is gone or the fetch was aborted: a Defer retry stops. */
    @Volatile
    var closed: Boolean = false

    /** The holder may start a frame at most the configured window past the last PROGRESS. */
    suspend fun onFrame(payload: LargeFileFrame) {
        if (payload is LargeFileFrame.Data) {
            val start = routedEnd
            routedEnd += payload.bytes.size
            if (start > lastSentProgress + routerConfig.value.largeFileStreamWindowBytes) {
                AppLog.warn(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_FETCH_FAILED,
                    message = "Holder overran the fetch window; closing the channel",
                    fields = mapOf("peer" to device, "fileId" to fileId, "offset" to start),
                )
                closeInput()
                io.closeQuietly(channel)
                return
            }
        }
        mailbox.trySend(RequesterInput.Frame(payload))
    }

    fun onChannelGone() {
        closeInput()
    }

    /** The channel is gone or the fetch was aborted: the loop's Defer retry must stop. */
    fun closeInput() {
        closed = true
        mailbox.trySend(RequesterInput.ChannelClosed)
    }
}

/**
 * The large path's requester (design §6.6): opens the file channel, sends PULL, checks PULL_OK's
 * block list against our fileId, appends DATA at the checkpointed offset and reports PROGRESS,
 * and ends with the channel. [abort] ends only our requester, never our serves of the same file.
 */
internal class LargeFileRequester(
    private val transport: WebRtcTransport,
    private val ingest: FileIngestPort,
    private val routerConfig: StateFlow<RouterConfig>,
    private val mux: LargeFileMux,
    private val io: LargeFileChannelIo,
) {
    @Volatile
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Pull [fileId] from [peer]'s holder, starting at [fromOffset] (the checkpointed P).
     * Bounded: ends when the channel ends. One fetch per file at a time (the request loop
     * enforces it).
     */
    suspend fun fetch(peer: PeerId, fileId: FileId, fromOffset: Long): LargeFileResult {
        val channel = try {
            transport.openFileChannel(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppLog.warn(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Fetch channel open failed",
                fields = mapOf("peer" to peer, "fileId" to fileId),
            )
            return LargeFileResult.UNANSWERED
        }
        val session = RequesterSession(fileId, peer, channel, fromOffset, routerConfig, io)
        if (!mux.registerRequester(session)) {
            // One fetch per file at a time (the request loop enforces it): a racing second
            // fetch backs off, leaving the running one alone.
            io.closeQuietly(channel)
            return LargeFileResult.UNANSWERED
        }
        val job = checkNotNull(scope) { "LargeFileRequester is not running" }.launch { requesterLoop(session) }
        try {
            try {
                transport.sendFileFrame(channel, LargeFileFrame.Pull(fileId, fromOffset).encode())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AppLog.warn(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_FETCH_FAILED,
                    message = "Fetch request send failed",
                    fields = mapOf("peer" to peer, "fileId" to fileId),
                )
                finishRequester(session, LargeFileResult.UNANSWERED)
                return LargeFileResult.UNANSWERED
            }
            val answer = withTimeoutOrNull(routerConfig.value.fileRequestAttemptTimeout) {
                session.answer.await()
            }
            if (answer == null) {
                io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, "fetch answer timeout")
                finishRequester(session, LargeFileResult.UNANSWERED)
                return LargeFileResult.UNANSWERED
            }
            when (answer) {
                is RequesterAnswer.Declined -> {
                    finishRequester(session, LargeFileResult.DECLINED)
                    return LargeFileResult.DECLINED
                }

                is RequesterAnswer.Settled -> {
                    finishRequester(session, answer.result)
                    return answer.result
                }

                is RequesterAnswer.Closed -> {
                    finishRequester(session, LargeFileResult.UNANSWERED)
                    return LargeFileResult.UNANSWERED
                }

                is RequesterAnswer.Ok -> Unit
            }
            return session.result.await()
        } finally {
            job.cancel()
            io.closeQuietly(channel)
            mux.removeRequester(session)
        }
    }

    /**
     * Abort our live fetch of [fileId] with the real reason (STALLED for the gap trigger,
     * CANCELLED for the user). Aborts only the requester, never our serves of the same file.
     */
    suspend fun abort(fileId: FileId, reason: LargeFileAbortReason) {
        val session = mux.requesterForFile(fileId) ?: return
        io.sendAbort(session.channel, reason, null)
        closeRequester(session)
    }

    private fun closeRequester(session: RequesterSession) {
        session.closeInput()
    }

    /**
     * Safety net around [runRequester]: whatever ends it, [fetch] gets a result, so a failure here
     * never leaves it waiting (or holding a request slot) forever.
     */
    private suspend fun requesterLoop(session: RequesterSession) {
        try {
            runRequester(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Fetch requester failed; ending as unanswered",
                throwable = e,
                fields = mapOf("peer" to session.device, "fileId" to session.fileId),
            )
        } finally {
            settle(session, LargeFileResult.UNANSWERED)
        }
    }

    private suspend fun runRequester(session: RequesterSession) {
        for (input in session.mailbox) {
            when (input) {
                is RequesterInput.ChannelClosed -> {
                    // The close may have raced an in-flight answer frame (separate flows):
                    // give it a short grace window before treating the close as the outcome.
                    // A real Declined/PULL_OK then still wins; otherwise the drop is UNANSWERED.
                    var answered = false
                    withTimeoutOrNull(routerConfig.value.largeFileAnswerGrace) {
                        for (queued in session.mailbox) {
                            when (queued) {
                                is RequesterInput.ChannelClosed -> Unit // extra close, ignore
                                is RequesterInput.Frame -> when (val frame = queued.frame) {
                                    is LargeFileFrame.PullOk -> {
                                        handlePullOk(session, frame)
                                        answered = true
                                        return@withTimeoutOrNull
                                    }

                                    is LargeFileFrame.Declined -> {
                                        session.answer.complete(RequesterAnswer.Declined(frame.reason))
                                        session.result.complete(LargeFileResult.DECLINED)
                                        answered = true
                                        return@withTimeoutOrNull
                                    }

                                    is LargeFileFrame.Abort -> {
                                        settle(session, LargeFileResult.UNANSWERED)
                                        answered = true
                                        return@withTimeoutOrNull
                                    }

                                    else -> Unit // DATA/PROGRESS/PULL after close: ignore
                                }
                            }
                            if (session.result.isCompleted) {
                                answered = true
                                return@withTimeoutOrNull
                            }
                        }
                    }
                    if (!answered) {
                        session.answer.complete(RequesterAnswer.Closed)
                        session.result.complete(LargeFileResult.UNANSWERED)
                    }
                    return
                }

                is RequesterInput.Frame -> when (val frame = input.frame) {
                    is LargeFileFrame.PullOk -> handlePullOk(session, frame)
                    is LargeFileFrame.Declined -> {
                        session.answer.complete(RequesterAnswer.Declined(frame.reason))
                        session.result.complete(LargeFileResult.DECLINED)
                        return
                    }

                    is LargeFileFrame.Data -> handleData(session, frame)
                    is LargeFileFrame.Abort -> {
                        // The holder gave up (a bad block at its source): back to PENDING.
                        settle(session, LargeFileResult.UNANSWERED)
                        return
                    }

                    is LargeFileFrame.Progress -> Unit
                    is LargeFileFrame.Pull -> Unit
                }
            }
            if (session.result.isCompleted) return
        }
    }

    private fun settle(session: RequesterSession, result: LargeFileResult) {
        session.answer.complete(RequesterAnswer.Settled(result))
        session.result.complete(result)
    }

    private suspend fun handlePullOk(session: RequesterSession, frame: LargeFileFrame.PullOk) {
        // PULL_OK carries no fileId (one pull = one channel = one file); the block list is
        // checked against our fileId before a byte is accepted (design §2).
        val verdict = try {
            ingest.checkOffer(
                FileManifest(
                    fileId = session.fileId,
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
                fields = mapOf("fileId" to session.fileId),
            )
            settle(session, LargeFileResult.UNANSWERED)
            return
        }
        when (verdict) {
            is FileOfferVerdict.Accept -> {
                session.totalBytes = frame.totalBytes
                if (!session.answer.complete(RequesterAnswer.Ok(frame))) return
                if (frame.totalBytes == 0L || session.nextOffset >= frame.totalBytes) {
                    session.result.complete(LargeFileResult.SATISFIED)
                }
            }

            is FileOfferVerdict.AlreadyReady -> {
                session.answer.complete(RequesterAnswer.Ok(frame))
                session.result.complete(LargeFileResult.SATISFIED)
            }

            is FileOfferVerdict.Refuse -> when (verdict.reason) {
                // Only a bad block list counts against the holder; a local refusal never does.
                FileRefusalReason.INVALID_OFFER -> {
                    io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, "refused")
                    settle(session, LargeFileResult.DECLINED)
                }

                FileRefusalReason.NO_SPACE -> {
                    io.sendAbort(session.channel, LargeFileAbortReason.NO_SPACE, null)
                    settle(session, LargeFileResult.NO_SPACE)
                }

                FileRefusalReason.NOT_RETRYABLE -> {
                    io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, "refused")
                    settle(session, LargeFileResult.ENDED)
                }
            }

            is FileOfferVerdict.Defer -> {
                io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, "deferred")
                settle(session, LargeFileResult.UNANSWERED)
            }
        }
    }

    private suspend fun handleData(session: RequesterSession, frame: LargeFileFrame.Data) {
        if (!session.answer.isCompleted) return
        if (session.result.isCompleted) return
        if (frame.bytes.isEmpty()) return
        val total = session.totalBytes ?: return
        if (session.nextOffset + frame.bytes.size > total) {
            // More bytes than PULL_OK announced: the holder is broken; end like a drop.
            io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, "overrun")
            session.result.complete(LargeFileResult.UNANSWERED)
            return
        }
        // Defer suspends: retry the same bytes until the engine takes them or the channel goes.
        // The window holds the holder back meanwhile, and no frame is dropped.
        while (!session.closed) {
            val result = try {
                ingest.append(session.fileId, session.nextOffset, frame.bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // P did not move: the next attempt resumes from it.
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Fetch append failed; the next attempt resumes from the checkpoint",
                    throwable = e,
                    fields = mapOf("fileId" to session.fileId),
                )
                io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, "append failed")
                session.result.complete(LargeFileResult.UNANSWERED)
                return
            }
            when (result) {
                is FileAppendResult.Appended -> {
                    session.nextOffset += frame.bytes.size
                    // PROGRESS names the durable checkpoint P (design §8): only when P moves.
                    if (result.committedBytes > session.lastSentProgress) {
                        session.lastSentProgress = result.committedBytes
                        io.sendProgress(session.channel, result.committedBytes)
                    }
                    return
                }

                is FileAppendResult.Ready -> {
                    session.nextOffset += frame.bytes.size
                    io.sendProgress(session.channel, total)
                    session.result.complete(LargeFileResult.SATISFIED)
                    return
                }

                is FileAppendResult.Mismatch -> {
                    io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, "mismatch")
                    session.result.complete(LargeFileResult.MISMATCH)
                    return
                }

                is FileAppendResult.NoSpace -> {
                    io.sendAbort(session.channel, LargeFileAbortReason.NO_SPACE, null)
                    // The engine declined the receive; drop the row, never blacklist.
                    session.result.complete(LargeFileResult.NO_SPACE)
                    return
                }

                is FileAppendResult.Ended -> {
                    io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, null)
                    session.result.complete(LargeFileResult.ENDED)
                    return
                }

                is FileAppendResult.Defer -> delay(routerConfig.value.largeFileDeferRetry)
            }
        }
        session.result.complete(LargeFileResult.UNANSWERED)
    }

    private suspend fun finishRequester(session: RequesterSession, result: LargeFileResult) {
        settle(session, result)
        session.mailbox.close()
        io.closeQuietly(session.channel)
        mux.removeRequester(session)
    }
}
