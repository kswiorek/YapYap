package org.yapyap.crypto.e2ee

import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.protocol.PeerId

private const val SESSION_WIRE_MAGIC_0 = 'Y'.code.toByte()
private const val SESSION_WIRE_MAGIC_1 = 'S'.code.toByte()
private const val SESSION_WIRE_MAGIC_2 = 'W'.code.toByte()
private const val SESSION_WIRE_MAGIC_3 = '1'.code.toByte()
private const val SESSION_WIRE_VERSION: Byte = 1

private const val INNER_KIND_APPLICATION: Byte = 0
private const val INNER_KIND_WITH_CONTROL: Byte = 1

private const val CONTROL_TAG_OPK_OFFER: Byte = 1

/** Fixed wire-format size limits for crypto session payloads (aligned with Tor max payload by default). */
object CryptoWireLimits {
    const val MAX_SESSION_WIRE_FRAME_BYTES: Int = 4 * 1024 * 1024
    /** X25519 keys are stored in DER form in this stack (~44 bytes); cap prevents hostile oversize. */
    const val MAX_DH_PUBLIC_KEY_BYTES: Int = 64
    const val MAX_X3DH_EPHEMERAL_KEY_BYTES: Int = 64
    const val MAX_RATCHET_BODY_BYTES: Int = 256 * 1024
    const val MAX_STRING_ID_BYTES: Int = 256
    const val MAX_INNER_PLAINTEXT_BYTES: Int = 256 * 1024
    const val MAX_INNER_CONTROL_BYTES: Int = 4 * 1024
    const val MAX_OPK_PUBLIC_KEY_BYTES: Int = 64
    const val MAX_SESSION_BINDING_BYTES: Int = OpkOfferBinding.BINDING_LENGTH
    const val MAX_SKIPPED_KEYS_BLOB_BYTES: Int = 512 * 1024
    const val MAX_SKIPPED_KEYS_COUNT: Int = 256
    const val MAX_MESSAGE_KEY_BYTES: Int = KmpCryptoProvider.AEAD_KEY_SIZE_BYTES

    fun requireSessionWireFrameSize(size: Int) {
        require(size in 0..MAX_SESSION_WIRE_FRAME_BYTES) {
            "session wire frame size $size exceeds max $MAX_SESSION_WIRE_FRAME_BYTES"
        }
    }

    fun requireInnerPlaintextSize(size: Int) {
        require(size in 0..MAX_INNER_PLAINTEXT_BYTES) {
            "inner plaintext size $size exceeds max $MAX_INNER_PLAINTEXT_BYTES"
        }
    }

    fun requireSkippedKeysBlobSize(size: Int) {
        require(size in 0..MAX_SKIPPED_KEYS_BLOB_BYTES) {
            "skipped message keys blob size $size exceeds max $MAX_SKIPPED_KEYS_BLOB_BYTES"
        }
    }

    fun requireDhPublicKeySize(size: Int) {
        require(size in 1..MAX_DH_PUBLIC_KEY_BYTES) {
            "DH public key size $size exceeds max $MAX_DH_PUBLIC_KEY_BYTES"
        }
    }

    fun requireRatchetBodySize(size: Int) {
        require(size in 0..MAX_RATCHET_BODY_BYTES) {
            "ratchet body size $size exceeds max $MAX_RATCHET_BODY_BYTES"
        }
    }

    fun requireX3dhEphemeralKeySize(size: Int) {
        require(size in 1..MAX_X3DH_EPHEMERAL_KEY_BYTES) {
            "X3DH ephemeral key size $size exceeds max $MAX_X3DH_EPHEMERAL_KEY_BYTES"
        }
    }

    fun requireStringIdSize(size: Int) {
        require(size in 0..MAX_STRING_ID_BYTES) {
            "string id size $size exceeds max $MAX_STRING_ID_BYTES"
        }
    }

    fun requireInnerControlSize(size: Int) {
        require(size in 0..MAX_INNER_CONTROL_BYTES) {
            "inner control size $size exceeds max $MAX_INNER_CONTROL_BYTES"
        }
    }

    fun requireOpkPublicKeySize(size: Int) {
        require(size in 1..MAX_OPK_PUBLIC_KEY_BYTES) {
            "OPK public key size $size exceeds max $MAX_OPK_PUBLIC_KEY_BYTES"
        }
    }

