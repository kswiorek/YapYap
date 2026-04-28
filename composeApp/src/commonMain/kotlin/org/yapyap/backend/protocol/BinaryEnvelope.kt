package org.yapyap.backend.protocol

data class BinaryEnvelope(
    val packetId: PacketId,
    val packetType: PacketType,
    val createdAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val source: String,
    val target: String,
    val payload: ByteArray,
) {
    init {
        require(expiresAtEpochSeconds >= createdAtEpochSeconds) { "expiresAt must be >= createdAt" }
        require(source.isNotBlank()) { "source must not be blank" }
        require(target.isNotBlank()) { "target must not be blank" }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter(128 + payload.size)
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(packetType.wireValue.toInt())
        writer.writeLong(createdAtEpochSeconds)
        writer.writeLong(expiresAtEpochSeconds)
        writer.writeBytes(packetId.toByteArray())
        writer.writeString(source)
        writer.writeString(target)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.PACKET_ID to packetId,
        Fields.PACKET_TYPE to packetType,
        Fields.CREATED_AT_EPOCH_SECONDS to createdAtEpochSeconds,
        Fields.EXPIRES_AT_EPOCH_SECONDS to expiresAtEpochSeconds,
        Fields.SOURCE to source,
        Fields.TARGET to target,
    )

    companion object {
        object Fields {
            const val PACKET_ID = "packetId"
            const val PACKET_TYPE = "packetType"
            const val CREATED_AT_EPOCH_SECONDS = "createdAtEpochSeconds"
            const val EXPIRES_AT_EPOCH_SECONDS = "expiresAtEpochSeconds"
            const val SOURCE = "source"
            const val TARGET = "target"
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

            val source = reader.readString()
            val target = reader.readString()
            val payload = reader.readByteArray()
            reader.requireFullyRead()

            return BinaryEnvelope(
                packetId = packetId,
                packetType = type,
                createdAtEpochSeconds = createdAt,
                expiresAtEpochSeconds = expiresAt,
                source = source,
                target = target,
                payload = payload,
            )
        }
    }
}


