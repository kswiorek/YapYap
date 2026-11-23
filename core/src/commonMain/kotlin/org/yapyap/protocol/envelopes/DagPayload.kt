package org.yapyap.protocol.envelopes

import org.yapyap.protocol.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The causal-history family: chat text, a room event or a global event. Every node shares the
 * common header; [payloadType] is its own second wire byte after the family byte.
 *
 * [decode] reads the family and node-type bytes, then runs that node type's own decode.
 *
 * Delivery lifecycle and orphan flags are local DB concerns and are not on the wire.
 */
internal sealed interface DagPayload : MessagePayload {
    override val family: MessagePayloadFamily get() = MessagePayloadFamily.DAG
    override val contentId: Uuid get() = messageId

    val messageId: Uuid
    val roomId: RoomId
    val senderAccountId: AccountId
    val authorDeviceId: PeerId

    /**
     * Causal parents of this message. Empty for the DAG root (genesis) only; every other
     * message references the room's chainable frontier at append time.
     */
    val prevIds: List<Uuid>

    /**
     * Sender's wall-clock composition time, set once by
     * [org.yapyap.orchestrator.dag.DagEngine.append] at send time and carried on the wire
     * unchanged. Distinct from [MessageEnvelope.createdAt] (transport-level, set at envelope
     * assembly). Used as the primary GUI display-order key.
     */
    val createdAt: Instant
    val payloadType: DagPayloadType
    val authorSignature: ByteArray?

    fun encodeForAuthorSigning(): ByteArray

    fun withSignature(signature: ByteArray): DagPayload

    companion object {
        /** Reads the family and node-type bytes, then runs the node type's own decode. */
        fun decode(bytes: ByteArray): DagPayload {
            val reader = ByteReader(bytes)
            require(MessagePayloadFamily.fromWireValue(reader.readByte()) == MessagePayloadFamily.DAG) {
                "Expected DAG payload family"
            }
            return when (DagPayloadType.fromWireValue(reader.readByte())) {
                DagPayloadType.TEXT -> Text.decode(bytes)
                DagPayloadType.GLOBAL_EVENT -> GlobalEvent.decode(bytes)
                DagPayloadType.ROOM_EVENT -> RoomEvent.decode(bytes)
            }
        }
    }

