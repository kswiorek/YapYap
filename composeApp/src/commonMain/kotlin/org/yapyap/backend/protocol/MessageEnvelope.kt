package org.yapyap.backend.protocol

import org.yapyap.backend.db.MessageLifecycleState
import org.yapyap.backend.db.MessagePayloadType

data class MessageEnvelope(
    val messageId: String,
    val source: PeerId,
    val target: PeerId,
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val securityScheme: SignalSecurityScheme,
    val signature: ByteArray?,
    val payload: ByteArray,
) {
    init {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
    }

    /** Canonical wire bytes with [signature] cleared; used as Ed25519 signing input. */
    fun encodeForSigning(): ByteArray = copy(signature = null).encode()

    fun encode(): ByteArray {
        val writer = ByteWriter(256 + nonce.size + payload.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeString(messageId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue.toInt())
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }
    fun decodePayload(): MessagePayload = MessagePayload.decode(payload)

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.MESSAGE_ID to messageId,
        Fields.SOURCE to source,
        Fields.TARGET to target,
        Fields.CREATED_AT_EPOCH_SECONDS to createdAtEpochSeconds,
        Fields.NONCE to nonce,
        Fields.SECURITY_SCHEME to securityScheme,
        Fields.SIGNATURE to signature,
    )

    companion object {
        object Fields {
            const val MESSAGE_ID = "messageId"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val CREATED_AT_EPOCH_SECONDS = "createdAtEpochSeconds"
            const val NONCE = "nonce"
            const val SECURITY_SCHEME = "securityScheme"
            const val SIGNATURE = "signature"
            const val PAYLOAD = "payload"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        fun decode(bytes: ByteArray): MessageEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid message envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported message envelope version: $version" }

            val transferId = reader.readString()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return MessageEnvelope(
                messageId = transferId,
                source = source,
                target = target,
                createdAtEpochSeconds = createdAtEpochSeconds,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                payload = encodedPayload
            )
        }
    }
}

sealed interface MessagePayload {
    val messageId: String
    val kind: MessageEnvelopeKind
    val payloadType: MessagePayloadType

    fun encode(): ByteArray

