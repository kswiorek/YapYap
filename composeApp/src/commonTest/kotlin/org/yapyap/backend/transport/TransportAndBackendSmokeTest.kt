package org.yapyap.backend.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.tor.TorIncomingFrame
import org.yapyap.backend.transport.tor.TorIncomingEnvelope
import org.yapyap.backend.transport.webrtc.WebRtcDataFrame
import org.yapyap.backend.transport.webrtc.WebRtcDataType
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import org.yapyap.backend.transport.webrtc.types.WebRtcSignalKind

class TransportAndBackendSmokeTest {

    @Test
    fun recordingTorTransport_start_stop_and_send_shape() = runBlocking {
        val t = RecordingTorTransport(TorEndpoint("x.onion", 1234))
        val ep = t.start()
        assertEquals("x.onion", ep.onionAddress)
        assertEquals(1234, ep.port)
        assertEquals(1, t.startCalls)

        val target = TorEndpoint("y.onion", 80)
        val env = sampleBinaryEnvelope()
        t.send(target, env)
        assertEquals(1, t.sends.size)
        assertEquals(target, t.sends[0].first)
        assertEquals(env.packetId, t.sends[0].second.packetId)

        t.stop()
        assertEquals(1, t.stopCalls)
    }

    @Test
    fun recordingTorTransport_incoming_emit_deliversToFlow() = runBlocking {
        val t = RecordingTorTransport()
        val env = sampleBinaryEnvelope()
        val inc = TorIncomingEnvelope(TorEndpoint("src.onion", 80), env)
        assertTrue(t.tryEmitIncoming(inc))
    }

    @Test
    fun recordingTorBackend_recordsLifecycleAndPayloads() = runBlocking {
        val b = RecordingTorBackend()
        val ep = b.start(localPort = 9050)
        assertEquals(b.nextEndpoint, ep)
        assertEquals(listOf<Int?>(9050), b.startCalls)

        val tgt = TorEndpoint("peer.onion", 443)
        val payload = byteArrayOf(1, 2, 3)
        b.send(tgt, payload)
        assertEquals(1, b.sends.size)
        assertEquals(tgt, b.sends[0].first)
        assertContentEquals(payload, b.sends[0].second)

        b.stop()
        assertEquals(1, b.stopCalls.size)
    }

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
