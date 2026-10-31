package org.yapyap.transport.tor

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.session.SessionStatus
import org.yapyap.transport.tor.backend.LoopbackTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.backend.TorStreamRecordType
import org.yapyap.transport.tor.backend.writeTorStreamRecord
import org.yapyap.transport.tor.transport.DefaultTorTransport
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Tor sessions (long-lived streams) end to end over [LoopbackTorBackend]: real TCP on
 * 127.0.0.1 and the real record protocol, no Tor daemon, so these run in the default suite.
 */
class TorSessionLoopbackTest {

    private val config = MutableStateFlow(TorBackendConfig())

    private fun transport(cfg: MutableStateFlow<TorBackendConfig> = config) =
        DefaultTorTransport(LoopbackTorBackend(cfg), cfg)

    private fun envelope(payload: Byte = 1) = BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = PacketType.MESSAGE,
        dispositionRequested = true,
        createdAt = epochSeconds(1_700_000_000L),
        expiresAt = epochSeconds(1_700_000_000L) + 1.hours,
        source = PeerId("0".repeat(64)),
        target = PeerId("1".repeat(64)),
        payload = byteArrayOf(payload),
    )

    private suspend fun DefaultTorTransport.awaitPhase(endpoint: TorEndpoint, phase: SessionPhase): SessionStatus =
        withTimeoutOrNull(10.seconds) { sessionStatesOf(endpoint).first { it.phase == phase } }
            ?: fail("${endpoint.onionAddress} never reached $phase; last state ${sessionStates.value[endpoint]}")

    private fun stopAll(vararg transports: DefaultTorTransport) = runBlocking {
        transports.forEach { runCatching { it.stop() } }
    }

    @Test
    fun openSession_connects_andCarriesEnvelopesBothWays() = runBlocking<Unit> {
        val a = transport()
        val b = transport()
        try {
            a.start()
            val endpointB = b.start()

            a.openSession(endpointB)
            a.awaitPhase(endpointB, SessionPhase.CONNECTED)
            assertTrue(a.hasSession(endpointB))

            val sent = envelope(7)
            val atB = withTimeout(10.seconds) {
                val received =
                    async(start = CoroutineStart.UNDISPATCHED) { b.incoming.first { it.envelope.packetId == sent.packetId } }
                a.send(endpointB, sent)
                received.await()
            }
            val link = assertNotNull(atB.link, "a session envelope names the stream it came on")

            // B answers on that stream without dialing A (it has no session to A of its own).
            val reply = envelope(8)
            val atA = withTimeout(10.seconds) {
                val received =
                    async(start = CoroutineStart.UNDISPATCHED) { a.incoming.first { it.envelope.packetId == reply.packetId } }
                b.send(atB.source, reply, via = link)
                received.await()
            }
            assertEquals(endpointB, atA.source, "on our own dialed stream the source is the endpoint we dialed")
            assertNotNull(atA.link)
        } finally {
            stopAll(a, b)
        }
    }

    @Test
    fun inboundStream_isNoSession_untilAdopted() = runBlocking<Unit> {
        val a = transport()
        val b = transport()
        try {
            val endpointA = a.start()
            val endpointB = b.start()
            a.openSession(endpointB)
            a.awaitPhase(endpointB, SessionPhase.CONNECTED)

            val first = envelope(1)
            val atB = withTimeout(10.seconds) {
                val received =
                    async(start = CoroutineStart.UNDISPATCHED) { b.incoming.first { it.envelope.packetId == first.packetId } }
                a.send(endpointB, first)
                received.await()
            }
            assertFalse(b.hasSession(endpointA), "an unauthenticated inbound stream is not a session")
            assertFalse(
                b.adoptInbound(atB.link!!, TorEndpoint("someone-else.onion", 80)),
                "only for the endpoint it claims"
            )

            assertTrue(b.adoptInbound(atB.link!!, endpointA))
            assertTrue(b.hasSession(endpointA))
            assertEquals(SessionPhase.CONNECTED, b.sessionStates.value[endpointA]?.phase)

            // B's own traffic to A now rides A's stream: it arrives on A's dialed link.
            val aLink = withTimeout(10.seconds) {
                val ping = envelope(2)
                val received =
                    async(start = CoroutineStart.UNDISPATCHED) { a.incoming.first { it.envelope.packetId == ping.packetId } }
                b.send(endpointA, ping)
                received.await().link
            }
            assertNotNull(aLink, "sent on the adopted stream, not as a one-shot frame")
        } finally {
            stopAll(a, b)
        }
    }

    @Test
    fun farEndStopping_closesTheSession() = runBlocking<Unit> {
        val a = transport()
        val b = transport()
        try {
            a.start()
            val endpointB = b.start()
            a.openSession(endpointB)
            a.awaitPhase(endpointB, SessionPhase.CONNECTED)

            b.stop()
            a.awaitPhase(endpointB, SessionPhase.CLOSED)
            assertFalse(a.hasSession(endpointB))
        } finally {
            stopAll(a, b)
        }
    }

    @Test
    fun dialToNobody_fails() = runBlocking<Unit> {
        val a = transport()
        try {
            a.start()
            // A port nothing listens on (bound then released).
            val dead = TorEndpoint("loopback-${freePort()}.onion", 80)
            a.openSession(dead)
            val state = a.awaitPhase(dead, SessionPhase.FAILED)
            assertNotNull(state.reason)
            assertFalse(a.hasSession(dead))
        } finally {
            stopAll(a)
        }
    }

    @Test
    fun silentFarEnd_isClosedAfterTheIdleTimeout() = runBlocking<Unit> {
        val fast = MutableStateFlow(
            TorBackendConfig(sessionKeepaliveInterval = 100.milliseconds, sessionIdleTimeout = 600.milliseconds),
        )
        val a = transport(fast)
        val selector = SelectorManager(Dispatchers.IO)
        val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
        try {
            a.start()
            val port = (server.localAddress as InetSocketAddress).port
            // Accepts the stream and goes quiet forever: a peer whose machine vanished.
            val silent = launch(Dispatchers.IO) {
                val socket = server.accept()
                val output = socket.openWriteChannel(autoFlush = true)
                writeTorStreamRecord(output, TorStreamRecordType.ACCEPT)
                awaitCancellation()
            }
            val endpoint = TorEndpoint("loopback-$port.onion", 80)
            a.openSession(endpoint)
            a.awaitPhase(endpoint, SessionPhase.CONNECTED)
            val closed = a.awaitPhase(endpoint, SessionPhase.CLOSED)
            assertTrue(
                closed.reason.orEmpty().contains("nothing received"),
                "closed by the idle check: ${closed.reason}"
            )
            silent.cancel()
        } finally {
            stopAll(a)
            server.close()
            selector.close()
        }
    }

    @Test
    fun bothSidesDialing_endUpOnOneStream_andStayConnected() = runBlocking<Unit> {
        val a = transport()
        val b = transport()
        try {
            val endpointA = a.start()
            val endpointB = b.start()
            a.openSession(endpointB)
            b.openSession(endpointA)
            a.awaitPhase(endpointB, SessionPhase.CONNECTED)
            b.awaitPhase(endpointA, SessionPhase.CONNECTED)

            // Each side learns (by the upper layer's authentication) that the other's stream is real.
            suspend fun adoptFrom(
                receiver: DefaultTorTransport,
                sender: DefaultTorTransport,
                senderEndpoint: TorEndpoint,
                receiverEndpoint: TorEndpoint
            ) {
                val hello = envelope(9)
                val arrived = withTimeout(10.seconds) {
                    val received = async(start = CoroutineStart.UNDISPATCHED) {
                        receiver.incoming.first { it.envelope.packetId == hello.packetId }
                    }
                    sender.send(receiverEndpoint, hello)
                    received.await()
                }
                receiver.adoptInbound(arrived.link!!, senderEndpoint)
            }
            adoptFrom(b, a, endpointA, endpointB)
            adoptFrom(a, b, endpointB, endpointA)
            delay(300)

            assertTrue(a.hasSession(endpointB))
            assertTrue(b.hasSession(endpointA))
            val probe = envelope(3)
            withTimeout(10.seconds) {
                val received =
                    async(start = CoroutineStart.UNDISPATCHED) { b.incoming.first { it.envelope.packetId == probe.packetId } }
                a.send(endpointB, probe)
                received.await()
            }
        } finally {
            stopAll(a, b)
        }
    }

    /**
     * The scenario the session design exists for: one device fanning a packet out to 40
     * peers, a quarter of which are gone. With sessions held open, the live peers get it
     * in about one write each, and the dead ones cost nothing at send time.
     */
    @Test
    fun fanOutToFortyPeers_withDeadOnesMixedIn_isNotHeldUp() = runBlocking<Unit> {
        val hub = transport()
        val peers = List(30) { transport() }
        try {
            hub.start()
            val live = peers.map { it.start() }
            val dead = List(10) { TorEndpoint("loopback-${freePort()}.onion", 80) }
            (live + dead).forEach { hub.openSession(it) }
            live.forEach { hub.awaitPhase(it, SessionPhase.CONNECTED) }
            dead.forEach { hub.awaitPhase(it, SessionPhase.FAILED) }

            val packet = envelope(42)
            val arrivals = peers.map { peer ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(10.seconds) { peer.incoming.first { it.envelope.packetId == packet.packetId } }
                }
            }
            val started = TimeSource.Monotonic.markNow()
            (live + dead).filter { hub.hasSession(it) }.map { endpoint ->
                async { hub.send(endpoint, packet) }
            }.awaitAll()
            arrivals.awaitAll()
            val elapsed = started.elapsedNow()
            println("fan-out to ${live.size} live peers (${dead.size} dead skipped): $elapsed")
            assertTrue(elapsed < 2.seconds, "fan-out took $elapsed")
        } finally {
            stopAll(hub, *peers.toTypedArray())
        }
    }

    private fun freePort(): Int = runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        val socket = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val port = (socket.localAddress as InetSocketAddress).port
        socket.close()
        selector.close()
        port
    }
}
