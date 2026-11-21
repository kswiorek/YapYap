package org.yapyap.protocol

import org.yapyap.protocol.envelopes.FilePayload

/**
 * Large-path fetch frames (design §6.6): plain fetch protocol on a dedicated
 * WebRTC data channel. No envelope, no `transferId`, no key, no outbox row.
 * One fetch = one channel between the requesting device and one holder.
 *
 * Each channel message is one frame: a type byte, then the body. `DATA` is the
 * type byte followed by raw bytes (SCTP preserves message boundaries, so no
 * length prefix is needed). All other frames use [ByteWriter]/[ByteReader].
 *
 * The `fileId` appears in `FETCH` in the clear inside the channel — DTLS
 * covers it, and only the peer device ever sees the channel (design §4,
 * invariant 23). This is intentionally *not* part of [FilePayload]: fetch
 * frames never go through the envelope pipeline, protection or the outbox.
 */
internal enum class FileFetchKind(val wireValue: Byte) {
    FETCH(21),
    FETCH_OK(22),
    FETCH_DECLINED(23),
    PROGRESS(24),
    ABORT(25),
    DATA(26);

    companion object {
        fun fromWireValue(value: Byte): FileFetchKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported file fetch kind wire value: $value")
    }
}

internal enum class FileFetchDeclineReason(val wireValue: Byte) {
    NOT_HELD(1),
    NOT_ALLOWED(2);

    companion object {
        fun fromWireValue(value: Byte): FileFetchDeclineReason =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported fetch decline reason wire value: $value")
    }
}

internal enum class FileFetchAbortReason(val wireValue: Byte) {
    SOURCE_ERROR(1),
    NO_SPACE(2),
    STALLED(3),
    CANCELLED(4);

    companion object {
        fun fromWireValue(value: Byte): FileFetchAbortReason =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported fetch abort reason wire value: $value")
    }
}

internal sealed interface FileFetchFrame {
    val kind: FileFetchKind

    fun encode(): ByteArray

