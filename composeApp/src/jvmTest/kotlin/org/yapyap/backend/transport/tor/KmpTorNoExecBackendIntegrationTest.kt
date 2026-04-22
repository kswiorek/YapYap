package org.yapyap.backend.transport.tor

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class KmpTorNoExecBackendIntegrationTest {

    @Test
    fun liveTorRoute() = runBlocking {
        val backend = KmpTorNoExecBackend(deviceId = "alice-phone")

        try {
            val localEndpoint = backend.start(localPort = 80)

            val payload = "live-tor-payload".encodeToByteArray()

            val inboundDeferred = async {
                withTimeout(180.seconds) {
                    backend.incomingFrames.first()
                }
            }

            backend.send(target = localEndpoint, payload = payload)

            val inbound = inboundDeferred.await()
            assertEquals(localEndpoint, inbound.source)
            assertContentEquals(payload, inbound.payload)
        } finally {
            backend.stop()
        }
    }
}