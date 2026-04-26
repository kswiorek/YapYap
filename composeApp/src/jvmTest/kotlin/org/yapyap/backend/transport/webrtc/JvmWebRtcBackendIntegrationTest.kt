package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.backend.testutil.testDevice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class JvmWebRtcBackendIntegrationTest {

    @Test
    fun twoPeersCanConnectAndExchangeData() = runBlocking {
        val peerA = testDevice("alice", "alice-phone", "alice1234567890abcdef1234567890abcdef1234567890abcdef.onion")
        val peerB = testDevice("bob", "bob-pi", "bob1234567890abcdef1234567890abcdef1234567890abcdef12.onion")

        val backendA = JvmWebRtcBackend()
        val backendB = JvmWebRtcBackend()
        backendA.start(peerA.address)
        backendB.start(peerB.address)

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
                            it.peer == peerB.address
                    }
                }
            }
            val connectedB = async {
                withTimeout(20.seconds) {
                    backendB.sessionEvents.first {
                        it is WebRtcSessionEvent.Connected &&
                            it.sessionId == sessionId &&
                            it.peer == peerA.address
                    }
                }
            }

            backendA.openSession(target = peerB.address, sessionId = sessionId)
            connectedA.await()
            connectedB.await()

            val payload = "hello-webrtc-jvm".encodeToByteArray()
            val received = async {
                withTimeout(20.seconds) {
                    backendB.incomingDataFrames.first { it.sessionId == sessionId && it.source == peerA.address }
                }
            }
            sendWhenReady(
                backend = backendA,
                sessionId = sessionId,
                target = peerB.address,
                payload = payload,
            )

            val frame = received.await()
            assertEquals(sessionId, frame.sessionId)
            assertEquals(peerA.address, frame.source)
            assertContentEquals(payload, frame.payload)
        } finally {
            backendA.stop()
            backendB.stop()
            bridgeA.cancel()
            bridgeB.cancel()
        }
    }

    private suspend fun sendWhenReady(
        backend: WebRtcBackend,
        sessionId: String,
        target: org.yapyap.backend.protocol.DeviceAddress,
        payload: ByteArray,
    ) {
        withTimeout(20.seconds) {
            while (true) {
                val sent = runCatching {
                    backend.sendData(sessionId = sessionId, target = target, payload = payload)
                }.isSuccess
                if (sent) return@withTimeout
                delay(100.milliseconds)
            }
        }
    }
}
