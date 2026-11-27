package org.yapyap.routing.file.large

import kotlinx.coroutines.CancellationException
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileDeclineReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId

/**
 * The large path's control-frame writes and quiet close, shared by the requester and the holder:
 * PULL_OK, DECLINED, ABORT, PROGRESS. Every send is best-effort — a lost frame only delays the
 * window, and each terminal frame (DECLINED, ABORT) is followed by the channel's close. PULL and
 * DATA stay with the loops that own their flow.
 */
internal class LargeFileChannelIo(private val transport: WebRtcTransport) {

    /** False when PULL_OK could not be sent: no serve starts on that channel. */
    suspend fun sendPullOk(channel: FileChannelId, info: FileManifest): Boolean {
        return try {
            transport.sendFileFrame(
                channel,
                LargeFileFrame.PullOk(
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
                message = "Failed to send PULL_OK",
                fields = mapOf("fileId" to info.fileId, "error" to e.toString()),
            )
            false
        }
    }

    /** A lost DECLINED still ends the fetch: the channel closes right after. */
    suspend fun sendDeclined(channel: FileChannelId, reason: LargeFileDeclineReason) {
        sendBestEffort(channel, LargeFileFrame.Declined(reason).encode(), "DECLINED")
        closeQuietly(channel)
    }

    /** A lost ABORT still ends the fetch: every abort is followed by the channel's close. */
    suspend fun sendAbort(channel: FileChannelId, reason: LargeFileAbortReason, text: String?) {
        sendBestEffort(channel, LargeFileFrame.Abort(reason, text).encode(), "ABORT")
    }

    /** A lost PROGRESS frame only delays the sender's window; the next one carries a later P. */
    suspend fun sendProgress(channel: FileChannelId, contiguousBytes: Long) {
        try {
            transport.sendFileFrame(channel, LargeFileFrame.Progress(contiguousBytes).encode())
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
    suspend fun closeQuietly(channel: FileChannelId) {
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
}
