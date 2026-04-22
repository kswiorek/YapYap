package org.yapyap.backend.protocol

data class FileEnvelope(
    val transferId: String,
    val kind: FileEnvelopeKind,
    val source: PeerId,
    val target: PeerId,
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val securityScheme: SignalSecurityScheme,
    val signature: ByteArray?,
    val protectedPayload: ByteArray,
) {
    init {
        require(transferId.isNotBlank()) { "transferId must not be blank" }
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
    }

    fun encode(): ByteArray {
        val writer = FileEnvelopeByteWriter(256 + nonce.size + protectedPayload.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(kind.wireValue.toInt())
        writer.writeString(transferId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue.toInt())
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(protectedPayload)
        return writer.toByteArray()
    }

    fun decodeOfferPayload(): FileOfferPayload {
        require(kind == FileEnvelopeKind.OFFER) { "Expected OFFER envelope, was $kind" }
        return FileOfferPayload.decode(protectedPayload)
    }

    fun decodeChunkPayload(): FileChunkPayload {
        require(kind == FileEnvelopeKind.CHUNK) { "Expected CHUNK envelope, was $kind" }
        return FileChunkPayload.decode(protectedPayload)
    }

    fun decodeAckPayload(): FileAckPayload {
        require(kind == FileEnvelopeKind.ACK) { "Expected ACK envelope, was $kind" }
        return FileAckPayload.decode(protectedPayload)
    }

    fun decodeCompletePayload(): FileCompletePayload {
        require(kind == FileEnvelopeKind.COMPLETE) { "Expected COMPLETE envelope, was $kind" }
        return FileCompletePayload.decode(protectedPayload)
    }

    fun decodeCancelPayload(): FileCancelPayload {
        require(kind == FileEnvelopeKind.CANCEL) { "Expected CANCEL envelope, was $kind" }
        return FileCancelPayload.decode(protectedPayload)
    }

    companion object {
        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        fun decode(bytes: ByteArray): FileEnvelope {
            val reader = FileEnvelopeByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid file envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported file envelope version: $version" }

            val kind = FileEnvelopeKind.fromWireValue(reader.readByte())
            val transferId = reader.readString()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val protectedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return FileEnvelope(
                transferId = transferId,
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

enum class FileEnvelopeKind(val wireValue: Byte) {
    OFFER(1),
    CHUNK(2),
    ACK(3),
    COMPLETE(4),
    CANCEL(5);

    companion object {
        fun fromWireValue(value: Byte): FileEnvelopeKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported file envelope kind wire value: $value")
    }
}

enum class FileTransferClass(val wireValue: Byte) {
    SMALL_STORE_FORWARD(1),
    LARGE_P2P(2);

    companion object {
        fun fromWireValue(value: Byte): FileTransferClass =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported file transfer class wire value: $value")
    }
}

enum class FileTransportPreference(val wireValue: Byte) {
    TOR(1),
    WEBRTC_DATA(2),
    AUTO(3);

    companion object {
        fun fromWireValue(value: Byte): FileTransportPreference =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported file transport preference wire value: $value")
    }
}

data class FileControlPayload(
    val transferClass: FileTransferClass,
    val preferredTransport: FileTransportPreference,
    val supportsResume: Boolean,
    val maxInFlightChunks: Int,
) {
    init {
        require(maxInFlightChunks > 0) { "maxInFlightChunks must be > 0" }
    }

    fun encode(writer: FileEnvelopeByteWriter) {
        writer.writeByte(transferClass.wireValue.toInt())
        writer.writeByte(preferredTransport.wireValue.toInt())
        writer.writeByte(if (supportsResume) 1 else 0)
        writer.writeInt(maxInFlightChunks)
    }

    companion object {
        fun decode(reader: FileEnvelopeByteReader): FileControlPayload {
            return FileControlPayload(
                transferClass = FileTransferClass.fromWireValue(reader.readByte()),
                preferredTransport = FileTransportPreference.fromWireValue(reader.readByte()),
                supportsResume = reader.readByte().toInt() != 0,
                maxInFlightChunks = reader.readInt(),
            )
        }
    }
}

data class FileOfferPayload(
    val fileNameHint: String?,
    val mimeType: String?,
    val totalBytes: Long,
    val chunkSizeBytes: Int,
    val chunkCount: Int,
    val objectHash: ByteArray,
    val control: FileControlPayload,
) {
    init {
        require(totalBytes >= 0) { "totalBytes must be >= 0" }
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }
        require(chunkCount > 0) { "chunkCount must be > 0" }
        require(objectHash.isNotEmpty()) { "objectHash must not be empty" }
    }

    fun encode(): ByteArray {
        val writer = FileEnvelopeByteWriter(128 + objectHash.size)
        writer.writeNullableString(fileNameHint)
        writer.writeNullableString(mimeType)
        writer.writeLong(totalBytes)
        writer.writeInt(chunkSizeBytes)
        writer.writeInt(chunkCount)
        writer.writeByteArray(objectHash)
        control.encode(writer)
        return writer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): FileOfferPayload {
            val reader = FileEnvelopeByteReader(bytes)
            val payload = FileOfferPayload(
                fileNameHint = reader.readNullableString(),
                mimeType = reader.readNullableString(),
                totalBytes = reader.readLong(),
                chunkSizeBytes = reader.readInt(),
                chunkCount = reader.readInt(),
                objectHash = reader.readByteArray(),
                control = FileControlPayload.decode(reader),
            )
            reader.requireFullyRead()
            return payload
        }
    }
}

data class FileChunkPayload(
    val chunkIndex: Int,
    val chunkCount: Int,
    val chunkCiphertext: ByteArray,
) {
    init {
        require(chunkIndex >= 0) { "chunkIndex must be >= 0" }
        require(chunkCount > 0) { "chunkCount must be > 0" }
        require(chunkIndex < chunkCount) { "chunkIndex must be < chunkCount" }
        require(chunkCiphertext.isNotEmpty()) { "chunkCiphertext must not be empty" }
    }

    fun encode(): ByteArray {
        val writer = FileEnvelopeByteWriter(64 + chunkCiphertext.size)
        writer.writeInt(chunkIndex)
        writer.writeInt(chunkCount)
        writer.writeByteArray(chunkCiphertext)
        return writer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): FileChunkPayload {
            val reader = FileEnvelopeByteReader(bytes)
            val payload = FileChunkPayload(
                chunkIndex = reader.readInt(),
                chunkCount = reader.readInt(),
                chunkCiphertext = reader.readByteArray(),
            )
            reader.requireFullyRead()
            return payload
        }
    }
}

data class FileAckPayload(
    val highestContiguousChunk: Int,
    val missingChunkIndices: IntArray,
) {
    init {
        require(highestContiguousChunk >= -1) { "highestContiguousChunk must be >= -1" }
        require(missingChunkIndices.all { it >= 0 }) { "missingChunkIndices must be >= 0" }
    }

    fun encode(): ByteArray {
        val writer = FileEnvelopeByteWriter(32 + (missingChunkIndices.size * 4))
        writer.writeInt(highestContiguousChunk)
        writer.writeInt(missingChunkIndices.size)
        missingChunkIndices.forEach { writer.writeInt(it) }
        return writer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): FileAckPayload {
            val reader = FileEnvelopeByteReader(bytes)
            val highestContiguousChunk = reader.readInt()
            val missingCount = reader.readInt()
            require(missingCount >= 0) { "missing chunk count must be >= 0" }
            val missing = IntArray(missingCount) { reader.readInt() }
            val payload = FileAckPayload(
                highestContiguousChunk = highestContiguousChunk,
                missingChunkIndices = missing,
            )
            reader.requireFullyRead()
            return payload
        }
    }
}

data class FileCompletePayload(
    val objectHash: ByteArray,
) {
    init {
        require(objectHash.isNotEmpty()) { "objectHash must not be empty" }
    }

    fun encode(): ByteArray {
        val writer = FileEnvelopeByteWriter(32 + objectHash.size)
        writer.writeByteArray(objectHash)
        return writer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): FileCompletePayload {
            val reader = FileEnvelopeByteReader(bytes)
            val payload = FileCompletePayload(
                objectHash = reader.readByteArray(),
            )
            reader.requireFullyRead()
            return payload
        }
    }
}

