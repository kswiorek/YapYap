package org.yapyap.transport.webrtc.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import java.util.concurrent.ConcurrentHashMap

/**
 * Inbound side of the backend's file channels (design §6.6): one queue per channel, drained by one
 * coroutine, so the frames of a channel are emitted in the order the native callbacks saw them, with
 * no launch per message. Callbacks only call [deliver] and [bufferedAmountChanged], from any thread.
 *
 * [close] lets the drainer flush what the channel already received before it reports the channel
 * gone, so a frame sent right before a close (a FETCH_DECLINED, an ABORT) is never dropped.
 * Queues are unbounded: the fetch's credit window bounds what a well-behaved holder sends, and the
 * requester closes a channel whose holder overruns it.
 */
internal class FileChannelInbox(
    private val scope: CoroutineScope,
    private val emitFrame: suspend (WebRtcFileFrame) -> Unit,
) {
    private class Inbound(val queue: Channel<ByteArray>, val buffered: MutableStateFlow<Long>)

    private val channels = ConcurrentHashMap<FileChannelId, Inbound>()

    /**
     * Start receiving on [id]. [onDrained] runs once the channel is closed and every frame it
     * received has been emitted.
     */
    fun open(id: FileChannelId, onDrained: () -> Unit) {
        val inbound = Inbound(Channel(Channel.UNLIMITED), MutableStateFlow(0L))
        if (channels.putIfAbsent(id, inbound) != null) return
        scope.launch {
            try {
                for (payload in inbound.queue) emitFrame(WebRtcFileFrame(id, payload))
            } finally {
                onDrained()
            }
        }
    }

    /** One message from [id]'s native callback. A frame for a channel already closed is dropped. */
    fun deliver(id: FileChannelId, payload: ByteArray): Boolean =
        channels[id]?.queue?.trySend(payload)?.isSuccess ?: false

    /** Native low-threshold callback: wakes the waiters whose threshold [amount] satisfies. */
    fun bufferedAmountChanged(id: FileChannelId, amount: Long) {
        channels[id]?.buffered?.value = amount
    }

    /**
     * Suspends until [id]'s buffered amount is at or below [threshold], or the channel closes.
     * [current] is the live amount read by the caller: the native callback reports only decreases,
     * so a send's increase is known only from that read.
     */
    suspend fun awaitBufferedBelow(id: FileChannelId, threshold: Long, current: Long) {
        val inbound = channels[id] ?: return
        inbound.buffered.value = current
        inbound.buffered.first { it == CLOSED || it <= threshold }
    }

    /** Stops [id]: no new frames are accepted, queued ones still go out, waiters wake. */
    fun close(id: FileChannelId) {
        val inbound = channels.remove(id) ?: return
        inbound.queue.close()
        inbound.buffered.value = CLOSED
    }

    /** Closes every channel (backend stop). */
    fun closeAll() {
        for (id in channels.keys.toList()) close(id)
    }

    fun isOpen(id: FileChannelId): Boolean = channels.containsKey(id)

    private companion object {
        /** Buffered-amount sentinel for a closed channel: every waiter returns. */
        const val CLOSED = -1L
    }
}
