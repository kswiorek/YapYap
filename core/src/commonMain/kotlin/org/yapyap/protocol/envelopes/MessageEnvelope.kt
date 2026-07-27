package org.yapyap.protocol.envelopes

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.MessagePayloadType
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class MessageEnvelope @OptIn(ExperimentalUuidApi::class) constructor(
    val messageEnvelopeId: Uuid,
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
        writer.writeUuid(messageEnvelopeId)
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
        Fields.MESSAGE_ID to messageEnvelopeId,
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

            val messageEnvelopeId = reader.readUuid()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return MessageEnvelope(
                messageEnvelopeId = messageEnvelopeId,
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
    val messageId: Uuid
    val roomId: String
    val senderAccountId: AccountId
    val authorDeviceId: PeerId
    val prevId: Uuid?
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
        override val messageId: Uuid,
        override val roomId: String,
        override val senderAccountId: AccountId,
        override val authorDeviceId: PeerId,
        override val prevId: Uuid?,
        override val lamportClock: Long,
        override val createdAtEpochSeconds: Long,
        val text: String,
        override val authorSignature: ByteArray? = null,
    ) : MessagePayload {
        init {
            require(roomId.isNotBlank()) { "roomId must not be blank" }
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

    data class GlobalEvent @OptIn(ExperimentalUuidApi::class) constructor(
        override val messageId: Uuid,
        override val roomId: String = "GLOBAL",
        override val senderAccountId: AccountId,
        override val authorDeviceId: PeerId,
        override val prevId: Uuid?,
        override val lamportClock: Long,
        override val createdAtEpochSeconds: Long,
        val eventBytes: ByteArray,
        override val authorSignature: ByteArray? = null,
    ) : MessagePayload {
        init {
            require(roomId.isNotBlank()) { "roomId must not be blank" }
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
    val messageId: Uuid,
    val roomId: String,
    val senderAccountId: AccountId,
    val authorDeviceId: PeerId,
    val prevId: Uuid?,
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
    writer.writeUuid(messageId)
    writer.writeString(roomId)
    writer.writeString(senderAccountId.id)
    writer.writePeerId(authorDeviceId)
    writer.writeNullableUuid(prevId)
    writer.writeLong(lamportClock)
    writer.writeLong(createdAtEpochSeconds)
}

private fun readCommonHeader(reader: ByteReader, expected: MessagePayloadType): MessagePayloadHeader {
    readPayloadHeaderVersion(reader)
    require(MessagePayloadType.fromWireValue(reader.readByte()) == expected) {
        "Expected ${expected.name} payload type"
    }
    return MessagePayloadHeader(
        messageId = reader.readUuid(),
        roomId = reader.readString(),
        senderAccountId = AccountId(reader.readString()),
        authorDeviceId = reader.readPeerId(),
        prevId = reader.readNullableUuid(),
        lamportClock = reader.readLong(),
        createdAtEpochSeconds = reader.readLong(),
    )
}