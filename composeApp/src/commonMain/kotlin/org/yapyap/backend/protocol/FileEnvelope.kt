package org.yapyap.backend.protocol

data class FileEnvelope(
    val transferId: String,
    val source: String,
    val target: String,
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val securityScheme: SignalSecurityScheme,
    val signature: ByteArray?,
    val payload: FilePayload,
) {
    init {
        require(transferId.isNotBlank()) { "transferId must not be blank" }
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
    }

    val kind: FileEnvelopeKind
        get() = payload.kind

    fun encode(): ByteArray {
        val encodedPayload = payload.encode()
        val writer = ByteWriter(256 + nonce.size + encodedPayload.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(payload.kind.wireValue.toInt())
        writer.writeString(transferId)
        writer.writeString(source)
        writer.writeString(target)
        writer.writeLong(createdAtEpochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue.toInt())
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(encodedPayload)
        return writer.toByteArray()
    }

    fun decodePayload(): FilePayload = payload

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.TRANSFER_ID to transferId,
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
            const val TRANSFER_ID = "transferId"
            const val KIND = "kind"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val CREATED_AT_EPOCH_SECONDS = "createdAtEpochSeconds"
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

            val kind = FileEnvelopeKind.fromWireValue(reader.readByte())
            val transferId = reader.readString()
            val source = reader.readString()
            val target = reader.readString()
            val createdAtEpochSeconds = reader.readLong()
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return FileEnvelope(
                transferId = transferId,
                source = source,
                target = target,
                createdAtEpochSeconds = createdAtEpochSeconds,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                payload = FilePayload.decode(kind, encodedPayload),
            )
        }
    }
}

enum class FileType(val wireValue: Byte) {
    GENERIC(1),
    IMAGE(2),
    VIDEO(3),
    AUDIO(4),
    DOCUMENT(5),
    OTHER(6)
}

data class FileChunk(
    val fileName: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    val type: FileType,
    val fileData: ByteArray,
)

data class OpenedFileEnvelope(
    val transferId: String,
    val source: String,
    val target: String,
    val createdAtEpochSeconds: Long,
    val securityScheme: SignalSecurityScheme,
    val payload: FilePayload,
)

sealed interface FilePayload {
    val kind: FileEnvelopeKind

    fun encode(): ByteArray

    data class Offer(
        val fileNameHint: String?,
        val mimeType: String?,
        val totalBytes: Long,
        val chunkSizeBytes: Int,
        val chunkCount: Int,
        val objectHash: ByteArray,
        val control: FileControlPayload,
    ) : FilePayload {
        init {
            require(totalBytes >= 0) { "totalBytes must be >= 0" }
            require(chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }
            require(chunkCount > 0) { "chunkCount must be > 0" }
            require(objectHash.isNotEmpty()) { "objectHash must not be empty" }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.OFFER

        override fun encode(): ByteArray {
            val writer = ByteWriter(128 + objectHash.size)
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
            fun decode(bytes: ByteArray): Offer {
                val reader = ByteReader(bytes)
                val payload = Offer(
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

    data class EncryptedChunk(
        val chunkIndex: Int,
        val chunkCount: Int,
        val chunkCiphertext: ByteArray,
    ) : FilePayload {
        init {
            require(chunkIndex >= 0) { "chunkIndex must be >= 0" }
            require(chunkCount > 0) { "chunkCount must be > 0" }
            require(chunkIndex < chunkCount) { "chunkIndex must be < chunkCount" }
            require(chunkCiphertext.isNotEmpty()) { "chunkCiphertext must not be empty" }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.CHUNK

        override fun encode(): ByteArray {
            val writer = ByteWriter(64 + chunkCiphertext.size)
            writer.writeInt(chunkIndex)
            writer.writeInt(chunkCount)
            writer.writeByteArray(chunkCiphertext)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): EncryptedChunk {
                val reader = ByteReader(bytes)
                val payload = EncryptedChunk(
                    chunkIndex = reader.readInt(),
                    chunkCount = reader.readInt(),
                    chunkCiphertext = reader.readByteArray(),
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    data class Ack(
        val highestContiguousChunk: Int,
        val missingChunkIndices: IntArray,
    ) : FilePayload {
        init {
            require(highestContiguousChunk >= -1) { "highestContiguousChunk must be >= -1" }
            require(missingChunkIndices.all { it >= 0 }) { "missingChunkIndices must be >= 0" }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.ACK

        override fun encode(): ByteArray {
            val writer = ByteWriter(32 + (missingChunkIndices.size * 4))
            writer.writeInt(highestContiguousChunk)
            writer.writeInt(missingChunkIndices.size)
            missingChunkIndices.forEach { writer.writeInt(it) }
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Ack {
                val reader = ByteReader(bytes)
                val highestContiguousChunk = reader.readInt()
                val missingCount = reader.readInt()
                require(missingCount >= 0) { "missing chunk count must be >= 0" }
                val missing = IntArray(missingCount) { reader.readInt() }
                val payload = Ack(
                    highestContiguousChunk = highestContiguousChunk,
                    missingChunkIndices = missing,
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    data class Complete(
        val objectHash: ByteArray,
    ) : FilePayload {
        init {
            require(objectHash.isNotEmpty()) { "objectHash must not be empty" }
        }

        override val kind: FileEnvelopeKind = FileEnvelopeKind.COMPLETE

        override fun encode(): ByteArray {
            val writer = ByteWriter(32 + objectHash.size)
            writer.writeByteArray(objectHash)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Complete {
                val reader = ByteReader(bytes)
                val payload = Complete(
                    objectHash = reader.readByteArray(),
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    data class Cancel(
        val reasonCode: Byte,
        val reasonText: String?,
    ) : FilePayload {
        override val kind: FileEnvelopeKind = FileEnvelopeKind.CANCEL

        override fun encode(): ByteArray {
            val writer = ByteWriter(32 + (reasonText?.length ?: 0))
            writer.writeByte(reasonCode.toInt())
            writer.writeNullableString(reasonText)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Cancel {
                val reader = ByteReader(bytes)
                val payload = Cancel(
                    reasonCode = reader.readByte(),
                    reasonText = reader.readNullableString(),
                )
                reader.requireFullyRead()
                return payload
            }
        }
    }

    companion object {
        fun decode(kind: FileEnvelopeKind, bytes: ByteArray): FilePayload =
            when (kind) {
                FileEnvelopeKind.OFFER -> Offer.decode(bytes)
                FileEnvelopeKind.CHUNK -> EncryptedChunk.decode(bytes)
                FileEnvelopeKind.ACK -> Ack.decode(bytes)
                FileEnvelopeKind.COMPLETE -> Complete.decode(bytes)
                FileEnvelopeKind.CANCEL -> Cancel.decode(bytes)
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

    fun encode(writer: ByteWriter) {
        writer.writeByte(transferClass.wireValue.toInt())
        writer.writeByte(preferredTransport.wireValue.toInt())
        writer.writeByte(if (supportsResume) 1 else 0)
        writer.writeInt(maxInFlightChunks)
    }

    companion object {
        fun decode(reader: ByteReader): FileControlPayload {
            return FileControlPayload(
                transferClass = FileTransferClass.fromWireValue(reader.readByte()),
                preferredTransport = FileTransportPreference.fromWireValue(reader.readByte()),
                supportsResume = reader.readByte().toInt() != 0,
                maxInFlightChunks = reader.readInt(),
            )
        }
    }
}