    fun requireSessionBindingSize(size: Int) {
        require(size == MAX_SESSION_BINDING_BYTES) {
            "session binding size $size must be $MAX_SESSION_BINDING_BYTES"
        }
    }

    fun requireSkippedMessageKeySize(size: Int) {
        require(size in 0..MAX_MESSAGE_KEY_BYTES) {
            "skipped message key size $size exceeds max $MAX_MESSAGE_KEY_BYTES"
        }
    }
}

data class SessionWireFrame(
    val sessionEpoch: Int,
    val sessionGeneration: Int = 1,
    val outerHandshake: X3dhWireInfo?,   // epoch-1 initiator first message only
    val ratchet: RatchetCiphertext,
) {
    fun encode(): ByteArray {
        val ratchetBytes = ratchet.encode()
        val outerBytes = outerHandshake?.let { encodeX3dhWireInfo(it) }
        val outerSectionSize = if (outerBytes == null) 1 else 1 + 4 + outerBytes.size
        val size = 4 + 1 + 4 + 4 + outerSectionSize + 4 + ratchetBytes.size
        CryptoWireLimits.requireSessionWireFrameSize(size)
        val bytes = ByteArray(size)
        var offset = 0
        offset = SessionWireCodec.writeMagic(bytes, offset)
        bytes[offset++] = SESSION_WIRE_VERSION
        offset = SessionWireCodec.writeInt(bytes, offset, this.sessionEpoch)
        offset = SessionWireCodec.writeInt(bytes, offset, this.sessionGeneration)
        if (outerBytes == null) {
            bytes[offset++] = 0
        } else {
            bytes[offset++] = 1
            offset = SessionWireCodec.writeByteArray(bytes, offset, outerBytes)
        }
        offset = SessionWireCodec.writeByteArray(bytes, offset, ratchetBytes)
        require(offset == bytes.size) { "session wire frame size mismatch" }
        return bytes
    }

    private fun encodeX3dhWireInfo(wire: X3dhWireInfo): ByteArray {
        CryptoWireLimits.requireX3dhEphemeralKeySize(wire.ephemeralPublicKey.size)
        val idBytes = wire.signedPreKeyId.encodeToByteArray()
        CryptoWireLimits.requireStringIdSize(idBytes.size)
        val opkIdBytes = wire.oneTimePreKeyId?.encodeToByteArray()
        if (opkIdBytes != null) {
            CryptoWireLimits.requireStringIdSize(opkIdBytes.size)
        }
        val opkTailSize = if (opkIdBytes != null) 4 + opkIdBytes.size else 0
        val size = (4 + wire.ephemeralPublicKey.size) + (4 + idBytes.size) + 4 + 4 + 1 + 1 + opkTailSize
        val bytes = ByteArray(size)
        var offset = 0
        offset = SessionWireCodec.writeByteArray(bytes, offset, wire.ephemeralPublicKey)
        offset = SessionWireCodec.writeByteArray(bytes, offset, idBytes)
        offset = SessionWireCodec.writeInt(bytes, offset, wire.sessionEpoch)
        offset = SessionWireCodec.writeInt(bytes, offset, wire.sessionGeneration)
        bytes[offset++] = wire.mode.wireValue
        if (opkIdBytes == null) {
            bytes[offset++] = 0
        } else {
            bytes[offset++] = 1
            offset = SessionWireCodec.writeByteArray(bytes, offset, opkIdBytes)
        }
        require(offset == bytes.size) { "session wire frame size mismatch" }
        return bytes
    }

    companion object {
        fun decode(bytes: ByteArray): SessionWireFrame {
            CryptoWireLimits.requireSessionWireFrameSize(bytes.size)
            var offset = 0
            SessionWireCodec.readMagic(bytes, offset)
            offset += 4
            val version = bytes[offset++]
            require(version == SESSION_WIRE_VERSION) { "unsupported session wire version: $version" }
            val sessionEpoch = SessionWireCodec.readInt(bytes, offset)
            offset += 4
            val sessionGeneration = SessionWireCodec.readInt(bytes, offset)
            offset += 4
            val hasOuter = bytes[offset++].toInt() != 0
            val outerHandshake = if (hasOuter) {
                val (wire, next) = SessionWireCodec.readByteArrayAt(
                    bytes,
                    offset,
                    CryptoWireLimits.MAX_SESSION_WIRE_FRAME_BYTES,
                )
                offset = next
                decodeX3dhWireInfo(wire)
            } else {
                null
            }
            val (ratchetBytes, next) = SessionWireCodec.readByteArrayAt(
                bytes,
                offset,
                CryptoWireLimits.MAX_SESSION_WIRE_FRAME_BYTES,
            )
            offset = next
            require(offset == bytes.size) { "trailing bytes in session wire frame" }
            return SessionWireFrame(
                sessionEpoch = sessionEpoch,
                sessionGeneration = sessionGeneration,
                outerHandshake = outerHandshake,
                ratchet = RatchetCiphertext.decode(ratchetBytes),
            )
        }

        private fun decodeX3dhWireInfo(bytes: ByteArray): X3dhWireInfo {
            var offset = 0
            val (ephemeralPublicKey, next1) = SessionWireCodec.readByteArrayAt(
                bytes,
                offset,
                CryptoWireLimits.MAX_X3DH_EPHEMERAL_KEY_BYTES,
            )
            offset = next1
            val (signedPreKeyIdBytes, next2) = SessionWireCodec.readByteArrayAt(
                bytes,
                offset,
                CryptoWireLimits.MAX_STRING_ID_BYTES,
            )
            offset = next2
            val sessionEpoch = SessionWireCodec.readInt(bytes, offset)
            offset += 4
            val sessionGeneration = SessionWireCodec.readInt(bytes, offset)
            offset += 4
            val mode = X3dhMode.fromWireValue(bytes[offset++])
            val hasOpk = bytes[offset++].toInt() != 0
            val oneTimePreKeyId = if (hasOpk) {
                val (opkBytes, next3) = SessionWireCodec.readByteArrayAt(
                    bytes,
                    offset,
                    CryptoWireLimits.MAX_STRING_ID_BYTES,
                )
                offset = next3
                opkBytes.decodeToString()
            } else {
                null
            }
            require(offset == bytes.size) { "trailing bytes in x3dh wire info" }
            return X3dhWireInfo(
                ephemeralPublicKey = ephemeralPublicKey,
                signedPreKeyId = signedPreKeyIdBytes.decodeToString(),
                sessionEpoch = sessionEpoch,
                sessionGeneration = sessionGeneration,
                mode = mode,
                oneTimePreKeyId = oneTimePreKeyId,
            )
        }
    }
}

