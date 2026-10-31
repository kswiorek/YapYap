package org.yapyap.transport.tor

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.backend.*
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

    /** Raw connection to [target]'s listener (what an older build or our own probe sends). */
    private suspend fun <T> rawConnection(
        target: TorEndpoint,
        block: suspend (ByteReadChannel, ByteWriteChannel) -> T
    ): T {
        val selector = SelectorManager(Dispatchers.IO)
        val socket = aSocket(selector).tcp().connect("127.0.0.1", LoopbackTorBackend.loopbackPortFor(target))
        try {
            return block(socket.openReadChannel(), socket.openWriteChannel(autoFlush = false))
        } finally {
            socket.close()
            selector.close()
        }
    }

    @Test
    fun olderBuildsOneShotFrame_isStillReceived() = runBlocking {
        val backend = backendUnderTest()
        try {
            val endpoint = backend.start()
            val sender = TorEndpoint("olderbuild.onion", 80)
            val payload = byteArrayOf(0x0a, 0x0b, 0x0c)
            withTimeout(10.seconds) {
                val received = async(start = CoroutineStart.UNDISPATCHED) { backend.incomingFrames.first() }
                rawConnection(endpoint) { _, output ->
                    writeTorTransportFrame(output, sender, payload)
                    output.flush()
                }
                val frame = received.await()
                assertEquals(sender, frame.source)
                assertContentEquals(payload, frame.payload)
            }
        } finally {
            runCatching { backend.stop() }
        }
    }

    @Test
    fun selfProbe_getsItsNonceEchoedBack() = runBlocking {
        val backend = backendUnderTest()
        try {
            val endpoint = backend.start()
            val nonce = ByteArray(TOR_PROBE_NONCE_SIZE) { it.toByte() }
            val echoed = withTimeout(10.seconds) {
                rawConnection(endpoint) { input, output ->
                    writeTorProbe(output, nonce)
                    output.flush()
                    input.readExact(TOR_PROBE_NONCE_SIZE)
                }
            }
            assertContentEquals(nonce, echoed)
        } finally {
            runCatching { backend.stop() }
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
    fun dialToNonLoopbackEndpoint_failsFast(): Unit = runBlocking {
        val backend = backendUnderTest()
        try {
            backend.start()
            val failure: TransportException.LoopbackError = assertFailsWith {
                withTimeout(10.seconds) {
                    backend.openStream(
                        TorEndpoint(
                            "realpeerxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.onion",
                            80
                        )
                    )
                }
            }
            assertTrue(failure.message.orEmpty().isNotBlank())
        } finally {
            runCatching { backend.stop() }
        }
    }

    @Test
    fun dialToDeadPort_failsFastInsteadOfRetrying(): Unit = runBlocking {
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
                    backend.openStream(dead)
                }
            }
            assertTrue(failure.message.orEmpty().isNotBlank())
        } finally {
            runCatching { backend.stop() }
            runCatching { probe.stop() }
        }
    }

    @Test
    fun oversizeEnvelope_failsBeforeAnyDial(): Unit = runBlocking {
        val transport = DefaultTorTransport(backendUnderTest())
        try {
            transport.start()
            val tooBig = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.MESSAGE,
                dispositionRequested = false,
                createdAt = epochSeconds(1_700_000_000L),
                expiresAt = epochSeconds(1_700_000_000L) + 1.hours,
                source = PeerId("0".repeat(64)),
                target = PeerId("1".repeat(64)),
                payload = ByteArray(TorBackendConfig().maxPayloadBytes + 1),
            )
            assertFailsWith<TransportException.PayloadTooLarge> {
                transport.send(TorEndpoint("loopback-1.onion", 1), tooBig)
            }
        } finally {
            runCatching { transport.stop() }
        }
    }

    @Test
    fun dialAfterStop_fails(): Unit = runBlocking {
        val backend = backendUnderTest()
        try {
            val endpoint = backend.start()
            backend.stop()
            val failure: IllegalStateException = assertFailsWith {
                backend.openStream(endpoint)
            }
            assertTrue(failure.message.orEmpty().isNotBlank())
        } finally {
            runCatching { backend.stop() }
        }
    }
}
