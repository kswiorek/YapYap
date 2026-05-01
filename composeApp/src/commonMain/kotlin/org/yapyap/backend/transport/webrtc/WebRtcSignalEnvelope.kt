package org.yapyap.backend.transport.webrtc

import org.yapyap.backend.protocol.ByteReader
import org.yapyap.backend.protocol.ByteWriter
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.transport.webrtc.types.WebRtcSignalKind

data class WebRtcSignalEnvelope(
    val sessionId: String,
    val kind: WebRtcSignalKind,
    val source: PeerId,
    val target: PeerId,
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val securityScheme: SignalSecurityScheme,
    val signature: ByteArray?,
    val protectedPayload: ByteArray,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter(256 + protectedPayload.size + nonce.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(kind.wireValue.toInt())
        writer.writeString(sessionId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue.toInt())
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(protectedPayload)
        return writer.toByteArray()
    }

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.SESSION_ID to sessionId,
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
            const val SESSION_ID = "sessionId"
            const val KIND = "kind"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val CREATED_AT_EPOCH_SECONDS = "createdAtEpochSeconds"
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
            val sessionId = reader.readString()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val protectedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return WebRtcSignalEnvelope(
                sessionId = sessionId,
                kind = kind,
                source = source,
                target = target,
                createdAtEpochSeconds = createdAtEpochSeconds,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                protectedPayload = protectedPayload,
            )
        }
    }
}