sealed interface RatchetInnerPlaintext {
    val bytes: ByteArray
    data class Payload(override val bytes: ByteArray) : RatchetInnerPlaintext

    data class WithControl(
        override val bytes: ByteArray,
        val control: InnerSessionControl?,
    ) : RatchetInnerPlaintext

    fun encode(): ByteArray =
        when (this) {
            is Payload -> {
                CryptoWireLimits.requireInnerPlaintextSize(bytes.size)
                byteArrayOf(INNER_KIND_APPLICATION) +
                    SessionWireCodec.encodeLengthPrefixed(bytes, CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES)
            }

            is WithControl -> {
                CryptoWireLimits.requireInnerPlaintextSize(bytes.size)
                val controlBytes = control?.encode()
                if (controlBytes == null) {
                    SessionWireCodec.concatBytes(
                        byteArrayOf(INNER_KIND_WITH_CONTROL),
                        SessionWireCodec.encodeLengthPrefixed(bytes, CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES),
                        byteArrayOf(0),
                    )
                } else {
                    SessionWireCodec.concatBytes(
                        byteArrayOf(INNER_KIND_WITH_CONTROL),
                        SessionWireCodec.encodeLengthPrefixed(bytes, CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES),
                        byteArrayOf(1),
                        SessionWireCodec.encodeLengthPrefixed(controlBytes, CryptoWireLimits.MAX_INNER_CONTROL_BYTES),
                    )
                }
            }
        }

