package org.yapyap.backend.transport.webrtc

import org.yapyap.backend.protocol.PeerId

data class WebRtcSignalEnvelope(
    val sessionId: String,
    val kind: WebRtcSignalKind,
    val source: PeerId,
    val target: PeerId,
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val securityScheme: WebRtcSignalSecurityScheme,
    val signature: ByteArray?,
    val protectedPayload: ByteArray,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter(256 + protectedPayload.size + nonce.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(kind.wireValue.toInt())
        writer.writeString(sessionId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue.toInt())
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(protectedPayload)
        return writer.toByteArray()
    }

    companion object {
        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'W'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        fun decode(bytes: ByteArray): WebRtcSignalEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid WebRTC signal envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported WebRTC signal envelope version: $version" }

            val kind = WebRtcSignalKind.fromWireValue(reader.readByte())
            val sessionId = reader.readString()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = WebRtcSignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val protectedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return WebRtcSignalEnvelope(
                sessionId = sessionId,
                kind = kind,
                source = source,
                target = target,
                createdAtEpochSeconds = createdAtEpochSeconds,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                protectedPayload = protectedPayload,
            )
        }
    }
}

enum class WebRtcSignalSecurityScheme(val wireValue: Byte) {
    PLAINTEXT_TEST_ONLY(0),
    SIGNED(1),
    ENCRYPTED_AND_SIGNED(2);

    companion object {
        fun fromWireValue(value: Byte): WebRtcSignalSecurityScheme =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported WebRTC signal security scheme wire value: $value")
    }
}

private fun ByteWriter.writePeerId(value: PeerId) {
    writeString(value.accountName)
    writeString(value.deviceId)
}

private fun ByteReader.readPeerId(): PeerId {
    return PeerId(
        accountName = readString(),
        deviceId = readString(),
    )
}

private class ByteWriter(initialCapacity: Int) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    fun writeByte(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    fun writeBytes(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(buffer, destinationOffset = size)
        size += value.size
    }

    fun writeLong(value: Long) {
        var shift = 56
        while (shift >= 0) {
            writeByte(((value ushr shift) and 0xff).toInt())
            shift -= 8
        }
    }

    fun writeInt(value: Int) {
        var shift = 24
        while (shift >= 0) {
            writeByte((value ushr shift) and 0xff)
            shift -= 8
        }
    }

    fun writeShort(value: Int) {
        writeByte((value ushr 8) and 0xff)
        writeByte(value and 0xff)
    }

    fun writeString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= Short.MAX_VALUE) { "String exceeds max size" }
        writeShort(bytes.size)
        writeBytes(bytes)
    }

    fun writeByteArray(value: ByteArray) {
        writeInt(value.size)
        writeBytes(value)
    }

    fun writeNullableByteArray(value: ByteArray?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeByteArray(value)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(extraBytes: Int) {
        val minCapacity = size + extraBytes
        if (minCapacity <= buffer.size) return

        var newSize = buffer.size * 2
        while (newSize < minCapacity) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize)
    }
}

private class ByteReader(private val bytes: ByteArray) {
    private var position: Int = 0

    fun readByte(): Byte {
        require(position < bytes.size) { "Unexpected end of WebRTC signal envelope" }
        return bytes[position++]
    }

    fun readUnsignedByte(): Int = readByte().toInt() and 0xff

    fun readBytes(size: Int): ByteArray {
        require(size >= 0) { "Size must be non-negative" }
        require(position + size <= bytes.size) { "Unexpected end of WebRTC signal envelope" }
        val out = bytes.copyOfRange(position, position + size)
        position += size
        return out
    }

    fun readLong(): Long {
        var value = 0L
        repeat(8) {
            value = (value shl 8) or readUnsignedByte().toLong()
        }
        return value
    }

    fun readInt(): Int {
        var value = 0
        repeat(4) {
            value = (value shl 8) or readUnsignedByte()
        }
        return value
    }

    fun readShort(): Int {
        val high = readUnsignedByte()
        val low = readUnsignedByte()
        return (high shl 8) or low
    }

    fun readString(): String {
        val len = readShort()
        return readBytes(len).decodeToString()
    }

    fun readByteArray(): ByteArray {
        val len = readInt()
        require(len >= 0) { "Byte array length must be >= 0" }
        return readBytes(len)
    }

    fun readNullableByteArray(): ByteArray? {
        val len = readInt()
        if (len < 0) return null
        return readBytes(len)
    }

    fun requireFullyRead() {
        require(position == bytes.size) { "WebRTC signal envelope has trailing bytes" }
    }
}

