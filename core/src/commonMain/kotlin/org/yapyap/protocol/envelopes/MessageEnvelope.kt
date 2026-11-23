package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class MessageEnvelope(
    val messageEnvelopeId: Uuid, //UUID as hash of message content
    val source: PeerId,
    val target: PeerId,
    val createdAt: Instant,
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
        writer.writeLong(createdAt.epochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue)
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.MESSAGE_ID to messageEnvelopeId,
        Fields.SOURCE to source,
        Fields.TARGET to target,
        Fields.CREATED_AT to createdAt,
        Fields.NONCE to nonce,
        Fields.SECURITY_SCHEME to securityScheme,
        Fields.SIGNATURE to signature,
    )

    companion object {
        object Fields {
            const val MESSAGE_ID = "messageId"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val CREATED_AT = "createdAt"
            const val NONCE = "nonce"
            const val SECURITY_SCHEME = "securityScheme"
            const val SIGNATURE = "signature"
            const val PAYLOAD = "payload"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        /**
         * Bytes added by [encode] around [payload] for the ENCRYPTED_AND_SIGNED message path
         * (worst case): MAGIC(4) + VERSION(1) + messageId(16) + source(2+64) + target(2+64)
         * + createdAt(8) + nonce(4+24) + scheme(1) + signature(1+4+64) + payload length prefix(4) = 263.
         * Nonce is 24 bytes (ENCRYPTED_AND_SIGNED), signature is 64 bytes (Ed25519).
         */
        const val ENCODED_OVERHEAD_BYTES: Int = 263

        /**
         * The largest protected payload a message envelope can carry inside a [BinaryEnvelope]
         * of [binaryEnvelopeBytes]: both envelopes' worst-case framing subtracted.
         */
        fun maxPayloadBytes(binaryEnvelopeBytes: Int): Int =
            binaryEnvelopeBytes - BinaryEnvelope.ENCODED_HEADER_BYTES - ENCODED_OVERHEAD_BYTES

        fun decode(bytes: ByteArray): MessageEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid message envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported message envelope version: $version" }

            val messageEnvelopeId = reader.readUuid()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAt = Instant.fromEpochSeconds(reader.readLong())
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return MessageEnvelope(
                messageEnvelopeId = messageEnvelopeId,
                source = source,
                target = target,
                createdAt = createdAt,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                payload = encodedPayload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MessageEnvelope

        if (createdAt != other.createdAt) return false
        if (messageEnvelopeId != other.messageEnvelopeId) return false
        if (source != other.source) return false
        if (target != other.target) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (securityScheme != other.securityScheme) return false
        if (!signature.contentEquals(other.signature)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = createdAt.hashCode()
        result = 31 * result + messageEnvelopeId.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + securityScheme.hashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
