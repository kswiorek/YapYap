package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.backend.testutil.testPeer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class JvmWebRtcBackendIntegrationTest {

    @Test
    fun twoPeersCanConnectAndExchangeData() = runBlocking {
        val peerA = testPeer("alice", "alice-phone", "alice1234567890abcdef1234567890abcdef1234567890abcdef.onion")
        val peerB = testPeer("bob", "bob-pi", "bob1234567890abcdef1234567890abcdef1234567890abcdef12.onion")

        val backendA = JvmWebRtcBackend()
        val backendB = JvmWebRtcBackend()
        backendA.start(peerA)
        backendB.start(peerB)

        val bridgeAReady = CompletableDeferred<Unit>()
        val bridgeBReady = CompletableDeferred<Unit>()
        val bridgeA: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            backendA.outgoingSignals
                .also { bridgeAReady.complete(Unit) }
                .collect { signal -> backendB.handleRemoteSignal(signal) }
        }
        val bridgeB: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            backendB.outgoingSignals
                .also { bridgeBReady.complete(Unit) }
                .collect { signal -> backendA.handleRemoteSignal(signal) }
        }
        bridgeAReady.await()
        bridgeBReady.await()

        try {
            val sessionId = "jvm-webrtc-session-1"
            val connectedA = async {
                withTimeout(20.seconds) {
                    backendA.sessionEvents.first {
                        it is WebRtcSessionEvent.Connected &&
                            it.sessionId == sessionId &&
                            it.peer == peerB.id
                    }
                }
            }
            val connectedB = async {
                withTimeout(20.seconds) {
                    backendB.sessionEvents.first {
                        it is WebRtcSessionEvent.Connected &&
                            it.sessionId == sessionId &&
                            it.peer == peerA.id
                    }
                }
            }

            backendA.openSession(target = peerB.id, sessionId = sessionId)
            connectedA.await()
            connectedB.await()

            val payload = "hello-webrtc-jvm".encodeToByteArray()
            val received = async {
                withTimeout(20.seconds) {
                    backendB.incomingDataFrames.first { it.sessionId == sessionId && it.source == peerA.id }
                }
            }
            backendA.sendData(sessionId = sessionId, target = peerB.id, payload = payload)

            val frame = received.await()
            assertEquals(sessionId, frame.sessionId)
            assertEquals(peerA.id, frame.source)
            assertContentEquals(payload, frame.payload)
        } finally {
            backendA.stop()
            backendB.stop()
            bridgeA.cancel()
            bridgeB.cancel()
        }
    }
}
