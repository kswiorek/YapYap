package org.yapyap.persistence.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.uuid.Uuid

/**
 * DB row mapped from [org.yapyap.persistence.Messages]: the decoded [MessagePayload]
 * plus local-only metadata not carried on the wire.
 */
data class MessageRow(
    val payload: MessagePayload,
    val isOrphaned: Boolean,
)

/**
 * Composite cursor for stable pagination of room messages.
 *
 * Display ordering is `(createdAtEpochSeconds DESC, lamportClock DESC, messageId DESC)` â€” a total
 * order with no ties, so pagination is stable across live inserts and reloads. The cursor captures
 * the oldest row of the currently-loaded window so the next page begins strictly below it.
 */
data class MessageCursor(
    val createdAtEpochSeconds: Long,
    val lamportClock: Long,
    val messageId: Uuid,
)

interface MessageRepository {

    //TODO Make all suspends
    /** Insert a message; returns false if a row with the same message_id already exists (dedup). */
    suspend fun insert(payload: MessagePayload, isOrphaned: Boolean): Boolean

    suspend fun findById(messageId: Uuid): MessageRow?

    /** Highest-lamport message in the room; tie-break by createdAt DESC, messageId DESC. Null if room is empty. */
    suspend fun findRoomTail(roomId: String): MessageRow?

    suspend fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow>

    suspend fun findMessagesInRoomAfterLamport(
        roomId: String,
        limit: Int,
        lamportClock: Long,
    ): List<MessageRow>

    suspend fun findAllInRoom(roomId: String): List<MessageRow>

    /** Max lamport_clock in the room (null if empty) â€” used to reconstruct rooms.local_seq_n on boot. */
    suspend fun maxLamportInRoom(roomId: String): Long?

    suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean)
}

class DefaultMessageRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : MessageRepository {
    //TODO add logging
    private val queries = database.messageQueries

    override suspend fun insert(
        payload: MessagePayload,
        isOrphaned: Boolean,
    ): Boolean = withContext(dbDispatcher) {
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
            is_orphaned = isOrphaned,
        )
        queries.selectMessageById(payload.messageId).executeAsOneOrNull() != null
    }

    override suspend fun findById(messageId: Uuid): MessageRow? =
        withContext(dbDispatcher) {
            queries.selectMessageById(messageId).executeAsOneOrNull()?.toRow()
        }

    override suspend fun findRoomTail(roomId: String): MessageRow? =
        withContext(dbDispatcher) {
            queries.selectRoomTail(roomId).executeAsOneOrNull()?.toRow()
        }

    override suspend fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow> =
        withContext(dbDispatcher) {
            queries.selectMessagesInRoomPageDesc(
                roomId = roomId,
                cursorCreated = cursor?.createdAtEpochSeconds,
                cursorLamport = cursor?.lamportClock,
                cursorMessageId = cursor?.messageId,
                limit = limit.toLong(),
            ).executeAsList().map { it.toRow() }
        }

    override suspend fun findMessagesInRoomAfterLamport(
        roomId: String,
        limit: Int,
        lamportClock: Long
    ): List<MessageRow> =
        withContext(dbDispatcher) {
            queries.selectMessagesInRoomAfterLamport(
                roomId = roomId,
                sinceLamport = lamportClock,
                limit = limit.toLong(),
            ).executeAsList().map { it.toRow() }
        }

    override suspend fun findAllInRoom(roomId: String): List<MessageRow> =
        withContext(dbDispatcher) {
            queries.selectAllMessagesInRoom(roomId).executeAsList().map { it.toRow() }
        }

    override suspend fun maxLamportInRoom(roomId: String): Long? =
        withContext(dbDispatcher) {
            queries.selectMaxLamportInRoom(roomId).executeAsOne().MAX
        }

    override suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean) {
        withContext(dbDispatcher) {
            queries.updateMessageOrphanedFlag(isOrphaned, messageId)
        }
    }

    private fun org.yapyap.persistence.Messages.toRow(): MessageRow {
        val payload = MessagePayload.decode(this.message_payload)
        return MessageRow(
            payload = payload,
            isOrphaned = this.is_orphaned,
        )
    }
}
