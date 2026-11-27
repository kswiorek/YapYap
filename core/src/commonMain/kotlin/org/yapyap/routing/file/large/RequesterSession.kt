package org.yapyap.routing.file.large

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileDeclineReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.LargeFileResult
import org.yapyap.routing.file.RequesterAnswer
import org.yapyap.routing.file.RequesterInput
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.types.FileChannelId
import kotlin.concurrent.Volatile

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

    /** The channel is gone or the pull was aborted: a Defer retry stops. */
    @Volatile
    var closed: Boolean = false

    /**
     * Posts one frame to the loop. The holder may start a frame at most the configured window past
     * the last PROGRESS; one that overruns it ends the pull like a channel loss, and the pull
     * closes the channel on its own coroutine (never on the mux's shared collector).
     */
    fun onFrame(payload: LargeFileFrame) {
        if (payload is LargeFileFrame.Data) {
            val start = routedEnd
            routedEnd += payload.bytes.size
            if (start > lastSentProgress + routerConfig.value.largeFileStreamWindowBytes) {
                AppLog.warn(
                    component = LogComponent.FILE,
                    event = LogEvent.LARGE_FILE_FAILED,
                    message = "Holder overran the pull window; ending the pull",
                    fields = mapOf("peer" to device, "fileId" to fileId, "offset" to start),
                )
                closeInput()
                return
            }
        }
        mailbox.trySend(RequesterInput.Frame(payload))
    }

    fun onChannelGone() {
        closeInput()
    }

    /** The channel is gone or the pull was aborted: the loop's Defer retry must stop. */
    fun closeInput() {
        closed = true
        mailbox.trySend(RequesterInput.ChannelClosed)
    }

    /** Settles the pull with [result] unless it already has an answer or result. */
    fun settle(result: LargeFileResult) {
        answer.complete(RequesterAnswer.Settled(result))
        this.result.complete(result)
    }

    /** The holder's own DECLINED. */
    fun decline(reason: LargeFileDeclineReason) {
        answer.complete(RequesterAnswer.Declined(reason))
        result.complete(LargeFileResult.DECLINED)
    }
}
