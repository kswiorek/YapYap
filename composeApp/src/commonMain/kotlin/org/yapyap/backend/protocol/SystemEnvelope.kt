package org.yapyap.backend.protocol

data class SystemEnvelope(
    val correlationId: String,
    val source: PeerId,
    val target: PeerId,
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val securityScheme: SignalSecurityScheme,
    val signature: ByteArray?,
    val payload: SystemPayload,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
    }

    val kind: SystemEnvelopeKind
        get() = payload.kind

    /** Canonical wire bytes with [signature] cleared; used as Ed25519 signing input. */
    fun encodeForSigning(): ByteArray = copy(signature = null).encode()

    fun encode(): ByteArray {
        val encodedPayload = payload.encode()
        val writer = ByteWriter(256 + nonce.size + encodedPayload.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(kind.wireValue.toInt())
        writer.writeString(correlationId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue.toInt())
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(encodedPayload)
        return writer.toByteArray()
    }

    fun decodePayload(): SystemPayload = payload

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.CORRELATION_ID to correlationId,
        Fields.KIND to kind,
        Fields.SOURCE to source,
        Fields.TARGET to target,
        Fields.CREATED_AT_EPOCH_SECONDS to createdAtEpochSeconds,
        Fields.NONCE to nonce,
        Fields.SECURITY_SCHEME to securityScheme,
        Fields.SIGNATURE to signature,
    )

    companion object {
        object Fields {
            const val CORRELATION_ID = "correlationId"
            const val KIND = "kind"
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

            val kind = SystemEnvelopeKind.fromWireValue(reader.readByte())
            val correlationId = reader.readString()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return SystemEnvelope(
                correlationId = correlationId,
                source = source,
                target = target,
                createdAtEpochSeconds = createdAtEpochSeconds,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                payload = SystemPayload.decode(kind, encodedPayload),
            )
        }
    }
}

sealed interface SystemPayload {
    val kind: SystemEnvelopeKind

    fun encode(): ByteArray

    data class PacketAck(
        val packetId: PacketId,
        val packetType: PacketType,
    ) : SystemPayload {
        override val kind: SystemEnvelopeKind = SystemEnvelopeKind.PACKET_ACK

        override fun encode(): ByteArray {
            val writer = ByteWriter(32 + PacketId.SIZE_BYTES)
            writer.writeBytes(packetId.toByteArray())
            writer.writeByte(packetType.wireValue.toInt())
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): PacketAck {
                val reader = ByteReader(bytes)
                val packetId = PacketId.fromBytes(reader.readBytes(PacketId.SIZE_BYTES))
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
        val packetId: PacketId,
        val packetType: PacketType,
        val reason: PacketNackReason,
        val reasonText: String?,
    ) : SystemPayload {
        override val kind: SystemEnvelopeKind = SystemEnvelopeKind.PACKET_NACK

        override fun encode(): ByteArray {
            val writer = ByteWriter(48 + PacketId.SIZE_BYTES + (reasonText?.length ?: 0))
            writer.writeBytes(packetId.toByteArray())
            writer.writeByte(packetType.wireValue.toInt())
            writer.writeByte(reason.wireValue.toInt())
            writer.writeNullableString(reasonText)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): PacketNack {
                val reader = ByteReader(bytes)
                val packetId = PacketId.fromBytes(reader.readBytes(PacketId.SIZE_BYTES))
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

    companion object {
        fun decode(kind: SystemEnvelopeKind, bytes: ByteArray): SystemPayload =
            when (kind) {
                SystemEnvelopeKind.PACKET_ACK -> PacketAck.decode(bytes)
                SystemEnvelopeKind.PACKET_NACK -> PacketNack.decode(bytes)
            }
    }
}

enum class SystemEnvelopeKind(val wireValue: Byte) {
    PACKET_ACK(1),
    PACKET_NACK(2);

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
