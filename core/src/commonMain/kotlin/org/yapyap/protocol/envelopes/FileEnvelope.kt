package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.FILE_BLOCK_MAX_COUNT
import org.yapyap.protocol.FILE_HASH_BYTES
import org.yapyap.protocol.FileId
import org.yapyap.protocol.MAX_CHUNK_CIPHERTEXT_BYTES
import org.yapyap.protocol.MAX_ENVELOPE_NONCE_BYTES
import org.yapyap.protocol.MAX_ENVELOPE_SIGNATURE_BYTES
import org.yapyap.protocol.MAX_FILE_NAME_BYTES
import org.yapyap.protocol.MAX_FILE_NAME_CHARS
import org.yapyap.protocol.MAX_MIME_TYPE_BYTES
import org.yapyap.protocol.MAX_MIME_TYPE_CHARS
import org.yapyap.protocol.MAX_SEALED_OFFER_BYTES
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TRANSFER_KEY_BYTES
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
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

        /**
         * Bytes added by [encode] around [payload] (worst case): MAGIC(4) + VERSION(1)
         * + transferId(16) + source(2+64) + target(2+64) + createdAt(8) + nonce(4+24) + scheme(1)
         * + signature(1+4+64) + payload length prefix(4) = 263.
         */
        const val ENCODED_OVERHEAD_BYTES: Int = 263

        /**
         * The largest payload a file envelope can carry inside a [BinaryEnvelope] of
         * [binaryEnvelopeBytes]: both envelopes' worst-case framing subtracted.
         */
        fun maxPayloadBytes(binaryEnvelopeBytes: Int): Int =
            binaryEnvelopeBytes - BinaryEnvelope.ENCODED_HEADER_BYTES - ENCODED_OVERHEAD_BYTES

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
            val nonce = reader.readByteArray(maxSize = MAX_ENVELOPE_NONCE_BYTES)
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray(maxSize = MAX_ENVELOPE_SIGNATURE_BYTES)
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
    val source: PeerId,
    val target: PeerId,
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
        require(startBytes <= Long.MAX_VALUE - lengthBytes) { "range end overflows" }
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
     * Public fields visible to relays (design §3.2): the OFFER public half, the CHUNK
     * index, ACK coverage, COMPLETE and the CANCEL reason. Sealed and encrypted content
     * (fileId, block list, keys, REQUEST coverage) never appears here.
     */
    fun observablePublicValues(): Map<String, Any?>

    /**
     * OFFER public half (relays read this) + opaque ratchet-sealed half.
     * The sealed bytes decrypt to [SealedOffer] (sealed via the ratchet).
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

        override fun observablePublicValues(): Map<String, Any?> = mapOf(
            "chunkSizeBytes" to chunkSizeBytes,
            "totalBytes" to totalBytes,
            "startIndex" to startIndex,
        )

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
                    sealedOfferBytes = reader.readByteArray(maxSize = MAX_SEALED_OFFER_BYTES),
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
        val transferKey: TransferKey,
        val fileName: String,
        val mimeType: String?,
    ) {
        init {
            require(blockSize > 0) { "blockSize must be > 0" }
            require(fileName.isNotBlank()) { "fileName must not be blank" }
            require(fileName.length <= MAX_FILE_NAME_CHARS) {
                "fileName exceeds $MAX_FILE_NAME_CHARS chars"
            }
            require(fileName.encodeToByteArray().size <= MAX_FILE_NAME_BYTES) {
                "fileName exceeds $MAX_FILE_NAME_BYTES bytes"
            }
            if (mimeType != null) {
                require(mimeType.isNotBlank()) { "mimeType must not be blank when present" }
                require(mimeType.length <= MAX_MIME_TYPE_CHARS) {
                    "mimeType exceeds $MAX_MIME_TYPE_CHARS chars"
                }
                require(mimeType.encodeToByteArray().size <= MAX_MIME_TYPE_BYTES) {
                    "mimeType exceeds $MAX_MIME_TYPE_BYTES bytes"
                }
            }
            require(blockHashes.size <= FILE_BLOCK_MAX_COUNT) {
                "block count ${blockHashes.size} exceeds max $FILE_BLOCK_MAX_COUNT"
            }
            require(blockHashes.all { it.size == FILE_HASH_BYTES }) { "every block hash must be $FILE_HASH_BYTES bytes" }
        }

        fun encode(): ByteArray {
            val writer = ByteWriter(128 + blockHashes.size * 32 + TRANSFER_KEY_BYTES)
            writer.writeString(fileId.value)
            writer.writeInt(blockSize)
            writer.writeInt(blockHashes.size)
            blockHashes.forEach { writer.writeByteArray(it, maxSize = FILE_HASH_BYTES) }
            writer.writeByteArray(transferKey.bytes, maxSize = TRANSFER_KEY_BYTES)
            writer.writeString(fileName)
            writer.writeNullableString(mimeType)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SealedOffer {
                val reader = ByteReader(bytes)
                val fileId = FileId(reader.readBoundedString(64))
                val blockSize = reader.readInt()
                val count = reader.readInt()
                require(count in 0..FILE_BLOCK_MAX_COUNT) { "block count $count out of bounds" }
                val hashes = List(count) { reader.readHash() }
                val key = TransferKey(
                    reader.readByteArray(maxSize = TRANSFER_KEY_BYTES).also {
                        require(it.size == TRANSFER_KEY_BYTES) { "transferKey must be $TRANSFER_KEY_BYTES bytes" }
                    },
                )
                val name = reader.readBoundedString(MAX_FILE_NAME_BYTES)
                val mime = reader.readBoundedNullableString(MAX_MIME_TYPE_BYTES)
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
            if (transferKey != other.transferKey) return false
            if (fileName != other.fileName) return false
            if (mimeType != other.mimeType) return false
            return true
        }

        override fun hashCode(): Int {
            var result = fileId.hashCode()
            result = 31 * result + blockSize
            result = 31 * result + blockHashes.fold(0) { acc, h -> 31 * acc + h.contentHashCode() }
            result = 31 * result + transferKey.hashCode()
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

        override fun observablePublicValues(): Map<String, Any?> = mapOf(
            "chunkIndex" to chunkIndex,
        )

        companion object {
            /** Bytes [encode] adds around the ciphertext: kind(1) + chunkIndex(4) + length prefix(4). */
            const val ENCODED_OVERHEAD_BYTES: Int = 9

            fun decode(bytes: ByteArray): EncryptedChunk {
                val reader = ByteReader(bytes)
                require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.CHUNK) {
                    "Expected CHUNK payload kind"
                }
                val payload = EncryptedChunk(
                    chunkIndex = reader.readInt(),
                    chunkCiphertext = reader.readByteArray(maxSize = MAX_CHUNK_CIPHERTEXT_BYTES),
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
        /** Highest contiguous chunk index covered (`-1` when none); `count - 1` when complete. */
        val highestContiguousIndex: Int,
        val missingRanges: List<ChunkRange>,
    ) : FilePayload {
        init {
            require(highestContiguousIndex >= -1) { "highestContiguousIndex must be >= -1" }
            require(missingRanges.size <= MAX_MISSING_RANGES) {
                "missingRanges exceed max $MAX_MISSING_RANGES"
            }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.ACK

        override fun encode(): ByteArray {
            val writer = ByteWriter(16 + missingRanges.size * 8)
            writer.writeByte(kind.wireValue)
            writer.writeInt(highestContiguousIndex)
            writer.writeInt(missingRanges.size)
            missingRanges.forEach {
                writer.writeInt(it.startIndex)
                writer.writeInt(it.count)
            }
            return writer.toByteArray()
        }

        override fun observablePublicValues(): Map<String, Any?> = mapOf(
            "highestContiguousIndex" to highestContiguousIndex,
            "missingRanges" to missingRanges,
        )

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
                val payload = Ack(highestContiguousIndex = contiguous, missingRanges = ranges)
                reader.requireFullyRead()
                return payload
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Ack
            if (highestContiguousIndex != other.highestContiguousIndex) return false
            if (missingRanges != other.missingRanges) return false
            if (kind != other.kind) return false
            return true
        }

        override fun hashCode(): Int {
            var result = highestContiguousIndex
            result = 31 * result + missingRanges.hashCode()
            result = 31 * result + kind.hashCode()
            return result
        }
    }

    data object Complete : FilePayload {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.COMPLETE

        override fun encode(): ByteArray = byteArrayOf(kind.wireValue)

        override fun observablePublicValues(): Map<String, Any?> = mapOf("complete" to true)

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
     * Protection ratchet-seals the encoded bytes, like messages; no public half.
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

        /**
         * REQUEST carries no public fields: the fileId and coverage ride ratchet-sealed,
         * so relays see only the kind byte. The map stays empty by design.
         */
        override fun observablePublicValues(): Map<String, Any?> = emptyMap()

        companion object {
            const val MAX_MISSING_RANGES: Int = 16

            fun decode(bytes: ByteArray): Request {
                val reader = ByteReader(bytes)
                require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.REQUEST) {
                    "Expected REQUEST payload kind"
                }
                val fileId = FileId(reader.readBoundedString(64))
                val contiguousBytes = reader.readLong()
                val count = reader.readInt()
                require(count in 0..MAX_MISSING_RANGES) { "missing range count $count out of bounds" }
                val ranges = List(count) { ByteRange(reader.readLong(), reader.readLong()) }
                val payload = Request(
                    fileId = fileId,
                    contiguousBytes = contiguousBytes,
                    missingRanges = ranges,
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    /**
     * Polite teardown (design §3.2, §3.6): the reason is visible to relays, so no
     * free-form text travels on the wire.
     */
    data class Cancel(
        val reason: FileCancelReason,
    ) : FilePayload {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.CANCEL

        override fun encode(): ByteArray {
            val writer = ByteWriter(2)
            writer.writeByte(kind.wireValue)
            writer.writeByte(reason.wireValue)
            return writer.toByteArray()
        }

        override fun observablePublicValues(): Map<String, Any?> = mapOf(
            "cancelReason" to reason,
        )

        companion object {
            fun decode(bytes: ByteArray): Cancel {
                val reader = ByteReader(bytes)
                require(FileEnvelopeKind.fromWireValue(reader.readByte()) == FileEnvelopeKind.CANCEL) {
                    "Expected CANCEL payload kind"
                }
                val payload = Cancel(
                    reason = FileCancelReason.fromWireValue(reader.readByte()),
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
