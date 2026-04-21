package org.yapyap.backend.transport.tor

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ManagedDesktopTorBackendIntegrationTest {

    @Test
    fun liveTorRouteBetweenTwoInstances() = runBlocking {
        if (!shouldRunLiveTorTest()) return@runBlocking

        val backendA = ManagedDesktopTorBackend(deviceId = "alice-phone")
        val backendB = ManagedDesktopTorBackend(deviceId = "bob-pi")

        try {
            val sourceEndpoint = backendA.start(localPort = 80)
            val target = backendB.start(localPort = 80)

            val payload = "live-tor-payload".encodeToByteArray()

            val inboundDeferred = async {
                withTimeout(180.seconds) {
                    backendB.incomingFrames.first()
                }
            }

            backendA.send(target = target, payload = payload)

            val inbound = inboundDeferred.await()
            assertEquals(sourceEndpoint, inbound.source)
            assertContentEquals(payload, inbound.payload)
        } finally {
            backendA.stop()
            backendB.stop()
        }
    }

    private fun shouldRunLiveTorTest(): Boolean {
        val enabled = System.getenv("YAPYAP_RUN_LIVE_TOR_TEST")?.equals("1") == true
        if (!enabled) return false

        val osName = System.getProperty("os.name").lowercase()
        if (!osName.contains("windows")) return false

        return ManagedDesktopTorBackend.anyBundledTorBinaryExists()
    }
}





