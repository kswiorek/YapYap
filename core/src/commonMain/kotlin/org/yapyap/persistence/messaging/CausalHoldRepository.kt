package org.yapyap.persistence.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.Causal_hold
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import kotlin.uuid.Uuid

data class CausalHoldRow(
    val gapId: Uuid,
    val missingPrevId: Uuid,
    val orphanedMessageId: Uuid,
    val detectedTimestamp: Long,
)

interface CausalHoldRepository {

    suspend fun insert(gapId: Uuid, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Long)

    suspend fun findByMissingPrevId(missingPrevId: Uuid): List<CausalHoldRow>

    suspend fun findByRoom(roomId: String): List<CausalHoldRow>

    suspend fun findAll(): List<CausalHoldRow>

    suspend fun deleteByMissingPrevId(missingPrevId: Uuid)

    suspend fun deleteByOrphanedMessageId(orphanedMessageId: Uuid)
}

class DefaultCausalHoldRepository(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : CausalHoldRepository {

    private val queries = database.messageQueries

    override suspend fun insert(gapId: Uuid, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Long) {
        withContext(dbDispatcher) {
            queries.insertCausalHold(gapId, missingPrevId, orphanedMessageId, detectedTimestamp)
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.CAUSAL_HOLD_INSERTED,
                message = "Inserted gap causal hold",
                fields = mapOf(
                    "gapId" to gapId,
                    "missingPrevId" to missingPrevId,
                    "orphanedMessageId" to orphanedMessageId,
                    "detectedTimestamp" to detectedTimestamp,
                ),
            )
        }
    }

    override suspend fun findByMissingPrevId(missingPrevId: Uuid): List<CausalHoldRow> =
        withContext(dbDispatcher) {
            val rows = queries.selectCausalHoldsByMissingPrevId(missingPrevId).executeAsList().map { it.toRow() }
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.CAUSAL_HOLD_QUERIED,
                message = "Fetched causal holds by missing prev id",
                fields = mapOf(
                    "missingPrevId" to missingPrevId,
                    "resultCount" to rows.size,
                ),
            )
            rows
        }

    override suspend fun findByRoom(roomId: String): List<CausalHoldRow> =
        withContext(dbDispatcher) {
            val rows = queries.selectCausalHoldsByRoom(roomId).executeAsList().map { it.toRow() }
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.CAUSAL_HOLD_QUERIED,
                message = "Fetched causal holds for room",
                fields = mapOf(
                    "roomId" to roomId,
                    "resultCount" to rows.size,
                ),
            )
            rows
        }

    override suspend fun findAll(): List<CausalHoldRow> =
        withContext(dbDispatcher) {
            val rows = queries.selectAllCausalHolds().executeAsList().map { it.toRow() }
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.CAUSAL_HOLD_QUERIED,
                message = "Fetched all causal holds",
                fields = mapOf("resultCount" to rows.size),
            )
            rows
        }

    override suspend fun deleteByMissingPrevId(missingPrevId: Uuid) {
        withContext(dbDispatcher) {
            queries.deleteCausalHoldsByMissingPrevId(missingPrevId)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.CAUSAL_HOLD_DELETED,
                message = "Deleted causal holds by missing prev id",
                fields = mapOf("missingPrevId" to missingPrevId),
            )
        }
    }

    override suspend fun deleteByOrphanedMessageId(orphanedMessageId: Uuid) {
        withContext(dbDispatcher) {
            queries.deleteCausalHoldByOrphanedMessageId(orphanedMessageId)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.CAUSAL_HOLD_DELETED,
                message = "Deleted causal hold by orphaned message id",
                fields = mapOf("orphanedMessageId" to orphanedMessageId),
            )
        }
    }

    private fun Causal_hold.toRow(): CausalHoldRow =
        CausalHoldRow(
            gapId = this.gap_id,
            missingPrevId = this.missing_prev_id,
            orphanedMessageId = this.orphaned_message_id,
            detectedTimestamp = this.detected_timestamp,
        )
}