package org.yapyap.protocol.envelopes

import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.protocol.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class MessageEnvelope(
    val messageEnvelopeId: Uuid, //UUID as hash of message content
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

    /** Canonical wire bytes with [signature] cleared; used as Ed25519 signing input. */
    fun encodeForSigning(): ByteArray = copy(signature = null).encode()

    fun encode(): ByteArray {
        val writer = ByteWriter(256 + nonce.size + payload.size + (signature?.size ?: 0))
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeUuid(messageEnvelopeId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAt.epochSeconds)
        writer.writeByteArray(nonce)
        writer.writeByte(securityScheme.wireValue)
        writer.writeNullableByteArray(signature)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    fun decodePayload(): MessagePayload = MessagePayload.decode(payload)

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.MESSAGE_ID to messageEnvelopeId,
        Fields.SOURCE to source,
        Fields.TARGET to target,
        Fields.CREATED_AT to createdAt,
        Fields.NONCE to nonce,
        Fields.SECURITY_SCHEME to securityScheme,
        Fields.SIGNATURE to signature,
    )

    companion object {
        object Fields {
            const val MESSAGE_ID = "messageId"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val CREATED_AT = "createdAt"
            const val NONCE = "nonce"
            const val SECURITY_SCHEME = "securityScheme"
            const val SIGNATURE = "signature"
            const val PAYLOAD = "payload"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        /**
         * Bytes added by [encode] around [payload] for the ENCRYPTED_AND_SIGNED message path
         * (worst case): MAGIC(4) + VERSION(1) + messageId(16) + source(2+64) + target(2+64)
         * + createdAt(8) + nonce(4+24) + scheme(1) + signature(1+4+64) + payload length prefix(4) = 263.
         * Nonce is 24 bytes (ENCRYPTED_AND_SIGNED), signature is 64 bytes (Ed25519).
         */
        const val ENCODED_OVERHEAD_BYTES: Int = 263

        fun decode(bytes: ByteArray): MessageEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid message envelope magic" }

            val version = reader.readByte()
            require(version == VERSION) { "Unsupported message envelope version: $version" }

            val messageEnvelopeId = reader.readUuid()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAt = Instant.fromEpochSeconds(reader.readLong())
            val nonce = reader.readByteArray()
            val securityScheme = SignalSecurityScheme.fromWireValue(reader.readByte())
            val signature = reader.readNullableByteArray()
            val encodedPayload = reader.readByteArray()
            reader.requireFullyRead()

            return MessageEnvelope(
                messageEnvelopeId = messageEnvelopeId,
                source = source,
                target = target,
                createdAt = createdAt,
                nonce = nonce,
                securityScheme = securityScheme,
                signature = signature,
                payload = encodedPayload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MessageEnvelope

        if (createdAt != other.createdAt) return false
        if (messageEnvelopeId != other.messageEnvelopeId) return false
        if (source != other.source) return false
        if (target != other.target) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (securityScheme != other.securityScheme) return false
        if (!signature.contentEquals(other.signature)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = createdAt.hashCode()
        result = 31 * result + messageEnvelopeId.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + securityScheme.hashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Causal DAG node carried inside [MessageEnvelope].
 *
 * Shared header fields are common to every room DAG (chat and global control).
 * Delivery lifecycle and orphan flags are local DB concerns and are not on the wire.
 *
 * TODO: [Sprint 5] Attachment / file-offer message payload types (link to FileEnvelope transfers).
 */
sealed interface MessagePayload {
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
     * Sender's wall-clock composition time, set once by [org.yapyap.orchestrator.dag.DagEngine.append]
     * at send time and carried on the wire unchanged. Distinct from
     * [MessageEnvelope.createdAt] (transport-level, set at envelope assembly).
     * Used as the primary GUI display-order key.
     */
    val createdAt: Instant
    val payloadType: MessagePayloadType
    val authorSignature: ByteArray?

    fun encode(): ByteArray

    fun encodeForAuthorSigning(): ByteArray

    fun withSignature(signature: ByteArray): MessagePayload

    data class Text(
        override val messageId: Uuid,
        override val roomId: RoomId,
        override val senderAccountId: AccountId,
        override val authorDeviceId: PeerId,
        override val prevIds: List<Uuid>,
        override val createdAt: Instant,
        val text: String,
        override val authorSignature: ByteArray? = null,
    ) : MessagePayload {
        init {
            if (authorSignature != null) {
                require(authorSignature.isNotEmpty()) { "authorSignature must not be empty" }
            }
        }

        override val payloadType: MessagePayloadType = MessagePayloadType.TEXT

        override fun withSignature(signature: ByteArray): Text = copy(authorSignature = signature)

        override fun encode(): ByteArray {
            val writer = ByteWriter(256 + text.length + (authorSignature?.size ?: 4))
            writeCommonHeader(writer)
            writer.writeString(text)
            writer.writeNullableByteArray(authorSignature)
            return writer.toByteArray()
        }

        override fun encodeForAuthorSigning(): ByteArray {
            val writer = ByteWriter(256 + text.length)
            writeCommonHeader(writer)
            writer.writeString(text)
            return writer.toByteArray()
        }

        companion object {
            /**
             * Generous reserve for the fixed [Text] header bytes added by [encode] around the
             * text content: version(1) + type(1) + messageId(16) + roomId(16) + accountId(2+n)
             * + authorDeviceId(2+64) + prevIds(4+16*n) + createdAt(8) + text length
             * prefix(2) + authorSignature(1+4+64). The variable `accountId` portion is the reason
             * for the margin; the fixed portion is ~127 bytes.
             */
            const val ENCODED_HEADER_RESERVE_BYTES: Int = 512

            fun decode(bytes: ByteArray): Text {
                val reader = ByteReader(bytes)
                val header = readCommonHeader(reader, MessagePayloadType.TEXT)
                val text = reader.readString()
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
            result = 31 * result + (authorSignature?.contentHashCode() ?: 0)
            result = 31 * result + payloadType.hashCode()
            return result
        }
    }

    data class GlobalEvent(
        override val messageId: Uuid,
        override val senderAccountId: AccountId,
        override val authorDeviceId: PeerId,
        override val prevIds: List<Uuid>,
        override val createdAt: Instant,
        val eventBytes: ByteArray,
        override val authorSignature: ByteArray? = null,
    ) : MessagePayload {
        override val roomId = RoomId.GLOBAL

        init {
            if (authorSignature != null) {
                require(authorSignature.isNotEmpty()) { "authorSignature must not be empty" }
            }
        }

        override val payloadType: MessagePayloadType = MessagePayloadType.GLOBAL_EVENT

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
                val header = readCommonHeader(reader, MessagePayloadType.GLOBAL_EVENT)
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
    ) : MessagePayload {
        init {
            if (authorSignature != null) {
                require(authorSignature.isNotEmpty()) { "authorSignature must not be empty" }
            }
        }

        override val payloadType: MessagePayloadType = MessagePayloadType.ROOM_EVENT

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

        /** Verify path: recompute the genesis id from stored row fields, no arg-plumbing. */
        fun derivationBytes(): ByteArray = derivationBytes(
            genesisMessageId = messageId,
            senderAccountId = senderAccountId,
            authorDeviceId = authorDeviceId,
            createdAt = createdAt,
            eventBytes = eventBytes,
        )

        /** Verify path. */
        suspend fun deriveRoomId(crypto: CryptoProvider): RoomId =
            deriveRoomId(
                crypto = crypto,
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
             * Append path: pure input assembly, no instance needed.
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

            /** Append path: self-certifying id before construction. */
            suspend fun deriveRoomId(
                crypto: CryptoProvider,
                genesisMessageId: Uuid,
                senderAccountId: AccountId,
                authorDeviceId: PeerId,
                createdAt: Instant,
                eventBytes: ByteArray,
            ): RoomId = RoomId(
                crypto.hashedUuid(
                    derivationBytes(
                        genesisMessageId = genesisMessageId,
                        senderAccountId = senderAccountId,
                        authorDeviceId = authorDeviceId,
                        createdAt = createdAt,
                        eventBytes = eventBytes,
                    ),
                ),
            )

            /**
             * Sole genesis append path. Derives the self-certifying id
             * internally — the caller never supplies a roomId, so
             * derive-for-A-but-construct-with-B is unrepresentable.
             * Returns the *unsigned* payload; signing follows via
             * [withSignature] like every other payload type.
             */
            suspend fun createGenesis(
                crypto: CryptoProvider,
                genesisMessageId: Uuid,
                senderAccountId: AccountId,
                authorDeviceId: PeerId,
                createdAt: Instant,
                event: RoomEventPayload,
            ): RoomEvent {
                val eventBytes = event.encode()
                val roomId = deriveRoomId(
                    crypto = crypto,
                    genesisMessageId = genesisMessageId,
                    senderAccountId = senderAccountId,
                    authorDeviceId = authorDeviceId,
                    createdAt = createdAt,
                    eventBytes = eventBytes,
                )
                return RoomEvent(
                    messageId = genesisMessageId,
                    roomId = roomId,
                    senderAccountId = senderAccountId,
                    authorDeviceId = authorDeviceId,
                    prevIds = emptyList(),
                    createdAt = createdAt,
                    eventBytes = eventBytes,
                )
            }

            fun decode(bytes: ByteArray): RoomEvent {
                val reader = ByteReader(bytes)
                val header = readCommonHeader(reader, MessagePayloadType.ROOM_EVENT)
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

    companion object {
        fun decode(bytes: ByteArray): MessagePayload {
            val reader = ByteReader(bytes)
            readPayloadHeaderVersion(reader)
            val payloadType = MessagePayloadType.fromWireValue(reader.readByte())
            return when (payloadType) {
                MessagePayloadType.TEXT -> Text.decode(bytes)
                MessagePayloadType.GLOBAL_EVENT -> GlobalEvent.decode(bytes)
                MessagePayloadType.ROOM_EVENT -> RoomEvent.decode(bytes)
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

private const val PAYLOAD_HEADER_VERSION: Byte = 2

private fun readPayloadHeaderVersion(reader: ByteReader) {
    val version = reader.readByte()
    require(version == PAYLOAD_HEADER_VERSION) {
        "Unsupported message payload header version: $version"
    }
}

private fun MessagePayload.writeCommonHeader(writer: ByteWriter) {
    writer.writeByte(PAYLOAD_HEADER_VERSION.toInt())
    writer.writeByte(payloadType.wireValue)
    writer.writeUuid(messageId)
    writer.writeUuid(roomId.value)
    writer.writeString(senderAccountId.id)
    writer.writePeerId(authorDeviceId)
    writer.writeInt(prevIds.size)
    prevIds.forEach { writer.writeUuid(it) }
    writer.writeLong(createdAt.epochSeconds)
}

private fun readCommonHeader(reader: ByteReader, expected: MessagePayloadType): MessagePayloadHeader {
    readPayloadHeaderVersion(reader)
    require(MessagePayloadType.fromWireValue(reader.readByte()) == expected) {
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