    data class Text(
        override val messageId: Uuid,
        override val roomId: RoomId,
        override val senderAccountId: AccountId,
        override val authorDeviceId: PeerId,
        override val prevIds: List<Uuid>,
        override val createdAt: Instant,
        val text: String,
        val attachments: List<Attachment> = emptyList(),
        override val authorSignature: ByteArray? = null,
    ) : DagPayload {
        init {
            if (authorSignature != null) {
                require(authorSignature.isNotEmpty()) { "authorSignature must not be empty" }
            }
            require(attachments.size <= Attachment.MAX_ATTACHMENTS) {
                "attachments exceed max ${Attachment.MAX_ATTACHMENTS}"
            }
        }

        override val payloadType: DagPayloadType = DagPayloadType.TEXT

        override fun withSignature(signature: ByteArray): Text = copy(authorSignature = signature)

        override fun encode(): ByteArray {
            val writer = ByteWriter(256 + text.length + (authorSignature?.size ?: 4))
            writeCommonHeader(writer)
            writer.writeString(text)
            writer.writeInt(attachments.size)
            attachments.forEach { it.encode(writer) }
            writer.writeNullableByteArray(authorSignature)
            return writer.toByteArray()
        }

        override fun encodeForAuthorSigning(): ByteArray {
            val writer = ByteWriter(256 + text.length)
            writeCommonHeader(writer)
            writer.writeString(text)
            writer.writeInt(attachments.size)
            attachments.forEach { it.encode(writer) }
            return writer.toByteArray()
        }

        companion object {
            /**
             * Generous reserve for the fixed [Text] header bytes added by [encode] around the
             * text content: family(1) + type(1) + messageId(16) + roomId(16) + accountId(2+n)
             * + authorDeviceId(2+64) + prevIds(4+16*n) + createdAt(8) + text length
             * prefix(2) + attachment count(4) + max attachments + authorSignature(1+4+64).
             * The variable `accountId` portion is the reason for the margin; the fixed
             * portion without attachments is ~127 bytes.
             */
            const val ENCODED_HEADER_RESERVE_BYTES: Int =
                512 + 4 + Attachment.MAX_ATTACHMENTS * Attachment.MAX_ENCODED_BYTES

            fun decode(bytes: ByteArray): Text {
                val reader = ByteReader(bytes)
                val header = readCommonHeader(reader, DagPayloadType.TEXT)
                val text = reader.readString()
                val count = reader.readInt()
                require(count in 0..Attachment.MAX_ATTACHMENTS) {
                    "attachment count $count out of bounds"
                }
                val attachments = List(count) { Attachment.decode(reader) }
                val authorSignature = reader.readNullableByteArray()
                reader.requireFullyRead()
                return Text(
                    messageId = header.messageId,
                    roomId = header.roomId,
                    senderAccountId = header.senderAccountId,
                    authorDeviceId = header.authorDeviceId,
                    prevIds = header.prevIds,
                    createdAt = header.createdAt,
                    text = text,
                    attachments = attachments,
                    authorSignature = authorSignature,
                )
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Text

            if (createdAt != other.createdAt) return false
            if (messageId != other.messageId) return false
            if (roomId != other.roomId) return false
            if (senderAccountId != other.senderAccountId) return false
            if (authorDeviceId != other.authorDeviceId) return false
            if (prevIds != other.prevIds) return false
            if (text != other.text) return false
            if (attachments != other.attachments) return false
            if (!authorSignature.contentEquals(other.authorSignature)) return false
            if (payloadType != other.payloadType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = createdAt.hashCode()
            result = 31 * result + messageId.hashCode()
            result = 31 * result + roomId.hashCode()
            result = 31 * result + senderAccountId.hashCode()
            result = 31 * result + authorDeviceId.hashCode()
            result = 31 * result + prevIds.hashCode()
            result = 31 * result + text.hashCode()
            result = 31 * result + attachments.hashCode()
            result = 31 * result + (authorSignature?.contentHashCode() ?: 0)
            result = 31 * result + payloadType.hashCode()
            return result
        }
    }

    data class Attachment(
        val fileId: FileId,
        val fileName: String,
        val mimeType: String?,
        val totalBytes: Long,
    ) {
        init {
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
            require(totalBytes >= 0) { "totalBytes must be >= 0" }
        }

        fun encode(writer: ByteWriter) {
            writer.writeString(fileId.value)
            writer.writeString(fileName)
            writer.writeNullableString(mimeType)
            writer.writeLong(totalBytes)
        }

        companion object {
            const val MAX_ATTACHMENTS: Int = 16

            /**
             * Worst-case encoded bytes per attachment:
             * fileId(2+64) + fileName(2+255) + mime(2+128 worst, 2 when null) + totalBytes(8).
             */
            const val MAX_ENCODED_BYTES: Int = (2 + 64) + (2 + MAX_FILE_NAME_BYTES) + (2 + MAX_MIME_TYPE_BYTES) + 8

            fun decode(reader: ByteReader): Attachment {
                // Length prefixes are bounded before allocation: oversized names are
                // rejected without allocating their bytes.
                val fileId = FileId(reader.readBoundedString(64))
                val fileName = reader.readBoundedString(MAX_FILE_NAME_BYTES)
                val mimeType = reader.readBoundedNullableString(MAX_MIME_TYPE_BYTES)
                val totalBytes = reader.readLong()
                return Attachment(
                    fileId = fileId,
                    fileName = fileName,
                    mimeType = mimeType,
                    totalBytes = totalBytes,
                )
            }
        }

        // FileId is a value class; default equals/hashCode suffice (fileId + strings + long).
    }

    data class GlobalEvent(
        override val messageId: Uuid,
        override val senderAccountId: AccountId,
        override val authorDeviceId: PeerId,
        override val prevIds: List<Uuid>,
        override val createdAt: Instant,
        val eventBytes: ByteArray,
        override val authorSignature: ByteArray? = null,
    ) : DagPayload {
        override val roomId = RoomId.GLOBAL

        init {
            if (authorSignature != null) {
                require(authorSignature.isNotEmpty()) { "authorSignature must not be empty" }
            }
        }

        override val payloadType: DagPayloadType = DagPayloadType.GLOBAL_EVENT

        override fun withSignature(signature: ByteArray): GlobalEvent = copy(authorSignature = signature)

        /** Decodes [eventBytes] into the typed control event (two-level dispatch). */
        fun decodeEvent(): GlobalEventPayload = GlobalEventPayload.decode(eventBytes)

        override fun encode(): ByteArray {
            val writer = ByteWriter(256 + eventBytes.size + (authorSignature?.size ?: 4))
            writeCommonHeader(writer)
            writer.writeByteArray(eventBytes)
            writer.writeNullableByteArray(authorSignature)
            return writer.toByteArray()
        }

        override fun encodeForAuthorSigning(): ByteArray {
            val writer = ByteWriter(256 + eventBytes.size)
            writeCommonHeader(writer)
            writer.writeByteArray(eventBytes)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): GlobalEvent {
                val reader = ByteReader(bytes)
                val header = readCommonHeader(reader, DagPayloadType.GLOBAL_EVENT)
                val eventBytes = reader.readByteArray()
                val authorSignature = reader.readNullableByteArray()
                reader.requireFullyRead()
                return GlobalEvent(
                    messageId = header.messageId,
                    senderAccountId = header.senderAccountId,
                    authorDeviceId = header.authorDeviceId,
                    prevIds = header.prevIds,
                    createdAt = header.createdAt,
                    eventBytes = eventBytes,
                    authorSignature = authorSignature,
                )
            }
        }
    }

    /**
     * Room-DAG node: chat-room messages live here, with their real [roomId]
     * (unlike [GlobalEvent], which pins [RoomId.GLOBAL]). `ROOM_EVENT` payloads
     * carry [eventBytes] exactly like `GLOBAL_EVENT` does; `TEXT` nodes share
     * the same header and authorship layer.
     */
    data class RoomEvent(
        override val messageId: Uuid,
        override val roomId: RoomId,
        override val senderAccountId: AccountId,
        override val authorDeviceId: PeerId,
        override val prevIds: List<Uuid>,
        override val createdAt: Instant,
        val eventBytes: ByteArray,
        override val authorSignature: ByteArray? = null,
    ) : DagPayload {
        init {
            if (authorSignature != null) {
                require(authorSignature.isNotEmpty()) { "authorSignature must not be empty" }
            }
        }

        override val payloadType: DagPayloadType = DagPayloadType.ROOM_EVENT

        override fun withSignature(signature: ByteArray): RoomEvent = copy(authorSignature = signature)

        /** Decodes [eventBytes] into the typed room event (two-level dispatch). */
        fun decodeEvent(): RoomEventPayload = RoomEventPayload.decode(eventBytes)

        override fun encode(): ByteArray {
            val writer = ByteWriter(256 + eventBytes.size + (authorSignature?.size ?: 4))
            writeCommonHeader(writer)
            writer.writeByteArray(eventBytes)
            writer.writeNullableByteArray(authorSignature)
            return writer.toByteArray()
        }

        override fun encodeForAuthorSigning(): ByteArray {
            val writer = ByteWriter(256 + eventBytes.size)
            writeCommonHeader(writer)
            writer.writeByteArray(eventBytes)
            return writer.toByteArray()
        }

        /** Verify path: the bytes whose hash this node's [roomId] must be, if it is a room genesis. */
        fun genesisDerivationBytes(): ByteArray =
            derivationBytes(
                genesisMessageId = messageId,
                senderAccountId = senderAccountId,
                authorDeviceId = authorDeviceId,
                createdAt = createdAt,
                eventBytes = eventBytes,
            )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as RoomEvent

            if (createdAt != other.createdAt) return false
            if (messageId != other.messageId) return false
            if (roomId != other.roomId) return false
            if (senderAccountId != other.senderAccountId) return false
            if (authorDeviceId != other.authorDeviceId) return false
            if (prevIds != other.prevIds) return false
            if (!eventBytes.contentEquals(other.eventBytes)) return false
            if (!authorSignature.contentEquals(other.authorSignature)) return false
            if (payloadType != other.payloadType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = createdAt.hashCode()
            result = 31 * result + messageId.hashCode()
            result = 31 * result + roomId.hashCode()
            result = 31 * result + senderAccountId.hashCode()
            result = 31 * result + authorDeviceId.hashCode()
            result = 31 * result + prevIds.hashCode()
            result = 31 * result + eventBytes.contentHashCode()
            result = 31 * result + (authorSignature?.contentHashCode() ?: 0)
            result = 31 * result + payloadType.hashCode()
            return result
        }

        companion object {
            val ROOM_ID_DOMAIN: ByteArray =
                "YapYapRoomIdV1".encodeToByteArray()

            /**
             * The canonical input of a self-certifying room id (hashed by the DAG's room-id
             * derivation). Append path: pure input assembly, no instance needed.
             * Covers every genesis field that can differ while keeping the same
             * `genesisMessageId`, so an id-reuse forgery with different
             * content/author/timestamp derives a *different* room (ghost),
             * never a fork of the honest room. Excludes `roomId` and
             * `authorSignature` (signature covers `roomId` — hashing either
             * would be circular). `prevIds` is always `[]` by the structural
             * rule and needs no encoding.
             */
            fun derivationBytes(
                genesisMessageId: Uuid,
                senderAccountId: AccountId,
                authorDeviceId: PeerId,
                createdAt: Instant,
                eventBytes: ByteArray,
            ): ByteArray {
                require(eventBytes.isNotEmpty()) { "eventBytes must not be empty" }
                val writer = ByteWriter(
                    ROOM_ID_DOMAIN.size + 16 + 2 +
                            senderAccountId.id.length + 2 +
                            authorDeviceId.id.length + 8 + 4 + eventBytes.size,
                )
                writer.writeBytes(ROOM_ID_DOMAIN)
                writer.writeUuid(genesisMessageId)
                writer.writeString(senderAccountId.id)
                writer.writePeerId(authorDeviceId)
                writer.writeLong(createdAt.epochSeconds)
                writer.writeByteArray(eventBytes)
                return writer.toByteArray()
            }

            fun decode(bytes: ByteArray): RoomEvent {
                val reader = ByteReader(bytes)
                val header = readCommonHeader(reader, DagPayloadType.ROOM_EVENT)
                val eventBytes = reader.readByteArray()
                val authorSignature = reader.readNullableByteArray()
                reader.requireFullyRead()
                return RoomEvent(
                    messageId = header.messageId,
                    roomId = header.roomId,
                    senderAccountId = header.senderAccountId,
                    authorDeviceId = header.authorDeviceId,
                    prevIds = header.prevIds,
                    createdAt = header.createdAt,
                    eventBytes = eventBytes,
                    authorSignature = authorSignature,
                )
            }
        }
    }
}

private data class MessagePayloadHeader(
    val messageId: Uuid,
    val roomId: RoomId,
    val senderAccountId: AccountId,
    val authorDeviceId: PeerId,
    val prevIds: List<Uuid>,
    val createdAt: Instant,
)

private fun DagPayload.writeCommonHeader(writer: ByteWriter) {
    writer.writeByte(family.wireValue.toInt())
    writer.writeByte(payloadType.wireValue.toInt())
    writer.writeUuid(messageId)
    writer.writeUuid(roomId.value)
    writer.writeString(senderAccountId.id)
    writer.writePeerId(authorDeviceId)
    writer.writeInt(prevIds.size)
    prevIds.forEach { writer.writeUuid(it) }
    writer.writeLong(createdAt.epochSeconds)
}

private fun readCommonHeader(reader: ByteReader, expected: DagPayloadType): MessagePayloadHeader {
    require(MessagePayloadFamily.fromWireValue(reader.readByte()) == MessagePayloadFamily.DAG) {
        "Expected DAG payload family"
    }
    require(DagPayloadType.fromWireValue(reader.readByte()) == expected) {
        "Expected ${expected.name} payload type"
    }
    return MessagePayloadHeader(
        messageId = reader.readUuid(),
        roomId = RoomId(reader.readUuid()),
        senderAccountId = AccountId(reader.readString()),
        authorDeviceId = reader.readPeerId(),
        prevIds = List(reader.readInt()) { reader.readUuid() },
        createdAt = Instant.fromEpochSeconds(reader.readLong()),
    )
}
