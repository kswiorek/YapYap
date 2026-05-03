package org.yapyap.backend.transport.tor

import io.matthewnelson.kmp.file.File
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerId

/**
 * Exercising [KmpTorNoExecBackend] and [DefaultTorTransport] against a real local Tor process.
 * These tests are **opt-in** (see `composeApp` Gradle: `-PintegrationTests=true`) because they are
 * slow and need a working Tor download/bootstrap on the host.
 */
@OptIn(ExperimentalPathApi::class)
class TorRealBackendTransportIntegrationTest {

    @Test
    fun defaultTorTransport_withKmpTorNoExecBackend_sendsToSelfAndDecodesIncoming() = runBlocking {
        val tempDir = Files.createTempDirectory("yapyap-tor-it")
        val deviceId = PeerId("it-" + UUID.randomUUID())
        val torStateRoot = File(tempDir.absolutePathString())
        val backend = KmpTorNoExecBackend(
            deviceId = deviceId,
            torStateRootPath = torStateRoot,
            config = TorBackendConfig(
                startupTimeoutMillis = 180_000L,
            ),
        )
        val transport = DefaultTorTransport(backend = backend)
        val local = PeerId("0".repeat(64))
        val remote = PeerId("1".repeat(64))
        val t0 = 1_700_000_000L
        val out = BinaryEnvelope(
            packetId = PacketId.random(),
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = t0,
            expiresAtEpochSeconds = t0 + 3_600L,
            source = local,
            target = remote,
            payload = byteArrayOf(0x0a, 0x0b, 0x0c),
        )
        try {
            val localEndpoint = transport.start()
            assertTrue(localEndpoint.onionAddress.endsWith(".onion"), "expected .onion from Tor")
            val received = withTimeout(300_000L) {
                coroutineScope {
                    val waitInbound = async {
                        transport.incoming.first()
                    }
                    transport.send(localEndpoint, out)
                    waitInbound.await()
                }
            }
            assertEquals(localEndpoint.onionAddress, received.source.onionAddress)
            assertEquals(localEndpoint.port, received.source.port)
            assertEquals(out.packetType, received.envelope.packetType)
            assertEquals(out.packetId, received.envelope.packetId)
            assertEquals(out.createdAtEpochSeconds, received.envelope.createdAtEpochSeconds)
            assertEquals(out.expiresAtEpochSeconds, received.envelope.expiresAtEpochSeconds)
            assertEquals(out.source, received.envelope.source)
            assertEquals(out.target, received.envelope.target)
            assertContentEquals(out.payload, received.envelope.payload)
        } finally {
            runCatching { transport.stop() }
            runCatching { tempDir.deleteRecursively() }
        }
    }
}
