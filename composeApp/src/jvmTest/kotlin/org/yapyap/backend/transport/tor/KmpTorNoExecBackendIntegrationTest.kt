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
    fun liveTorRouteBetweenTwoInstances() = runBlocking {
        if (!shouldRunLiveTorTest()) return@runBlocking

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

    private fun shouldRunLiveTorTest(): Boolean {
        val enabled = System.getenv("YAPYAP_RUN_LIVE_TOR_TEST")?.equals("1") == true
        if (!enabled) return false

        val osName = System.getProperty("os.name").lowercase()
        return osName.contains("windows") || osName.contains("linux")
    }
}
