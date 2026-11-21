package org.yapyap.transport.webrtc.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.yapyap.protocol.PeerId
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcDataType
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The JVM backend's file-channel inbound layer ([FileChannelInbox]) and channel parameters
 * ([dataChannelInit]), driven the way the native callbacks drive them: from plain threads, with a
 * consumer that suspends at random, on a multi-threaded dispatcher.
 */
class FileChannelInboxTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peer = PeerId("inbox-peer-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun channel(i: Int) = FileChannelId(peer, "yapyap-file-$i")

    private fun frame(i: Int): ByteArray = ByteBuffer.allocate(4).putInt(i).array()

    private fun indexOf(frame: WebRtcFileFrame): Int = ByteBuffer.wrap(frame.payload).int

    @Test
    fun framesOfEachChannel_areEmittedInCallbackOrder_acrossThreads() = runBlocking {
        val channels = 4
        val perChannel = 10_000
        val seen = List(channels) { Collections.synchronizedList(mutableListOf<Int>()) }
        val inbox = FileChannelInbox(scope) { frame ->
            // A consumer that suspends at random: a launch per message would reorder here.
            if (Random.nextInt(8) == 0) yield()
            seen[frame.channel.label.substringAfterLast('-').toInt()] += indexOf(frame)
        }
        val drained = List(channels) { CompletableDeferred<Unit>() }
        repeat(channels) { c -> inbox.open(channel(c)) { drained[c].complete(Unit) } }
        // One native callback thread per channel, all running at once.
        val start = CountDownLatch(1)
        val threads = List(channels) { c ->
            thread {
                start.await()
                repeat(perChannel) { i -> check(inbox.deliver(channel(c), frame(i))) }
                inbox.close(channel(c))
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        withTimeout(30.seconds) { drained.forEach { it.await() } }
        repeat(channels) { c -> assertEquals(List(perChannel) { it }, seen[c].toList(), "channel $c order") }
    }

    @Test
    fun close_flushesFramesAlreadyReceived_beforeReportingTheChannelGone() = runBlocking {
        val emitted = Collections.synchronizedList(mutableListOf<Int>())
        val release = CompletableDeferred<Unit>()
        val inbox = FileChannelInbox(scope) { frame ->
            release.await()
            emitted += indexOf(frame)
        }
        var emittedAtDrain = -1
        val drained = CompletableDeferred<Unit>()
        inbox.open(channel(0)) {
            emittedAtDrain = emitted.size
            drained.complete(Unit)
        }
        repeat(100) { inbox.deliver(channel(0), frame(it)) }
        // The last frame (a FETCH_DECLINED, say) is followed at once by the close.
        inbox.close(channel(0))
        assertFalse(inbox.deliver(channel(0), frame(100)), "a frame after the close is dropped")
        release.complete(Unit)
        withTimeout(10.seconds) { drained.await() }
        assertEquals(List(100) { it }, emitted.toList())
        assertEquals(100, emittedAtDrain, "Removed follows the last queued frame")
    }

    @Test
    fun awaitBufferedBelow_wakesOnTheCallback_andOnClose() = runBlocking {
        val inbox = FileChannelInbox(scope) { }
        inbox.open(channel(0)) { }
        inbox.open(channel(1)) { }

        val belowNow = async { inbox.awaitBufferedBelow(channel(0), threshold = 1_000, current = 500) }
        withTimeout(5.seconds) { belowNow.await() }

        val waiting = async(Dispatchers.Default) {
            inbox.awaitBufferedBelow(channel(0), threshold = 1_000, current = 4_000)
        }
        delay(50)
        inbox.bufferedAmountChanged(channel(0), 2_000)
        delay(50)
        assertFalse(waiting.isCompleted, "still above the threshold")
        thread { inbox.bufferedAmountChanged(channel(0), 900) }.join()
        withTimeout(5.seconds) { waiting.await() }

        val closing = async(Dispatchers.Default) {
            inbox.awaitBufferedBelow(channel(1), threshold = 1_000, current = 4_000)
        }
        delay(50)
        inbox.close(channel(1))
        withTimeout(5.seconds) { closing.await() }
        // A closed channel never parks a waiter.
        withTimeout(5.seconds) { inbox.awaitBufferedBelow(channel(1), threshold = 0, current = 4_000) }
    }

    @Test
    fun fileChannels_areReliable_underAnUnreliableSharedConfig() {
        val unreliable = WebRtcBackendConfig(orderedDataChannel = false, maxRetransmits = 0, maxPacketLifeTimeMs = 100)

        val file = dataChannelInit(unreliable, WebRtcDataType.FILE_DATA)
        assertTrue(file.ordered)
        assertEquals(-1, file.maxRetransmits, "no retransmit limit")
        assertEquals(-1, file.maxPacketLifeTime, "no lifetime limit")

        // The same config does reach the envelope channel: the file channel ignores it on purpose.
        val envelope = dataChannelInit(unreliable, WebRtcDataType.ENVELOPE_BINARY)
        assertFalse(envelope.ordered)
        assertEquals(0, envelope.maxRetransmits)
        assertEquals(100, envelope.maxPacketLifeTime)
    }
}
