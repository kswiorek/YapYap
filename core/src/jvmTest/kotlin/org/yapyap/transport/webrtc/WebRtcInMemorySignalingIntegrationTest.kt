package org.yapyap.transport.webrtc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.TransportException
import org.yapyap.transport.webrtc.backend.JvmWebRtcBackend
import org.yapyap.transport.webrtc.backend.WebRtcBackendConfig
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Two JVM peer stacks exchange OFFER/ANSWER/ICE via in-memory forwarding (no Tor/signaling server).
 * Data still uses the real WebRTC stack (ICE/STUN may hit the network).
 *
 * Enabled only with Gradle `-PintegrationTests=true` (see `jvmTest` task filter in `composeApp/build.gradle.kts`).
 */
class WebRtcInMemorySignalingIntegrationTest {

    @Test
    fun defaultWebRtcTransport_twoPeers_relayBootstrapSignals_andDeliverEnvelope() = runBlocking {
        val peerA = PeerId("a".repeat(64))
        val peerB = PeerId("b".repeat(64))

        val config = MutableStateFlow(WebRtcBackendConfig())

        val backendA = JvmWebRtcBackend(config)
        val backendB = JvmWebRtcBackend(config)
        val alice = DefaultWebRtcTransport(backendA)
        val bob = DefaultWebRtcTransport(backendB)

        val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relayJobs = mutableListOf<Job>()
        try {
            alice.start(peerA)
            bob.start(peerB)

            relayJobs += relayScope.launch {
                alice.outgoingBootstrapSignals.collect { sig ->
                    bob.handleBootstrapSignal(sig)
                }
            }
            relayJobs += relayScope.launch {
                bob.outgoingBootstrapSignals.collect { sig ->
                    alice.handleBootstrapSignal(sig)
                }
            }

            val (received, envelope) =
                withTimeout(30L.seconds) {
                    coroutineScope {
                        val inbound = async {
                            bob.incomingEnvelopes.first { it.source == peerA }
                        }
                        yield()

                        alice.openSession(peerB)

                        alice.sessionStates.first {
                            it[peerB]?.phase == WebRtcSessionPhase.CONNECTED
                        }
                        bob.sessionStates.first {
                            it[peerA]?.phase == WebRtcSessionPhase.CONNECTED
                        }

                        val t0 = epochSeconds(1_800_000_000L)
                        val out =
                            BinaryEnvelope(
                                packetId = Uuid.random(),
                                packetType = PacketType.MESSAGE,
                                dispositionRequested = true,
                                createdAt = t0,
                                expiresAt = t0 + 1.hours,
                                source = peerA,
                                target = peerB,
                                payload = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                            )

                        sendEnvelopeWhenChannelReady(alice, peerB, out)

                        Pair(inbound.await(), out)
                    }
                }

            assertEquals(peerA, received.source)
            assertEquals(envelope.packetId, received.envelope.packetId)
            assertEquals(envelope.packetType, received.envelope.packetType)
            assertContentEquals(envelope.payload, received.envelope.payload)
        } finally {
            relayJobs.forEach { it.cancel() }
            relayScope.cancel()
            runCatching { alice.stop() }
            runCatching { bob.stop() }
        }
    }

