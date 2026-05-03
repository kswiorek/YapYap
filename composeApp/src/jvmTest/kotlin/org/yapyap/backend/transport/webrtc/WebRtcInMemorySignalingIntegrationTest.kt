package org.yapyap.backend.transport.webrtc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionPhase

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
        val sessionId = "signaling-it-${System.nanoTime()}"
        val epochSeconds = System.currentTimeMillis() / 1_000L

        val backendA = JvmWebRtcBackend()
        val backendB = JvmWebRtcBackend()
        val alice = DefaultWebRtcTransport(backendA)
        val bob = DefaultWebRtcTransport(backendB)

        val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relayJobs = mutableListOf<Job>()
        try {
            alice.start(peerA)
            bob.start(peerB)

            relayJobs += relayScope.launch {
                alice.outgoingBootstrapSignals.collect { sig ->
                    bob.handleBootstrapSignal(sig, epochSeconds)
                }
            }
            relayJobs += relayScope.launch {
                bob.outgoingBootstrapSignals.collect { sig ->
                    alice.handleBootstrapSignal(sig, epochSeconds)
                }
            }

            val (received, envelope) =
                withTimeout(180_000L) {
                    coroutineScope {
                        val inbound = async {
                            bob.incomingEnvelopes.first { it.sessionId == sessionId }
                        }
                        yield()

                        alice.openSession(peerB, sessionId)

                        alice.sessionStates.first {
                            it.sessionId == sessionId && it.phase == WebRtcSessionPhase.CONNECTED
                        }
                        bob.sessionStates.first {
                            it.sessionId == sessionId && it.phase == WebRtcSessionPhase.CONNECTED
                        }

                        val t0 = 1_800_000_000L
                        val out =
                            BinaryEnvelope(
                                packetId = PacketId.random(),
                                packetType = PacketType.MESSAGE,
                                createdAtEpochSeconds = t0,
                                expiresAtEpochSeconds = t0 + 3_600L,
                                source = peerA,
                                target = peerB,
                                payload = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                            )

                        sendEnvelopeWhenChannelReady(alice, sessionId, peerB, out)

                        Pair(inbound.await(), out)
                    }
                }

            assertEquals(sessionId, received.sessionId)
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
     * Offer-side may report CONNECTED before the outbound data channel is open for send.
     */
    private suspend fun sendEnvelopeWhenChannelReady(
        transport: DefaultWebRtcTransport,
        sessionId: String,
        target: PeerId,
        envelope: BinaryEnvelope,
    ) {
        withTimeout(90_000L) {
            var last: Exception? = null
            repeat(300) {
                try {
                    transport.sendEnvelope(sessionId, target, envelope)
                    return@withTimeout
                } catch (e: IllegalStateException) {
                    last = e
                    delay(100L)
                }
            }
            throw AssertionError("Timed out waiting for data channel to open", last)
        }
    }
}