    companion object{
        fun decode(bytes: ByteArray): RatchetInnerPlaintext {
            require(bytes.isNotEmpty()) { "inner plaintext is empty" }
            return when (bytes[0]) {
                INNER_KIND_APPLICATION -> {
                    val app = SessionWireCodec.readLengthPrefixed(
                        bytes,
                        1,
                        CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES,
                    )
                    Payload(app)
                }
                INNER_KIND_WITH_CONTROL -> {
                    var offset = 1
                    val (application, next) = SessionWireCodec.readLengthPrefixedAt(
                        bytes,
                        offset,
                        CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES,
                    )
                    offset = next
                    val hasControl = bytes[offset++].toInt() != 0
                    val control = if (hasControl) {
                        val (controlBytes, controlNext) = SessionWireCodec.readByteArrayAt(
                            bytes,
                            offset,
                            CryptoWireLimits.MAX_INNER_CONTROL_BYTES,
                        )
                        offset = controlNext
                        InnerSessionControl.decode(controlBytes)
                    } else {
                        null
                    }
                    require(offset == bytes.size) { "trailing bytes in inner with-control plaintext" }
                    WithControl(bytes = application, control = control)
                }
                else -> error("unsupported inner plaintext kind: ${bytes[0]}")
            }
        }
    }
}

sealed interface InnerSessionControl {
    data class OpkOffer(
        val sessionEpoch: Int,
        val sessionGeneration: Int,
        val opkId: String,
        val opkPublicKey: ByteArray,
        val sessionBinding: ByteArray,
    ) : InnerSessionControl

    fun encode(): ByteArray =
        when (this) {
            is OpkOffer -> {
                val idBytes = opkId.encodeToByteArray()
                CryptoWireLimits.requireStringIdSize(idBytes.size)
                CryptoWireLimits.requireOpkPublicKeySize(opkPublicKey.size)
                CryptoWireLimits.requireSessionBindingSize(sessionBinding.size)
                SessionWireCodec.concatBytes(
                    byteArrayOf(CONTROL_TAG_OPK_OFFER),
                    SessionWireCodec.encodeInt(sessionEpoch),
                    SessionWireCodec.encodeInt(sessionGeneration),
                    SessionWireCodec.encodeLengthPrefixed(idBytes, CryptoWireLimits.MAX_STRING_ID_BYTES),
                    SessionWireCodec.encodeLengthPrefixed(opkPublicKey, CryptoWireLimits.MAX_OPK_PUBLIC_KEY_BYTES),
                    SessionWireCodec.encodeLengthPrefixed(sessionBinding, CryptoWireLimits.MAX_SESSION_BINDING_BYTES),
                )
            }
        }

    companion object {
        fun decode(bytes: ByteArray): InnerSessionControl {
            CryptoWireLimits.requireInnerControlSize(bytes.size)
            require(bytes.isNotEmpty()) { "control block is empty" }
            return when (bytes[0]) {
                CONTROL_TAG_OPK_OFFER -> {
                    var offset = 1
                    val sessionEpoch = SessionWireCodec.readInt(bytes, offset)
                    offset += 4
                    val sessionGeneration = SessionWireCodec.readInt(bytes, offset)
                    offset += 4
                    val (idBytes, next1) = SessionWireCodec.readLengthPrefixedAt(
                        bytes,
                        offset,
                        CryptoWireLimits.MAX_STRING_ID_BYTES,
                    )
                    offset = next1
                    val (opkPublicKey, next2) = SessionWireCodec.readLengthPrefixedAt(
                        bytes,
                        offset,
                        CryptoWireLimits.MAX_OPK_PUBLIC_KEY_BYTES,
                    )
                    offset = next2
                    val (sessionBinding, next3) = SessionWireCodec.readLengthPrefixedAt(
                        bytes,
                        offset,
                        CryptoWireLimits.MAX_SESSION_BINDING_BYTES,
                    )
                    offset = next3
                    require(offset == bytes.size) { "trailing bytes in opk offer control" }
                    CryptoWireLimits.requireSessionBindingSize(sessionBinding.size)
                    OpkOffer(
                        sessionEpoch = sessionEpoch,
                        sessionGeneration = sessionGeneration,
                        opkId = idBytes.decodeToString(),
                        opkPublicKey = opkPublicKey,
                        sessionBinding = sessionBinding,
                    )
                }
                else -> error("unsupported inner session control tag: ${bytes[0]}")
            }
        }
    }
}