    /**
     * Delivers the largest envelope the configured limit admits
     * (`maxPayloadBytes - ENCODED_HEADER_BYTES`). Retried with fresh packet ids: the first
     * seconds after connect can flap and eat one in-flight message, which router ACK/outbox
     * retry absorbs in production but a single-shot await cannot.
     */
    @Test
    fun defaultWebRtcTransport_twoPeers_deliversMaxSize() = runBlocking {
        val peerA = PeerId("a".repeat(64))
        val peerB = PeerId("b".repeat(64))

        val config = MutableStateFlow(WebRtcBackendConfig())

        val backendA = JvmWebRtcBackend(config)
        val backendB = JvmWebRtcBackend(config)
        val alice = DefaultWebRtcTransport(backendA)
        val bob = DefaultWebRtcTransport(backendB)

        val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relayJobs = mutableListOf<Job>()

        val payload =
            ByteArray(size = WebRtcBackendConfig().maxPayloadBytes - BinaryEnvelope.ENCODED_HEADER_BYTES) { (it % 251).toByte() }
        try {
            alice.start(peerA)
            bob.start(peerB)

            relayJobs += relayScope.launch {
                alice.outgoingBootstrapSignals.collect { sig ->
                    bob.handleBootstrapSignal(sig)
                }
            }
            relayJobs += relayScope.launch {
                bob.outgoingBootstrapSignals.collect { sig ->
                    alice.handleBootstrapSignal(sig)
                }
            }

            val (received, envelope) =
                withTimeout(120L.seconds) {
                    coroutineScope {
                        alice.openSession(peerB)

                        alice.sessionStates.first {
                            it[peerB]?.phase == WebRtcSessionPhase.CONNECTED
                        }
                        bob.sessionStates.first {
                            it[peerA]?.phase == WebRtcSessionPhase.CONNECTED
                        }

                        repeat(3) {
                            val candidate = BinaryEnvelope(
                                packetId = Uuid.random(),
                                packetType = PacketType.MESSAGE,
                                dispositionRequested = true,
                                createdAt = epochSeconds(1_800_000_000L),
                                expiresAt = epochSeconds(1_800_000_000L) + 1.hours,
                                source = peerA,
                                target = peerB,
                                payload = payload,
                            )
                            sendEnvelopeWhenChannelReady(alice, peerB, candidate)
                            // Fresh subscription per attempt (replay covers already-delivered).
                            val got = try {
                                withTimeout(15.seconds) {
                                    bob.incomingEnvelopes.first {
                                        it.source == peerA && it.envelope.packetId == candidate.packetId
                                    }
                                }
                            } catch (e: TimeoutCancellationException) {
                                null
                            }
                            if (got != null) return@coroutineScope Pair(got, candidate)
                        }
                        error("Large envelope was not delivered in 3 attempts")
                    }
                }

            assertEquals(peerA, received.source)
            assertEquals(envelope.packetId, received.envelope.packetId)
            assertEquals(envelope.packetType, received.envelope.packetType)
            assertEquals(envelope.payload.size, received.envelope.payload.size)
            assertContentEquals(envelope.payload, received.envelope.payload)
        } finally {
            relayJobs.forEach { it.cancel() }
            relayScope.cancel()
            runCatching { alice.stop() }
            runCatching { bob.stop() }
        }
    }

