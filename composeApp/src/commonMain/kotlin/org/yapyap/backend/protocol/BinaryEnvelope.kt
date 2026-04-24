package org.yapyap.backend.protocol

data class EnvelopeRoute(
    val destinationAccount: String,
    val destinationDevice: String?,
    val nextHopDevice: String? = null,
) {
    init {
        require(destinationAccount.isNotBlank()) { "destinationAccount must not be blank" }
    }
}

data class BinaryEnvelope(
    val packetId: PacketId,
    val packetType: PacketType,
    val createdAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val hopCount: Int,
    val route: EnvelopeRoute,
    val payload: ByteArray,
) {
    init {
        require(expiresAtEpochSeconds >= createdAtEpochSeconds) { "expiresAt must be >= createdAt" }
        require(hopCount in 0..255) { "hopCount must be in range 0..255" }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter(128 + payload.size)
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(packetType.wireValue.toInt())
        writer.writeByte(hopCount)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeLong(expiresAtEpochSeconds)
        writer.writeBytes(packetId.toByteArray())
        writer.writeString(route.destinationAccount)
        writer.writeNullableString(route.destinationDevice)
        writer.writeNullableString(route.nextHopDevice)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.PACKET_ID to packetId,
        Fields.PACKET_TYPE to packetType,
        Fields.CREATED_AT_EPOCH_SECONDS to createdAtEpochSeconds,
        Fields.EXPIRES_AT_EPOCH_SECONDS to expiresAtEpochSeconds,
        Fields.HOP_COUNT to hopCount,
        Fields.ROUTE_DESTINATION_ACCOUNT to route.destinationAccount,
        Fields.ROUTE_DESTINATION_DEVICE to route.destinationDevice,
        Fields.ROUTE_NEXT_HOP_DEVICE to route.nextHopDevice,
    )

    companion object {
        object Fields {
            const val PACKET_ID = "packetId"
            const val PACKET_TYPE = "packetType"
            const val CREATED_AT_EPOCH_SECONDS = "createdAtEpochSeconds"
            const val EXPIRES_AT_EPOCH_SECONDS = "expiresAtEpochSeconds"
            const val HOP_COUNT = "hopCount"
            const val ROUTE_DESTINATION_ACCOUNT = "route.destinationAccount"
            const val ROUTE_DESTINATION_DEVICE = "route.destinationDevice"
            const val ROUTE_NEXT_HOP_DEVICE = "route.nextHopDevice"
            const val PAYLOAD = "payload"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'Y'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        fun decode(bytes: ByteArray): BinaryEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported envelope version: $version" }

            val type = PacketType.fromWireValue(reader.readByte())
            val hopCount = reader.readUnsignedByte()
            val createdAt = reader.readLong()
            val expiresAt = reader.readLong()
            val packetId = PacketId.fromBytes(reader.readBytes(PacketId.SIZE_BYTES))

            val route = EnvelopeRoute(
                destinationAccount = reader.readString(),
                destinationDevice = reader.readNullableString(),
                nextHopDevice = reader.readNullableString(),
            )
            val payload = reader.readByteArray()
            reader.requireFullyRead()

            return BinaryEnvelope(
                packetId = packetId,
                packetType = type,
                createdAtEpochSeconds = createdAt,
                expiresAtEpochSeconds = expiresAt,
                hopCount = hopCount,
                route = route,
                payload = payload,
            )
        }
    }
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

    fun writeNullableString(value: String?) {
        if (value == null) {
            writeShort(0xffff)
            return
        }
        writeString(value)
    }

    fun writeByteArray(value: ByteArray) {
        writeInt(value.size)
        writeBytes(value)
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
        require(position < bytes.size) { "Unexpected end of envelope" }
        return bytes[position++]
    }

    fun readUnsignedByte(): Int = readByte().toInt() and 0xff

    fun readBytes(size: Int): ByteArray {
        require(size >= 0) { "Size must be non-negative" }
        require(position + size <= bytes.size) { "Unexpected end of envelope" }
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
        require(len != 0xffff) { "String cannot be null" }
        return readBytes(len).decodeToString()
    }

    fun readNullableString(): String? {
        val len = readShort()
        if (len == 0xffff) return null
        return readBytes(len).decodeToString()
    }

    fun readByteArray(): ByteArray {
        val len = readInt()
        require(len >= 0) { "Byte array length must be >= 0" }
        return readBytes(len)
    }

    fun requireFullyRead() {
        require(position == bytes.size) { "Envelope has trailing bytes" }
    }
}