    data class Fetch(
        val fileId: FileId,
        val fromOffset: Long,
    ) : FileFetchFrame {
        init {
            require(fromOffset >= 0) { "fromOffset must be >= 0" }
        }

        override val kind: FileFetchKind = FileFetchKind.FETCH

        override fun encode(): ByteArray {
            val writer = ByteWriter(80)
            writer.writeByte(kind.wireValue)
            writer.writeString(fileId.value)
            writer.writeLong(fromOffset)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Fetch {
                val reader = ByteReader(bytes)
                require(FileFetchKind.fromWireValue(reader.readByte()) == FileFetchKind.FETCH) {
                    "Expected FETCH frame kind"
                }
                val payload = Fetch(
                    fileId = FileId(reader.readBoundedString(64)),
                    fromOffset = reader.readLong(),
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    data class FetchOk(
        val totalBytes: Long,
        val blockSize: Int,
        val blockHashes: List<ByteArray>,
        val fileName: String,
        val mimeType: String?,
    ) : FileFetchFrame {
        init {
            require(totalBytes >= 0) { "totalBytes must be >= 0" }
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
            require(blockHashes.all { it.size == FILE_HASH_BYTES }) {
                "every block hash must be $FILE_HASH_BYTES bytes"
            }
        }

        override val kind: FileFetchKind = FileFetchKind.FETCH_OK

        override fun encode(): ByteArray {
            val writer = ByteWriter(64 + blockHashes.size * 32 + fileName.length)
            writer.writeByte(kind.wireValue)
            writer.writeLong(totalBytes)
            writer.writeInt(blockSize)
            writer.writeInt(blockHashes.size)
            blockHashes.forEach { writer.writeByteArray(it, maxSize = FILE_HASH_BYTES) }
            writer.writeString(fileName)
            writer.writeNullableString(mimeType)
            return writer.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as FetchOk
            if (totalBytes != other.totalBytes) return false
            if (blockSize != other.blockSize) return false
            if (blockHashes.size != other.blockHashes.size) return false
            if (!blockHashes.zip(other.blockHashes).all { (a, b) -> a.contentEquals(b) }) return false
            if (fileName != other.fileName) return false
            if (mimeType != other.mimeType) return false
            if (kind != other.kind) return false
            return true
        }

        override fun hashCode(): Int {
            var result = totalBytes.hashCode()
            result = 31 * result + blockSize
            result = 31 * result + blockHashes.fold(0) { acc, h -> 31 * acc + h.contentHashCode() }
            result = 31 * result + fileName.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            result = 31 * result + kind.hashCode()
            return result
        }

        companion object {
            fun decode(bytes: ByteArray): FetchOk {
                val reader = ByteReader(bytes)
                require(FileFetchKind.fromWireValue(reader.readByte()) == FileFetchKind.FETCH_OK) {
                    "Expected FETCH_OK frame kind"
                }
                val totalBytes = reader.readLong()
                val blockSize = reader.readInt()
                val count = reader.readInt()
                require(count in 0..FILE_BLOCK_MAX_COUNT) { "block count $count out of bounds" }
                val hashes = List(count) { reader.readHash() }
                val fileName = reader.readBoundedString(MAX_FILE_NAME_BYTES)
                val mimeType = reader.readBoundedNullableString(MAX_MIME_TYPE_BYTES)
                reader.requireFullyRead()
                return FetchOk(
                    totalBytes = totalBytes,
                    blockSize = blockSize,
                    blockHashes = hashes,
                    fileName = fileName,
                    mimeType = mimeType,
                )
            }
        }
    }

    data class FetchDeclined(
        val reason: FileFetchDeclineReason,
    ) : FileFetchFrame {
        override val kind: FileFetchKind = FileFetchKind.FETCH_DECLINED

        override fun encode(): ByteArray {
            val writer = ByteWriter(2)
            writer.writeByte(kind.wireValue)
            writer.writeByte(reason.wireValue)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): FetchDeclined {
                val reader = ByteReader(bytes)
                require(FileFetchKind.fromWireValue(reader.readByte()) == FileFetchKind.FETCH_DECLINED) {
                    "Expected FETCH_DECLINED frame kind"
                }
                val payload = FetchDeclined(reason = FileFetchDeclineReason.fromWireValue(reader.readByte()))
                reader.requireFullyRead()
                return payload
            }
        }
    }

    data class Progress(
        val contiguousBytes: Long,
    ) : FileFetchFrame {
        init {
            require(contiguousBytes >= 0) { "contiguousBytes must be >= 0" }
        }

        override val kind: FileFetchKind = FileFetchKind.PROGRESS

        override fun encode(): ByteArray {
            val writer = ByteWriter(9)
            writer.writeByte(kind.wireValue)
            writer.writeLong(contiguousBytes)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Progress {
                val reader = ByteReader(bytes)
                require(FileFetchKind.fromWireValue(reader.readByte()) == FileFetchKind.PROGRESS) {
                    "Expected PROGRESS frame kind"
                }
                val payload = Progress(contiguousBytes = reader.readLong())
                reader.requireFullyRead()
                return payload
            }
        }
    }

    data class Abort(
        val reason: FileFetchAbortReason,
        val reasonText: String?,
    ) : FileFetchFrame {
        init {
            require(reasonText == null || reasonText.encodeToByteArray().size <= MAX_REASON_TEXT_BYTES) {
                "reasonText exceeds $MAX_REASON_TEXT_BYTES bytes"
            }
        }

        override val kind: FileFetchKind = FileFetchKind.ABORT

        override fun encode(): ByteArray {
            val writer = ByteWriter(16 + (reasonText?.length ?: 0))
            writer.writeByte(kind.wireValue)
            writer.writeByte(reason.wireValue)
            writer.writeNullableString(reasonText)
            return writer.toByteArray()
        }

        companion object {
            /** Cap on the diagnostic text, enforced on both encode and decode. */
            const val MAX_REASON_TEXT_BYTES: Int = 512

            fun decode(bytes: ByteArray): Abort {
                val reader = ByteReader(bytes)
                require(FileFetchKind.fromWireValue(reader.readByte()) == FileFetchKind.ABORT) {
                    "Expected ABORT frame kind"
                }
                val payload = Abort(
                    reason = FileFetchAbortReason.fromWireValue(reader.readByte()),
                    reasonText = reader.readBoundedNullableString(MAX_REASON_TEXT_BYTES),
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    data class Data(
        val bytes: ByteArray,
    ) : FileFetchFrame {
        override val kind: FileFetchKind = FileFetchKind.DATA

        override fun encode(): ByteArray {
            val writer = ByteWriter(1 + bytes.size)
            writer.writeByte(kind.wireValue)
            writer.writeBytes(bytes)
            return writer.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Data
            if (!bytes.contentEquals(other.bytes)) return false
            if (kind != other.kind) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + kind.hashCode()
            return result
        }

        companion object {
            /** Bytes [encode] adds around the file bytes: kind(1). */
            const val ENCODED_OVERHEAD_BYTES: Int = 1

            fun decode(bytes: ByteArray): Data {
                require(bytes.isNotEmpty()) { "Empty fetch frame" }
                require(FileFetchKind.fromWireValue(bytes[0]) == FileFetchKind.DATA) {
                    "Expected DATA frame kind"
                }
                return Data(bytes = bytes.copyOfRange(1, bytes.size))
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray): FileFetchFrame {
            require(bytes.isNotEmpty()) { "Empty fetch frame" }
            return when (FileFetchKind.fromWireValue(bytes[0])) {
                FileFetchKind.FETCH -> Fetch.decode(bytes)
                FileFetchKind.FETCH_OK -> FetchOk.decode(bytes)
                FileFetchKind.FETCH_DECLINED -> FetchDeclined.decode(bytes)
                FileFetchKind.PROGRESS -> Progress.decode(bytes)
                FileFetchKind.ABORT -> Abort.decode(bytes)
                FileFetchKind.DATA -> Data.decode(bytes)
            }
        }
    }
}
