package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.CONTENT_ID_BYTES
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FILE_BLOCK_MAX_COUNT
import org.yapyap.protocol.FILE_HASH_BYTES
import org.yapyap.protocol.FileId
import org.yapyap.protocol.MAX_FILE_NAME_BYTES
import org.yapyap.protocol.MAX_FILE_NAME_CHARS
import org.yapyap.protocol.MAX_MIME_TYPE_BYTES
import org.yapyap.protocol.MAX_MIME_TYPE_CHARS
import org.yapyap.protocol.MIN_FILE_CHUNK_SIZE_BYTES
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SEAL_KEY_BYTES
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.TransferId

/**
 * The FILE family's routing headers (docs/envelope-redesign.md §4–§5). A transfer is one hop
 * between two devices: OFFER opens it, CHUNKs fill it, ACK and CANCEL answer it. COMPLETE is the
 * only seal-level packet (final target → origin). REQUEST starts a serve.
 *
 * Contents per kind: an OFFER carries the seal (a ratchet frame from the origin to the final
 * target, decrypting to [FileSeal]); a CHUNK carries AEAD ciphertext under the seal's key; a
 * REQUEST carries a ratchet frame decrypting to [FileRequest]. The other kinds have no content.
 */
internal sealed interface FileHeader {
    val kind: FileEnvelopeKind

    fun encode(): ByteArray

    /** Fields every hop may read (the observability profile's FILE entries). */
    fun observableValues(): Map<String, Any?>

    /** A header addressed by its transfer (every kind but COMPLETE and REQUEST). */
    sealed interface OfTransfer : FileHeader {
        val transferId: TransferId
    }

    /**
     * Opens a hop. [origin] made the seal in the content and [finalTarget] is the device it is
     * sealed to: a receiver that is not [finalTarget] is a relay and parks the chunks. [shape]
     * is public so a relay can reserve space and count wholeness.
     */
    data class Offer(
        override val transferId: TransferId,
        val origin: PeerId,
        val finalTarget: PeerId,
        val shape: TransferShape,
    ) : OfTransfer {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.OFFER

        override fun encode(): ByteArray {
            val writer = ByteWriter(192)
            writer.writeByte(kind.wireValue)
            writer.writeUuid(transferId.value)
            writer.writePeerId(origin)
            writer.writePeerId(finalTarget)
            shape.write(writer)
            return writer.toByteArray()
        }

        override fun observableValues(): Map<String, Any?> = mapOf(
            Fields.TRANSFER_ID to transferId,
            Fields.ORIGIN to origin,
            Fields.FINAL_TARGET to finalTarget,
            Fields.CHUNK_SIZE to shape.chunkSizeBytes,
            Fields.BYTE_FROM to shape.byteFrom,
            Fields.BYTE_TO to shape.byteTo,
        )
    }

