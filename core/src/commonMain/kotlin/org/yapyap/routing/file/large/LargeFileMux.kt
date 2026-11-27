package org.yapyap.routing.file.large

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yapyap.logging.LogEvent
import org.yapyap.logging.LogLevel
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.routing.file.fileGuarded
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame

/**
 * The large path's dispatch (design §6.6): subscribes to the transport's file frames and channel
 * events, decodes each frame and hands it to its owner — the requester for a channel it opened,
 * the holder for a channel somebody pulled on. It never touches the wire or a file itself.
 */
internal class LargeFileMux(
    private val transport: WebRtcTransport,
    private val channels: LargeFileChannels,
    private val requester: LargeFileRequester,
    private val holder: LargeFileHolder,
) {
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        val serves = this
        launch {
            transport.incomingFileFrames.collect { frame ->
                fileGuarded("Failed to route a large-file frame") { routeFrame(serves, frame) }
            }
        }
        launch {
            transport.fileChannelEvents.collect { event ->
                fileGuarded("Failed to route a large-file channel event") { routeEvent(event) }
            }
        }
    }

    private suspend fun routeFrame(serves: CoroutineScope, frame: WebRtcFileFrame) {
        val payload = fileGuarded(
            "Ignoring an undecodable large-file frame",
            level = LogLevel.DEBUG,
            event = LogEvent.LARGE_FILE_FAILED,
        ) { LargeFileFrame.decode(frame.payload) } ?: return
        val requesterSession = channels.requesterForChannel(frame.channel)
        when {
            // Our own requester channel never serves: a holder sending PULL back is ignored there.
            requesterSession != null -> requesterSession.onFrame(payload)
            // A serve runs for as long as its channel: off the shared frame collector, so DATA
            // for every other pull keeps flowing (the channel claim stops a duplicate PULL).
            payload is LargeFileFrame.Pull -> serves.launch { holder.handlePull(frame.channel, payload) }
            else -> channels.holderFor(frame.channel)?.let { holder.onFrame(it, payload) }
        }
    }

    private suspend fun routeEvent(event: WebRtcFileChannelEvent) {
        when (event) {
            is WebRtcFileChannelEvent.Adding, is WebRtcFileChannelEvent.Active -> Unit
            is WebRtcFileChannelEvent.Removed, is WebRtcFileChannelEvent.Failed -> {
                channels.requesterForChannel(event.channel)?.onChannelGone()
                holder.onChannelGone(event.channel)
            }
        }
    }
}
