package org.yapyap.persistence.messaging

import org.yapyap.persistence.Causal_hold
import org.yapyap.persistence.YapYapDatabase
import kotlin.uuid.Uuid

data class CausalHoldRow(
    val gapId: String,
    val missingPrevId: Uuid,
    val orphanedMessageId: Uuid,
    val detectedTimestamp: Long,
)

interface CausalHoldRepository {

    fun insert(gapId: String, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Long)

    fun findByMissingPrevId(missingPrevId: Uuid): List<CausalHoldRow>

    fun findByRoom(roomId: String): List<CausalHoldRow>

    fun findAll(): List<CausalHoldRow>

    fun deleteByMissingPrevId(missingPrevId: Uuid)

    fun deleteByOrphanedMessageId(orphanedMessageId: Uuid)
}

class DefaultCausalHoldRepository(
    private val database: YapYapDatabase,
) : CausalHoldRepository {

    private val queries = database.messageQueries

    override fun insert(gapId: String, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Long) {
        queries.insertCausalHold(gapId, missingPrevId.toHexString(), orphanedMessageId.toHexString(), detectedTimestamp)
    }

    override fun findByMissingPrevId(missingPrevId: Uuid): List<CausalHoldRow> =
        queries.selectCausalHoldsByMissingPrevId(missingPrevId.toHexString()).executeAsList().map { it.toRow() }

    override fun findByRoom(roomId: String): List<CausalHoldRow> =
        queries.selectCausalHoldsByRoom(roomId).executeAsList().map { it.toRow() }

    override fun findAll(): List<CausalHoldRow> =
        queries.selectAllCausalHolds().executeAsList().map { it.toRow() }

    override fun deleteByMissingPrevId(missingPrevId: Uuid) {
        queries.deleteCausalHoldsByMissingPrevId(missingPrevId.toHexString())
    }

    override fun deleteByOrphanedMessageId(orphanedMessageId: Uuid) {
        queries.deleteCausalHoldByOrphanedMessageId(orphanedMessageId.toHexString())
    }

    private fun Causal_hold.toRow(): CausalHoldRow =
        CausalHoldRow(
            gapId = this.gap_id,
            missingPrevId = Uuid.parseHex(this.missing_prev_id),
            orphanedMessageId = Uuid.parseHex(this.orphaned_message_id),
            detectedTimestamp = this.detected_timestamp,
        )
}