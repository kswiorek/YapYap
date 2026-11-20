package org.yapyap.transport.webrtc

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.PacketType
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import org.yapyap.transport.webrtc.transport.WebRtcIncomingEnvelope
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcDataType
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Step 20: per-fetch file channels carry raw frames beside the envelope channel.
 */
class FileChannelTransportTest {

    private val self = PeerId("file-self-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val peer = PeerId("file-peer-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    @Test
    fun twoFileChannels_streamWithoutStarvingEnvelopeChannel() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            backend.simulateEnvelopeChannelOpen(peer)
            transport.openFileChannel(peer, "yapyap-file-fetch-1")
            transport.openFileChannel(peer, "yapyap-file-fetch-2")
            assertEquals(
                listOf(peer to "yapyap-file-fetch-1", peer to "yapyap-file-fetch-2"),
                backend.openFileChannelCalls,
            )

            // Two file frames on different channels + one envelope frame all land.
            val fileFrame1 = WebRtcDataFrame(peer, self, WebRtcDataType.FILE_DATA, byteArrayOf(1, 2), "yapyap-file-fetch-1")
            val fileFrame2 = WebRtcDataFrame(peer, self, WebRtcDataType.FILE_DATA, byteArrayOf(3, 4), "yapyap-file-fetch-2")
            // Subscribe first so buffered emissions are not missed, then emit.
            val collected = async {
                withTimeout(5.seconds) { transport.incomingFileFrames.take(2).toList() }
            }
            // Small yield so the collector is active before emitting.
            delay(10)
            assertTrue(backend.tryEmitFileFrame(fileFrame1))
            assertTrue(backend.tryEmitFileFrame(fileFrame2))
            val frames = collected.await()
            val labels = frames.map { it.channelLabel }.toSet()
            assertEquals(setOf("yapyap-file-fetch-1", "yapyap-file-fetch-2"), labels)

            // Envelope channel still flows alongside file channels.
            val envelope = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = org.yapyap.protocol.PacketType.MESSAGE,
                dispositionRequested = false,
                createdAt = epochSeconds(1L),
                expiresAt = epochSeconds(2L),
                source = peer,
                target = self,
                payload = byteArrayOf(0xAB.toByte()),
            )
            val inboundCollected = async {
                withTimeout(5.seconds) { transport.incomingEnvelopes.first() }
            }
            delay(10)
            assertTrue(backend.tryEmitFileFrame(
                WebRtcDataFrame(peer, self, WebRtcDataType.ENVELOPE_BINARY, envelope.encode())
            ))
            val inbound = inboundCollected.await()
            assertEquals(envelope.packetId, inbound.envelope.packetId)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun fileChannelEvents_forwardBufferedLow() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            transport.openFileChannel(peer, "yapyap-file-fetch-9")
            transport.setFileChannelBufferedThreshold(peer, "yapyap-file-fetch-9", 1024L)
            assertEquals(listOf(Triple(peer, "yapyap-file-fetch-9", 1024L)), backend.setThresholdCalls)

            val collected = async {
                withTimeout(5.seconds) {
                    transport.fileChannelEvents.first { it is WebRtcFileChannelEvent.BufferedLow }
                }
            }
            delay(10)
            assertTrue(backend.tryEmitFileChannelEvent(
                WebRtcFileChannelEvent.BufferedLow(peer, "yapyap-file-fetch-9", 512L)
            ))
            val event = collected.await()
            assertTrue(event is WebRtcFileChannelEvent.BufferedLow)
            assertEquals(512L, (event as WebRtcFileChannelEvent.BufferedLow).bufferedAmount)
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
            val job = launch {
                transport.fileChannelEvents.collect { event ->
                    if (event is WebRtcFileChannelEvent.Removed) {
                        removed.add(event)
                    }
                }
            }
            delay(10)
            transport.openFileChannel(peer, "yapyap-file-fetch-a")
            transport.openFileChannel(peer, "yapyap-file-fetch-b")
            // Wait for the opens to forward (Adding/Active) so the collector is live.
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
            val labels = removed.map { it.channelLabel }.toSet()
            assertEquals(setOf("yapyap-file-fetch-a", "yapyap-file-fetch-b"), labels)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun sendFileFrame_routesByChannelLabel() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            val payload = byteArrayOf(7, 8, 9)
            transport.sendFileFrame(peer, "yapyap-file-fetch-x", payload)
            assertEquals(1, backend.sendDataCalls.size)
            val sent = backend.sendDataCalls.single()
            assertEquals(WebRtcDataType.FILE_DATA, sent.dataType)
            assertEquals("yapyap-file-fetch-x", sent.channelLabel)
            assertTrue(payload.contentEquals(sent.payload))
        } finally {
            transport.stop()
        }
    }
}
