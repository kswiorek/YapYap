package org.yapyap.persistence.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
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

    suspend fun findAllInRoom(roomId: String): List<MessageRow>

    /** Max lamport_clock in the room (null if empty) used to reconstruct rooms.local_seq_n on boot. */
    suspend fun maxLamportInRoom(roomId: String): Long?

    suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean)

    suspend fun isOrphanAtLamport(roomId: String, lamport: Long): Boolean

    suspend fun maxLamportBelow(roomId: String, lamport: Long): Long?

    suspend fun findMessagesInLamportRange(
        roomId: String,
        lowerInclusive: Long,
        upperInclusive: Long,
        limit: Int,
    ): List<MessageRow>

    /** Number of messages in [roomId] at exactly [lamport] (branching detection). */
    suspend fun countAtLamport(roomId: String, lamport: Long): Long
}

class DefaultMessageRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : MessageRepository {
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
        val inserted = queries.selectMessageById(payload.messageId).executeAsOneOrNull() != null
        if (inserted) {
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_INSERTED,
                message = "Inserted message into room",
                fields = mapOf(
                    "messageId" to payload.messageId,
                    "roomId" to payload.roomId,
                    "lamportClock" to payload.lamportClock,
                    "isOrphaned" to isOrphaned,
                ),
            )
        } else {
            AppLog.warn(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_INSERT_DUPLICATE,
                message = "Message insert skipped — message_id already present",
                fields = mapOf(
                    "messageId" to payload.messageId,
                    "roomId" to payload.roomId,
                ),
            )
        }
        inserted
    }

    override suspend fun findById(messageId: Uuid): MessageRow? =
        withContext(dbDispatcher) {
            val row = queries.selectMessageById(messageId).executeAsOneOrNull()?.toRow()
            if (row == null) {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.MESSAGE_FETCH_MISS,
                    message = "Message not found by id",
                    fields = mapOf("messageId" to messageId),
                )
            } else {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.MESSAGE_FETCHED,
                    message = "Message found by id",
                    fields = mapOf(
                        "messageId" to messageId,
                        "roomId" to row.payload.roomId,
                        "lamportClock" to row.payload.lamportClock,
                        "isOrphaned" to row.isOrphaned,
                    ),
                )
            }
            row
        }

    override suspend fun findRoomTail(roomId: String): MessageRow? =
        withContext(dbDispatcher) {
            val row = queries.selectRoomTail(roomId).executeAsOneOrNull()?.toRow()
            if (row == null) {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.MESSAGE_FETCH_MISS,
                    message = "Room tail message not found — room empty",
                    fields = mapOf("roomId" to roomId),
                )
            } else {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.MESSAGE_FETCHED,
                    message = "Room tail message found",
                    fields = mapOf(
                        "roomId" to roomId,
                        "messageId" to row.payload.messageId,
                        "lamportClock" to row.payload.lamportClock,
                    ),
                )
            }
            row
        }

    override suspend fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow> =
        withContext(dbDispatcher) {
            val rows = queries.selectMessagesInRoomPageDesc(
                roomId = roomId,
                cursorCreated = cursor?.createdAtEpochSeconds,
                cursorLamport = cursor?.lamportClock,
                cursorMessageId = cursor?.messageId,
                limit = limit.toLong(),
            ).executeAsList().map { it.toRow() }
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_ROOM_QUERIED,
                message = "Fetched message page from room",
                fields = mapOf(
                    "roomId" to roomId,
                    "limit" to limit,
                    "cursorLamport" to cursor?.lamportClock,
                    "resultCount" to rows.size,
                ),
            )
            rows
        }

    override suspend fun findAllInRoom(roomId: String): List<MessageRow> =
        withContext(dbDispatcher) {
            val rows = queries.selectAllMessagesInRoom(roomId).executeAsList().map { it.toRow() }
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_ROOM_QUERIED,
                message = "Fetched all messages in room",
                fields = mapOf(
                    "roomId" to roomId,
                    "resultCount" to rows.size,
                ),
            )
            rows
        }

    override suspend fun maxLamportInRoom(roomId: String): Long? =
        withContext(dbDispatcher) {
            val max = queries.selectMaxLamportInRoom(roomId).executeAsOne().MAX
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_LAMPORT_QUERIED,
                message = "Queried max lamport clock in room",
                fields = mapOf(
                    "roomId" to roomId,
                    "maxLamportClock" to (max ?: "null"),
                ),
            )
            max
        }

    override suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean) {
        withContext(dbDispatcher) {
            queries.updateMessageOrphanedFlag(isOrphaned, messageId)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_ORPHAN_FLAG_UPDATED,
                message = "Updated message orphaned flag",
                fields = mapOf(
                    "messageId" to messageId,
                    "isOrphaned" to isOrphaned,
                ),
            )
        }
    }

    override suspend fun isOrphanAtLamport(roomId: String, lamport: Long): Boolean =
        withContext(dbDispatcher) {
            val isOrphan = queries.selectIsOrphanAtLamport(roomId, lamport).executeAsOne()
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_ORPHAN_STATE_QUERIED,
                message = "Checked orphan state at lamport",
                fields = mapOf(
                    "roomId" to roomId,
                    "lamportClock" to lamport,
                    "isOrphan" to isOrphan,
                ),
            )
            isOrphan
        }

    override suspend fun maxLamportBelow(roomId: String, lamport: Long): Long? =
        withContext(dbDispatcher) {
            val max = queries.selectMaxLamportBelow(roomId, lamport).executeAsOne().MAX
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_LAMPORT_QUERIED,
                message = "Queried max lamport clock below value",
                fields = mapOf(
                    "roomId" to roomId,
                    "belowLamportClock" to lamport,
                    "maxLamportClock" to (max ?: "null"),
                ),
            )
            max
        }

    override suspend fun findMessagesInLamportRange(
        roomId: String, lowerInclusive: Long, upperInclusive: Long, limit: Int,
    ): List<MessageRow> = withContext(dbDispatcher) {
        val rows = queries.selectMessagesInLamportRange(roomId, lowerInclusive, upperInclusive, limit.toLong())
            .executeAsList().map { it.toRow() }
        AppLog.debug(
            component = LogComponent.DATABASE,
            event = LogEvent.MESSAGE_LAMPORT_RANGE_QUERIED,
            message = "Fetched messages in lamport range",
            fields = mapOf(
                "roomId" to roomId,
                "lowerInclusive" to lowerInclusive,
                "upperInclusive" to upperInclusive,
                "limit" to limit,
                "resultCount" to rows.size,
            ),
        )
        rows
    }

    override suspend fun countAtLamport(roomId: String, lamport: Long): Long =
        withContext(dbDispatcher) {
            val count = queries.selectMessageCountAtLamport(roomId, lamport).executeAsOne()
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_COUNT_QUERIED,
                message = "Counted messages at lamport",
                fields = mapOf(
                    "roomId" to roomId,
                    "lamportClock" to lamport,
                    "count" to count,
                ),
            )
            count
        }

    private fun org.yapyap.persistence.Messages.toRow(): MessageRow {
        val payload = runCatching { MessagePayload.decode(this.message_payload) }.getOrElse { error ->
            AppLog.error(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_DECODE_FAILED,
                message = "Failed to decode stored message payload",
                throwable = error,
                fields = mapOf(
                    "messageId" to this.message_id,
                    "roomId" to this.room_id,
                    "payloadType" to this.payload_type,
                ),
            )
            throw error
        }
        return MessageRow(
            payload = payload,
            isOrphaned = this.is_orphaned,
        )
    }
}
