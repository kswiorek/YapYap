package org.yapyap.transport.webrtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcDataType
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Per-fetch file channels are backend-minted `FileChannelId` handles carrying raw [WebRtcFileFrame]
 * frames on their own flow and buffer. Ordering and reliability are the backend's (see
 * `FileChannelInboxTest`); this covers the transport's delegation, routing and isolation.
 */
class FileChannelTransportTest {

    private val self = PeerId("file-self-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val peer = PeerId("file-peer-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    private fun testEnvelope() = BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = PacketType.MESSAGE,
        dispositionRequested = false,
        createdAt = epochSeconds(1L),
        expiresAt = epochSeconds(2L),
        source = peer,
        target = self,
        payload = byteArrayOf(0xAB.toByte()),
    )

    @Test
    fun twoFileChannels_streamWithoutStarvingEnvelopeChannel() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            backend.simulateEnvelopeChannelOpen(peer)
            val channel1 = transport.openFileChannel(peer)
            val channel2 = transport.openFileChannel(peer)
            assertEquals(listOf(peer, peer), backend.openFileChannelCalls)

            // Two file frames on different channels both land.
            val framesSubscribed = CompletableDeferred<Unit>()
            val collected = async {
                withTimeout(5.seconds) { transport.incomingFileFrames.subscribed(framesSubscribed).take(2).toList() }
            }
            framesSubscribed.await()
            assertTrue(backend.tryEmitFileFrame(WebRtcFileFrame(channel1, byteArrayOf(1, 2))))
            assertTrue(backend.tryEmitFileFrame(WebRtcFileFrame(channel2, byteArrayOf(3, 4))))
            val frames = collected.await()
            assertEquals(setOf(channel1, channel2), frames.map { it.channel }.toSet())

            // Envelope channel still flows alongside file channels.
            val envelope = testEnvelope()
            val envelopesSubscribed = CompletableDeferred<Unit>()
            val inboundCollected = async {
                withTimeout(5.seconds) { transport.incomingEnvelopes.subscribed(envelopesSubscribed).first() }
            }
            envelopesSubscribed.await()
            assertTrue(
                backend.tryEmitDataFrame(
                    WebRtcDataFrame(peer, self, WebRtcDataType.ENVELOPE_BINARY, envelope.encode()),
                ),
            )
            val inbound = inboundCollected.await()
            assertEquals(envelope.packetId, inbound.envelope.packetId)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun fileChannels_openReturnsDistinctHandlesAndRoutesByChannel() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            // Reliability is backend-enforced (ordered=true whatever the shared config says);
            // here we verify the transport delegates opens and routes sends by handle.
            val channel1 = transport.openFileChannel(peer)
            val channel2 = transport.openFileChannel(peer)
            assertNotEquals(channel1, channel2)
            assertEquals(listOf(peer, peer), backend.openFileChannelCalls)

            val payload1 = byteArrayOf(1, 2, 3)
            val payload2 = byteArrayOf(4, 5, 6)
            transport.sendFileFrame(channel1, payload1)
            transport.sendFileFrame(channel2, payload2)
            assertEquals(2, backend.sendFileFrameCalls.size)
            assertEquals(channel1, backend.sendFileFrameCalls[0].first)
            assertTrue(payload1.contentEquals(backend.sendFileFrameCalls[0].second))
            assertEquals(channel2, backend.sendFileFrameCalls[1].first)
            assertTrue(payload2.contentEquals(backend.sendFileFrameCalls[1].second))
        } finally {
            transport.stop()
        }
    }

    @Test
    fun fileChannels_closeWhenSessionEnds() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            val removed = mutableListOf<WebRtcFileChannelEvent.Removed>()
            val eventsSubscribed = CompletableDeferred<Unit>()
            val job = launch {
                transport.fileChannelEvents.subscribed(eventsSubscribed).collect { event ->
                    if (event is WebRtcFileChannelEvent.Removed) {
                        removed.add(event)
                    }
                }
            }
            eventsSubscribed.await()
            val channel1 = transport.openFileChannel(peer)
            val channel2 = transport.openFileChannel(peer)
            withTimeout(5.seconds) {
                while (backend.openFileChannelCalls.size < 2) {
                    delay(10)
                }
            }
            delay(50)

            // Closing the session drops both file channels (one Removed per channel).
            backend.closeSession(peer)
            withTimeout(5.seconds) {
                while (removed.size < 2) {
                    delay(10)
                }
            }
            job.cancel()
            assertEquals(setOf(channel1, channel2), removed.map { it.channel }.toSet())
        } finally {
            transport.stop()
        }
    }

    @Test
    fun sendFileFrame_routesByChannel() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            val channel = transport.openFileChannel(peer)
            val payload = byteArrayOf(7, 8, 9)
            transport.sendFileFrame(channel, payload)
            assertEquals(1, backend.sendFileFrameCalls.size)
            val sent = backend.sendFileFrameCalls.single()
            assertEquals(channel, sent.first)
            assertTrue(payload.contentEquals(sent.second))
        } finally {
            transport.stop()
        }
    }

    @Test
    fun stalledFileConsumer_doesNotDelayEnvelopes() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            backend.simulateEnvelopeChannelOpen(peer)
            val channel = transport.openFileChannel(peer)
            delay(50)

            // A file consumer that takes one frame and then never returns: the transport's file
            // collector backs up behind it until the backend's file buffer is full.
            val stalledAt = CompletableDeferred<Unit>()
            val stalled = launch {
                transport.incomingFileFrames.collect {
                    stalledAt.complete(Unit)
                    awaitCancellation()
                }
            }
            delay(50)
            var refused = 0
            repeat(2_000) { i ->
                if (!backend.tryEmitFileFrame(WebRtcFileFrame(channel, byteArrayOf(i.toByte())))) refused++
            }
            withTimeout(5.seconds) { stalledAt.await() }
            assertTrue(refused > 0, "the file path must really be backed up")

            // Envelopes ride a separate flow and must still arrive promptly.
            val envelope = testEnvelope()
            val envelopesSubscribed = CompletableDeferred<Unit>()
            val inboundCollected = async {
                withTimeout(5.seconds) { transport.incomingEnvelopes.subscribed(envelopesSubscribed).first() }
            }
            envelopesSubscribed.await()
            assertTrue(
                backend.tryEmitDataFrame(
                    WebRtcDataFrame(peer, self, WebRtcDataType.ENVELOPE_BINARY, envelope.encode()),
                ),
            )
            val inbound = inboundCollected.await()
            assertEquals(envelope.packetId, inbound.envelope.packetId)
            stalled.cancel()
        } finally {
            transport.stop()
        }
    }
}

/**
 * The transport's flows are shared flows: [signal] completes once this collector is subscribed,
 * so a test emits only after it, instead of sleeping and hoping.
 */
private fun <T> Flow<T>.subscribed(signal: CompletableDeferred<Unit>): Flow<T> =
    (this as SharedFlow<T>).onSubscription { signal.complete(Unit) }
