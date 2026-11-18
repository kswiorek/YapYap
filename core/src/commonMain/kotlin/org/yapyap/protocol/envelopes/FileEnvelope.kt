package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import kotlin.time.Instant

internal data class FileEnvelope(
    val transferId: TransferId,
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

    fun encode(): ByteArray {
        val writer = ByteWriter(256 + nonce.size + payload.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeUuid(transferId.value)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAt.epochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue)
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    /** Canonical bytes covered by the author's signature (everything but the signature itself). */
    fun encodeForSigning(): ByteArray = copy(signature = null).encode()

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.TRANSFER_ID to transferId,
        Fields.SOURCE to source,
        Fields.TARGET to target,
        Fields.CREATED_AT to createdAt,
        Fields.NONCE to nonce,
        Fields.SECURITY_SCHEME to securityScheme,
        Fields.SIGNATURE to signature,
    )

    fun decodePayload(): FilePayload = FilePayload.decode(payload)

    companion object {
        object Fields {
            const val TRANSFER_ID = "transferId"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val CREATED_AT = "createdAt"
            const val NONCE = "nonce"
            const val SECURITY_SCHEME = "securityScheme"
            const val SIGNATURE = "signature"
            const val PAYLOAD = "payload"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        fun decode(bytes: ByteArray): FileEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid file envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported file envelope version: $version" }

            val transferId = TransferId(reader.readUuid())
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAt = Instant.fromEpochSeconds(reader.readLong())
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return FileEnvelope(
                transferId = transferId,
                source = source,
                target = target,
                createdAt = createdAt,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                payload = encodedPayload,
            )
        }
    }
}

internal data class OpenedFileEnvelope(
    val transferId: TransferId,
    val source: String,
    val target: String,
    val createdAt: Instant,
    val securityScheme: SignalSecurityScheme,
    val payload: FilePayload,
)

internal data class ChunkRange(
    val startIndex: Int,
    val count: Int,
) {
    init {
        require(startIndex >= 0) { "startIndex must be >= 0" }
        require(count > 0) { "count must be > 0" }
    }
}

internal data class ByteRange(
    val startBytes: Long,
    val lengthBytes: Long,
) {
    init {
        require(startBytes >= 0) { "startBytes must be >= 0" }
        require(lengthBytes > 0) { "lengthBytes must be > 0" }
    }
}

internal enum class FileCancelReason(val wireValue: Byte) {
    DECLINED(1),
    NO_SPACE(2),
    ABORT(3);

    companion object {
        fun fromWireValue(value: Byte): FileCancelReason =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported file cancel reason wire value: $value")
    }
}

internal sealed interface FilePayload {
    val kind: FileEnvelopeKind

    fun encode(): ByteArray

