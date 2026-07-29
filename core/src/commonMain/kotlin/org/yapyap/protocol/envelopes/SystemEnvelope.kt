package org.yapyap.protocol.envelopes

import org.yapyap.persistence.messaging.MessageCursor
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.packet.PacketType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class SystemEnvelope @OptIn(ExperimentalUuidApi::class) constructor(
    val systemEnvelopeId: Uuid,
    val source: PeerId,
    val target: PeerId,
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val securityScheme: SignalSecurityScheme,
    val signature: ByteArray?,
    val payload: ByteArray,
) {
    init {
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
    }

    /** Canonical wire bytes with [signature] cleared; used as Ed25519 signing input. */
    fun encodeForSigning(): ByteArray = copy(signature = null).encode()

    fun encode(): ByteArray {
        val writer = ByteWriter(256 + nonce.size + payload.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeUuid(systemEnvelopeId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue.toInt())
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.CORRELATION_ID to systemEnvelopeId,
        Fields.SOURCE to source,
        Fields.TARGET to target,
        Fields.CREATED_AT_EPOCH_SECONDS to createdAtEpochSeconds,
        Fields.NONCE to nonce,
        Fields.SECURITY_SCHEME to securityScheme,
        Fields.SIGNATURE to signature,
    )

    fun decodePayload(): SystemPayload = SystemPayload.decode(payload)

    companion object {
        object Fields {
            const val CORRELATION_ID = "correlationId"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val CREATED_AT_EPOCH_SECONDS = "createdAtEpochSeconds"
            const val NONCE = "nonce"
            const val SECURITY_SCHEME = "securityScheme"
            const val SIGNATURE = "signature"
            const val PAYLOAD = "payload"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

            fun decode(bytes: ByteArray): SystemEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid system envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported system envelope version: $version" }

            val correlationId = reader.readUuid()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return SystemEnvelope(
                systemEnvelopeId = correlationId,
                source = source,
                target = target,
                createdAtEpochSeconds = createdAtEpochSeconds,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                payload = encodedPayload,
            )
        }
    }
}

sealed interface SystemPayload {
    val kind: SystemEnvelopeKind

    fun encode(): ByteArray

    data class PacketAck(
        val packetId: Uuid,
        val packetType: PacketType,
    ) : SystemPayload {
        override val kind: SystemEnvelopeKind = SystemEnvelopeKind.PACKET_ACK

            override fun encode(): ByteArray {
            val writer = ByteWriter(32 + Uuid.SIZE_BYTES)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeUuid(packetId)
            writer.writeByte(packetType.wireValue.toInt())
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): PacketAck {
                val reader = ByteReader(bytes)
                require(SystemEnvelopeKind.fromWireValue(reader.readByte()) == SystemEnvelopeKind.PACKET_ACK) {
                    "Expected PACKET_ACK payload kind"
                }
                val packetId = reader.readUuid()
                val packetType = PacketType.fromWireValue(reader.readByte())
                reader.requireFullyRead()
                return PacketAck(
                    packetId = packetId,
                    packetType = packetType,
                )
            }
        }
    }

    data class PacketNack(
        val packetId: Uuid,
        val packetType: PacketType,
        val reason: PacketNackReason,
        val reasonText: String?,
    ) : SystemPayload {
        override val kind: SystemEnvelopeKind = SystemEnvelopeKind.PACKET_NACK

            override fun encode(): ByteArray {
            val writer = ByteWriter(48 + Uuid.SIZE_BYTES + (reasonText?.length ?: 0))
            writer.writeByte(kind.wireValue.toInt())
            writer.writeUuid(packetId)
            writer.writeByte(packetType.wireValue.toInt())
            writer.writeByte(reason.wireValue.toInt())
            writer.writeNullableString(reasonText)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): PacketNack {
                val reader = ByteReader(bytes)
                require(SystemEnvelopeKind.fromWireValue(reader.readByte()) == SystemEnvelopeKind.PACKET_NACK) {
                    "Expected PACKET_NACK payload kind"
                }
                val packetId = reader.readUuid()
                val packetType = PacketType.fromWireValue(reader.readByte())
                val reason = PacketNackReason.fromWireValue(reader.readByte())
                val reasonText = reader.readNullableString()
                reader.requireFullyRead()
                return PacketNack(
                    packetId = packetId,
                    packetType = packetType,
                    reason = reason,
                    reasonText = reasonText,
                )
            }
        }
    }
    data class GapSyncRequest(
        val roomId: String,
        val missingPrevId: String,          // the message id we're missing
        val orphanedMessageId: String,      // our orphan that's waiting (lets responder stop walking once it sees this)
        val maxAncestors: Int,              // cap, e.g. 32; prevents runaway streams
    ) : SystemPayload {

        override val kind: SystemEnvelopeKind = SystemEnvelopeKind.GAP_SYNC_REQUEST
        override fun encode(): ByteArray {
            val writer = ByteWriter(128 + roomId.length + missingPrevId.length + orphanedMessageId.length)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(roomId)
            writer.writeString(missingPrevId)
            writer.writeString(orphanedMessageId)
            writer.writeInt(maxAncestors)
            return writer.toByteArray()
        }
        companion object {
            fun decode(bytes: ByteArray): GapSyncRequest {
                val reader = ByteReader(bytes)
                require(SystemEnvelopeKind.fromWireValue(reader.readByte()) == SystemEnvelopeKind.GAP_SYNC_REQUEST) {
                    "Expected GAP_SYNC_REQUEST payload kind"
                }
                val roomId = reader.readString()
                val missingPrevId = reader.readString()
                val orphanedMessageId = reader.readString()
                val maxAncestors = reader.readInt()
                reader.requireFullyRead()
                return GapSyncRequest(
                    roomId = roomId,
                    missingPrevId = missingPrevId,
                    orphanedMessageId = orphanedMessageId,
                    maxAncestors = maxAncestors,
                )
            }
        }
    }

    data class RangeSyncRequest(
        val roomId: String,
        val sinceCursor: MessageCursor,      // what we believe is our newest known message in the room
        val maxMessages: Int,              // cap, e.g. 64
    ) : SystemPayload {
        override val kind: SystemEnvelopeKind = SystemEnvelopeKind.RANGE_SYNC_REQUEST
        override fun encode(): ByteArray {
            val writer = ByteWriter(128 + roomId.length + 2*64+128 + 32)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(roomId)
            writer.writeLong(sinceCursor.createdAtEpochSeconds)
            writer.writeLong(sinceCursor.lamportClock)
            writer.writeUuid(sinceCursor.messageId)
            writer.writeInt(maxMessages)
            return writer.toByteArray()
        }
        companion object {
            fun decode(bytes: ByteArray): RangeSyncRequest {
                val reader = ByteReader(bytes)
                require(SystemEnvelopeKind.fromWireValue(reader.readByte()) == SystemEnvelopeKind.RANGE_SYNC_REQUEST) {
                    "Expected RANGE_SYNC_REQUEST payload kind"
                }
                val roomId = reader.readString()
                val sinceCursor = MessageCursor(
                    createdAtEpochSeconds = reader.readLong(),
                    lamportClock = reader.readLong(),
                    messageId = reader.readUuid(),
                )
                val maxMessages = reader.readLong()
                return RangeSyncRequest(
                    roomId = roomId,
                    sinceCursor = sinceCursor,
                    maxMessages = maxMessages.toInt(),
                )
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray): SystemPayload {
            val kind = SystemEnvelopeKind.fromWireValue(ByteReader(bytes).readByte())
            return when (kind) {
                SystemEnvelopeKind.PACKET_ACK -> PacketAck.decode(bytes)
                SystemEnvelopeKind.PACKET_NACK -> PacketNack.decode(bytes)
                SystemEnvelopeKind.GAP_SYNC_REQUEST -> GapSyncRequest.decode(bytes)
                SystemEnvelopeKind.RANGE_SYNC_REQUEST -> RangeSyncRequest.decode(bytes)
            }
        }
    }
}

enum class SystemEnvelopeKind(val wireValue: Byte) {
    PACKET_ACK(1),
    PACKET_NACK(2),
    GAP_SYNC_REQUEST(3),
    RANGE_SYNC_REQUEST(4);

    companion object {
        fun fromWireValue(value: Byte): SystemEnvelopeKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported system envelope kind wire value: $value")
    }
}

enum class PacketNackReason(val wireValue: Byte) {
    WRONG_TARGET(1),
    PROTECTION_FAILED(2),
    EXPIRED(3),
    UNSUPPORTED_TYPE(4),
    DECODE_FAILED(5);

    companion object {
        fun fromWireValue(value: Byte): PacketNackReason =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported packet nack reason wire value: $value")
    }
}
