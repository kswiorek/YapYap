package org.yapyap.transport.tor.backend

import io.ktor.utils.io.*
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.tor.TorIncomingFrame

/**
 * Wire codec for the YapYap Tor transport frame, shared by the real
 * ([KmpTorBackend]) and loopback ([LoopbackTorBackend]) backends so the two
 * can never drift apart.
 *
 * Frame layout: magic int, source host length byte, source host bytes,
 * source port short, payload length int, payload bytes.
 */
internal const val TOR_TRANSPORT_FRAME_MAGIC: Int = 0x59595431

internal suspend fun writeTorTransportFrame(
    output: ByteWriteChannel,
    source: TorEndpoint,
    payload: ByteArray,
) {
    val sourceHost = source.onionAddress.encodeToByteArray()
    require(sourceHost.size <= 255) { "Source onion host is too long" }

    output.writeInt(TOR_TRANSPORT_FRAME_MAGIC)
    output.writeByte(sourceHost.size.toByte())
    output.writeFully(sourceHost)
    output.writeShort(source.port.toShort())
    output.writeInt(payload.size)
    output.writeFully(payload)
}

internal suspend fun readTorTransportFrame(input: ByteReadChannel, maxPayloadBytes: Int): TorIncomingFrame {
    val magic = input.readInt()
    require(magic == TOR_TRANSPORT_FRAME_MAGIC) { "Invalid Tor transport frame magic" }

    val sourceHostLength = input.readUnsignedByte()
    val sourceHost = input.readExact(sourceHostLength).decodeToString()
    val sourcePort = input.readShort().toInt() and 0xffff
    val payloadLength = input.readInt()
    require(payloadLength >= 0) { "Payload length must be >= 0" }
    require(payloadLength <= maxPayloadBytes) {
        "Payload length $payloadLength exceeds configured max $maxPayloadBytes"
    }
    val payload = input.readExact(payloadLength)

    return TorIncomingFrame(
        source = TorEndpoint(onionAddress = sourceHost, port = sourcePort),
        payload = payload,
    )
}

internal suspend fun ByteReadChannel.readUnsignedByte(): Int {
    return readByte().toInt() and 0xff
}

internal suspend fun ByteReadChannel.readExact(count: Int): ByteArray {
    require(count >= 0) { "count must be >= 0" }
    val data = ByteArray(count)
    readFully(data, 0, count)
    return data
}