    data class Chunk(
        override val transferId: TransferId,
        val chunkIndex: Int,
    ) : OfTransfer {
        init {
            require(chunkIndex >= 0) { "chunkIndex must be >= 0" }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.CHUNK

        override fun encode(): ByteArray {
            val writer = ByteWriter(CHUNK_ENCODED_BYTES)
            writer.writeByte(kind.wireValue)
            writer.writeUuid(transferId.value)
            writer.writeInt(chunkIndex)
            return writer.toByteArray()
        }

        override fun observableValues(): Map<String, Any?> = mapOf(
            Fields.TRANSFER_ID to transferId,
            Fields.CHUNK_INDEX to chunkIndex,
        )
    }

    /** The receiver's coverage in the transfer's own chunk indices. */
    data class Ack(
        override val transferId: TransferId,
        /** Highest contiguous chunk index covered (`-1` when none); `count - 1` when complete. */
        val highestContiguousIndex: Int,
        val missingRanges: List<ChunkRange>,
    ) : OfTransfer {
        init {
            require(highestContiguousIndex >= -1) { "highestContiguousIndex must be >= -1" }
            require(missingRanges.size <= MAX_MISSING_RANGES) { "missingRanges exceed max $MAX_MISSING_RANGES" }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.ACK

        override fun encode(): ByteArray {
            val writer = ByteWriter(32 + missingRanges.size * 8)
            writer.writeByte(kind.wireValue)
            writer.writeUuid(transferId.value)
            writer.writeInt(highestContiguousIndex)
            writer.writeInt(missingRanges.size)
            missingRanges.forEach {
                writer.writeInt(it.startIndex)
                writer.writeInt(it.count)
            }
            return writer.toByteArray()
        }

        override fun observableValues(): Map<String, Any?> = mapOf(
            Fields.TRANSFER_ID to transferId,
            Fields.HIGHEST_CONTIGUOUS_INDEX to highestContiguousIndex,
            Fields.MISSING_RANGES to missingRanges,
        )
    }

    /** Ends the hop at the other end; the reason is visible, so no free-form text travels. */
    data class Cancel(
        override val transferId: TransferId,
        val reason: FileCancelReason,
    ) : OfTransfer {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.CANCEL

        override fun encode(): ByteArray {
            val writer = ByteWriter(18)
            writer.writeByte(kind.wireValue)
            writer.writeUuid(transferId.value)
            writer.writeByte(reason.wireValue)
            return writer.toByteArray()
        }

        override fun observableValues(): Map<String, Any?> = mapOf(
            Fields.TRANSFER_ID to transferId,
            Fields.CANCEL_REASON to reason,
        )
    }

    /** The final target holds the file: sent to the seal's origin, the only seal-level packet. */
    data class Complete(val sealId: ContentId) : FileHeader {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.COMPLETE

        override fun encode(): ByteArray {
            val writer = ByteWriter(1 + CONTENT_ID_BYTES)
            writer.writeByte(kind.wireValue)
            writer.writeBytes(sealId.bytes)
            return writer.toByteArray()
        }

        override fun observableValues(): Map<String, Any?> = mapOf(Fields.SEAL_ID to sealId)
    }

    /** A sealed, direct-only REQUEST: everything it says is in its content ([FileRequest]). */
    data object Request : FileHeader {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.REQUEST

        override fun encode(): ByteArray = byteArrayOf(kind.wireValue)

        override fun observableValues(): Map<String, Any?> = emptyMap()
    }

    companion object {
        object Fields {
            const val TRANSFER_ID = "transferId"
            const val ORIGIN = "origin"
            const val FINAL_TARGET = "finalTarget"
            const val CHUNK_SIZE = "chunkSizeBytes"
            const val BYTE_FROM = "byteFrom"
            const val BYTE_TO = "byteTo"
            const val CHUNK_INDEX = "chunkIndex"
            const val HIGHEST_CONTIGUOUS_INDEX = "highestContiguousIndex"
            const val MISSING_RANGES = "missingRanges"
            const val CANCEL_REASON = "cancelReason"
            const val SEAL_ID = "sealId"
        }

        /** A CHUNK header's size: kind(1) + transferId(16) + chunkIndex(4). */
        const val CHUNK_ENCODED_BYTES: Int = 21

        const val MAX_MISSING_RANGES: Int = 16

        fun decode(bytes: ByteArray): FileHeader {
            val reader = ByteReader(bytes)
            val header = when (FileEnvelopeKind.fromWireValue(reader.readByte())) {
                FileEnvelopeKind.OFFER -> Offer(
                    transferId = TransferId(reader.readUuid()),
                    origin = reader.readPeerId(),
                    finalTarget = reader.readPeerId(),
                    shape = TransferShape.read(reader),
                )

                FileEnvelopeKind.CHUNK -> Chunk(TransferId(reader.readUuid()), reader.readInt())
                FileEnvelopeKind.ACK -> {
                    val transferId = TransferId(reader.readUuid())
                    val contiguous = reader.readInt()
                    val count = reader.readInt()
                    require(count in 0..MAX_MISSING_RANGES) { "missing range count $count out of bounds" }
                    Ack(transferId, contiguous, List(count) { ChunkRange(reader.readInt(), reader.readInt()) })
                }

                FileEnvelopeKind.CANCEL -> Cancel(
                    TransferId(reader.readUuid()),
                    FileCancelReason.fromWireValue(reader.readByte()),
                )

                FileEnvelopeKind.COMPLETE -> Complete(ContentId(reader.readBytes(CONTENT_ID_BYTES)))
                FileEnvelopeKind.REQUEST -> Request
            }
            reader.requireFullyRead()
            return header
        }
    }
}

/**
 * The public shape of one transfer: the byte range `[byteFrom, byteTo)` it moves, cut into
 * chunks of [chunkSizeBytes] starting at [byteFrom] (the last may be short). Fixed for the
 * transfer's life; transfers of the same seal may differ in every field, because each chunk's
 * AEAD binds its own byte range (docs/envelope-redesign.md §5.3).
 */
internal data class TransferShape(
    val chunkSizeBytes: Int,
    val byteFrom: Long,
    val byteTo: Long,
) {
    init {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }
        require(byteFrom in 0..byteTo) { "byteFrom must be in 0..byteTo" }
    }

    val byteCount: Long get() = byteTo - byteFrom

    /** Chunks in the transfer; 0 for an empty range. */
    val chunkCount: Int
        get() = if (byteCount == 0L) 0 else ((byteCount - 1) / chunkSizeBytes + 1).toInt()

    fun chunkOffset(index: Int): Long = byteFrom + index.toLong() * chunkSizeBytes

    fun chunkLength(index: Int): Int = minOf(chunkSizeBytes.toLong(), byteTo - chunkOffset(index)).toInt()

    /**
     * What any receiver accepts (target and relay alike): chunks of at least
     * [MIN_FILE_CHUNK_SIZE_BYTES] and a small-path range. Anything finer would let a 10 MiB
     * reservation turn into millions of stored chunks.
     */
    fun isAcceptable(): Boolean = chunkSizeBytes >= MIN_FILE_CHUNK_SIZE_BYTES && byteTo <= SMALL_FILE_MAX_BYTES

    /**
     * Coverage in this transfer's own chunk indices for a receiver whose contiguous file prefix
     * is [contiguousBytes]: chunk `k` is covered once its whole range is.
     */
    fun highestContiguousIndex(contiguousBytes: Long): Int = when {
        chunkCount == 0 -> -1
        contiguousBytes >= byteTo -> chunkCount - 1
        contiguousBytes <= byteFrom -> -1
        else -> ((contiguousBytes - byteFrom) / chunkSizeBytes).toInt() - 1
    }

    internal fun write(writer: ByteWriter) {
        writer.writeInt(chunkSizeBytes)
        writer.writeLong(byteFrom)
        writer.writeLong(byteTo)
    }

    internal companion object {
        fun read(reader: ByteReader): TransferShape =
            TransferShape(chunkSizeBytes = reader.readInt(), byteFrom = reader.readLong(), byteTo = reader.readLong())
    }
}

/**
 * A file sealed for one target device — the plaintext of an OFFER's content. File facts only:
 * the identity and block list, display metadata, and the [key] every chunk of this seal is
 * encrypted under. Made once per (file, target device) per push and carried unchanged by every
 * transfer of it (docs/envelope-redesign.md §5.2).
 */
internal class FileSeal(
    val fileId: FileId,
    val totalBytes: Long,
    val blockSize: Int,
    val blockHashes: List<ByteArray>,
    val fileName: String,
    val mimeType: String?,
    val key: SealKey,
) {
    init {
        require(totalBytes in 0..SMALL_FILE_MAX_BYTES) { "totalBytes out of the small-file range" }
        require(blockSize > 0) { "blockSize must be > 0" }
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        require(fileName.length <= MAX_FILE_NAME_CHARS) { "fileName exceeds $MAX_FILE_NAME_CHARS chars" }
        require(fileName.encodeToByteArray().size <= MAX_FILE_NAME_BYTES) { "fileName exceeds $MAX_FILE_NAME_BYTES bytes" }
        if (mimeType != null) {
            require(mimeType.isNotBlank()) { "mimeType must not be blank when present" }
            require(mimeType.length <= MAX_MIME_TYPE_CHARS) { "mimeType exceeds $MAX_MIME_TYPE_CHARS chars" }
            require(mimeType.encodeToByteArray().size <= MAX_MIME_TYPE_BYTES) { "mimeType exceeds $MAX_MIME_TYPE_BYTES bytes" }
        }
        require(blockHashes.size <= FILE_BLOCK_MAX_COUNT) { "block count ${blockHashes.size} exceeds max $FILE_BLOCK_MAX_COUNT" }
        require(blockHashes.all { it.size == FILE_HASH_BYTES }) { "every block hash must be $FILE_HASH_BYTES bytes" }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter(192 + blockHashes.size * (4 + FILE_HASH_BYTES))
        writer.writeString(fileId.value)
        writer.writeLong(totalBytes)
        writer.writeInt(blockSize)
        writer.writeInt(blockHashes.size)
        blockHashes.forEach { writer.writeByteArray(it, maxSize = FILE_HASH_BYTES) }
        writer.writeString(fileName)
        writer.writeNullableString(mimeType)
        writer.writeByteArray(key.bytes, maxSize = SEAL_KEY_BYTES)
        return writer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): FileSeal {
            val reader = ByteReader(bytes)
            val fileId = FileId(reader.readBoundedString(64))
            val totalBytes = reader.readLong()
            val blockSize = reader.readInt()
            val count = reader.readInt()
            require(count in 0..FILE_BLOCK_MAX_COUNT) { "block count $count out of bounds" }
            val hashes = List(count) { reader.readHash() }
            val name = reader.readBoundedString(MAX_FILE_NAME_BYTES)
            val mime = reader.readBoundedNullableString(MAX_MIME_TYPE_BYTES)
            val key = reader.readByteArray(maxSize = SEAL_KEY_BYTES)
            reader.requireFullyRead()
            return FileSeal(fileId, totalBytes, blockSize, hashes, name, mime, SealKey(key))
        }
    }
}

/** A REQUEST's sealed content: the requester's held coverage of [fileId] in bytes. */
internal data class FileRequest(
    val fileId: FileId,
    val contiguousBytes: Long,
    val missingRanges: List<ByteRange>,
) {
    init {
        require(contiguousBytes >= 0) { "contiguousBytes must be >= 0" }
        require(missingRanges.size <= MAX_MISSING_RANGES) { "missingRanges exceed max $MAX_MISSING_RANGES" }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter(96 + missingRanges.size * 16)
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

        fun decode(bytes: ByteArray): FileRequest {
            val reader = ByteReader(bytes)
            val fileId = FileId(reader.readBoundedString(64))
            val contiguousBytes = reader.readLong()
            val count = reader.readInt()
            require(count in 0..MAX_MISSING_RANGES) { "missing range count $count out of bounds" }
            val ranges = List(count) { ByteRange(reader.readLong(), reader.readLong()) }
            reader.requireFullyRead()
            return FileRequest(fileId, contiguousBytes, ranges)
        }
    }
}

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