    data class Text(
        override val messageId: String,
        val roomId: String,
        val senderAccountId: String,
        val prevId: String?,
        val lamportClock: Long,
        val messagePayload: ByteArray,
        val lifecycleState: MessageLifecycleState,
        val isOrphaned: Boolean,
    ) : MessagePayload {
        init {
            require(messageId.isNotBlank()) { "messageId must not be blank" }
            require(roomId.isNotBlank()) { "roomId must not be blank" }
            require(senderAccountId.isNotBlank()) { "senderAccountId must not be blank" }
            require(lamportClock >= 0) { "lamportClock must be >= 0" }
        }

        override val kind: MessageEnvelopeKind = MessageEnvelopeKind.TEXT
        override val payloadType: MessagePayloadType = MessagePayloadType.TEXT

        override fun encode(): ByteArray {
            val writer = ByteWriter(256 + messagePayload.size)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(messageId)
            writer.writeString(roomId)
            writer.writeString(senderAccountId)
            writer.writeNullableString(prevId)
            writer.writeLong(lamportClock)
            writer.writeByteArray(messagePayload)
            writer.writeByte(lifecycleState.toWireValue().toInt())
            writer.writeByte(if (isOrphaned) 1 else 0)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Text {
                val reader = ByteReader(bytes)
                require(MessageEnvelopeKind.fromWireValue(reader.readByte()) == MessageEnvelopeKind.TEXT) {
                    "Expected TEXT payload kind"
                }
                val payload = Text(
                    messageId = reader.readString(),
                    roomId = reader.readString(),
                    senderAccountId = reader.readString(),
                    prevId = reader.readNullableString(),
                    lamportClock = reader.readLong(),
                    messagePayload = reader.readByteArray(),
                    lifecycleState = messageLifecycleStateFromWireValue(reader.readByte()),
                    isOrphaned = reader.readByte().toInt() != 0,
                )
                reader.requireFullyRead()
                return payload
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Text

            if (lamportClock != other.lamportClock) return false
            if (isOrphaned != other.isOrphaned) return false
            if (messageId != other.messageId) return false
            if (roomId != other.roomId) return false
            if (senderAccountId != other.senderAccountId) return false
            if (prevId != other.prevId) return false
            if (!messagePayload.contentEquals(other.messagePayload)) return false
            if (lifecycleState != other.lifecycleState) return false
            if (kind != other.kind) return false
            if (payloadType != other.payloadType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = lamportClock.hashCode()
            result = 31 * result + isOrphaned.hashCode()
            result = 31 * result + messageId.hashCode()
            result = 31 * result + roomId.hashCode()
            result = 31 * result + senderAccountId.hashCode()
            result = 31 * result + (prevId?.hashCode() ?: 0)
            result = 31 * result + messagePayload.contentHashCode()
            result = 31 * result + lifecycleState.hashCode()
            result = 31 * result + kind.hashCode()
            result = 31 * result + payloadType.hashCode()
            return result
        }
    }

    data class GlobalEvent(
        override val messageId: String,
        val roomId: String = "GLOBAL",
        val senderAccountId: String,
        val prevId: String?,
        val lamportClock: Long,
        val eventPayload: ByteArray,
        val lifecycleState: MessageLifecycleState,
        val isOrphaned: Boolean,
    ) : MessagePayload {
        init {
            require(messageId.isNotBlank()) { "messageId must not be blank" }
            require(roomId.isNotBlank()) { "roomId must not be blank" }
            require(senderAccountId.isNotBlank()) { "senderAccountId must not be blank" }
            require(lamportClock >= 0) { "lamportClock must be >= 0" }
        }

        override val kind: MessageEnvelopeKind = MessageEnvelopeKind.GLOBAL_EVENT
        override val payloadType: MessagePayloadType = MessagePayloadType.GLOBAL_EVENT

        override fun encode(): ByteArray {
            val writer = ByteWriter(256 + eventPayload.size)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(messageId)
            writer.writeString(roomId)
            writer.writeString(senderAccountId)
            writer.writeNullableString(prevId)
            writer.writeLong(lamportClock)
            // TODO: Replace raw global event payload blob with typed control event codec.
            writer.writeByteArray(eventPayload)
            writer.writeByte(lifecycleState.toWireValue().toInt())
            writer.writeByte(if (isOrphaned) 1 else 0)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): GlobalEvent {
                val reader = ByteReader(bytes)
                require(MessageEnvelopeKind.fromWireValue(reader.readByte()) == MessageEnvelopeKind.GLOBAL_EVENT) {
                    "Expected GLOBAL_EVENT payload kind"
                }
                val payload = GlobalEvent(
                    messageId = reader.readString(),
                    roomId = reader.readString(),
                    senderAccountId = reader.readString(),
                    prevId = reader.readNullableString(),
                    lamportClock = reader.readLong(),
                    // TODO: Decode typed global control events once schema is finalized.
                    eventPayload = reader.readByteArray(),
                    lifecycleState = messageLifecycleStateFromWireValue(reader.readByte()),
                    isOrphaned = reader.readByte().toInt() != 0,
                )
                reader.requireFullyRead()
                return payload
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as GlobalEvent

            if (lamportClock != other.lamportClock) return false
            if (isOrphaned != other.isOrphaned) return false
            if (messageId != other.messageId) return false
            if (roomId != other.roomId) return false
            if (senderAccountId != other.senderAccountId) return false
            if (prevId != other.prevId) return false
            if (!eventPayload.contentEquals(other.eventPayload)) return false
            if (lifecycleState != other.lifecycleState) return false
            if (kind != other.kind) return false
            if (payloadType != other.payloadType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = lamportClock.hashCode()
            result = 31 * result + isOrphaned.hashCode()
            result = 31 * result + messageId.hashCode()
            result = 31 * result + roomId.hashCode()
            result = 31 * result + senderAccountId.hashCode()
            result = 31 * result + (prevId?.hashCode() ?: 0)
            result = 31 * result + eventPayload.contentHashCode()
            result = 31 * result + lifecycleState.hashCode()
            result = 31 * result + kind.hashCode()
            result = 31 * result + payloadType.hashCode()
            return result
        }
    }

    companion object {
        fun decode(bytes: ByteArray): MessagePayload {
            val kind = MessageEnvelopeKind.fromWireValue(ByteReader(bytes).readByte())
            return when (kind) {
                MessageEnvelopeKind.TEXT -> Text.decode(bytes)
                MessageEnvelopeKind.GLOBAL_EVENT -> GlobalEvent.decode(bytes)
            }
        }
    }
}

enum class MessageEnvelopeKind(val wireValue: Byte) {
    TEXT(1),
    GLOBAL_EVENT(2);

    companion object {
        fun fromWireValue(value: Byte): MessageEnvelopeKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported message envelope kind wire value: $value")
    }
}

private fun MessageLifecycleState.toWireValue(): Byte =
    when (this) {
        MessageLifecycleState.CREATED -> 1
        MessageLifecycleState.SENT -> 2
        MessageLifecycleState.ACKED -> 3
        MessageLifecycleState.ARCHIVED -> 4
    }

private fun messageLifecycleStateFromWireValue(value: Byte): MessageLifecycleState =
    when (value.toInt()) {
        1 -> MessageLifecycleState.CREATED
        2 -> MessageLifecycleState.SENT
        3 -> MessageLifecycleState.ACKED
        4 -> MessageLifecycleState.ARCHIVED
        else -> error("Unsupported message lifecycle state wire value: $value")
    }