package org.yapyap.persistence.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.VerificationState
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * DB row mapped from [org.yapyap.persistence.Messages]: the decoded [MessagePayload]
 * plus local-only metadata not carried on the wire.
 */
data class MessageRow(
    val payload: MessagePayload,
    val isOrphaned: Boolean,
    val verificationState: VerificationState = VerificationState.VERIFIED,
    /** Transitively complete ancestry (every parent present and itself complete). */
    val ancestryComplete: Boolean = true,
)

/**
 * Composite cursor for stable pagination of room messages.
 *
 * Display ordering is `(createdAtEpochSeconds DESC, lamportClock DESC, messageId DESC)` â€” a total
 * order with no ties, so pagination is stable across live inserts and reloads. The cursor captures
 * the oldest row of the currently-loaded window so the next page begins strictly below it.
 */
data class MessageCursor(
    val createdAt: Instant,
    val lamportClock: Long,
    val messageId: Uuid,
)

interface MessageRepository {

    /** Insert a message; returns false if a row with the same message_id already exists (dedup). */
    suspend fun insert(
        payload: MessagePayload,
        isOrphaned: Boolean,
        ancestryComplete: Boolean,
        verificationState: VerificationState
    ): Boolean

    suspend fun findById(messageId: Uuid): MessageRow?

    /**
     * Chainable frontier (covering antichain): chainable messages that no chainable
     * message references as a parent. Empty if the room is empty or every tip is parked.
     */
    suspend fun findRoomFrontier(roomId: RoomId): List<MessageRow>

    /** Parent IDs of a stored message (empty for the DAG root). */
    suspend fun findParents(messageId: Uuid): List<Uuid>

    suspend fun insertParent(messageId: Uuid, parentId: Uuid)

    /** Stored messages in [roomId] referencing [parentId] (used for the ancestry cascade). */
    suspend fun findChildrenInRoom(parentId: Uuid, roomId: RoomId): List<MessageRow>

    suspend fun findMessagesInRoomPageDesc(
        roomId: RoomId,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow>

    suspend fun findAllInRoom(roomId: RoomId): List<MessageRow>

    /** Max lamport_clock in the room (null if empty). */
    suspend fun maxLamportInRoom(roomId: RoomId): Long?

    /** Highest-lamport non-rejected message (append-guard fallback only). Null if room is empty. */
    suspend fun findLatestInRoom(roomId: RoomId): MessageRow?

    suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean)

    /** Mark a stored message's ancestry transitively complete/incomplete. */
    suspend fun updateAncestryComplete(messageId: Uuid, complete: Boolean)

    /** Transition a stored message between verification states (e.g. PENDING -> VERIFIED/REJECTED). */
    suspend fun updateVerificationState(messageId: Uuid, state: VerificationState)

    /** Messages waiting on identity state for [deviceId] (used to re-verify once the device is known). */
    suspend fun findPendingByAuthor(deviceId: PeerId): List<MessageRow>

    /** All messages in the holding-tank state, useful for a boot-time re-verification sweep. */
    suspend fun findAllPending(): List<MessageRow>
}

class DefaultMessageRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : MessageRepository {
    private val queries = database.messageQueries

    override suspend fun insert(
        payload: MessagePayload,
        isOrphaned: Boolean,
        ancestryComplete: Boolean,
        verificationState: VerificationState,
    ): Boolean = withContext(dbDispatcher) {
        queries.insertMessage(
            message_id = payload.messageId,
            room_id = payload.roomId,
            sender_account_id = payload.senderAccountId,
            author_device_id = payload.authorDeviceId,
            verification_state = verificationState,
            lamport_clock = payload.lamportClock,
            created_at_epoch_seconds = payload.createdAt,
            payload_type = payload.payloadType,
            message_payload = payload.encode(),
            is_orphaned = isOrphaned,
            ancestry_complete = ancestryComplete,
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
                    "ancestryComplete" to ancestryComplete,
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

    override suspend fun findRoomFrontier(roomId: RoomId): List<MessageRow> =
        withContext(dbDispatcher) {
            val rows = queries.selectRoomFrontier(roomId).executeAsList().map { it.toRow() }
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_FETCHED,
                message = "Room frontier fetched",
                fields = mapOf(
                    "roomId" to roomId,
                    "tipCount" to rows.size,
                ),
            )
            rows
        }

    override suspend fun findParents(messageId: Uuid): List<Uuid> =
        withContext(dbDispatcher) {
            queries.selectParentsByMessage(messageId).executeAsList()
        }

    override suspend fun insertParent(messageId: Uuid, parentId: Uuid) {
        withContext(dbDispatcher) {
            queries.insertMessageParent(message_id = messageId, parent_id = parentId)
        }
    }

    override suspend fun findChildrenInRoom(parentId: Uuid, roomId: RoomId): List<MessageRow> =
        withContext(dbDispatcher) {
            queries.selectChildrenInRoom(parentId, roomId).executeAsList().map { it.toRow() }
        }

    override suspend fun findMessagesInRoomPageDesc(
        roomId: RoomId,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow> =
        withContext(dbDispatcher) {
            val rows = queries.selectMessagesInRoomPageDesc(
                roomId = roomId,
                cursorCreated = cursor?.createdAt,
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

    override suspend fun findAllInRoom(roomId: RoomId): List<MessageRow> =
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

    override suspend fun maxLamportInRoom(roomId: RoomId): Long? =
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

    override suspend fun findLatestInRoom(roomId: RoomId): MessageRow? =
        withContext(dbDispatcher) {
            queries.selectLatestInRoom(roomId).executeAsOneOrNull()?.toRow()
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

    override suspend fun updateVerificationState(messageId: Uuid, state: VerificationState) {
        withContext(dbDispatcher) {
            queries.updateMessageVerificationState(state, messageId)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_VERIFICATION_STATE_UPDATED,
                message = "Updated message verification state",
                fields = mapOf(
                    "messageId" to messageId,
                    "verificationState" to state,
                ),
            )
        }
    }

    override suspend fun findPendingByAuthor(deviceId: PeerId): List<MessageRow> =
        withContext(dbDispatcher) {
            queries.selectPendingByAuthor(deviceId).executeAsList().map { it.toRow() }
        }

    override suspend fun findAllPending(): List<MessageRow> =
        withContext(dbDispatcher) {
            queries.selectAllPending().executeAsList().map { it.toRow() }
        }

    override suspend fun updateAncestryComplete(messageId: Uuid, complete: Boolean) {
        withContext(dbDispatcher) {
            queries.updateMessageAncestryComplete(complete, messageId)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.MESSAGE_ORPHAN_FLAG_UPDATED,
                message = "Updated message ancestry-complete flag",
                fields = mapOf(
                    "messageId" to messageId,
                    "ancestryComplete" to complete,
                ),
            )
        }
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
            verificationState = this.verification_state,
            ancestryComplete = this.ancestry_complete,
        )
    }
}
