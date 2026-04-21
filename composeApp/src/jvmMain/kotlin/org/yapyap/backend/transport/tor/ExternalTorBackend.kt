package org.yapyap.backend.transport.tor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.backend.protocol.TorEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Attach-mode Tor backend that reuses an already running Tor SOCKS endpoint.
 *
 * This skeleton currently supports outbound send and lifecycle wiring.
 * Inbound reception can be added later via a pluggable local listener strategy.
 */
class ExternalTorBackend(
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int,
    private val localEndpoint: TorEndpoint,
) : TorBackend {

    private val inboundFlow = MutableSharedFlow<TorIncomingFrame>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<TorIncomingFrame> = inboundFlow.asSharedFlow()

    private var started = false

    override suspend fun start(localPort: Int): TorEndpoint {
        require(localPort in 1..65535) { "localPort must be in range 1..65535" }
        require(localEndpoint.port == localPort) {
            "ExternalTorBackend local endpoint port ${localEndpoint.port} must match requested localPort $localPort"
        }
        started = true
        return localEndpoint
    }

    override suspend fun stop() {
        started = false
    }

    override suspend fun send(target: TorEndpoint, payload: ByteArray) {
        check(started) { "Tor backend must be started before send" }

        Socket(socksHost, socksPort).use { socket ->
            socket.soTimeout = 120_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            performSocks5Handshake(target, input, output)
            writeTransportFrame(output, localEndpoint, payload)
            output.flush()
        }
    }

    private fun performSocks5Handshake(
        target: TorEndpoint,
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        val methodResponse = readNBytesStrict(input, 2)
        require(methodResponse[0].toInt() == 0x05) { "Invalid SOCKS version in method response" }
        require(methodResponse[1].toInt() == 0x00) { "SOCKS server rejected no-auth method" }

        val hostBytes = target.onionAddress.encodeToByteArray()
        require(hostBytes.size <= 255) { "Target onion host is too long" }

        output.writeByte(0x05)
        output.writeByte(0x01)
        output.writeByte(0x00)
        output.writeByte(0x03)
        output.writeByte(hostBytes.size)
        output.write(hostBytes)
        output.writeByte((target.port ushr 8) and 0xff)
        output.writeByte(target.port and 0xff)
        output.flush()

        val header = readNBytesStrict(input, 4)
        require(header[0].toInt() == 0x05) { "Invalid SOCKS version in connect response" }
        require(header[1].toInt() == 0x00) { "SOCKS connect failed with code ${header[1].toInt()}" }

        val addressLength = when (header[3].toInt()) {
            0x01 -> 4
            0x03 -> input.readUnsignedByte()
            0x04 -> 16
            else -> error("Unsupported SOCKS bind address type ${header[3].toInt()}")
        }

        readNBytesStrict(input, addressLength + 2)
    }

    private fun writeTransportFrame(
        output: DataOutputStream,
        source: TorEndpoint,
        payload: ByteArray,
    ) {
        val sourceHost = source.onionAddress.encodeToByteArray()
        require(sourceHost.size <= 255) { "Source onion host is too long" }

        output.writeInt(FRAME_MAGIC)
        output.writeByte(sourceHost.size)
        output.write(sourceHost)
        output.writeShort(source.port)
        output.writeInt(payload.size)
        output.write(payload)
    }

    private fun readNBytesStrict(input: DataInputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read < 0) error("Unexpected EOF while reading $count bytes")
            offset += read
        }
        return out
    }

    companion object {
        private const val FRAME_MAGIC: Int = 0x59595431
    }
}

