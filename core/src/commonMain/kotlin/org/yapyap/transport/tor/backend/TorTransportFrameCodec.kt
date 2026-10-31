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

/**
 * Reachability-probe marker: self-dial frames carry `MAGIC + 16-byte nonce`
 * as the frame payload so the sender can match its own looped-back frame.
 * [DefaultTorTransport] skips these before envelope decode (silent, no warn).
 */
internal val TOR_PROBE_MAGIC: ByteArray = "YAPROBE1".encodeToByteArray()

internal fun buildProbePayload(nonce: ByteArray): ByteArray {
    require(nonce.size == TOR_PROBE_NONCE_SIZE) { "probe nonce must be $TOR_PROBE_NONCE_SIZE bytes" }
    return TOR_PROBE_MAGIC + nonce
}

/** Returns the probe nonce, or null when [payload] is not a probe frame. */
internal fun probeNonceOrNull(payload: ByteArray): ByteArray? {
    if (payload.size < TOR_PROBE_MAGIC.size + TOR_PROBE_NONCE_SIZE) return null
    if (!payload.copyOfRange(0, TOR_PROBE_MAGIC.size).contentEquals(TOR_PROBE_MAGIC)) return null
    return payload.copyOfRange(TOR_PROBE_MAGIC.size, TOR_PROBE_MAGIC.size + TOR_PROBE_NONCE_SIZE)
}

internal const val TOR_PROBE_NONCE_SIZE: Int = 16

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
    return readTorTransportFrameBody(input, maxPayloadBytes)
}

/** The rest of a one-shot frame once its [TOR_TRANSPORT_FRAME_MAGIC] has been read. */
internal suspend fun readTorTransportFrameBody(input: ByteReadChannel, maxPayloadBytes: Int): TorIncomingFrame {
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

/**
 * Stream (session) protocol: a long-lived connection carrying many records, as opposed to the
 * one-shot frame above (one frame, then close). A connection's first int tells the receiver
 * which of the two it is, so both share one listener and old one-shot senders keep working.
 *
 * Layout: the dialer writes the hello — magic int, source host length byte, source host bytes,
 * source port short (the one-shot frame header without its payload) — and then both sides
 * exchange records: type byte, payload length int, payload bytes. The listener's first
 * record is [TorStreamRecordType.ACCEPT], which proves the far end's app (not just its Tor)
 * is up and speaks this protocol. Unknown record types are skipped, so newer peers can add
 * records without breaking older ones.
 */
internal const val TOR_STREAM_MAGIC: Int = 0x59595432

internal enum class TorStreamRecordType(val wireValue: Byte) {
    /** Listener → dialer, once, first: the stream is accepted. */
    ACCEPT(1),

    /** One encoded envelope. */
    DATA(2),

    /** Liveness only; sent on an otherwise idle stream. */
    KEEPALIVE(3);

    companion object {
        fun fromWireValueOrNull(value: Byte): TorStreamRecordType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A record as read; [type] is null for record types this build doesn't know (to be skipped). */
internal class TorStreamRecord(
    val type: TorStreamRecordType?,
    val payload: ByteArray,
)

internal suspend fun writeTorStreamHello(output: ByteWriteChannel, source: TorEndpoint) {
    val sourceHost = source.onionAddress.encodeToByteArray()
    require(sourceHost.size <= 255) { "Source onion host is too long" }

    output.writeInt(TOR_STREAM_MAGIC)
    output.writeByte(sourceHost.size.toByte())
    output.writeFully(sourceHost)
    output.writeShort(source.port.toShort())
}

/** The dialer's claimed endpoint, once the stream's [TOR_STREAM_MAGIC] has been read. */
internal suspend fun readTorStreamHelloBody(input: ByteReadChannel): TorEndpoint {
    val sourceHostLength = input.readUnsignedByte()
    val sourceHost = input.readExact(sourceHostLength).decodeToString()
    val sourcePort = input.readShort().toInt() and 0xffff
    return TorEndpoint(onionAddress = sourceHost, port = sourcePort)
}

internal suspend fun writeTorStreamRecord(
    output: ByteWriteChannel,
    type: TorStreamRecordType,
    payload: ByteArray = EMPTY_RECORD_PAYLOAD,
) {
    output.writeByte(type.wireValue)
    output.writeInt(payload.size)
    output.writeFully(payload)
}

internal suspend fun readTorStreamRecord(input: ByteReadChannel, maxPayloadBytes: Int): TorStreamRecord {
    val type = TorStreamRecordType.fromWireValueOrNull(input.readByte())
    val length = input.readInt()
    require(length >= 0) { "Record length must be >= 0" }
    require(length <= maxPayloadBytes) { "Record length $length exceeds configured max $maxPayloadBytes" }
    return TorStreamRecord(type, input.readExact(length))
}

private val EMPTY_RECORD_PAYLOAD = ByteArray(0)
