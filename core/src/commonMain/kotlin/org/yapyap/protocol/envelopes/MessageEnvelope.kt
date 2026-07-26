package org.yapyap.protocol.envelopes

import org.yapyap.persistence.db.MessagePayloadType
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
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

            val messageId = reader.readString()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return MessageEnvelope(
                messageId = messageId,
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

/**
 * Causal DAG node carried inside [MessageEnvelope].
 *
 * Shared header fields are common to every room DAG (chat and global control).
 * Delivery lifecycle and orphan flags are local DB concerns and are not on the wire.
 *
 * TODO: Attachment / file-offer message payload types (link to FileEnvelope transfers).
 */
sealed interface MessagePayload {
    val messageId: String
    val roomId: String
    val senderAccountId: String
    val authorDeviceId: PeerId
    val prevId: String?
    val lamportClock: Long
    /**
     * Sender's wall-clock composition time, set once by [org.yapyap.orchestrator.dag.DagEngine.append]
     * at send time and carried on the wire unchanged. Distinct from
     * [MessageEnvelope.createdAtEpochSeconds] (transport-level, set at envelope assembly).
     * Used as the primary GUI display-order key.
     */
    val createdAtEpochSeconds: Long
    val payloadType: MessagePayloadType
    val authorSignature: ByteArray?

    fun encode(): ByteArray

    fun encodeForAuthorSigning(): ByteArray

    fun withSignature(signature: ByteArray): MessagePayload

    data class Text(
        override val messageId: String,
        override val roomId: String,
        override val senderAccountId: String,
        override val authorDeviceId: PeerId,
        override val prevId: String?,
        override val lamportClock: Long,
        override val createdAtEpochSeconds: Long,
        val text: String,
        override val authorSignature: ByteArray? = null,
    ) : MessagePayload {
        init {
            require(messageId.isNotBlank()) { "messageId must not be blank" }
            require(roomId.isNotBlank()) { "roomId must not be blank" }
            require(senderAccountId.isNotBlank()) { "senderAccountId must not be blank" }
            require(lamportClock >= 0) { "lamportClock must be >= 0" }
            require(createdAtEpochSeconds >= 0) { "createdAtEpochSeconds must be >= 0" }
            if (authorSignature != null) {
                require(authorSignature.isNotEmpty()) { "authorSignature must not be empty" }
            }
        }

        override val payloadType: MessagePayloadType = MessagePayloadType.TEXT

        override fun withSignature(signature: ByteArray): Text = copy(authorSignature = signature)

        override fun encode(): ByteArray {
            val writer = ByteWriter(256 + text.length + (authorSignature?.size ?: 4))
            writeCommonHeader(writer)
            writer.writeString(text)
            writer.writeNullableByteArray(authorSignature)
            return writer.toByteArray()
        }

        override fun encodeForAuthorSigning(): ByteArray {
            val writer = ByteWriter(256 + text.length)
            writeCommonHeader(writer)
            writer.writeString(text)
            return writer.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Text) return false
            return messageId == other.messageId &&
                    roomId == other.roomId &&
                    senderAccountId == other.senderAccountId &&
                    authorDeviceId == other.authorDeviceId &&
                    prevId == other.prevId &&
                    lamportClock == other.lamportClock &&
                    createdAtEpochSeconds == other.createdAtEpochSeconds &&
                    text == other.text &&
                    ((authorSignature == null && other.authorSignature == null) ||
                     (authorSignature != null && other.authorSignature != null && authorSignature.contentEquals(other.authorSignature)))
        }

        override fun hashCode(): Int {
            var result = messageId.hashCode()
            result = 31 * result + roomId.hashCode()
            result = 31 * result + senderAccountId.hashCode()
            result = 31 * result + authorDeviceId.hashCode()
            result = 31 * result + (prevId?.hashCode() ?: 0)
            result = 31 * result + lamportClock.hashCode()
            result = 31 * result + createdAtEpochSeconds.hashCode()
            result = 31 * result + text.hashCode()
            result = 31 * result + (authorSignature?.contentHashCode() ?: 0)
            return result
        }

        companion object {
            fun decode(bytes: ByteArray): Text {
                val reader = ByteReader(bytes)
                val header = readCommonHeader(reader, MessagePayloadType.TEXT)
                val text = reader.readString()
                val authorSignature = reader.readNullableByteArray()
                reader.requireFullyRead()
                return Text(
                    messageId = header.messageId,
                    roomId = header.roomId,
                    senderAccountId = header.senderAccountId,
                    authorDeviceId = header.authorDeviceId,
                    prevId = header.prevId,
                    lamportClock = header.lamportClock,
                    createdAtEpochSeconds = header.createdAtEpochSeconds,
                    text = text,
                    authorSignature = authorSignature,
                )
            }
        }
    }

    data class GlobalEvent(
        override val messageId: String,
        override val roomId: String = "GLOBAL",
        override val senderAccountId: String,
        override val authorDeviceId: PeerId,
        override val prevId: String?,
        override val lamportClock: Long,
        override val createdAtEpochSeconds: Long,
        val eventBytes: ByteArray,
        override val authorSignature: ByteArray? = null,
    ) : MessagePayload {
        init {
            require(messageId.isNotBlank()) { "messageId must not be blank" }
            require(roomId.isNotBlank()) { "roomId must not be blank" }
            require(senderAccountId.isNotBlank()) { "senderAccountId must not be blank" }
            require(lamportClock >= 0) { "lamportClock must be >= 0" }
            require(createdAtEpochSeconds >= 0) { "createdAtEpochSeconds must be >= 0" }
            if (authorSignature != null) {
                require(authorSignature.isNotEmpty()) { "authorSignature must not be empty" }
            }
        }

        override val payloadType: MessagePayloadType = MessagePayloadType.GLOBAL_EVENT

        override fun withSignature(signature: ByteArray): GlobalEvent = copy(authorSignature = signature)

        override fun encode(): ByteArray {
            val writer = ByteWriter(256 + eventBytes.size + (authorSignature?.size ?: 4))
            writeCommonHeader(writer)
            // TODO: Replace raw global event payload blob with typed control event codec.
            writer.writeByteArray(eventBytes)
            writer.writeNullableByteArray(authorSignature)
            return writer.toByteArray()
        }

        override fun encodeForAuthorSigning(): ByteArray {
            val writer = ByteWriter(256 + eventBytes.size)
            writeCommonHeader(writer)
            writer.writeByteArray(eventBytes)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): GlobalEvent {
                val reader = ByteReader(bytes)
                val header = readCommonHeader(reader, MessagePayloadType.GLOBAL_EVENT)
                // TODO: Decode typed global control events once schema is finalized.
                val eventBytes = reader.readByteArray()
                val authorSignature = reader.readNullableByteArray()
                reader.requireFullyRead()
                return GlobalEvent(
                    messageId = header.messageId,
                    roomId = header.roomId,
                    senderAccountId = header.senderAccountId,
                    authorDeviceId = header.authorDeviceId,
                    prevId = header.prevId,
                    lamportClock = header.lamportClock,
                    createdAtEpochSeconds = header.createdAtEpochSeconds,
                    eventBytes = eventBytes,
                    authorSignature = authorSignature,
                )
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as GlobalEvent

            if (lamportClock != other.lamportClock) return false
            if (createdAtEpochSeconds != other.createdAtEpochSeconds) return false
            if (messageId != other.messageId) return false
            if (roomId != other.roomId) return false
            if (senderAccountId != other.senderAccountId) return false
            if (authorDeviceId != other.authorDeviceId) return false
            if (prevId != other.prevId) return false
            if (!eventBytes.contentEquals(other.eventBytes)) return false
            if (!((authorSignature == null && other.authorSignature == null) ||
                  (authorSignature != null && other.authorSignature != null && authorSignature.contentEquals(other.authorSignature)))) return false
            if (payloadType != other.payloadType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = lamportClock.hashCode()
            result = 31 * result + createdAtEpochSeconds.hashCode()
            result = 31 * result + messageId.hashCode()
            result = 31 * result + roomId.hashCode()
            result = 31 * result + senderAccountId.hashCode()
            result = 31 * result + authorDeviceId.hashCode()
            result = 31 * result + (prevId?.hashCode() ?: 0)
            result = 31 * result + eventBytes.contentHashCode()
            result = 31 * result + (authorSignature?.contentHashCode() ?: 0)
            result = 31 * result + payloadType.hashCode()
            return result
        }
    }

    companion object {
        fun decode(bytes: ByteArray): MessagePayload {
            val reader = ByteReader(bytes)
            readPayloadHeaderVersion(reader)
            val payloadType = MessagePayloadType.fromWireValue(reader.readByte())
            return when (payloadType) {
                MessagePayloadType.TEXT -> Text.decode(bytes)
                MessagePayloadType.GLOBAL_EVENT -> GlobalEvent.decode(bytes)
            }
        }
    }
}

private data class MessagePayloadHeader(
    val messageId: String,
    val roomId: String,
    val senderAccountId: String,
    val authorDeviceId: PeerId,
    val prevId: String?,
    val lamportClock: Long,
    val createdAtEpochSeconds: Long,
)

private const val PAYLOAD_HEADER_VERSION: Byte = 1

private fun readPayloadHeaderVersion(reader: ByteReader) {
    val version = reader.readByte()
    require(version == PAYLOAD_HEADER_VERSION) {
        "Unsupported message payload header version: $version"
    }
}

private fun MessagePayload.writeCommonHeader(writer: ByteWriter) {
    writer.writeByte(PAYLOAD_HEADER_VERSION.toInt())
    writer.writeByte(payloadType.wireValue.toInt())
    writer.writeString(messageId)
    writer.writeString(roomId)
    writer.writeString(senderAccountId)
    writer.writePeerId(authorDeviceId)
    writer.writeNullableString(prevId)
    writer.writeLong(lamportClock)
    writer.writeLong(createdAtEpochSeconds)
}

private fun readCommonHeader(reader: ByteReader, expected: MessagePayloadType): MessagePayloadHeader {
    readPayloadHeaderVersion(reader)
    require(MessagePayloadType.fromWireValue(reader.readByte()) == expected) {
        "Expected ${expected.name} payload type"
    }
    return MessagePayloadHeader(
        messageId = reader.readString(),
        roomId = reader.readString(),
        senderAccountId = reader.readString(),
        authorDeviceId = reader.readPeerId(),
        prevId = reader.readNullableString(),
        lamportClock = reader.readLong(),
        createdAtEpochSeconds = reader.readLong(),
    )
}