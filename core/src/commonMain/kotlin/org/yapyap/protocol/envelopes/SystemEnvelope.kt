package org.yapyap.protocol.envelopes

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
        data class SyncRequest(
            val roomId: String,
            val syncId: Uuid,
            val maxMessages: Int,
            val anchorLamport: Long,
            val orphanLamport: Long,
        ): SystemPayload {
            override val kind: SystemEnvelopeKind = SystemEnvelopeKind.SYNC_REQUEST

            override fun encode(): ByteArray {
                val writer = ByteWriter(32 + roomId.length + Uuid.SIZE_BYTES + 4 + 8 + 8)
                writer.writeByte(kind.wireValue.toInt())
                writer.writeString(roomId)
                writer.writeUuid(syncId)
                writer.writeInt(maxMessages)
                writer.writeLong(anchorLamport)
                writer.writeLong(orphanLamport)
                return writer.toByteArray()
            }

            companion object {
                fun decode(bytes: ByteArray): SyncRequest {
                    val reader = ByteReader(bytes)
                    require(SystemEnvelopeKind.fromWireValue(reader.readByte()) == SystemEnvelopeKind.SYNC_REQUEST) {
                        "Expected SYNC_REQUEST payload kind"
                    }
                    val roomId = reader.readString()
                    val syncId = reader.readUuid()
                    val maxMessages = reader.readInt()
                    val anchorLamport = reader.readLong()
                    val orphanLamport = reader.readLong()
                    reader.requireFullyRead()
                    return SyncRequest(
                        roomId = roomId,
                        syncId = syncId,
                        maxMessages = maxMessages,
                        anchorLamport = anchorLamport,
                        orphanLamport = orphanLamport,
                    )
                }
            }
        }
    data class SyncNack(
        val syncId: Uuid,
        val reason: String,
    ) : SystemPayload {
        override val kind: SystemEnvelopeKind = SystemEnvelopeKind.SYNC_NACK
        override fun encode(): ByteArray {
            val writer = ByteWriter(32 + reason.length + Uuid.SIZE_BYTES)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeUuid(syncId)
            writer.writeString(reason)
            return writer.toByteArray()
        }
        companion object {
            fun decode(bytes: ByteArray): SyncNack {
                val reader = ByteReader(bytes)
                require(SystemEnvelopeKind.fromWireValue(reader.readByte()) == SystemEnvelopeKind.SYNC_NACK) {
                    "Expected SYNC_NACK payload kind"
                }
                val syncId = reader.readUuid()
                val reason = reader.readString()
                reader.requireFullyRead()
                return SyncNack(
                    syncId = syncId,
                    reason = reason,
                )
            }
        }
    }

    /**
     * Fire-and-forget presence signal sent periodically over an open WebRTC session while the
     * author is composing a message. The presence of the indicator means "typing"; there is no
     * explicit stop message. [intervalMillis] is the sender's send cadence — a receiver that sees
     * no further indicator within roughly 2x this interval considers the author idle.
     */
    data class TypingIndicator(
        val roomId: String,
        val intervalMillis: Int,
    ) : SystemPayload {
        override val kind: SystemEnvelopeKind = SystemEnvelopeKind.TYPING_INDICATOR

        override fun encode(): ByteArray {
            val writer = ByteWriter(16 + roomId.length)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(roomId)
            writer.writeInt(intervalMillis)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): TypingIndicator {
                val reader = ByteReader(bytes)
                require(SystemEnvelopeKind.fromWireValue(reader.readByte()) == SystemEnvelopeKind.TYPING_INDICATOR) {
                    "Expected TYPING_INDICATOR payload kind"
                }
                val roomId = reader.readString()
                val intervalMillis = reader.readInt()
                reader.requireFullyRead()
                return TypingIndicator(
                    roomId = roomId,
                    intervalMillis = intervalMillis,
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
                SystemEnvelopeKind.SYNC_REQUEST -> SyncRequest.decode(bytes)
                SystemEnvelopeKind.SYNC_NACK -> SyncNack.decode(bytes)
                SystemEnvelopeKind.TYPING_INDICATOR -> TypingIndicator.decode(bytes)
            }
        }
    }
}

enum class SystemEnvelopeKind(val wireValue: Byte) {
    PACKET_ACK(1),
    PACKET_NACK(2),
    SYNC_REQUEST(3),
    SYNC_NACK(4),
    TYPING_INDICATOR(5);

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
