package org.yapyap.routing.file.large

import org.yapyap.logging.LogEvent
import org.yapyap.logging.LogLevel
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileDeclineReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.routing.file.fileGuarded
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId

/**
 * The large path's control-frame writes and quiet close, shared by the requester and the holder:
 * PULL_OK, DECLINED, ABORT, PROGRESS. Every send is best-effort — a lost frame only delays the
 * window, and a terminal frame (DECLINED, ABORT) is always followed by the channel's close: here
 * for DECLINED, by the side that ends the channel for ABORT. PULL and DATA stay with the loops
 * that own their flow.
 */
internal class LargeFileChannelIo(private val transport: WebRtcTransport) {

    /** False when PULL_OK could not be sent: no serve starts on that channel. */
    suspend fun sendPullOk(channel: FileChannelId, manifest: FileManifest): Boolean {
        val frame = LargeFileFrame.PullOk(
            totalBytes = manifest.totalBytes,
            blockSize = manifest.blockSize,
            blockHashes = manifest.blockHashes,
            fileName = manifest.fileName,
            mimeType = manifest.mimeType,
        )
        return send(channel, frame, "PULL_OK", LogLevel.WARN)
    }

    /** A lost DECLINED still ends the pull: the channel closes right after. */
    suspend fun sendDeclined(channel: FileChannelId, reason: LargeFileDeclineReason) {
        send(channel, LargeFileFrame.Declined(reason), "DECLINED", LogLevel.DEBUG)
        closeQuietly(channel)
    }

    /** A lost ABORT still ends the pull: the caller closes the channel after it. */
    suspend fun sendAbort(channel: FileChannelId, reason: LargeFileAbortReason, text: String?) {
        send(channel, LargeFileFrame.Abort(reason, text), "ABORT", LogLevel.DEBUG)
    }

    /** A lost PROGRESS frame only delays the sender's window; the next one carries a later P. */
    suspend fun sendProgress(channel: FileChannelId, contiguousBytes: Long) {
        send(channel, LargeFileFrame.Progress(contiguousBytes), "PROGRESS", LogLevel.WARN)
    }

    /** The channel may already be gone (the peer closed it, the session dropped). */
    suspend fun closeQuietly(channel: FileChannelId) {
        fileGuarded(
            "Large-file channel close failed; it is already gone",
            mapOf("peer" to channel.peer),
            level = LogLevel.DEBUG,
            event = LogEvent.LARGE_FILE_FAILED,
        ) { transport.closeFileChannel(channel) }
    }

    private suspend fun send(channel: FileChannelId, frame: LargeFileFrame, kind: String, level: LogLevel): Boolean =
        fileGuarded(
            "Failed to send large-file $kind",
            mapOf("peer" to channel.peer),
            level = level,
            event = LogEvent.LARGE_FILE_FAILED,
        ) { transport.sendFileFrame(channel, frame.encode()) } != null
}
