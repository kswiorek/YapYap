package org.yapyap.routing.file.large

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.routing.file.LargeFileAdmission
import org.yapyap.routing.file.LargeFileServeClaim
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * One incoming PULL channel: serves the file holder side (design §6.6). Sends PULL_OK then DATA
 * from a [FilePlaintextSource] stream, staying within the credit window past the last PROGRESS and
 * the transport's buffered threshold. Ends on channel close or after an idle timeout.
 */
internal class HolderSession(
    val channel: FileChannelId,
    @Volatile var lastProgress: Long,
    @Volatile var lastProgressAt: Long,
)

/**
 * The large path's holder (design §6.6): gate a PULL, answer PULL_OK or DECLINED, then stream the
 * file from the requester's offset. It owns only its serve loops; the channel maps and claims are
 * the mux's.
 */
internal class LargeFileHolder(
    private val transport: WebRtcTransport,
    private val plaintext: FilePlaintextSource,
    private val events: MutableSharedFlow<FileEvent>,
    private val routerConfig: StateFlow<RouterConfig>,
    private val fileLimits: StateFlow<FileLimits>,
    private val gate: LargeFileAdmissionGate,
    private val mux: LargeFileMux,
    private val io: LargeFileChannelIo,
    private val clock: Clock = Clock.System,
) {
    @Volatile
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * A PULL claims its channel before the gate runs: a repeated PULL on the same channel is
     * ignored at once, and one peer holds at most [MAX_SERVES_PER_PEER] serves (each an open
     * stream and channel); past that it is told to come back later, never declined.
     */
    suspend fun handlePull(channel: FileChannelId, frame: LargeFileFrame.Pull) {
        var claimed = false
        try {
            when (mux.claimServe(channel, MAX_SERVES_PER_PEER)) {
                LargeFileServeClaim.ALREADY -> return
                LargeFileServeClaim.BUSY -> {
                    io.sendAbort(channel, LargeFileAbortReason.CANCELLED, "busy")
                    io.closeQuietly(channel)
                    return
                }

                LargeFileServeClaim.CLAIMED -> claimed = true
            }
            if (!startServe(channel, frame)) {
                mux.releaseServe(channel)
                claimed = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Fetch serve request failed",
                throwable = e,
                fields = mapOf("fileId" to frame.fileId),
            )
            io.closeQuietly(channel)
            if (claimed) mux.releaseServe(channel)
        }
    }

    /** Runs the gate and starts the serve; false when no serve started. */
    private suspend fun startServe(channel: FileChannelId, frame: LargeFileFrame.Pull): Boolean {
        when (val admission = gate.admit(channel.peer, frame.fileId, frame.fromOffset)) {
            is LargeFileAdmission.Declined -> {
                io.sendDeclined(channel, admission.reason)
                return false
            }

            is LargeFileAdmission.Abort -> {
                io.sendAbort(channel, admission.reason, admission.text)
                io.closeQuietly(channel)
                return false
            }

            is LargeFileAdmission.Allowed -> {
                if (!io.sendPullOk(channel, admission.manifest)) {
                    io.closeQuietly(channel)
                    return false
                }
                val session = HolderSession(
                    channel = channel,
                    lastProgress = frame.fromOffset,
                    lastProgressAt = clock.now().toEpochMilliseconds(),
                )
                mux.registerHolder(session)
                checkNotNull(scope) { "LargeFileHolder is not running" }
                    .launch { serveLoop(session, admission.manifest, frame.fromOffset) }
                try {
                    events.emit(FileEvent.ServeStarted(frame.fileId, admission.account))
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
        }
    }

    /** The mux hands one holder-channel frame: PROGRESS advances the credit, ABORT ends the serve. */
    suspend fun onFrame(session: HolderSession, payload: LargeFileFrame) {
        when (payload) {
            is LargeFileFrame.Progress -> {
                // Only real progress keeps a holder alive: a requester repeating its P cannot pin
                // our stream and channel forever.
                if (payload.contiguousBytes > session.lastProgress) {
                    session.lastProgress = payload.contiguousBytes
                    session.lastProgressAt = clock.now().toEpochMilliseconds()
                }
            }

            is LargeFileFrame.Abort -> onChannelGone(session.channel)
            else -> Unit // DATA on a holder channel: not ours, ignore.
        }
    }

    /** The channel is gone or its serve ended: forget it, release the claim, close it once. */
    suspend fun onChannelGone(channel: FileChannelId) {
        val existed = mux.forgetHolder(channel)
        if (existed) io.closeQuietly(channel)
    }

    private suspend fun serveLoop(session: HolderSession, info: FileManifest, fromOffset: Long) {
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
            io.sendAbort(session.channel, LargeFileAbortReason.SOURCE_ERROR, null)
            onChannelGone(session.channel)
            return
        }
        if (stream == null) {
            io.sendAbort(session.channel, LargeFileAbortReason.SOURCE_ERROR, null)
            onChannelGone(session.channel)
            return
        }
        try {
            val window = routerConfig.value.largeFileStreamWindowBytes
            var offset = fromOffset
            while (offset < info.totalBytes) {
                // Idle timeout with no PROGRESS: the requester is gone; end the serve so a
                // parked holder never strands the requester (which is bounded by its channel).
                // Uses the attempt scope (not the receiver gap trigger, which aborts the
                // requester side): a legitimate long Defer keeps PROGRESS flowing via byte
                // checkpoints, so only a dead requester trips this.
                if (clock.now().toEpochMilliseconds() - session.lastProgressAt >
                    routerConfig.value.fileRequestAttemptTimeout.inWholeMilliseconds
                ) {
                    break
                }
                if (offset > session.lastProgress + window) {
                    delay(routerConfig.value.largeFilePacingDelay)
                    continue
                }
                // Bounded like the idle check: a buffer that never drains is a requester that stopped
                // reading, and the idle check above does not run while we wait here.
                val drained = try {
                    withTimeoutOrNull(routerConfig.value.fileRequestAttemptTimeout) {
                        transport.awaitBufferedBelow(session.channel, routerConfig.value.largeFileBufferedResumeBytes)
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
                val maxBytes = minOf(fileLimits.value.largeFileFrameBytes.toLong(), info.totalBytes - offset).toInt()
                    .coerceAtLeast(1)
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
                    io.sendAbort(session.channel, LargeFileAbortReason.SOURCE_ERROR, null)
                    onChannelGone(session.channel)
                    return
                }
                when (read) {
                    is PlaintextRead.Bytes -> {
                        if (read.bytes.isEmpty()) {
                            delay(routerConfig.value.largeFilePacingDelay)
                            continue
                        }
                        val sent = try {
                            transport.sendFileFrame(session.channel, LargeFileFrame.Data(read.bytes).encode())
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
                            onChannelGone(session.channel)
                            return
                        }
                        offset += read.bytes.size
                    }

                    // The source ended short of totalBytes: the serve ends (cleaned up below).
                    is PlaintextRead.Eof -> break
                    is PlaintextRead.SourceLost -> {
                        io.sendAbort(session.channel, LargeFileAbortReason.SOURCE_ERROR, null)
                        onChannelGone(session.channel)
                        return
                    }
                }
            }
            // All bytes sent: the requester closes on READY, and the holder parks until then. A
            // requester that stops making progress (or never closes) is reaped by the same idle
            // timeout, so a parked holder never keeps its stream and channel forever. An early
            // break (idle timeout, backpressure failure) ends the serve at once.
            if (offset >= info.totalBytes) {
                while (mux.holderFor(session.channel) != null && session.lastProgress < info.totalBytes &&
                    clock.now().toEpochMilliseconds() - session.lastProgressAt <=
                    routerConfig.value.fileRequestAttemptTimeout.inWholeMilliseconds
                ) {
                    delay(routerConfig.value.largeFileParkedPoll)
                }
            }
            onChannelGone(session.channel)
        } finally {
            stream.close()
        }
    }

    private companion object {
        /** Serves one peer may hold open at once (each holds a plaintext stream and a channel). */
        const val MAX_SERVES_PER_PEER = 4
    }
}
