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
import org.yapyap.logging.AppLog
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.testfixtures.PrintingAppLogger
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.transport.DefaultTorTransport
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Exercising [org.yapyap.transport.tor.backend.KmpTorBackend] and [DefaultTorTransport] against a real local Tor process.
 * These tests are **opt-in** (see `composeApp` Gradle: `-PintegrationTests=true`) because they are
 * slow and need a working Tor download/bootstrap on the host.
 */
class TorRealBackendTransportIntegrationTest {

    @BeforeTest
    fun installLogger() = AppLog.init(PrintingAppLogger())

    @AfterTest
    fun resetLogger() = AppLog.reset()
    @Test
    fun defaultTorTransport_withKmpTorBackend_sendsToSelfAndDecodesIncoming() = runBlocking {
        val tempDir = Path(SystemTemporaryDirectory, "yapyap-tor-it-${Uuid.random()}")
        SystemFileSystem.createDirectories(tempDir)
        val backend = KmpTorBackend(
            torStateRootPath = tempDir,
            config = MutableStateFlow(
                TorBackendConfig(
                    startupTimeout = 180.seconds,
                )
            ),
        )
        val transport = DefaultTorTransport(backend = backend)
        val local = PeerId("0".repeat(64))
        val remote = PeerId("1".repeat(64))
        val t0 = epochSeconds(1_700_000_000L)
        val out = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.MESSAGE,
            dispositionRequested = true,
            createdAt = t0,
            expiresAt = t0 + 1.hours,
            source = local,
            target = remote,
            payload = byteArrayOf(0x0a, 0x0b, 0x0c),
        )
        try {
            val localEndpoint = transport.start()
            // Dialing an onion before its descriptor is uploaded makes Tor cache
            // the miss and hold the stream for minutes; start() no longer waits
            // for bootstrap, so wait for the backend's own published signal.
            withTimeout(5.minutes) { backend.publishState.first { it == TorPublishState.PUBLISHED } }
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
            assertEquals(out.createdAt, received.envelope.createdAt)
            assertEquals(out.expiresAt, received.envelope.expiresAt)
            assertEquals(out.source, received.envelope.source)
            assertEquals(out.target, received.envelope.target)
            assertContentEquals(out.payload, received.envelope.payload)
            // A completed round trip means Tor bootstrapped; health must have
            // seen it through the STATUS_CLIENT events (parser vs real Tor lines).
            withTimeout(30_000L.milliseconds) { backend.health.first { it == TorHealth.Ready } }
            Unit
        } finally {
            runCatching { transport.stop() }
            runCatching { deleteRecursively(tempDir) }
        }
    }

    @Test
    fun defaultTorTransport_sendsMaximumSizeMessage() = runBlocking {
        val tempDir = Path(SystemTemporaryDirectory, "yapyap-tor-it-${Uuid.random()}")
        SystemFileSystem.createDirectories(tempDir)
        val backend = KmpTorBackend(
            torStateRootPath = tempDir,
            config = MutableStateFlow(
                TorBackendConfig(
                    startupTimeout = 180.seconds,
                )
            ),
        )
        val transport = DefaultTorTransport(backend = backend)
        val local = PeerId("0".repeat(64))
        val remote = PeerId("1".repeat(64))
        val t0 = epochSeconds(1_700_000_000L)
        val payload = ByteArray(TorBackendConfig().maxPayloadBytes - BinaryEnvelope.ENCODED_HEADER_BYTES)
        val out = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.MESSAGE,
            dispositionRequested = true,
            createdAt = t0,
            expiresAt = t0 + 1.hours,
            source = local,
            target = remote,
            payload = payload,
        )
        try {
            val localEndpoint = transport.start()
            // Dialing an onion before its descriptor is uploaded makes Tor cache
            // the miss and hold the stream for minutes; start() no longer waits
            // for bootstrap, so wait for the backend's own published signal.
            withTimeout(5.minutes) { backend.publishState.first { it == TorPublishState.PUBLISHED } }
            assertTrue(localEndpoint.onionAddress.endsWith(".onion"), "expected .onion from Tor")
            val received = withTimeout(5.minutes) {
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
            assertEquals(out.createdAt, received.envelope.createdAt)
            assertEquals(out.expiresAt, received.envelope.expiresAt)
            assertEquals(out.source, received.envelope.source)
            assertEquals(out.target, received.envelope.target)
            assertEquals(out.payload.size, received.envelope.payload.size)
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