object SessionWireCodec {
    fun concatBytes(vararg parts: ByteArray): ByteArray {
        val totalSize = parts.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (part in parts) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    fun encodeLengthPrefixed(value: ByteArray, maxSize: Int): ByteArray {
        require(value.size in 0..maxSize) { "length-prefixed value size ${value.size} exceeds max $maxSize" }
        val bytes = ByteArray(4 + value.size)
        writeInt(bytes, 0, value.size)
        value.copyInto(bytes, 4)
        return bytes
    }

    fun encodeInt(value: Int): ByteArray {
        val bytes = ByteArray(4)
        writeInt(bytes, 0, value)
        return bytes
    }

    fun readLengthPrefixed(bytes: ByteArray, offset: Int, maxSize: Int): ByteArray =
        readLengthPrefixedAt(bytes, offset, maxSize).first

    fun readLengthPrefixedAt(bytes: ByteArray, offset: Int, maxSize: Int): Pair<ByteArray, Int> {
        val size = readInt(bytes, offset)
        require(size >= 0) { "negative length-prefixed size: $size" }
        require(size <= maxSize) { "length-prefixed size $size exceeds max $maxSize" }
        val start = offset + 4
        require(start <= bytes.size) { "unexpected end of length-prefixed bytes" }
        require(size <= bytes.size - start) { "unexpected end of length-prefixed bytes" }
        return bytes.copyOfRange(start, start + size) to start + size
    }

    fun readByteArrayAt(bytes: ByteArray, offset: Int, maxSize: Int): Pair<ByteArray, Int> {
        val size = readInt(bytes, offset)
        require(size >= 0) { "negative byte array size: $size" }
        require(size <= maxSize) { "byte array size $size exceeds max $maxSize" }
        val start = offset + 4
        require(start <= bytes.size) { "unexpected end of byte array" }
        require(size <= bytes.size - start) { "unexpected end of byte array" }
        return bytes.copyOfRange(start, start + size) to start + size
    }

    fun writeByteArray(target: ByteArray, offset: Int, value: ByteArray): Int {
        writeInt(target, offset, value.size)
        value.copyInto(target, offset + 4)
        return offset + 4 + value.size
    }

    fun writeMagic(target: ByteArray, offset: Int): Int {
        target[offset] = SESSION_WIRE_MAGIC_0
        target[offset + 1] = SESSION_WIRE_MAGIC_1
        target[offset + 2] = SESSION_WIRE_MAGIC_2
        target[offset + 3] = SESSION_WIRE_MAGIC_3
        return offset + 4
    }

    fun readMagic(bytes: ByteArray, offset: Int) {
        require(bytes.size >= offset + 4) { "unexpected end of session wire magic" }
        require(bytes[offset] == SESSION_WIRE_MAGIC_0) { "invalid session wire magic" }
        require(bytes[offset + 1] == SESSION_WIRE_MAGIC_1) { "invalid session wire magic" }
        require(bytes[offset + 2] == SESSION_WIRE_MAGIC_2) { "invalid session wire magic" }
        require(bytes[offset + 3] == SESSION_WIRE_MAGIC_3) { "invalid session wire magic" }
    }

    fun writeInt(target: ByteArray, offset: Int, value: Int): Int {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
        return offset + 4
    }

    fun readInt(bytes: ByteArray, offset: Int): Int {
        require(offset + 4 <= bytes.size) { "unexpected end of int" }
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }
}

data class CryptoSessionRecord(
    val peerDeviceId: PeerId,
    val sessionEpoch: Int,
    val ratchetState: RatchetSessionState,
    val meta: CryptoSessionMeta,
    val canonical: Boolean,
)

data class CryptoSessionMeta(
    val role: SessionRole,
    val x3dhMode: X3dhMode,
    val handshakeSpkId: String,
    val handshakeOpkId: String? = null,
    val initiatorEphemeralPrivateKey: ByteArray? = null,
    val initiatorEphemeralPublicKey: ByteArray? = null,
    val offeredOpkId: String? = null,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val sessionGeneration: Int = 1,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
)

enum class SessionRole { INITIATOR, RESPONDER }
enum class SessionStatus { ACTIVE, PENDING, SUPERSEDED }
