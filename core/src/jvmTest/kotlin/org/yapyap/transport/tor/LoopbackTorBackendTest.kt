package org.yapyap.transport.tor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.backend.LoopbackTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.transport.DefaultTorTransport
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Loopback-only tests for [LoopbackTorBackend]: real TCP to 127.0.0.1, no Tor process,
 * so these run in the default suite (unlike `*TorRealBackendTransportIntegrationTest`).
 */
class LoopbackTorBackendTest {

    private fun backendUnderTest() = LoopbackTorBackend(
        config = MutableStateFlow(TorBackendConfig()),
    )

    @Test
    fun start_advertisesDistinctLoopbackEndpoints() = runBlocking {
        val a = backendUnderTest()
        val b = backendUnderTest()
        try {
            val endpointA = a.start()
            val endpointB = b.start()
            for (endpoint in listOf(endpointA, endpointB)) {
                assertTrue(endpoint.onionAddress.endsWith(".onion"), "must stay onion-shaped")
                assertTrue(endpoint.onionAddress.startsWith("loopback-"), "must be recognizable as loopback")
                assertTrue(endpoint.port in 1..65535)
            }
            assertNotEquals(endpointA, endpointB, "two instances must get different ports")
        } finally {
            runCatching { a.stop() }
            runCatching { b.stop() }
        }
    }

    @Test
    fun twoBackends_deliverPayloadBothDirections() = runBlocking {
        val a = backendUnderTest()
        val b = backendUnderTest()
        try {
            val endpointA = a.start()
            val endpointB = b.start()
            val payload = byteArrayOf(0x0a, 0x0b, 0x0c)

            withTimeout(10.seconds) {
                a.send(endpointB, payload)
                val atB = b.incomingFrames.first()
                assertEquals(endpointA, atB.source, "source must be the sender's advertised endpoint")
                assertContentEquals(payload, atB.payload)

                b.send(endpointA, payload)
                val atA = a.incomingFrames.first()
                assertEquals(endpointB, atA.source)
                assertContentEquals(payload, atA.payload)
            }
        } finally {
            runCatching { a.stop() }
            runCatching { b.stop() }
        }
    }

    @Test
    fun transportsOverLoopback_decodeBinaryEnvelope() = runBlocking {
        val backendA = backendUnderTest()
        val backendB = backendUnderTest()
        val transportA = DefaultTorTransport(backendA)
        val transportB = DefaultTorTransport(backendB)
        try {
            val endpointB = transportB.start()
            transportA.start()

            val out = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.MESSAGE,
                dispositionRequested = true,
                createdAt = epochSeconds(1_700_000_000L),
                expiresAt = epochSeconds(1_700_000_000L) + 1.hours,
                source = PeerId("0".repeat(64)),
                target = PeerId("1".repeat(64)),
                payload = byteArrayOf(0x01, 0x02),
            )
            val received = withTimeout(10.seconds) {
                transportA.send(endpointB, out)
                transportB.incoming.first()
            }
            assertEquals(out.packetId, received.envelope.packetId)
            assertContentEquals(out.payload, received.envelope.payload)
        } finally {
            runCatching { transportA.stop() }
            runCatching { transportB.stop() }
        }
    }

    @Test
    fun sendToNonLoopbackEndpoint_failsFast(): Unit = runBlocking {
        val backend = backendUnderTest()
        try {
            backend.start()
            val failure: TransportException.LoopbackError = assertFailsWith {
                withTimeout(10.seconds) {
                    backend.send(TorEndpoint("realpeerxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.onion", 80), byteArrayOf(1))
                }
            }
            assertTrue(failure.message.orEmpty().isNotBlank())
        } finally {
            runCatching { backend.stop() }
        }
    }

    @Test
    fun sendToDeadPort_failsFastInsteadOfRetrying(): Unit = runBlocking {
        val backend = backendUnderTest()
        var dead: TorEndpoint? = null
        val probe = backendUnderTest()
        try {
            // Bind then release a port so nothing listens on it anymore.
            dead = probe.start()
            probe.stop()
            backend.start()
            val failure: TransportException.LoopbackError = assertFailsWith {
                withTimeout(10.seconds) {
                    backend.send(dead, byteArrayOf(1))
                }
            }
            assertTrue(failure.message.orEmpty().isNotBlank())
        } finally {
            runCatching { backend.stop() }
            runCatching { probe.stop() }
        }
    }

    @Test
    fun oversizePayload_failsFast(): Unit = runBlocking {
        val backend = backendUnderTest()
        try {
            val endpoint = backend.start()
            val tooBig = ByteArray(TorBackendConfig().maxPayloadBytes + 1)
            val failure: TransportException.PayloadTooLarge = assertFailsWith {
                backend.send(endpoint, tooBig)
            }
            assertEquals(tooBig.size, failure.sizeBytes)
        } finally {
            runCatching { backend.stop() }
        }
    }

    @Test
    fun sendBeforeStart_fails(): Unit = runBlocking {
        val backend = backendUnderTest()
        try {
            val endpoint = backend.start()
            backend.stop()
            val failure: IllegalStateException = assertFailsWith {
                backend.send(endpoint, byteArrayOf(1))
            }
            assertTrue(failure.message.orEmpty().isNotBlank())
        } finally {
            runCatching { backend.stop() }
        }
    }
}
