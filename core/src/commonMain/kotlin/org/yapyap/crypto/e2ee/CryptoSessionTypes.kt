package org.yapyap.crypto.e2ee

import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId

private val SESSION_WIRE_MAGIC = byteArrayOf(
    'Y'.code.toByte(),
    'S'.code.toByte(),
    'W'.code.toByte(),
    '1'.code.toByte(),
)
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
        val writer = ByteWriter(64 + ratchetBytes.size)
        writer.writeMagic(SESSION_WIRE_MAGIC)
        writer.writeByte(SESSION_WIRE_VERSION.toInt())
        writer.writeInt(sessionEpoch)
        writer.writeInt(sessionGeneration)
        if (outerHandshake == null) {
            writer.writeByte(0)
        } else {
            writer.writeByte(1)
            writer.writeByteArray(
                encodeX3dhWireInfo(outerHandshake),
                CryptoWireLimits.MAX_SESSION_WIRE_FRAME_BYTES,
            )
        }
        writer.writeByteArray(ratchetBytes, CryptoWireLimits.MAX_SESSION_WIRE_FRAME_BYTES)
        val bytes = writer.toByteArray()
        CryptoWireLimits.requireSessionWireFrameSize(bytes.size)
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
        val writer = ByteWriter(
            wire.ephemeralPublicKey.size + idBytes.size + (opkIdBytes?.size ?: 0) + 32,
        )
        writer.writeByteArray(wire.ephemeralPublicKey, CryptoWireLimits.MAX_X3DH_EPHEMERAL_KEY_BYTES)
        writer.writeByteArray(idBytes, CryptoWireLimits.MAX_STRING_ID_BYTES)
        writer.writeInt(wire.sessionEpoch)
        writer.writeInt(wire.sessionGeneration)
        writer.writeByte(wire.mode.wireValue.toInt())
        if (opkIdBytes == null) {
            writer.writeByte(0)
        } else {
            writer.writeByte(1)
            writer.writeByteArray(opkIdBytes, CryptoWireLimits.MAX_STRING_ID_BYTES)
        }
        return writer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): SessionWireFrame {
            CryptoWireLimits.requireSessionWireFrameSize(bytes.size)
            val reader = ByteReader(bytes)
            reader.readMagic(SESSION_WIRE_MAGIC)
            val version = reader.readByte()
            require(version == SESSION_WIRE_VERSION) { "unsupported session wire version: $version" }
            val sessionEpoch = reader.readInt()
            val sessionGeneration = reader.readInt()
            val hasOuter = reader.readUnsignedByte() != 0
            val outerHandshake = if (hasOuter) {
                decodeX3dhWireInfo(
                    reader.readByteArray(CryptoWireLimits.MAX_SESSION_WIRE_FRAME_BYTES),
                )
            } else {
                null
            }
            val ratchetBytes = reader.readByteArray(CryptoWireLimits.MAX_SESSION_WIRE_FRAME_BYTES)
            reader.requireFullyRead()
            return SessionWireFrame(
                sessionEpoch = sessionEpoch,
                sessionGeneration = sessionGeneration,
                outerHandshake = outerHandshake,
                ratchet = RatchetCiphertext.decode(ratchetBytes),
            )
        }

        private fun decodeX3dhWireInfo(bytes: ByteArray): X3dhWireInfo {
            val reader = ByteReader(bytes)
            val ephemeralPublicKey = reader.readByteArray(CryptoWireLimits.MAX_X3DH_EPHEMERAL_KEY_BYTES)
            val signedPreKeyIdBytes = reader.readByteArray(CryptoWireLimits.MAX_STRING_ID_BYTES)
            val sessionEpoch = reader.readInt()
            val sessionGeneration = reader.readInt()
            val mode = X3dhMode.fromWireValue(reader.readByte())
            val hasOpk = reader.readUnsignedByte() != 0
            val oneTimePreKeyId = if (hasOpk) {
                reader.readByteArray(CryptoWireLimits.MAX_STRING_ID_BYTES).decodeToString()
            } else {
                null
            }
            reader.requireFullyRead()
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
                ByteWriter(1 + 4 + bytes.size).apply {
                    writeByte(INNER_KIND_APPLICATION.toInt())
                    writeByteArray(bytes, CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES)
                }.toByteArray()
            }

            is WithControl -> {
                CryptoWireLimits.requireInnerPlaintextSize(bytes.size)
                val controlBytes = control?.encode()
                ByteWriter(8 + bytes.size + (controlBytes?.size ?: 0)).apply {
                    writeByte(INNER_KIND_WITH_CONTROL.toInt())
                    writeByteArray(bytes, CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES)
                    if (controlBytes == null) {
                        writeByte(0)
                    } else {
                        writeByte(1)
                        writeByteArray(controlBytes, CryptoWireLimits.MAX_INNER_CONTROL_BYTES)
                    }
                }.toByteArray()
            }
        }

    companion object{
        fun decode(bytes: ByteArray): RatchetInnerPlaintext {
            require(bytes.isNotEmpty()) { "inner plaintext is empty" }
            val reader = ByteReader(bytes)
            return when (reader.readByte()) {
                INNER_KIND_APPLICATION -> {
                    val app = reader.readByteArray(CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES)
                    reader.requireFullyRead()
                    Payload(app)
                }
                INNER_KIND_WITH_CONTROL -> {
                    val application = reader.readByteArray(CryptoWireLimits.MAX_INNER_PLAINTEXT_BYTES)
                    val hasControl = reader.readUnsignedByte() != 0
                    val control = if (hasControl) {
                        InnerSessionControl.decode(
                            reader.readByteArray(CryptoWireLimits.MAX_INNER_CONTROL_BYTES),
                        )
                    } else {
                        null
                    }
                    reader.requireFullyRead()
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
                ByteWriter(
                    1 + 8 + idBytes.size + opkPublicKey.size + sessionBinding.size + 16,
                ).apply {
                    writeByte(CONTROL_TAG_OPK_OFFER.toInt())
                    writeInt(sessionEpoch)
                    writeInt(sessionGeneration)
                    writeByteArray(idBytes, CryptoWireLimits.MAX_STRING_ID_BYTES)
                    writeByteArray(opkPublicKey, CryptoWireLimits.MAX_OPK_PUBLIC_KEY_BYTES)
                    writeByteArray(sessionBinding, CryptoWireLimits.MAX_SESSION_BINDING_BYTES)
                }.toByteArray()
            }
        }

    companion object {
        fun decode(bytes: ByteArray): InnerSessionControl {
            CryptoWireLimits.requireInnerControlSize(bytes.size)
            require(bytes.isNotEmpty()) { "control block is empty" }
            val reader = ByteReader(bytes)
            return when (reader.readByte()) {
                CONTROL_TAG_OPK_OFFER -> {
                    val sessionEpoch = reader.readInt()
                    val sessionGeneration = reader.readInt()
                    val idBytes = reader.readByteArray(CryptoWireLimits.MAX_STRING_ID_BYTES)
                    val opkPublicKey = reader.readByteArray(CryptoWireLimits.MAX_OPK_PUBLIC_KEY_BYTES)
                    val sessionBinding = reader.readByteArray(CryptoWireLimits.MAX_SESSION_BINDING_BYTES)
                    reader.requireFullyRead()
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