data class FileCancelPayload(
    val reasonCode: Byte,
    val reasonText: String?,
) {
    fun encode(): ByteArray {
        val writer = FileEnvelopeByteWriter(32 + (reasonText?.length ?: 0))
        writer.writeByte(reasonCode.toInt())
        writer.writeNullableString(reasonText)
        return writer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): FileCancelPayload {
            val reader = FileEnvelopeByteReader(bytes)
            val payload = FileCancelPayload(
                reasonCode = reader.readByte(),
                reasonText = reader.readNullableString(),
            )
            reader.requireFullyRead()
            return payload
        }
    }
}

private fun FileEnvelopeByteWriter.writePeerId(value: PeerId) {
    writeString(value.accountName)
    writeString(value.deviceId)
}

private fun FileEnvelopeByteReader.readPeerId(): PeerId {
    return PeerId(
        accountName = readString(),
        deviceId = readString(),
    )
}

class FileEnvelopeByteWriter(initialCapacity: Int) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    fun writeByte(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    fun writeBytes(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(buffer, destinationOffset = size)
        size += value.size
    }

    fun writeLong(value: Long) {
        var shift = 56
        while (shift >= 0) {
            writeByte(((value ushr shift) and 0xff).toInt())
            shift -= 8
        }
    }

    fun writeInt(value: Int) {
        var shift = 24
        while (shift >= 0) {
            writeByte((value ushr shift) and 0xff)
            shift -= 8
        }
    }

    fun writeShort(value: Int) {
        writeByte((value ushr 8) and 0xff)
        writeByte(value and 0xff)
    }

    fun writeString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= Short.MAX_VALUE) { "String exceeds max size" }
        writeShort(bytes.size)
        writeBytes(bytes)
    }

    fun writeNullableString(value: String?) {
        if (value == null) {
            writeShort(0xffff)
            return
        }
        writeString(value)
    }

    fun writeByteArray(value: ByteArray) {
        writeInt(value.size)
        writeBytes(value)
    }

    fun writeNullableByteArray(value: ByteArray?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeByteArray(value)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(extraBytes: Int) {
        val minCapacity = size + extraBytes
        if (minCapacity <= buffer.size) return

        var newSize = buffer.size * 2
        while (newSize < minCapacity) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize)
    }
}

