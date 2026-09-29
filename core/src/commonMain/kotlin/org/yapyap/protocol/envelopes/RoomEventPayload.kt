package org.yapyap.protocol.envelopes

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.MessagePayloadType
import org.yapyap.persistence.db.RoomType
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import kotlin.uuid.Uuid

/**
 * Typed room-membership event carried inside [MessagePayload.RoomEvent.eventBytes].
 *
 * Two-level dispatch mirrors BinaryEnvelope → MessagePayload → RoomEventPayload:
 * [MessagePayloadType.ROOM_EVENT] selects the DAG node type, [RoomEventKind] selects the event.
 *
 * Authorship is the outer [MessagePayload] layer alone (`authorSignature` over the whole
 * node, verified by `classifyMessageAuthorship`): unlike `GlobalEventPayload.AddDevice`
 * there is no inner binding signature here. The codec transports the
 * `MemberRemove.successorAccountId` handover field verbatim — shape validation
 * (owner-only, self-leave-only, member-successor) belongs to the room fold, never the codec.
 */
sealed interface RoomEventPayload {
    val kind: RoomEventKind

    fun encode(): ByteArray

    /**
     * Genesis content of a chat room. Only a node with `prevIds == []` may carry this;
     * only a node whose `roomId` matches the genesis derivation may land it (both
     * enforced at ingest, not here). The genesis author becomes owner by definition.
     *
     * Member-list targets are ungated in the fold; rows for still-unknown accounts
     * are deferred at projection time.
     *
     * The optional [spaceId] names the space this room belongs to (creator-declared,
     * immutable: a room never moves between spaces). The fold never validates or
     * consumes it — like member-list targets, an unknown space is ungated in the
     * fold and resolved at projection time. Null until spaces exist.
     */
    data class RoomCreated(
        val initialMemberIds: List<AccountId>,
        val roomName: String,
        val roomType: RoomType,
        val spaceId: Uuid?,
    ) : RoomEventPayload {
        override val kind: RoomEventKind = RoomEventKind.ROOM_CREATED

        init {
            require(roomName.isNotBlank()) { "roomName must not be blank" }
            require(roomType != RoomType.GLOBAL_CONTROL) { "chat rooms must not use GLOBAL_CONTROL" }
            require(roomType != RoomType.UNKNOWN) { "room type UNKNOWN is local-only" }
        }

        override fun encode(): ByteArray {
            val writer = ByteWriter(64 + roomName.length + initialMemberIds.size * 40)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeInt(initialMemberIds.size)
            initialMemberIds.forEach { writer.writeString(it.id) }
            writer.writeString(roomName)
            writer.writeByte(roomType.wireValue.toInt())
            writer.writeNullableUuid(spaceId)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): RoomCreated {
                val reader = ByteReader(bytes)
                require(RoomEventKind.fromWireValue(reader.readByte()) == RoomEventKind.ROOM_CREATED) {
                    "Expected ROOM_CREATED event kind"
                }
                val members = List(reader.readInt()) { AccountId(reader.readString()) }
                val roomName = reader.readString()
                val roomType = RoomType.fromWireValue(reader.readByte())
                val spaceId = reader.readNullableUuid()
                reader.requireFullyRead()
                return RoomCreated(members, roomName, roomType, spaceId)
            }
        }
    }

    /** Adds [targetAccountId] as `MEMBER`. Admin-gated at fold position (seal-subject). */
    data class MemberAdd(val targetAccountId: AccountId) : RoomEventPayload {
        override val kind: RoomEventKind = RoomEventKind.MEMBER_ADD

        override fun encode(): ByteArray {
            val writer = ByteWriter(64)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(targetAccountId.id)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): MemberAdd {
                val reader = ByteReader(bytes)
                require(RoomEventKind.fromWireValue(reader.readByte()) == RoomEventKind.MEMBER_ADD) {
                    "Expected MEMBER_ADD event kind"
                }
                val targetAccountId = AccountId(reader.readString())
                reader.requireFullyRead()
                return MemberAdd(targetAccountId)
            }
        }
    }

    /**
     * Removes [targetAccountId]. Admin-gated for others (owner irrevocable);
     * own-account leave always allowed; the owner's own leave is the handover and
     * additionally carries [successorAccountId] (honored only in that shape).
     * The codec never validates the shape — the fold ignores malformed uses whole.
     */
    data class MemberRemove(
        val targetAccountId: AccountId,
        val successorAccountId: AccountId?,
    ) : RoomEventPayload {
        override val kind: RoomEventKind = RoomEventKind.MEMBER_REMOVE

        override fun encode(): ByteArray {
            val writer = ByteWriter(96)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(targetAccountId.id)
            writer.writeNullableString(successorAccountId?.id)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): MemberRemove {
                val reader = ByteReader(bytes)
                require(RoomEventKind.fromWireValue(reader.readByte()) == RoomEventKind.MEMBER_REMOVE) {
                    "Expected MEMBER_REMOVE event kind"
                }
                val targetAccountId = AccountId(reader.readString())
                val successorAccountId = reader.readNullableString()?.let(::AccountId)
                reader.requireFullyRead()
                return MemberRemove(targetAccountId, successorAccountId)
            }
        }
    }

    /** Grants admin to [targetAccountId]. Admin-gated at fold position (seal-subject). */
    data class AddAdmin(val targetAccountId: AccountId) : RoomEventPayload {
        override val kind: RoomEventKind = RoomEventKind.ADD_ADMIN

        override fun encode(): ByteArray {
            val writer = ByteWriter(64)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(targetAccountId.id)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): AddAdmin {
                val reader = ByteReader(bytes)
                require(RoomEventKind.fromWireValue(reader.readByte()) == RoomEventKind.ADD_ADMIN) {
                    "Expected ADD_ADMIN event kind"
                }
                val targetAccountId = AccountId(reader.readString())
                reader.requireFullyRead()
                return AddAdmin(targetAccountId)
            }
        }
    }

    /** Revokes admin from [targetAccountId]; same authorization as [AddAdmin]; owner irrevocable. */
    data class RemoveAdmin(val targetAccountId: AccountId) : RoomEventPayload {
        override val kind: RoomEventKind = RoomEventKind.REMOVE_ADMIN

        override fun encode(): ByteArray {
            val writer = ByteWriter(64)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(targetAccountId.id)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): RemoveAdmin {
                val reader = ByteReader(bytes)
                require(RoomEventKind.fromWireValue(reader.readByte()) == RoomEventKind.REMOVE_ADMIN) {
                    "Expected REMOVE_ADMIN event kind"
                }
                val targetAccountId = AccountId(reader.readString())
                reader.requireFullyRead()
                return RemoveAdmin(targetAccountId)
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray): RoomEventPayload {
            val kind = RoomEventKind.fromWireValue(ByteReader(bytes).readByte())
            return when (kind) {
                RoomEventKind.ROOM_CREATED -> RoomCreated.decode(bytes)
                RoomEventKind.MEMBER_ADD -> MemberAdd.decode(bytes)
                RoomEventKind.MEMBER_REMOVE -> MemberRemove.decode(bytes)
                RoomEventKind.ADD_ADMIN -> AddAdmin.decode(bytes)
                RoomEventKind.REMOVE_ADMIN -> RemoveAdmin.decode(bytes)
            }
        }
    }
}

enum class RoomEventKind(val wireValue: Byte) {
    ROOM_CREATED(1),
    MEMBER_ADD(2),
    MEMBER_REMOVE(3),
    ADD_ADMIN(4),
    REMOVE_ADMIN(5);

    companion object {
        fun fromWireValue(value: Byte): RoomEventKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported room event kind wire value: $value")
    }
}
