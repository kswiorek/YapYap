package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The one envelope on the wire, for every packet family (docs/envelope-redesign.md §2):
 *
 * - the **hop header** — this hop only: who hands the bytes to whom, now;
 * - the **routing header** ([routingHeader]) — public, typed by [packetType]: what any device that
 *   handles the packet (the hop's receiver, a relay forwarding it) may read;
 * - the **content** — end to end from the packet's origin to its final target; hops copy it
 *   verbatim and cannot read it;
 * - the **hop signature** — by [source], over `hop header ‖ routing header ‖ contentId`.
 *
 * Every hop signs what it sends, relays included; nothing is ever signed by anyone but the hop.
 * A relay forward copies [expiresAt] and never extends it.
 */
internal class BinaryEnvelope(
    val packetId: Uuid,
    val packetType: PacketType,
    val dispositionRequested: Boolean,
    val createdAt: Instant,
    val expiresAt: Instant,
    val source: PeerId,
    val target: PeerId,
    val routingHeader: ByteArray,
    val content: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(expiresAt >= createdAt) { "expiresAt must be >= createdAt" }
        require(signature.size == SIGNATURE_BYTES) { "hop signature must be $SIGNATURE_BYTES bytes" }
    }

    /** This envelope without its signature: what the signature was made over. */
    fun unsigned(): UnsignedEnvelope = UnsignedEnvelope(
        packetId = packetId,
        packetType = packetType,
        dispositionRequested = dispositionRequested,
        createdAt = createdAt,
        expiresAt = expiresAt,
        source = source,
        target = target,
        routingHeader = routingHeader,
        content = content,
    )

    fun encode(): ByteArray {
        val writer = ByteWriter(ENCODED_OVERHEAD_BYTES + routingHeader.size + content.size)
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writeHopHeader(writer, packetType, dispositionRequested, createdAt, expiresAt, packetId, source, target)
        writer.writeByteArray(routingHeader, maxSize = MAX_ROUTING_HEADER_BYTES)
        writer.writeByteArray(content)
        writer.writeBytes(signature)
        return writer.toByteArray()
    }

    fun observableHeaderValues(): Map<String, Any?> = mapOf(
        Fields.PACKET_ID to packetId,
        Fields.PACKET_TYPE to packetType,
        Fields.DISPOSITION_REQUESTED to dispositionRequested,
        Fields.CREATED_AT to createdAt,
        Fields.EXPIRES_AT to expiresAt,
        Fields.SOURCE to source,
        Fields.TARGET to target,
        Fields.ROUTING_HEADER to routingHeader,
        Fields.SIGNATURE to signature,
    )

    companion object {
        object Fields {
            const val PACKET_ID = "packetId"
            const val PACKET_TYPE = "packetType"
            const val DISPOSITION_REQUESTED = "dispositionRequested"
            const val CREATED_AT = "createdAt"
            const val EXPIRES_AT = "expiresAt"
            const val SOURCE = "source"
            const val TARGET = "target"
            const val ROUTING_HEADER = "routingHeader"
            const val CONTENT = "content"
            const val SIGNATURE = "signature"
        }

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'Y'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 2

        /** Ed25519 signature length. */
        const val SIGNATURE_BYTES: Int = 64

        /** A routing header is a few hundred bytes at most; the bound applies before allocation. */
        const val MAX_ROUTING_HEADER_BYTES: Int = 16 * 1024

        /**
         * Fixed bytes [encode] adds around the routing header and the content:
         * MAGIC(4) + VERSION(1) + packetType(1) + flags(1) + createdAt(8) + expiresAt(8)
         * + packetId(16) + source(2+64) + target(2+64) + routing header length(4)
         * + content length(4) + signature(64) = 243.
         * Assumes a 64-char hex [PeerId] (SHA-256 of the signing key).
         */
        const val ENCODED_OVERHEAD_BYTES: Int = 243

        /**
         * The most content an envelope of [envelopeBytes] can carry beside a routing header of
         * [routingHeaderBytes]: each family states its own header size next to its codec.
         */
        fun maxContentBytes(envelopeBytes: Int, routingHeaderBytes: Int): Int =
            envelopeBytes - ENCODED_OVERHEAD_BYTES - routingHeaderBytes

        fun decode(bytes: ByteArray): BinaryEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid envelope magic" }
            val version = reader.readByte()
            require(version == VERSION) { "Unsupported envelope version: $version" }

            val type = PacketType.fromWireValue(reader.readByte())
            val dispositionRequested = (reader.readByte().toInt() and FLAG_DISPOSITION) != 0
            val createdAt = Instant.fromEpochSeconds(reader.readLong())
            val expiresAt = Instant.fromEpochSeconds(reader.readLong())
            val packetId = reader.readUuid()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val routingHeader = reader.readByteArray(maxSize = MAX_ROUTING_HEADER_BYTES)
            val content = reader.readByteArray()
            val signature = reader.readBytes(SIGNATURE_BYTES)
            reader.requireFullyRead()

            return BinaryEnvelope(
                packetId = packetId,
                packetType = type,
                dispositionRequested = dispositionRequested,
                createdAt = createdAt,
                expiresAt = expiresAt,
                source = source,
                target = target,
                routingHeader = routingHeader,
                content = content,
                signature = signature,
            )
        }

        private const val FLAG_DISPOSITION = 0x01

        internal fun writeHopHeader(
            writer: ByteWriter,
            packetType: PacketType,
            dispositionRequested: Boolean,
            createdAt: Instant,
            expiresAt: Instant,
            packetId: Uuid,
            source: PeerId,
            target: PeerId,
        ) {
            writer.writeByte(packetType.wireValue)
            writer.writeByte(if (dispositionRequested) FLAG_DISPOSITION else 0)
            writer.writeLong(createdAt.epochSeconds)
            writer.writeLong(expiresAt.epochSeconds)
            writer.writeUuid(packetId)
            writer.writePeerId(source)
            writer.writePeerId(target)
        }
    }
}

/**
 * An envelope before its hop signs it. Routing builds these; protection's hop layer signs them
 * into a [BinaryEnvelope]. [signingInput] is the exact byte string the signature covers.
 */
internal class UnsignedEnvelope(
    val packetId: Uuid,
    val packetType: PacketType,
    val dispositionRequested: Boolean,
    val createdAt: Instant,
    val expiresAt: Instant,
    val source: PeerId,
    val target: PeerId,
    val routingHeader: ByteArray,
    val content: ByteArray,
) {
    init {
        require(expiresAt >= createdAt) { "expiresAt must be >= createdAt" }
    }

    /** `hop header ‖ routing header ‖ contentId`: the content enters only as its hash. */
    fun signingInput(contentId: ByteArray): ByteArray {
        val writer = ByteWriter(SIGNING_INPUT_RESERVE + routingHeader.size + contentId.size)
        BinaryEnvelope.writeHopHeader(
            writer,
            packetType,
            dispositionRequested,
            createdAt,
            expiresAt,
            packetId,
            source,
            target
        )
        writer.writeByteArray(routingHeader)
        writer.writeByteArray(contentId)
        return writer.toByteArray()
    }

    fun signed(signature: ByteArray): BinaryEnvelope = BinaryEnvelope(
        packetId = packetId,
        packetType = packetType,
        dispositionRequested = dispositionRequested,
        createdAt = createdAt,
        expiresAt = expiresAt,
        source = source,
        target = target,
        routingHeader = routingHeader,
        content = content,
        signature = signature,
    )

    private companion object {
        const val SIGNING_INPUT_RESERVE = 192
    }
}
