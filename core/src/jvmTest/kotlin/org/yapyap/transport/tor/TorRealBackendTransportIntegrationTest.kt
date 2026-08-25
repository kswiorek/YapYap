package org.yapyap.transport.tor

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketType
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.transport.DefaultTorTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Exercising [org.yapyap.transport.tor.backend.KmpTorBackend] and [DefaultTorTransport] against a real local Tor process.
 * These tests are **opt-in** (see `composeApp` Gradle: `-PintegrationTests=true`) because they are
 * slow and need a working Tor download/bootstrap on the host.
 */
class TorRealBackendTransportIntegrationTest {
    @Test
    fun defaultTorTransport_withKmpTorNoExecBackend_sendsToSelfAndDecodesIncoming() = runBlocking {
        val tempDir = Path(SystemTemporaryDirectory, "yapyap-tor-it-${Uuid.random()}")
        SystemFileSystem.createDirectories(tempDir)
        val backend = KmpTorBackend(
            torStateRootPath = tempDir,
            config = MutableStateFlow(TorBackendConfig(
                startupTimeout = 180.seconds,
            )),
        )
        val transport = DefaultTorTransport(backend = backend)
        val local = PeerId("0".repeat(64))
        val remote = PeerId("1".repeat(64))
        val t0 = 1_700_000_000L
        val out = BinaryEnvelope(
            packetId = Uuid.random(),
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
            val received = withTimeout(300_000L.milliseconds) {
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
            runCatching { deleteRecursively(tempDir) }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
