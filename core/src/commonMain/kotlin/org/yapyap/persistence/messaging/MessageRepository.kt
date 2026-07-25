package org.yapyap.persistence.messaging

import org.yapyap.logging.AppLogger
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.MessageLifecycleState
import org.yapyap.protocol.envelopes.MessagePayload

/**
 * DB row mapped from [org.yapyap.persistence.Messages]: the decoded [MessagePayload]
 * plus local-only metadata not carried on the wire.
 */
data class MessageRow(
    val payload: MessagePayload,
    val lifecycleState: MessageLifecycleState,
    val isOrphaned: Boolean,
)

interface MessageRepository {

    /** Insert a message; returns false if a row with the same message_id already exists (dedup). */
    fun insert(payload: MessagePayload, lifecycleState: MessageLifecycleState, isOrphaned: Boolean): Boolean

    fun findById(messageId: String): MessageRow?

    /** Highest-lamport message in the room; tie-break by createdAt DESC, messageId DESC. Null if room is empty. */
    fun findRoomTail(roomId: String): MessageRow?

    fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursorCreated: Long?,
        cursorLamport: Long,
        cursorMessageId: String,
    ): List<MessageRow>

    fun findAllInRoom(roomId: String): List<MessageRow>

    /** Max lamport_clock in the room (null if empty) — used to reconstruct rooms.local_seq_n on boot. */
    fun maxLamportInRoom(roomId: String): Long?

    fun updateOrphanedFlag(messageId: String, isOrphaned: Boolean)

    fun updateLifecycleState(messageId: String, state: MessageLifecycleState)
}

class DefaultMessageRepository(
    private val database: YapYapDatabase,
    private val logger: AppLogger = NoopAppLogger, //TODO add logging
) : MessageRepository {

    private val queries = database.messageQueries

    override fun insert(
        payload: MessagePayload,
        lifecycleState: MessageLifecycleState,
        isOrphaned: Boolean,
    ): Boolean {
        queries.insertMessage(
            message_id = payload.messageId,
            room_id = payload.roomId,
            sender_account_id = payload.senderAccountId,
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

    override fun findById(messageId: String): MessageRow? =
        queries.selectMessageById(messageId).executeAsOneOrNull()?.toRow()

    override fun findRoomTail(roomId: String): MessageRow? =
        queries.selectRoomTail(roomId).executeAsOneOrNull()?.toRow()

    override fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursorCreated: Long?,
        cursorLamport: Long,
        cursorMessageId: String,
    ): List<MessageRow> =
        queries.selectMessagesInRoomPageDesc(
            roomId = roomId,
            cursorCreated = cursorCreated,
            cursorLamport = cursorLamport,
            cursorMessageId = cursorMessageId,
            limit = limit.toLong(),
        ).executeAsList().map { it.toRow() }

    override fun findAllInRoom(roomId: String): List<MessageRow> =
        queries.selectAllMessagesInRoom(roomId).executeAsList().map { it.toRow() }

    override fun maxLamportInRoom(roomId: String): Long? =
        queries.selectMaxLamportInRoom(roomId).executeAsOne().MAX

    override fun updateOrphanedFlag(messageId: String, isOrphaned: Boolean) {
        queries.updateMessageOrphanedFlag(isOrphaned, messageId)
    }

    override fun updateLifecycleState(messageId: String, state: MessageLifecycleState) {
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