    /**
     * Envelopes over the configured limit fail fast with [TransportException.PayloadTooLarge]
     * instead of being silently lost on the wire.
     */
    @Test
    fun defaultWebRtcTransport_twoPeers_rejectsOversizeEnvelopeFast() = runBlocking {
        val peerA = PeerId("a".repeat(64))
        val peerB = PeerId("b".repeat(64))

        val config = MutableStateFlow(WebRtcBackendConfig())

        val backendA = JvmWebRtcBackend(config)
        val backendB = JvmWebRtcBackend(config)
        val alice = DefaultWebRtcTransport(backendA)
        val bob = DefaultWebRtcTransport(backendB)

        val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relayJobs = mutableListOf<Job>()

        // Payload alone hits the limit, so the encoded envelope exceeds it.
        val payload = ByteArray(size = WebRtcBackendConfig().maxPayloadBytes) { (it % 251).toByte() }
        try {
            alice.start(peerA)
            bob.start(peerB)

            relayJobs += relayScope.launch {
                alice.outgoingBootstrapSignals.collect { sig ->
                    bob.handleBootstrapSignal(sig)
                }
            }
            relayJobs += relayScope.launch {
                bob.outgoingBootstrapSignals.collect { sig ->
                    alice.handleBootstrapSignal(sig)
                }
            }

            alice.openSession(peerB)
            alice.sessionStates.first {
                it[peerB]?.phase == WebRtcSessionPhase.CONNECTED
            }

            val out = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.MESSAGE,
                dispositionRequested = true,
                createdAt = epochSeconds(1_800_000_000L),
                expiresAt = epochSeconds(1_800_000_000L) + 1.hours,
                source = peerA,
                target = peerB,
                payload = payload,
            )

            val failure = assertFailsWith<TransportException.PayloadTooLarge> {
                withTimeout(15.seconds) { alice.sendEnvelope(peerB, out) }
            }
            // The limit applies to the on-wire message (encoded envelope), not the payload.
            assertEquals(out.encode().size, failure.sizeBytes)
            assertEquals(WebRtcBackendConfig().maxPayloadBytes, failure.limitBytes)
        } finally {
            relayJobs.forEach { it.cancel() }
            relayScope.cancel()
            runCatching { alice.stop() }
            runCatching { bob.stop() }
        }
    }

    /**
     * The previously-silent transition: when one side tears its session down, the remote
     * side must observe a terminal state instead of sticking at CONNECTED forever. The
     * local close is orderly (deterministic CLOSED); the remote side gets no signal at
     * all, so whatever it observes arrives purely through channel/connection observers —
     * accept either terminal phase, since native stacks may report CLOSED or FAILED first.
     */
    @Test
    fun remoteClose_surfacesTerminalState() = runBlocking {
        val peerA = PeerId("a".repeat(64))
        val peerB = PeerId("b".repeat(64))

        val config = MutableStateFlow(WebRtcBackendConfig())

        val backendA = JvmWebRtcBackend(config)
        val backendB = JvmWebRtcBackend(config)
        val alice = DefaultWebRtcTransport(backendA)
        val bob = DefaultWebRtcTransport(backendB)

        val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relayJobs = mutableListOf<Job>()
        try {
            alice.start(peerA)
            bob.start(peerB)

            relayJobs += relayScope.launch {
                alice.outgoingBootstrapSignals.collect { sig ->
                    bob.handleBootstrapSignal(sig)
                }
            }
            relayJobs += relayScope.launch {
                bob.outgoingBootstrapSignals.collect { sig ->
                    alice.handleBootstrapSignal(sig)
                }
            }

            alice.openSession(peerB)
            withTimeout(30L.seconds) {
                alice.sessionStates.first {
                    it[peerB]?.phase == WebRtcSessionPhase.CONNECTED
                }
                bob.sessionStates.first {
                    it[peerA]?.phase == WebRtcSessionPhase.CONNECTED
                }
            }

            alice.closeSession(peerB)

            val (aliceState, bobState) = withTimeout(60L.seconds) {
                val aliceClosed = alice.sessionStates.first {
                    it[peerB]?.phase == WebRtcSessionPhase.CLOSED
                }
                val bobTerminal = bob.sessionStates.first {
                    val phase = it[peerA]?.phase
                    phase == WebRtcSessionPhase.CLOSED || phase == WebRtcSessionPhase.FAILED
                }
                aliceClosed to bobTerminal
            }
            assertEquals(WebRtcSessionPhase.CLOSED, aliceState[peerB]?.phase)
            val bobPhase = bobState[peerA]?.phase
            assertTrue(
                bobPhase == WebRtcSessionPhase.CLOSED || bobPhase == WebRtcSessionPhase.FAILED,
                "expected bob to observe a terminal state, got $bobPhase",
            )
        } finally {
            relayJobs.forEach { it.cancel() }
            relayScope.cancel()
            runCatching { alice.stop() }
            runCatching { bob.stop() }
        }
    }

    /**
     * CONNECTED now means the envelope channel is open for send (the transport only
     * reports it on channel OPEN), so the first send normally succeeds immediately.
     * The retry loop is retained as a safety net against residual native races.
     */
    private suspend fun sendEnvelopeWhenChannelReady(
        transport: DefaultWebRtcTransport,
        target: PeerId,
        envelope: BinaryEnvelope,
    ) {
        withTimeout(90_000L.milliseconds) {
            var last: Exception? = null
            repeat(300) {
                try {
                    transport.sendEnvelope(target, envelope)
                    return@withTimeout
                } catch (e: IllegalStateException) {
                    last = e
                    delay(100L.milliseconds)
                }
            }
            throw AssertionError("Timed out waiting for data channel to open", last)
        }
    }
}
