package org.yapyap.crypto.e2ee.session

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.e2ee.CryptoSessionException
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter

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

/**
 * Encodes/decodes the crypto session wire formats (SessionWireFrame, RatchetCiphertext,
 * RatchetInnerPlaintext, InnerSessionControl) against a [CryptoLimits] snapshot.
 *
 * The data types themselves are pure data; this codec owns the single wire-format implementation
 * and all size enforcement. Encoding a too-large value is a programming error and throws
 * [IllegalArgumentException] via [require]; decoding a peer-sent oversized value throws
 * [CryptoSessionException.OversizedFrame].
 *
 * Capacity limits come from [limits]; protocol-invariant structural caps (key sizes, string id
 * caps, binding length) come from [CryptoWireLimits].
 */
class CryptoWireCodec(
    private val limits: StateFlow<CryptoLimits>,
) {
    // ── SessionWireFrame ──

    fun encode(frame: SessionWireFrame): ByteArray {
        val limitsSnapshot = limits.value
        val ratchetBytes = encode(frame.ratchet)
        val writer = ByteWriter(64 + ratchetBytes.size)
        writer.writeMagic(SESSION_WIRE_MAGIC)
        writer.writeByte(SESSION_WIRE_VERSION.toInt())
        writer.writeInt(frame.sessionEpoch)
        writer.writeInt(frame.sessionGeneration)
        if (frame.outerHandshake == null) {
            writer.writeByte(0)
        } else {
            writer.writeByte(1)
            writer.writeByteArray(
                encodeX3dhWireInfo(frame.outerHandshake),
                limitsSnapshot.maxSessionWireFrameBytes,
            )
        }
        writer.writeByteArray(ratchetBytes, limitsSnapshot.maxSessionWireFrameBytes)
        val bytes = writer.toByteArray()
        require(bytes.size <= limitsSnapshot.maxSessionWireFrameBytes) {
            "encoded session wire frame ${bytes.size} exceeds max ${limitsSnapshot.maxSessionWireFrameBytes}"
        }
        return bytes
    }

    fun decodeSessionWireFrame(bytes: ByteArray): SessionWireFrame {
        val limitsSnapshot = limits.value
        if (bytes.size > limitsSnapshot.maxSessionWireFrameBytes) {
            throw CryptoSessionException.OversizedFrame(
                "session wire frame ${bytes.size} exceeds max ${limitsSnapshot.maxSessionWireFrameBytes}",
            )
        }
        val reader = ByteReader(bytes)
        reader.readMagic(SESSION_WIRE_MAGIC)
        val version = reader.readByte()
        require(version == SESSION_WIRE_VERSION) { "unsupported session wire version: $version" }
        val sessionEpoch = reader.readInt()
        val sessionGeneration = reader.readInt()
        val hasOuter = reader.readUnsignedByte() != 0
        val outerHandshake = if (hasOuter) {
            decodeX3dhWireInfo(readBounded(reader, limitsSnapshot.maxSessionWireFrameBytes))
        } else {
            null
        }
        val ratchetBytes = readBounded(reader, limitsSnapshot.maxSessionWireFrameBytes)
        reader.requireFullyRead()
        return SessionWireFrame(
            sessionEpoch = sessionEpoch,
            sessionGeneration = sessionGeneration,
            outerHandshake = outerHandshake,
            ratchet = decodeRatchetCiphertext(ratchetBytes),
        )
    }

    // ── RatchetCiphertext ──

    fun encode(ciphertext: RatchetCiphertext): ByteArray {
        CryptoWireLimits.requireDhPublicKeySize(ciphertext.dhPublicKey.size)
        require(ciphertext.body.size <= limits.value.maxRatchetBodyBytes) {
            "ratchet body size ${ciphertext.body.size} exceeds max ${limits.value.maxRatchetBodyBytes}"
        }
        val dhSize = ciphertext.dhPublicKey.size
        val bodySize = ciphertext.body.size
        val bytes = ByteArray(4 + dhSize + 4 + 4 + 4 + bodySize)
        var offset = 0
        writeInt(bytes, offset, dhSize); offset += 4
        ciphertext.dhPublicKey.copyInto(bytes, offset); offset += dhSize
        writeInt(bytes, offset, ciphertext.messageNumber); offset += 4
        writeInt(bytes, offset, ciphertext.previousChainLength); offset += 4
        writeInt(bytes, offset, bodySize); offset += 4
        ciphertext.body.copyInto(bytes, offset)
        return bytes
    }

    fun decodeRatchetCiphertext(bytes: ByteArray): RatchetCiphertext {
        var offset = 0
        val dhSize = readInt(bytes, offset)
        require(dhSize in 1..CryptoWireLimits.MAX_DH_PUBLIC_KEY_BYTES) {
            "DH public key size $dhSize exceeds max ${CryptoWireLimits.MAX_DH_PUBLIC_KEY_BYTES}"
        }
        offset += 4
        require(offset + dhSize <= bytes.size) { "unexpected end of ratchet ciphertext" }
        val dhPublicKey = bytes.copyOfRange(offset, offset + dhSize)
        offset += dhSize
        val messageNumber = readInt(bytes, offset)
        offset += 4
        val previousChainLength = readInt(bytes, offset)
        offset += 4
        val bodySize = readInt(bytes, offset)
        if (bodySize > limits.value.maxRatchetBodyBytes) {
            throw CryptoSessionException.OversizedFrame(
                "ratchet body $bodySize exceeds max ${limits.value.maxRatchetBodyBytes}",
            )
        }
        offset += 4
        require(offset + bodySize == bytes.size) { "trailing bytes in ratchet ciphertext" }
        val body = bytes.copyOfRange(offset, offset + bodySize)
        return RatchetCiphertext(
            dhPublicKey = dhPublicKey,
            messageNumber = messageNumber,
            previousChainLength = previousChainLength,
            body = body,
        )
    }

    // ── RatchetInnerPlaintext ──

    fun encode(plaintext: RatchetInnerPlaintext): ByteArray {
        val limitsSnapshot = limits.value
        return when (plaintext) {
            is RatchetInnerPlaintext.Payload -> {
                require(plaintext.bytes.size <= limitsSnapshot.maxInnerPlaintextBytes) {
                    "inner plaintext size ${plaintext.bytes.size} exceeds max ${limitsSnapshot.maxInnerPlaintextBytes}"
                }
                ByteWriter(1 + 4 + plaintext.bytes.size).apply {
                    writeByte(INNER_KIND_APPLICATION.toInt())
                    writeByteArray(plaintext.bytes, limitsSnapshot.maxInnerPlaintextBytes)
                }.toByteArray()
            }

            is RatchetInnerPlaintext.WithControl -> {
                require(plaintext.bytes.size <= limitsSnapshot.maxInnerPlaintextBytes) {
                    "inner plaintext size ${plaintext.bytes.size} exceeds max ${limitsSnapshot.maxInnerPlaintextBytes}"
                }
                val controlBytes = plaintext.control?.let { encode(it) }
                ByteWriter(8 + plaintext.bytes.size + (controlBytes?.size ?: 0)).apply {
                    writeByte(INNER_KIND_WITH_CONTROL.toInt())
                    writeByteArray(plaintext.bytes, limitsSnapshot.maxInnerPlaintextBytes)
                    if (controlBytes == null) {
                        writeByte(0)
                    } else {
                        writeByte(1)
                        writeByteArray(controlBytes, limitsSnapshot.maxInnerControlBytes)
                    }
                }.toByteArray()
            }
        }
    }

    fun decodeRatchetInnerPlaintext(bytes: ByteArray): RatchetInnerPlaintext {
        require(bytes.isNotEmpty()) { "inner plaintext is empty" }
        val limitsSnapshot = limits.value
        val reader = ByteReader(bytes)
        return when (reader.readByte()) {
            INNER_KIND_APPLICATION -> {
                val app = readBounded(reader, limitsSnapshot.maxInnerPlaintextBytes)
                reader.requireFullyRead()
                RatchetInnerPlaintext.Payload(app)
            }

            INNER_KIND_WITH_CONTROL -> {
                val application = readBounded(reader, limitsSnapshot.maxInnerPlaintextBytes)
                val hasControl = reader.readUnsignedByte() != 0
                val control = if (hasControl) {
                    decodeInnerSessionControl(readBounded(reader, limitsSnapshot.maxInnerControlBytes))
                } else {
                    null
                }
                reader.requireFullyRead()
                RatchetInnerPlaintext.WithControl(bytes = application, control = control)
            }

            else -> error("unsupported inner plaintext kind: ${bytes[0]}")
        }
    }

    // ── InnerSessionControl ──

    fun encode(control: InnerSessionControl): ByteArray =
        when (control) {
            is InnerSessionControl.OpkOffer -> {
                val idBytes = control.opkId.encodeToByteArray()
                CryptoWireLimits.requireStringIdSize(idBytes.size)
                CryptoWireLimits.requireOpkPublicKeySize(control.opkPublicKey.size)
                CryptoWireLimits.requireSessionBindingSize(control.sessionBinding.size)
                ByteWriter(
                    1 + 8 + idBytes.size + control.opkPublicKey.size + control.sessionBinding.size + 16,
                ).apply {
                    writeByte(CONTROL_TAG_OPK_OFFER.toInt())
                    writeInt(control.sessionEpoch)
                    writeInt(control.sessionGeneration)
                    writeByteArray(idBytes, CryptoWireLimits.MAX_STRING_ID_BYTES)
                    writeByteArray(control.opkPublicKey, CryptoWireLimits.MAX_OPK_PUBLIC_KEY_BYTES)
                    writeByteArray(control.sessionBinding, CryptoWireLimits.MAX_SESSION_BINDING_BYTES)
                }.toByteArray()
            }
        }

    fun decodeInnerSessionControl(bytes: ByteArray): InnerSessionControl {
        if (bytes.size > limits.value.maxInnerControlBytes) {
            throw CryptoSessionException.OversizedFrame(
                "inner control ${bytes.size} exceeds max ${limits.value.maxInnerControlBytes}",
            )
        }
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
                InnerSessionControl.OpkOffer(
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

    // ── X3dhWireInfo helpers ──

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

    /**
     * Reads a length-prefixed byte array, enforcing [maxSize] with a typed [CryptoSessionException.OversizedFrame]
     * (peer-sent oversize) rather than [require].
     */
    private fun readBounded(reader: ByteReader, maxSize: Int): ByteArray {
        val len = reader.readInt()
        if (len !in 0..maxSize) {
            throw CryptoSessionException.OversizedFrame("field length $len exceeds max $maxSize")
        }
        return reader.readBytes(len)
    }
}
