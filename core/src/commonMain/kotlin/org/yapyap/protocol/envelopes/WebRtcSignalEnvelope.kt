package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.transport.webrtc.types.WebRtcSignalKind
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class WebRtcSignalEnvelope @OptIn(ExperimentalUuidApi::class) constructor(
    val signalEnvelopeId: Uuid,
    val kind: WebRtcSignalKind,
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
        val writer = ByteWriter(256 + payload.size + nonce.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(kind.wireValue.toInt())
        writer.writeUuid(signalEnvelopeId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAt.epochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue.toInt())
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.SIGNAL_ENVELOPE_ID to signalEnvelopeId,
        Fields.KIND to kind,
        Fields.SOURCE to source,
        Fields.TARGET to target,
        Fields.CREATED_AT to createdAt,
        Fields.NONCE to nonce,
        Fields.SECURITY_SCHEME to securityScheme,
        Fields.SIGNATURE to signature,
    )

    companion object {
        object Fields {
            const val SIGNAL_ENVELOPE_ID = "signalEnvelopeId"
            const val KIND = "kind"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val CREATED_AT = "createdAt"
            const val NONCE = "nonce"
            const val SECURITY_SCHEME = "securityScheme"
            const val SIGNATURE = "signature"
            const val PROTECTED_PAYLOAD = "protectedPayload"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'W'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

            fun decode(bytes: ByteArray): WebRtcSignalEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid WebRTC signal envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported WebRTC signal envelope version: $version" }

            val kind = WebRtcSignalKind.fromWireValue(reader.readByte())
            val sessionId = reader.readUuid()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
                val createdAt = Instant.fromEpochSeconds(reader.readLong())
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val protectedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return WebRtcSignalEnvelope(
                signalEnvelopeId = sessionId,
                kind = kind,
                source = source,
                target = target,
                createdAt = createdAt,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                payload = protectedPayload,
            )
        }
    }
}