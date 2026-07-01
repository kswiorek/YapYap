package org.yapyap.transport.webrtc

import kotlinx.coroutines.runBlocking
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.packet.PacketType
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcDataType
import org.yapyap.transport.webrtc.types.WebRtcSignal
import org.yapyap.transport.webrtc.types.WebRtcSignalKind
import kotlin.test.Test
import kotlin.test.assertEquals

class WebRtcTransportAndBackendSmokeTest {

    @Test
    fun recordingWebRtcTransport_recordsSessionAndEnvelopeCalls() = runBlocking {
        val w = RecordingWebRtcTransport()
        val self = PeerId("selfpeeridaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        w.start(self)
        assertEquals(listOf(self), w.startCalls)

        val peer = PeerId("peerbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        w.openSession(peer, "sess-1")
        assertEquals(listOf(peer to "sess-1"), w.openSessionCalls)

        val be = sampleBinaryEnvelope()
        w.sendEnvelope("sess-1", peer, be)
        assertEquals(1, w.sendEnvelopeCalls.size)
        assertEquals("sess-1", w.sendEnvelopeCalls[0].first)
        assertEquals(peer, w.sendEnvelopeCalls[0].second)

        w.stop()
        assertEquals(1, w.stopCalls.size)
    }

    @Test
    fun recordingWebRtcBackend_recordsOperations() = runBlocking {
        val b = RecordingWebRtcBackend()
        val local = PeerId("localccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
        b.start(local)
        assertEquals(listOf(local), b.startCalls)

        val peer = PeerId("peerddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
        b.openSession(peer, "s1")
        val sig = WebRtcSignal("s1", WebRtcSignalKind.OFFER, peer, local, byteArrayOf(9))
        b.handleRemoteSignal(sig)
        assertEquals(listOf(sig), b.handleRemoteSignalCalls)

        val frame = WebRtcDataFrame(
            sessionId = "s1",
            source = peer,
            target = local,
            dataType = WebRtcDataType.ENVELOPE_BINARY,
            payload = byteArrayOf(7),
        )
        b.sendData(frame)
        assertEquals(listOf(frame), b.sendDataCalls)

        b.closeSession("s1")
        assertEquals(listOf("s1"), b.closeSessionCalls)
        b.stop()
        assertEquals(1, b.stopCalls.size)
    }
}

private fun sampleBinaryEnvelope(): BinaryEnvelope {
    val pid = PacketId.fromHex("11".repeat(PacketId.SIZE_BYTES))
    val src = PeerId("srcaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    val dst = PeerId("dstbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    return BinaryEnvelope(
        packetId = pid,
        packetType = PacketType.MESSAGE,
        createdAtEpochSeconds = 1L,
        expiresAtEpochSeconds = 2L,
        source = src,
        target = dst,
        payload = byteArrayOf(0xAB.toByte()),
    )
}
