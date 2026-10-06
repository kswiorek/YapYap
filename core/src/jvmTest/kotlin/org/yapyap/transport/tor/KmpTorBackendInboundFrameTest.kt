package org.yapyap.transport.tor

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.backend.KmpTorBackend
import org.yapyap.transport.tor.backend.TorBackendConfig
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Loopback-only tests for [KmpTorBackend.handleInboundConnection]: no Tor process is
 * started, so these run in the default suite (unlike `*TorRealBackendTransportIntegrationTest`).
 *
 * Covers the teardown race where an in-flight frame read is cancelled by backend shutdown:
 * cancellation must propagate as [CancellationException], never surface as a
 * [TransportException.TorException.TransportFrameError] ("Failed to parse transport frame:
 * Job was cancelled") uncaught failure that poisons later tests in the worker.
 */
class KmpTorBackendInboundFrameTest {

    private fun backendUnderTest() = KmpTorBackend(
        torStateRootPath = Path("unused-backend-root"),
        config = MutableStateFlow(TorBackendConfig()),
    )

    private suspend fun CoroutineScope.withLoopbackPair(
        block: suspend (serverSide: Socket, peerOut: ByteWriteChannel) -> Unit,
    ) {
        val selector = SelectorManager(Dispatchers.IO)
        try {
            val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
            try {
                val port = (server.localAddress as InetSocketAddress).port
                val accepted = async(Dispatchers.IO) { server.accept() }
                val peer = aSocket(selector).tcp().connect("127.0.0.1", port)
                try {
                    block(accepted.await(), peer.openWriteChannel(autoFlush = true))
                } finally {
                    peer.close()
                }
            } finally {
                server.close()
            }
        } finally {
            selector.close()
        }
    }

    @Test
    fun cancelledMidFrameRead_rethrowsCancellationInsteadOfFrameError() = runBlocking {
        val backend = backendUnderTest()
        withLoopbackPair { serverSide, peerOut ->
            // Push the reader past the channel open into a blocked mid-frame read.
            peerOut.writeInt(FRAME_MAGIC)
            peerOut.flush()
            val reader = async(Dispatchers.IO) { backend.handleInboundConnection(serverSide) }
            delay(500)
            reader.cancel()
            // await() rethrows the child's failure untouched: raw cancellation passes
            // through, while a wrapped TransportFrameError would surface here instead.
            assertFailsWith<CancellationException> { reader.await() }
        }
    }

    @Test
    fun validFrame_isEmitted() = runBlocking {
        val backend = backendUnderTest()
        withLoopbackPair { serverSide, peerOut ->
            // Undispatched: the subscription is established before anything is
            // written, so the emission below always has a live collector.
            val collected = async(start = CoroutineStart.UNDISPATCHED) {
                backend.incomingFrames.first()
            }
            val host = "peer.onion".encodeToByteArray()
            val payload = byteArrayOf(1, 2, 3)
            peerOut.writeInt(FRAME_MAGIC)
            peerOut.writeByte(host.size.toByte())
            peerOut.writeFully(host)
            peerOut.writeShort(80.toShort())
            peerOut.writeInt(payload.size)
            peerOut.writeFully(payload)
            peerOut.flush()

            withTimeout(10.seconds) {
                backend.handleInboundConnection(serverSide)
                val frame = collected.await()
                assertEquals("peer.onion", frame.source.onionAddress)
                assertEquals(80, frame.source.port)
                assertContentEquals(payload, frame.payload)
            }
        }
    }

    @Test
    fun corruptFrame_throwsTransportFrameError() = runBlocking {
        val backend = backendUnderTest()
        withLoopbackPair { serverSide, peerOut ->
            peerOut.writeInt(0xDEADBEEF.toInt())
            peerOut.flush()

            val failure = assertFailsWith<TransportException.TorException.TransportFrameError> {
                withTimeout(10.seconds) { backend.handleInboundConnection(serverSide) }
            }
            assertTrue(failure.message.orEmpty().startsWith("Failed to parse transport frame:"))
        }
    }

    private companion object {
        /** Mirrors the private `FRAME_MAGIC` of [KmpTorBackend] (`0x59595431`). */
        const val FRAME_MAGIC: Int = 0x59595431
    }
}
