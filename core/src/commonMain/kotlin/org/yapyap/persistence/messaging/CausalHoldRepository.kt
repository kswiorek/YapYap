package org.yapyap.persistence.messaging

import org.yapyap.persistence.Causal_hold
import org.yapyap.persistence.YapYapDatabase

data class CausalHoldRow(
    val gapId: String,
    val missingPrevId: String,
    val orphanedMessageId: String,
    val detectedTimestamp: Long,
)

interface CausalHoldRepository {

    fun insert(gapId: String, missingPrevId: String, orphanedMessageId: String, detectedTimestamp: Long)

    fun findByMissingPrevId(missingPrevId: String): List<CausalHoldRow>

    fun findByRoom(roomId: String): List<CausalHoldRow>

    fun findAll(): List<CausalHoldRow>

    fun deleteByMissingPrevId(missingPrevId: String)

    fun deleteByOrphanedMessageId(orphanedMessageId: String)
}

class DefaultCausalHoldRepository(
    private val database: YapYapDatabase,
) : CausalHoldRepository {

    private val queries = database.messageQueries

    override fun insert(gapId: String, missingPrevId: String, orphanedMessageId: String, detectedTimestamp: Long) {
        queries.insertCausalHold(gapId, missingPrevId, orphanedMessageId, detectedTimestamp)
    }

    override fun findByMissingPrevId(missingPrevId: String): List<CausalHoldRow> =
        queries.selectCausalHoldsByMissingPrevId(missingPrevId).executeAsList().map { it.toRow() }

    override fun findByRoom(roomId: String): List<CausalHoldRow> =
        queries.selectCausalHoldsByRoom(roomId).executeAsList().map { it.toRow() }

    override fun findAll(): List<CausalHoldRow> =
        queries.selectAllCausalHolds().executeAsList().map { it.toRow() }

    override fun deleteByMissingPrevId(missingPrevId: String) {
        queries.deleteCausalHoldsByMissingPrevId(missingPrevId)
    }

    override fun deleteByOrphanedMessageId(orphanedMessageId: String) {
        queries.deleteCausalHoldByOrphanedMessageId(orphanedMessageId)
    }

    private fun Causal_hold.toRow(): CausalHoldRow =
        CausalHoldRow(
            gapId = this.gap_id,
            missingPrevId = this.missing_prev_id,
            orphanedMessageId = this.orphaned_message_id,
            detectedTimestamp = this.detected_timestamp,
        )
}