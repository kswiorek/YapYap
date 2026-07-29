package org.yapyap.persistence.messaging

import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.MessageLifecycleState
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.uuid.Uuid

/**
 * DB row mapped from [org.yapyap.persistence.Messages]: the decoded [MessagePayload]
 * plus local-only metadata not carried on the wire.
 */
data class MessageRow(
    val payload: MessagePayload,
    val lifecycleState: MessageLifecycleState,
    val isOrphaned: Boolean,
)

/**
 * Composite cursor for stable pagination of room messages.
 *
 * Display ordering is `(createdAtEpochSeconds DESC, lamportClock DESC, messageId DESC)` — a total
 * order with no ties, so pagination is stable across live inserts and reloads. The cursor captures
 * the oldest row of the currently-loaded window so the next page begins strictly below it.
 */
data class MessageCursor(
    val createdAtEpochSeconds: Long,
    val lamportClock: Long,
    val messageId: Uuid,
)

interface MessageRepository {

    /** Insert a message; returns false if a row with the same message_id already exists (dedup). */
    fun insert(payload: MessagePayload, lifecycleState: MessageLifecycleState, isOrphaned: Boolean): Boolean

    fun findById(messageId: Uuid): MessageRow?

    /** Highest-lamport message in the room; tie-break by createdAt DESC, messageId DESC. Null if room is empty. */
    fun findRoomTail(roomId: String): MessageRow?

    fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow>

    fun findAllInRoom(roomId: String): List<MessageRow>

    /** Max lamport_clock in the room (null if empty) — used to reconstruct rooms.local_seq_n on boot. */
    fun maxLamportInRoom(roomId: String): Long?

    fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean)

    fun updateLifecycleState(messageId: Uuid, state: MessageLifecycleState)
}

class DefaultMessageRepository(
    private val database: YapYapDatabase,
) : MessageRepository {
    //TODO add logging
    private val queries = database.messageQueries

    override fun insert(
        payload: MessagePayload,
        lifecycleState: MessageLifecycleState,
        isOrphaned: Boolean,
    ): Boolean {
        queries.insertMessage(
            message_id = payload.messageId,
            room_id = payload.roomId,
            sender_account_id = payload.senderAccountId.id,
            author_device_id = payload.authorDeviceId.id,
            prev_id = payload.prevId,
            lamport_clock = payload.lamportClock,
            created_at_epoch_seconds = payload.createdAtEpochSeconds,
            payload_type = payload.payloadType,
            message_payload = payload.encode(),
            lifecycle_state = lifecycleState,
            is_orphaned = isOrphaned,
        )
        return queries.selectMessageById(payload.messageId).executeAsOneOrNull() != null
    }

    override fun findById(messageId: Uuid): MessageRow? =
        queries.selectMessageById(messageId).executeAsOneOrNull()?.toRow()

    override fun findRoomTail(roomId: String): MessageRow? =
        queries.selectRoomTail(roomId).executeAsOneOrNull()?.toRow()

    override fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow> =
        queries.selectMessagesInRoomPageDesc(
            roomId = roomId,
            cursorCreated = cursor?.createdAtEpochSeconds,
            cursorLamport = cursor?.lamportClock,
            cursorMessageId = cursor?.messageId,
            limit = limit.toLong(),
        ).executeAsList().map { it.toRow() }

    override fun findAllInRoom(roomId: String): List<MessageRow> =
        queries.selectAllMessagesInRoom(roomId).executeAsList().map { it.toRow() }

    override fun maxLamportInRoom(roomId: String): Long? =
        queries.selectMaxLamportInRoom(roomId).executeAsOne().MAX

    override fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean) {
        queries.updateMessageOrphanedFlag(isOrphaned, messageId)
    }

    override fun updateLifecycleState(messageId: Uuid, state: MessageLifecycleState) {
        queries.updateMessageLifecycleState(state, messageId)
    }

    private fun org.yapyap.persistence.Messages.toRow(): MessageRow {
        val payload = MessagePayload.decode(this.message_payload)
        return MessageRow(
            payload = payload,
            lifecycleState = this.lifecycle_state,
            isOrphaned = this.is_orphaned,
        )
    }
}