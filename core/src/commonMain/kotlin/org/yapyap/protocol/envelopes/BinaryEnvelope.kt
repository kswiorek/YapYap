package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.packet.PacketType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class BinaryEnvelope @OptIn(ExperimentalUuidApi::class) constructor(
    val packetId: Uuid,
    val packetType: PacketType,
    val dispositionRequested: Boolean,
    val createdAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val source: PeerId,
    val target: PeerId,
    val payload: ByteArray,
) {
    init {
        require(expiresAtEpochSeconds >= createdAtEpochSeconds) { "expiresAt must be >= createdAt" }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter(ENCODED_HEADER_BYTES + payload.size)
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(packetType.wireValue.toInt())
        writer.writeByte(if (dispositionRequested) 1 else 0)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeLong(expiresAtEpochSeconds)
        writer.writeUuid(packetId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.PACKET_ID to packetId,
        Fields.PACKET_TYPE to packetType,
        Fields.DISPOSITION_REQUESTED to dispositionRequested,
        Fields.CREATED_AT_EPOCH_SECONDS to createdAtEpochSeconds,
        Fields.EXPIRES_AT_EPOCH_SECONDS to expiresAtEpochSeconds,
        Fields.SOURCE to source,
        Fields.TARGET to target,
    )

    companion object {
        object Fields {
            const val PACKET_ID = "packetId"
            const val PACKET_TYPE = "packetType"
            const val DISPOSITION_REQUESTED = "dispositionRequested"
            const val CREATED_AT_EPOCH_SECONDS = "createdAtEpochSeconds"
            const val EXPIRES_AT_EPOCH_SECONDS = "expiresAtEpochSeconds"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val PAYLOAD = "payload"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'Y'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        /**
         * Fixed bytes added by [encode] around [payload], excluding the payload itself.
         * MAGIC(4) + VERSION(1) + packetType(1) + dispositionRequested(1) + createdAt(8) + expiresAt(8) + packetId(16)
         * + source(2+64) + target(2+64) + payload length prefix(4) = 175.
         * Assumes a 64-char hex [PeerId] (SHA-256 of the signing key).
         */
        const val ENCODED_HEADER_BYTES: Int = 175

            fun decode(bytes: ByteArray): BinaryEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported envelope version: $version" }

            val type = PacketType.fromWireValue(reader.readByte())
            val dispositionRequested = reader.readByte().toInt() != 0
            val createdAt = reader.readLong()
            val expiresAt = reader.readLong()
            val packetId = reader.readUuid()

            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val payload = reader.readByteArray()
            reader.requireFullyRead()

            return BinaryEnvelope(
                packetId = packetId,
                packetType = type,
                dispositionRequested = dispositionRequested,
                createdAtEpochSeconds = createdAt,
                expiresAtEpochSeconds = expiresAt,
                source = source,
                target = target,
                payload = payload,
            )
        }
    }
}


