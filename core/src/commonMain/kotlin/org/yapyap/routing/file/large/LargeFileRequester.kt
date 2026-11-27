package org.yapyap.routing.file.large

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.LogEvent
import org.yapyap.logging.LogLevel
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.LargeFileResult
import org.yapyap.routing.file.RequesterAnswer
import org.yapyap.routing.file.RequesterInput
import org.yapyap.routing.file.fileGuarded
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.transport.WebRtcTransport

/**
 * The large path's requester (design §6.6): opens the file channel, sends PULL, checks PULL_OK's
 * block list against our fileId, appends DATA at the checkpointed offset and reports PROGRESS,
 * and ends with the channel. [abort] ends only our requester, never our serves of the same file.
 */
internal class LargeFileRequester(
    private val transport: WebRtcTransport,
    private val ingest: FileIngestPort,
    private val routerConfig: StateFlow<RouterConfig>,
    private val channels: LargeFileChannels,
    private val io: LargeFileChannelIo,
) {
    /**
     * Pull [fileId] from [peer]'s holder, starting at [fromOffset] (the checkpointed P). Bounded:
     * ends when the channel ends. One pull per file at a time (the pull loop enforces it). The
     * session's loop runs as a child of this call, and the channel closes when it returns.
     */
    suspend fun pull(peer: PeerId, fileId: FileId, fromOffset: Long): LargeFileResult {
        val fields = mapOf("peer" to peer, "fileId" to fileId)
        val channel = fileGuarded(
            "Large-file channel open failed",
            fields,
            level = LogLevel.WARN,
            event = LogEvent.LARGE_FILE_FAILED,
        ) { transport.openFileChannel(peer) } ?: return LargeFileResult.UNANSWERED
        val session = RequesterSession(fileId, peer, channel, fromOffset, routerConfig)
        if (!channels.registerRequester(session)) {
            // A racing second pull of the same file backs off, leaving the running one alone.
            io.closeQuietly(channel)
            return LargeFileResult.UNANSWERED
        }
        return try {
            coroutineScope {
                val loop = launch { requesterLoop(session) }
                try {
                    exchange(session)
                } finally {
                    loop.cancel()
                }
            }
        } finally {
            io.closeQuietly(channel)
            channels.removeRequester(session)
        }
    }

    /**
     * Abort our live pull of [fileId] with the real reason (STALLED for the gap trigger,
     * CANCELLED for the user). Aborts only the requester, never our serves of the same file.
     */
    suspend fun abort(fileId: FileId, reason: LargeFileAbortReason) {
        val session = channels.requesterForFile(fileId) ?: return
        io.sendAbort(session.channel, reason, null)
        session.closeInput()
    }

    /** Sends PULL and waits for the answer, then for the loop to settle the bytes. */
    private suspend fun exchange(session: RequesterSession): LargeFileResult {
        val sent = fileGuarded(
            "Large-file PULL send failed",
            mapOf("peer" to session.device, "fileId" to session.fileId),
            level = LogLevel.WARN,
            event = LogEvent.LARGE_FILE_FAILED,
        ) { transport.sendFileFrame(session.channel, LargeFileFrame.Pull(session.fileId, session.nextOffset).encode()) }
        if (sent == null) return LargeFileResult.UNANSWERED
        val answer = withTimeoutOrNull(routerConfig.value.filePullAttemptTimeout) { session.answer.await() }
        return when (answer) {
            null -> {
                io.sendAbort(session.channel, LargeFileAbortReason.CANCELLED, "pull answer timeout")
                LargeFileResult.UNANSWERED
            }

            is RequesterAnswer.Declined -> LargeFileResult.DECLINED
            is RequesterAnswer.Settled -> answer.result
            RequesterAnswer.Closed -> LargeFileResult.UNANSWERED
            is RequesterAnswer.Ok -> session.result.await()
        }
    }

    /** Safety net around [runRequester]: whatever ends it, [pull] gets a result. */
    private suspend fun requesterLoop(session: RequesterSession) {
        try {
            fileGuarded(
                "Large-file requester failed; ending as unanswered",
                mapOf("peer" to session.device, "fileId" to session.fileId),
                event = LogEvent.LARGE_FILE_FAILED,
            ) { runRequester(session) }
        } finally {
            session.settle(LargeFileResult.UNANSWERED)
        }
    }

    private suspend fun runRequester(session: RequesterSession) {
        for (input in session.mailbox) {
            when (input) {
                RequesterInput.ChannelClosed -> return closeGrace(session)
                is RequesterInput.Frame -> handleFrame(session, input.frame)
            }
            if (session.result.isCompleted) return
        }
    }

    private suspend fun handleFrame(session: RequesterSession, frame: LargeFileFrame) {
        when (frame) {
            is LargeFileFrame.PullOk -> handlePullOk(session, frame)
            is LargeFileFrame.Declined -> session.decline(frame.reason)
            is LargeFileFrame.Data -> handleData(session, frame)
            // The holder gave up (a bad block at its source): back to PENDING.
            is LargeFileFrame.Abort -> session.settle(LargeFileResult.UNANSWERED)
            is LargeFileFrame.Progress, is LargeFileFrame.Pull -> Unit
        }
    }

    /**
     * The close may have raced an in-flight answer frame (separate flows): queued answers get a
     * short grace window, so a real DECLINED or PULL_OK still wins. Without one the drop is
     * UNANSWERED; DATA after the close is ignored.
     */
    private suspend fun closeGrace(session: RequesterSession) {
        withTimeoutOrNull(routerConfig.value.largeFileAnswerGrace) {
            for (queued in session.mailbox) {
                val frame = (queued as? RequesterInput.Frame)?.frame ?: continue
                if (frame is LargeFileFrame.PullOk || frame is LargeFileFrame.Declined || frame is LargeFileFrame.Abort) {
                    handleFrame(session, frame)
                    return@withTimeoutOrNull
                }
            }
        }
        session.answer.complete(RequesterAnswer.Closed)
        session.result.complete(LargeFileResult.UNANSWERED)
    }

    private suspend fun handlePullOk(session: RequesterSession, frame: LargeFileFrame.PullOk) {
        // PULL_OK carries no fileId (one pull = one channel = one file); the block list is
        // checked against our fileId before a byte is accepted (design §2).
        val manifest = FileManifest(
            fileId = session.fileId,
            totalBytes = frame.totalBytes,
            blockSize = frame.blockSize,
            blockHashes = frame.blockHashes,
            fileName = frame.fileName,
            mimeType = frame.mimeType,
        )
        val verdict =
            fileGuarded("Large-file PULL_OK check failed; ending as unanswered", mapOf("fileId" to session.fileId)) {
                ingest.checkOffer(manifest)
            } ?: return session.settle(LargeFileResult.UNANSWERED)
        when (verdict) {
            FileOfferVerdict.Accept -> {
                session.totalBytes = frame.totalBytes
                if (!session.answer.complete(RequesterAnswer.Ok(frame))) return
                if (frame.totalBytes == 0L || session.nextOffset >= frame.totalBytes) {
                    session.result.complete(LargeFileResult.SATISFIED)
                }
            }

            FileOfferVerdict.AlreadyReady -> {
                session.answer.complete(RequesterAnswer.Ok(frame))
                session.result.complete(LargeFileResult.SATISFIED)
            }

            // Only a bad block list counts against the holder; a local refusal never does.
            is FileOfferVerdict.Refuse -> when (verdict.reason) {
                FileRefusalReason.INVALID_OFFER -> stop(
                    session,
                    LargeFileAbortReason.CANCELLED,
                    "refused",
                    LargeFileResult.DECLINED
                )

                FileRefusalReason.NO_SPACE -> stop(
                    session,
                    LargeFileAbortReason.NO_SPACE,
                    null,
                    LargeFileResult.NO_SPACE
                )

                FileRefusalReason.NOT_RETRYABLE -> stop(
                    session,
                    LargeFileAbortReason.CANCELLED,
                    "refused",
                    LargeFileResult.ENDED
                )
            }

            FileOfferVerdict.Defer -> stop(
                session,
                LargeFileAbortReason.CANCELLED,
                "deferred",
                LargeFileResult.UNANSWERED
            )
        }
    }

    private suspend fun handleData(session: RequesterSession, frame: LargeFileFrame.Data) {
        if (!session.answer.isCompleted || session.result.isCompleted || frame.bytes.isEmpty()) return
        val total = session.totalBytes ?: return
        if (session.nextOffset + frame.bytes.size > total) {
            // More bytes than PULL_OK announced: the holder is broken; end like a drop.
            return stop(session, LargeFileAbortReason.CANCELLED, "overrun", LargeFileResult.UNANSWERED)
        }
        // Defer suspends: retry the same bytes until the engine takes them or the channel goes.
        // The window holds the holder back meanwhile, and no frame is dropped.
        while (!session.closed) {
            val result = fileGuarded(
                "Large-file append failed; the next attempt resumes from the checkpoint",
                mapOf("fileId" to session.fileId),
            ) { ingest.append(session.fileId, session.nextOffset, frame.bytes) }
            when (result) {
                // P did not move: the next attempt resumes from it.
                null -> return stop(
                    session,
                    LargeFileAbortReason.CANCELLED,
                    "append failed",
                    LargeFileResult.UNANSWERED
                )
                is FileAppendResult.Appended -> {
                    session.nextOffset += frame.bytes.size
                    // PROGRESS names the durable checkpoint P (design §8): only when P moves.
                    if (result.committedBytes > session.lastSentProgress) {
                        session.lastSentProgress = result.committedBytes
                        io.sendProgress(session.channel, result.committedBytes)
                    }
                    return
                }

                FileAppendResult.Ready -> {
                    session.nextOffset += frame.bytes.size
                    io.sendProgress(session.channel, total)
                    session.result.complete(LargeFileResult.SATISFIED)
                    return
                }

                is FileAppendResult.Mismatch ->
                    return stop(session, LargeFileAbortReason.CANCELLED, "mismatch", LargeFileResult.MISMATCH)
                // The engine declined the receive; drop the row, never blacklist.
                FileAppendResult.NoSpace ->
                    return stop(session, LargeFileAbortReason.NO_SPACE, null, LargeFileResult.NO_SPACE)

                FileAppendResult.Ended ->
                    return stop(session, LargeFileAbortReason.CANCELLED, null, LargeFileResult.ENDED)

                FileAppendResult.Defer -> delay(routerConfig.value.largeFileDeferRetry)
            }
        }
        session.result.complete(LargeFileResult.UNANSWERED)
    }

    /** Tells the holder we stop, and settles the pull with [result]; the pull then closes the channel. */
    private suspend fun stop(
        session: RequesterSession,
        reason: LargeFileAbortReason,
        text: String?,
        result: LargeFileResult,
    ) {
        io.sendAbort(session.channel, reason, text)
        session.settle(result)
    }
}