    /**
     * OFFER public half (relays read this) + opaque ratchet-sealed half.
     * The sealed bytes decrypt to [SealedOffer] (Step 10 seals via the ratchet).
     */
    data class Offer(
        val chunkSizeBytes: Int,
        val totalBytes: Long,
        val startIndex: Int,
        val sealedOfferBytes: ByteArray,
    ) : FilePayload {
        init {
            require(chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }
            require(totalBytes >= 0) { "totalBytes must be >= 0" }
            require(startIndex >= 0) { "startIndex must be >= 0" }
            require(sealedOfferBytes.isNotEmpty()) { "sealedOfferBytes must not be empty" }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.OFFER

        override fun encode(): ByteArray {
            val writer = ByteWriter(32 + sealedOfferBytes.size)
            writer.writeByte(kind.wireValue)
            writer.writeInt(chunkSizeBytes)
            writer.writeLong(totalBytes)
            writer.writeInt(startIndex)
            writer.writeByteArray(sealedOfferBytes)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Offer {
                val reader = ByteReader(bytes)
                require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.OFFER) {
                    "Expected OFFER payload kind"
                }
                val payload = Offer(
                    chunkSizeBytes = reader.readInt(),
                    totalBytes = reader.readLong(),
                    startIndex = reader.readInt(),
                    sealedOfferBytes = reader.readByteArray(),
                )
                reader.requireFullyRead()
                return payload
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Offer
            if (chunkSizeBytes != other.chunkSizeBytes) return false
            if (totalBytes != other.totalBytes) return false
            if (startIndex != other.startIndex) return false
            if (!sealedOfferBytes.contentEquals(other.sealedOfferBytes)) return false
            if (kind != other.kind) return false
            return true
        }

        override fun hashCode(): Int {
            var result = chunkSizeBytes
            result = 31 * result + totalBytes.hashCode()
            result = 31 * result + startIndex
            result = 31 * result + sealedOfferBytes.contentHashCode()
            result = 31 * result + kind.hashCode()
            return result
        }
    }

    /**
     * Sealed OFFER half plaintext (design §3.3): encoded before protection seals it.
     * Carries the only copy of a transfer's key.
     */
    data class SealedOffer(
        val fileId: FileId,
        val blockSize: Int,
        val blockHashes: List<ByteArray>,
        val transferKey: ByteArray,
        val fileName: String,
        val mimeType: String?,
    ) {
        init {
            require(blockSize > 0) { "blockSize must be > 0" }
            require(transferKey.size == 32) { "transferKey must be 32 bytes" }
            require(fileName.isNotBlank()) { "fileName must not be blank" }
        }

        fun encode(): ByteArray {
            val writer = ByteWriter(128 + blockHashes.size * 32 + transferKey.size)
            writer.writeString(fileId.value)
            writer.writeInt(blockSize)
            writer.writeInt(blockHashes.size)
            blockHashes.forEach { writer.writeByteArray(it, maxSize = 32) }
            writer.writeByteArray(transferKey, maxSize = 32)
            writer.writeString(fileName)
            writer.writeNullableString(mimeType)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SealedOffer {
                val reader = ByteReader(bytes)
                val fileId = FileId(reader.readString())
                val blockSize = reader.readInt()
                val count = reader.readInt()
                require(count >= 0) { "block count must be >= 0" }
                val hashes = List(count) { reader.readByteArray(maxSize = 32) }
                val key = reader.readByteArray(maxSize = 32)
                val name = reader.readString()
                val mime = reader.readNullableString()
                reader.requireFullyRead()
                return SealedOffer(
                    fileId = fileId,
                    blockSize = blockSize,
                    blockHashes = hashes,
                    transferKey = key,
                    fileName = name,
                    mimeType = mime,
                )
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as SealedOffer
            if (fileId != other.fileId) return false
            if (blockSize != other.blockSize) return false
            if (blockHashes.size != other.blockHashes.size) return false
            if (!blockHashes.zip(other.blockHashes).all { (a, b) -> a.contentEquals(b) }) return false
            if (!transferKey.contentEquals(other.transferKey)) return false
            if (fileName != other.fileName) return false
            if (mimeType != other.mimeType) return false
            return true
        }

        override fun hashCode(): Int {
            var result = fileId.hashCode()
            result = 31 * result + blockSize
            result = 31 * result + blockHashes.fold(0) { acc, h -> 31 * acc + h.contentHashCode() }
            result = 31 * result + transferKey.contentHashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            return result
        }
    }

    data class EncryptedChunk(
        val chunkIndex: Int,
        val chunkCiphertext: ByteArray,
    ) : FilePayload {
        init {
            require(chunkIndex >= 0) { "chunkIndex must be >= 0" }
            require(chunkCiphertext.isNotEmpty()) { "chunkCiphertext must not be empty" }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.CHUNK

        override fun encode(): ByteArray {
            val writer = ByteWriter(16 + chunkCiphertext.size)
            writer.writeByte(kind.wireValue)
            writer.writeInt(chunkIndex)
            writer.writeByteArray(chunkCiphertext)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): EncryptedChunk {
                val reader = ByteReader(bytes)
                require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.CHUNK) {
                    "Expected CHUNK payload kind"
                }
                val payload = EncryptedChunk(
                    chunkIndex = reader.readInt(),
                    chunkCiphertext = reader.readByteArray(),
                )
                reader.requireFullyRead()
                return payload
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as EncryptedChunk
            if (chunkIndex != other.chunkIndex) return false
            if (!chunkCiphertext.contentEquals(other.chunkCiphertext)) return false
            if (kind != other.kind) return false
            return true
        }

        override fun hashCode(): Int {
            var result = chunkIndex
            result = 31 * result + chunkCiphertext.contentHashCode()
            result = 31 * result + kind.hashCode()
            return result
        }
    }

    data class Ack(
        val contiguousChunks: Int,
        val missingRanges: List<ChunkRange>,
    ) : FilePayload {
        init {
            require(contiguousChunks >= -1) { "contiguousChunks must be >= -1" }
            require(missingRanges.size <= MAX_MISSING_RANGES) {
                "missingRanges exceed max $MAX_MISSING_RANGES"
            }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.ACK

        override fun encode(): ByteArray {
            val writer = ByteWriter(16 + missingRanges.size * 8)
            writer.writeByte(kind.wireValue)
            writer.writeInt(contiguousChunks)
            writer.writeInt(missingRanges.size)
            missingRanges.forEach {
                writer.writeInt(it.startIndex)
                writer.writeInt(it.count)
            }
            return writer.toByteArray()
        }

        companion object {
            const val MAX_MISSING_RANGES: Int = 16

            fun decode(bytes: ByteArray): Ack {
                val reader = ByteReader(bytes)
                require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.ACK) {
                    "Expected ACK payload kind"
                }
                val contiguous = reader.readInt()
                val count = reader.readInt()
                require(count in 0..MAX_MISSING_RANGES) { "missing range count $count out of bounds" }
                val ranges = List(count) { ChunkRange(reader.readInt(), reader.readInt()) }
                val payload = Ack(contiguousChunks = contiguous, missingRanges = ranges)
                reader.requireFullyRead()
                return payload
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Ack
            if (contiguousChunks != other.contiguousChunks) return false
            if (missingRanges != other.missingRanges) return false
            if (kind != other.kind) return false
            return true
        }

        override fun hashCode(): Int {
            var result = contiguousChunks
            result = 31 * result + missingRanges.hashCode()
            result = 31 * result + kind.hashCode()
            return result
        }
    }

    data object Complete : FilePayload {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.COMPLETE

        override fun encode(): ByteArray = byteArrayOf(kind.wireValue)

        fun decode(bytes: ByteArray): Complete {
            val reader = ByteReader(bytes)
            require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.COMPLETE) {
                "Expected COMPLETE payload kind"
            }
            reader.requireFullyRead()
            return Complete
        }
    }

    /**
     * Sealed, direct-only REQUEST (design §3.2): the requester's held coverage in bytes.
     * Protection ratchet-seals the encoded bytes (Step 10); no public half.
     */
    data class Request(
        val fileId: FileId,
        val contiguousBytes: Long,
        val missingRanges: List<ByteRange>,
    ) : FilePayload {
        init {
            require(contiguousBytes >= 0) { "contiguousBytes must be >= 0" }
            require(missingRanges.size <= MAX_MISSING_RANGES) {
                "missingRanges exceed max $MAX_MISSING_RANGES"
            }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.REQUEST

        override fun encode(): ByteArray {
            val writer = ByteWriter(64 + missingRanges.size * 16)
            writer.writeByte(kind.wireValue)
            writer.writeString(fileId.value)
            writer.writeLong(contiguousBytes)
            writer.writeInt(missingRanges.size)
            missingRanges.forEach {
                writer.writeLong(it.startBytes)
                writer.writeLong(it.lengthBytes)
            }
            return writer.toByteArray()
        }

        companion object {
            const val MAX_MISSING_RANGES: Int = 16

            fun decode(bytes: ByteArray): Request {
                val reader = ByteReader(bytes)
                require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.REQUEST) {
                    "Expected REQUEST payload kind"
                }
                val payload = Request(
                    fileId = FileId(reader.readString()),
                    contiguousBytes = reader.readLong(),
                    missingRanges = List(reader.readInt()) { ByteRange(reader.readLong(), reader.readLong()) }
                        .also { require(it.size <= MAX_MISSING_RANGES) },
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    data class Cancel(
        val reason: FileCancelReason,
        val reasonText: String?,
    ) : FilePayload {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.CANCEL

        override fun encode(): ByteArray {
            val writer = ByteWriter(16 + (reasonText?.length ?: 0))
            writer.writeByte(kind.wireValue)
            writer.writeByte(reason.wireValue)
            writer.writeNullableString(reasonText)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Cancel {
                val reader = ByteReader(bytes)
                require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.CANCEL) {
                    "Expected CANCEL payload kind"
                }
                val payload = Cancel(
                    reason = FileCancelReason.fromWireValue(reader.readByte()),
                    reasonText = reader.readNullableString(),
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray): FilePayload {
            val kind = FileEnvelopeKind.fromWireValue(ByteReader(bytes).readByte())
            return when (kind) {
                FileEnvelopeKind.OFFER -> Offer.decode(bytes)
                FileEnvelopeKind.CHUNK -> EncryptedChunk.decode(bytes)
                FileEnvelopeKind.ACK -> Ack.decode(bytes)
                FileEnvelopeKind.COMPLETE -> Complete.decode(bytes)
                FileEnvelopeKind.REQUEST -> Request.decode(bytes)
                FileEnvelopeKind.CANCEL -> Cancel.decode(bytes)
            }
        }
    }
}

internal enum class FileEnvelopeKind(val wireValue: Byte) {
    OFFER(1),
    CHUNK(2),
    ACK(3),
    COMPLETE(4),
    CANCEL(5),
    REQUEST(6);

    companion object {
        fun fromWireValue(value: Byte): FileEnvelopeKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported file envelope kind wire value: $value")
    }
}
