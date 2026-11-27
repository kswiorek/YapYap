package org.yapyap.routing.file.large

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.routing.file.HolderSession
import org.yapyap.routing.file.LargeFileAdmission
import org.yapyap.routing.file.LargeFileServeClaim
import org.yapyap.routing.file.fileGuarded
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * The large path's holder (design §6.6): gate a PULL, answer PULL_OK or DECLINED, then stream the
 * file from the requester's offset within the credit window past its last PROGRESS and the
 * transport's buffered threshold. Each serve runs on the coroutine the mux launched for its PULL
 * and ends with its channel; the channel maps and claims are [LargeFileChannels]'.
 */
internal class LargeFileHolder(
    private val transport: WebRtcTransport,
    private val plaintext: FilePlaintextSource,
    private val events: MutableSharedFlow<FileEvent>,
    private val routerConfig: StateFlow<RouterConfig>,
    private val fileLimits: StateFlow<FileLimits>,
    private val gate: LargeFileAdmissionGate,
    private val channels: LargeFileChannels,
    private val io: LargeFileChannelIo,
    private val clock: Clock = Clock.System,
) {
    /**
     * Serves one PULL for as long as its channel lives. The PULL claims its channel before the gate
     * runs: a repeated PULL on the same channel is ignored at once, and one peer holds at most
     * `maxLargeFileServesPerPeer` serves; past that it is told to come back later, never declined.
     */
    suspend fun handlePull(channel: FileChannelId, frame: LargeFileFrame.Pull) {
        when (channels.claimServe(channel, routerConfig.value.maxLargeFileServesPerPeer)) {
            LargeFileServeClaim.ALREADY -> return
            LargeFileServeClaim.BUSY -> return abortAndClose(channel, LargeFileAbortReason.CANCELLED, "busy")
            LargeFileServeClaim.CLAIMED -> Unit
        }
        try {
            fileGuarded(
                "Large file serve failed",
                mapOf("fileId" to frame.fileId),
                event = LogEvent.LARGE_FILE_FAILED
            ) {
                serve(channel, frame)
            } ?: io.closeQuietly(channel)
        } finally {
            onChannelGone(channel)
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
                    session.lastProgressAt = clock.now()
                }
            }

            is LargeFileFrame.Abort -> onChannelGone(session.channel)
            else -> Unit // DATA on a holder channel: not ours, ignore.
        }
    }

    /** The channel is gone or its serve ended: forget it, release the claim, close it once. */
    suspend fun onChannelGone(channel: FileChannelId) {
        if (channels.forgetHolder(channel)) io.closeQuietly(channel)
    }

    /** Runs the gate, answers it, and streams the file when allowed. */
    private suspend fun serve(channel: FileChannelId, frame: LargeFileFrame.Pull) {
        val allowed = when (val admission = gate.admit(channel.peer, frame.fileId, frame.fromOffset)) {
            is LargeFileAdmission.Declined -> return io.sendDeclined(channel, admission.reason)
            is LargeFileAdmission.Abort -> return abortAndClose(channel, admission.reason, admission.text)
            is LargeFileAdmission.Allowed -> admission
        }
        if (!io.sendPullOk(channel, allowed.manifest)) return io.closeQuietly(channel)
        val session = HolderSession(channel, lastProgress = frame.fromOffset, lastProgressAt = clock.now())
        channels.registerHolder(session)
        fileGuarded("Failed to emit the large-file serve event") {
            events.emit(FileEvent.ServeStarted(frame.fileId, allowed.account))
        }
        stream(session, allowed.manifest, frame.fromOffset)
    }

    /**
     * Streams [manifest]'s bytes from [fromOffset], then parks until the requester holds them all.
     * Any early end (source lost, requester gone, a failed send) returns; [handlePull] closes.
     */
    private suspend fun stream(session: HolderSession, manifest: FileManifest, fromOffset: Long) {
        val source = openSource(manifest.fileId, fromOffset)
            ?: return io.sendAbort(session.channel, LargeFileAbortReason.SOURCE_ERROR, null)
        source.use { stream ->
            var offset = fromOffset
            while (offset < manifest.totalBytes) {
                if (!awaitCredit(session, offset)) return
                val maxBytes =
                    minOf(fileLimits.value.largeFileFrameBytes.toLong(), manifest.totalBytes - offset).toInt()
                when (val read = read(stream, maxBytes, manifest.fileId)) {
                    is PlaintextRead.Bytes -> {
                        if (read.bytes.isEmpty()) {
                            delay(routerConfig.value.largeFilePacingDelay)
                        } else {
                            if (!sendData(session, read.bytes, manifest.fileId)) return
                            offset += read.bytes.size
                        }
                    }

                    // The source ended short of totalBytes: the serve ends.
                    PlaintextRead.Eof -> return
                    is PlaintextRead.SourceLost, null ->
                        return io.sendAbort(session.channel, LargeFileAbortReason.SOURCE_ERROR, null)
                }
            }
            parkUntilHeld(session, manifest.totalBytes)
        }
    }

    private suspend fun openSource(fileId: FileId, fromOffset: Long): PlaintextStream? =
        fileGuarded("Large file serve open failed", mapOf("fileId" to fileId)) { plaintext.open(fileId, fromOffset) }

    private suspend fun read(stream: PlaintextStream, maxBytes: Int, fileId: FileId): PlaintextRead? =
        fileGuarded("Large file serve read failed", mapOf("fileId" to fileId)) { stream.read(maxBytes) }

    /**
     * Waits until the next frame fits: within the window past the last PROGRESS, and below the
     * transport's buffered threshold. False when the requester is gone: no real PROGRESS for the
     * idle timeout, or a buffer that never drains (a requester that stopped reading).
     */
    private suspend fun awaitCredit(session: HolderSession, offset: Long): Boolean {
        val config = routerConfig.value
        while (offset > session.lastProgress + config.largeFileStreamWindowBytes) {
            if (idle(session)) return false
            delay(config.largeFilePacingDelay)
        }
        if (idle(session)) return false
        return fileGuarded("Large file backpressure wait failed; ending the serve") {
            withTimeoutOrNull(idleTimeout()) {
                transport.awaitBufferedBelow(session.channel, config.largeFileBufferedResumeBytes)
            } != null
        } == true
    }

    private suspend fun sendData(session: HolderSession, bytes: ByteArray, fileId: FileId): Boolean =
        fileGuarded(
            "Failed to send a large-file DATA frame; ending the serve",
            mapOf("fileId" to fileId),
            event = LogEvent.LARGE_FILE_FAILED,
        ) { transport.sendFileFrame(session.channel, LargeFileFrame.Data(bytes).encode()) } != null

    /**
     * Every byte is sent: the requester closes on READY, and the holder parks until then. A
     * requester that stops making progress (or never closes) is reaped by the idle timeout, so a
     * parked holder never keeps its stream and channel forever.
     */
    private suspend fun parkUntilHeld(session: HolderSession, totalBytes: Long) {
        while (channels.holderFor(session.channel) != null && session.lastProgress < totalBytes && !idle(session)) {
            delay(routerConfig.value.largeFileParkedPoll)
        }
    }

    /**
     * No real PROGRESS for the idle timeout: the requester is gone. A legitimate long Defer on its
     * side keeps PROGRESS flowing through byte checkpoints, so only a dead requester trips this.
     */
    private fun idle(session: HolderSession): Boolean = clock.now() - session.lastProgressAt > idleTimeout()

    /** The holder's idle bound: one pull attempt's budget, as long as the requester waits for PULL_OK. */
    private fun idleTimeout(): Duration = routerConfig.value.filePullAttemptTimeout

    private suspend fun abortAndClose(channel: FileChannelId, reason: LargeFileAbortReason, text: String?) {
        io.sendAbort(channel, reason, text)
        io.closeQuietly(channel)
    }
}