class FileEnvelopeByteReader(private val bytes: ByteArray) {
    private var position: Int = 0

    fun readByte(): Byte {
        require(position < bytes.size) { "Unexpected end of file envelope" }
        return bytes[position++]
    }

    fun readUnsignedByte(): Int = readByte().toInt() and 0xff

    fun readBytes(size: Int): ByteArray {
        require(size >= 0) { "Size must be non-negative" }
        require(position + size <= bytes.size) { "Unexpected end of file envelope" }
        val out = bytes.copyOfRange(position, position + size)
        position += size
        return out
    }

    fun readLong(): Long {
        var value = 0L
        repeat(8) {
            value = (value shl 8) or readUnsignedByte().toLong()
        }
        return value
    }

    fun readInt(): Int {
        var value = 0
        repeat(4) {
            value = (value shl 8) or readUnsignedByte()
        }
        return value
    }

    fun readShort(): Int {
        val high = readUnsignedByte()
        val low = readUnsignedByte()
        return (high shl 8) or low
    }

    fun readString(): String {
        val len = readShort()
        require(len != 0xffff) { "String cannot be null" }
        return readBytes(len).decodeToString()
    }

    fun readNullableString(): String? {
        val len = readShort()
        if (len == 0xffff) return null
        return readBytes(len).decodeToString()
    }

    fun readByteArray(): ByteArray {
        val len = readInt()
        require(len >= 0) { "Byte array length must be >= 0" }
        return readBytes(len)
    }

    fun readNullableByteArray(): ByteArray? {
        val len = readInt()
        if (len < 0) return null
        return readBytes(len)
    }

    fun requireFullyRead() {
        require(position == bytes.size) { "File envelope has trailing bytes" }
    }
}
