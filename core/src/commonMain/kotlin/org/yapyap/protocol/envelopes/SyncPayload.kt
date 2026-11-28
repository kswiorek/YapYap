package org.yapyap.protocol.envelopes

import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.MessagePayloadFamily
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SyncId
import org.yapyap.protocol.SyncPayloadType
import kotlin.uuid.Uuid

/**
 * The pull-path control family. It shares the MESSAGE envelope's protection and wire framing
 * only so a request is sealed and authenticated like the history it asks for; it is never a DAG
 * payload. [decode] reads the family and verb bytes, then runs that verb type's own decode.
 * The verb byte is reserved for further sync types.
 */
internal sealed interface SyncPayload : MessagePayload {
    override val family: MessagePayloadFamily get() = MessagePayloadFamily.SYNC

    val syncType: SyncPayloadType

    companion object {
        /** Reads the family and sync-verb bytes, then runs the verb type's own decode. */
        fun decode(bytes: ByteArray): SyncPayload {
            val reader = ByteReader(bytes)
            require(MessagePayloadFamily.fromWireValue(reader.readByte()) == MessagePayloadFamily.SYNC) {
                "Expected SYNC payload family"
            }
            return when (SyncPayloadType.fromWireValue(reader.readByte())) {
                SyncPayloadType.REQUEST -> Request.decode(bytes)
            }
        }
    }

    /**
     * Recover the ancestry of [missingIds] in [roomId], stopping at the requester's [knownIds]
     * frontier. The responder answers with ordinary [DagPayload] nodes.
     *
     * No causal header, no author signature, no room row. Its envelope id is its own (fresh per
     * protection); [syncId] is the pull attempt's durable correlation id.
     */
    data class Request(
        val roomId: RoomId,
        val syncId: SyncId,
        val missingIds: List<MessageId>,
        val knownIds: List<MessageId>,
    ) : SyncPayload {
        override val contentId: Uuid = Uuid.random()
        override val syncType: SyncPayloadType = SyncPayloadType.REQUEST

        override fun encode(): ByteArray {
            val writer = ByteWriter(2 + 32 + 8 + 16 * (missingIds.size + knownIds.size))
            writer.writeByte(family.wireValue.toInt())
            writer.writeByte(syncType.wireValue.toInt())
            writer.writeUuid(roomId.value)
            writer.writeUuid(syncId.value)
            writer.writeInt(missingIds.size)
            missingIds.forEach { writer.writeMessageId(it) }
            writer.writeInt(knownIds.size)
            knownIds.forEach { writer.writeMessageId(it) }
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Request {
                val reader = ByteReader(bytes)
                require(MessagePayloadFamily.fromWireValue(reader.readByte()) == MessagePayloadFamily.SYNC) {
                    "Expected SYNC payload family"
                }
                require(SyncPayloadType.fromWireValue(reader.readByte()) == SyncPayloadType.REQUEST) {
                    "Expected SYNC_REQUEST payload type"
                }
                val roomId = RoomId(reader.readUuid())
                val syncId = SyncId(reader.readUuid())
                val missingIds = List(reader.readInt()) { reader.readMessageId() }
                val knownIds = List(reader.readInt()) { reader.readMessageId() }
                reader.requireFullyRead()
                return Request(
                    roomId = roomId,
                    syncId = syncId,
                    missingIds = missingIds,
                    knownIds = knownIds,
                )
            }
        }
    }
}
