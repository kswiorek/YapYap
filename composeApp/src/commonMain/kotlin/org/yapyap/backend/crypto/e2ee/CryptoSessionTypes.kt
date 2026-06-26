package org.yapyap.backend.crypto.e2ee
private const val SESSION_WIRE_MAGIC_0 = 'Y'.code.toByte()
private const val SESSION_WIRE_MAGIC_1 = 'S'.code.toByte()
private const val SESSION_WIRE_MAGIC_2 = 'W'.code.toByte()
private const val SESSION_WIRE_MAGIC_3 = '1'.code.toByte()
private const val SESSION_WIRE_VERSION: Byte = 1

private const val INNER_KIND_APPLICATION: Byte = 0
private const val INNER_KIND_WITH_CONTROL: Byte = 1

private const val CONTROL_TAG_OPK_OFFER: Byte = 1

data class SessionWireFrame(
    val sessionEpoch: Int,
    val sessionGeneration: Int = 1,
    val outerHandshake: X3dhWireInfo?,   // epoch-1 initiator first message only
    val ratchet: RatchetCiphertext,
) {
    fun encode(): ByteArray {
        val ratchetBytes = this.ratchet.encode()
        val outerBytes = this.outerHandshake?.let { encodeX3dhWireInfo(it) }
        val outerSectionSize = if (outerBytes == null) 1 else 1 + 4 + outerBytes.size
        val size = 4 + 1 + 4 + 4 + outerSectionSize + 4 + ratchetBytes.size
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
        val idBytes = wire.signedPreKeyId.encodeToByteArray()
        val opkIdBytes = wire.oneTimePreKeyId?.encodeToByteArray()
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
                val (wire, next) = SessionWireCodec.readByteArrayAt(bytes, offset)
                offset = next
                decodeX3dhWireInfo(wire)
            } else {
                null
            }
            val (ratchetBytes, next) = SessionWireCodec.readByteArrayAt(bytes, offset)
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
            val (ephemeralPublicKey, next1) = SessionWireCodec.readByteArrayAt(bytes, offset)
            offset = next1
            val (signedPreKeyIdBytes, next2) = SessionWireCodec.readByteArrayAt(bytes, offset)
            offset = next2
            val sessionEpoch = SessionWireCodec.readInt(bytes, offset)
            offset += 4
            val sessionGeneration = SessionWireCodec.readInt(bytes, offset)
            offset += 4
            val mode = X3dhMode.fromWireValue(bytes[offset++])
            val hasOpk = bytes[offset++].toInt() != 0
            val oneTimePreKeyId = if (hasOpk) {
                val (opkBytes, next3) = SessionWireCodec.readByteArrayAt(bytes, offset)
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
            is Payload ->
                byteArrayOf(INNER_KIND_APPLICATION) + SessionWireCodec.encodeLengthPrefixed(this.bytes)

            is WithControl -> {
                val controlBytes = this.control?.encode()
                if (controlBytes == null) {
                    SessionWireCodec.concatBytes(
                        byteArrayOf(INNER_KIND_WITH_CONTROL),
                        SessionWireCodec.encodeLengthPrefixed(this.bytes),
                        byteArrayOf(0),
                    )
                } else {
                    SessionWireCodec.concatBytes(
                        byteArrayOf(INNER_KIND_WITH_CONTROL),
                        SessionWireCodec.encodeLengthPrefixed(this.bytes),
                        byteArrayOf(1),
                        SessionWireCodec.encodeLengthPrefixed(controlBytes),
                    )
                }
            }
        }

    companion object{
        fun decode(bytes: ByteArray): RatchetInnerPlaintext {
            require(bytes.isNotEmpty()) { "inner plaintext is empty" }
            return when (bytes[0]) {
                INNER_KIND_APPLICATION -> {
                    val app = SessionWireCodec.readLengthPrefixed(bytes, 1)
                    Payload(app)
                }
                INNER_KIND_WITH_CONTROL -> {
                    var offset = 1
                    val (application, next) = SessionWireCodec.readLengthPrefixedAt(bytes, offset)
                    offset = next
                    val hasControl = bytes[offset++].toInt() != 0
                    val control = if (hasControl) {
                        val (controlBytes, controlNext) = SessionWireCodec.readByteArrayAt(bytes, offset)
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
    data class OpkOffer(val opkId: String, val opkPublicKey: ByteArray) : InnerSessionControl

    fun encode(): ByteArray =
        when (this) {
            is OpkOffer -> {
                val idBytes = this.opkId.encodeToByteArray()
                SessionWireCodec.concatBytes(
                    byteArrayOf(CONTROL_TAG_OPK_OFFER),
                    SessionWireCodec.encodeLengthPrefixed(idBytes),
                    SessionWireCodec.encodeLengthPrefixed(this.opkPublicKey),
                )
            }
        }

    companion object {
        fun decode(bytes: ByteArray): InnerSessionControl {
            require(bytes.isNotEmpty()) { "control block is empty" }
            return when (bytes[0]) {
                CONTROL_TAG_OPK_OFFER -> {
                    var offset = 1
                    val (idBytes, next1) = SessionWireCodec.readLengthPrefixedAt(bytes, offset)
                    offset = next1
                    val (opkPublicKey, next2) = SessionWireCodec.readLengthPrefixedAt(bytes, offset)
                    offset = next2
                    require(offset == bytes.size) { "trailing bytes in opk offer control" }
                    OpkOffer(
                        opkId = idBytes.decodeToString(),
                        opkPublicKey = opkPublicKey,
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

    fun encodeLengthPrefixed(value: ByteArray): ByteArray {
        val bytes = ByteArray(4 + value.size)
        writeInt(bytes, 0, value.size)
        value.copyInto(bytes, 4)
        return bytes
    }

    fun readLengthPrefixed(bytes: ByteArray, offset: Int): ByteArray =
        readLengthPrefixedAt(bytes, offset).first

    fun readLengthPrefixedAt(bytes: ByteArray, offset: Int): Pair<ByteArray, Int> {
        val size = readInt(bytes, offset)
        val start = offset + 4
        val end = start + size
        require(end <= bytes.size) { "unexpected end of length-prefixed bytes" }
        return bytes.copyOfRange(start, end) to end
    }

    fun readByteArrayAt(bytes: ByteArray, offset: Int): Pair<ByteArray, Int> {
        val size = readInt(bytes, offset)
        val start = offset + 4
        val end = start + size
        require(end <= bytes.size) { "unexpected end of byte array" }
        return bytes.copyOfRange(start, end) to end
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
