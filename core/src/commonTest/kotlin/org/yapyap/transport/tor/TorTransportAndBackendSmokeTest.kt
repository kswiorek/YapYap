package org.yapyap.transport.tor

import kotlinx.coroutines.runBlocking
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.packet.PacketType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorTransportAndBackendSmokeTest {

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
