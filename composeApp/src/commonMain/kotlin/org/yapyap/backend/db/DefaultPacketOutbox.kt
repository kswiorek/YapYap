package org.yapyap.backend.db

import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerId

class DefaultPacketOutbox(
    private val database: YapYapDatabase,
    private val logger: AppLogger = NoopAppLogger,
) : PacketOutbox {
    val queries = database.outboxQueries

    override fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long, relayMessage: Boolean) {
        val envelopeBlob = envelope.encode()
        queries.insertOutbox(
            packet_id = envelope.packetId.toHex(),
            target_device_id = envelope.target.id,
            is_relay = relayMessage,
            retry_count = 0,
            expires_at = envelope.expiresAtEpochSeconds,
            last_attempt_at = envelope.createdAtEpochSeconds,
            envelope_blob = envelopeBlob,
            blob_size = envelopeBlob.size.toLong(),
            next_retry_at = nextRetryAt,
        )
        logger.debug(
            component = LogComponent.DATABASE,
            event = LogEvent.OUTBOX_ENQUEUED,
            message = "Enqueued packet to outbox",
            fields = mapOf(
                "packetId" to envelope.packetId,
                "packetType" to envelope.packetType,
                "target" to envelope.target,
                "isRelay" to relayMessage,
                "nextRetryAt" to nextRetryAt,
                "expiresAt" to envelope.expiresAtEpochSeconds,
                "blobSize" to envelopeBlob.size,
            ),
        )
    }

    override fun markDelivered(packetId: PacketId) {
        queries.deleteByPacketId(packetId.toHex())
        logger.debug(
            component = LogComponent.DATABASE,
            event = LogEvent.OUTBOX_DELIVERED,
            message = "Removed delivered packet from outbox",
            fields = mapOf("packetId" to packetId),
        )
    }

    override fun setDueForTarget(target: PeerId, nextRetryAt: Long) {
        queries.setNextRetry(nextRetryAt, target.id)
        logger.debug(
            component = LogComponent.DATABASE,
            event = LogEvent.OUTBOX_DUE_SET,
            message = "Accelerated pending outbox retries for target",
            fields = mapOf(
                "target" to target,
                "nextRetryAt" to nextRetryAt,
            ),
        )
    }

    override fun recordAttempt(packetId: PacketId, nextRetryAt: Long, now: Long) {
        queries.updateAttempt(
            packet_id = packetId.toHex(),
            last_attempt_at = now,
            next_retry_at = nextRetryAt,
        )
        logger.debug(
            component = LogComponent.DATABASE,
            event = LogEvent.OUTBOX_ATTEMPT_RECORDED,
            message = "Recorded outbox dispatch attempt",
            fields = mapOf(
                "packetId" to packetId,
                "lastAttemptAt" to now,
                "nextRetryAt" to nextRetryAt,
            ),
        )
    }

    override fun listAllForTarget(target: PeerId): List<OutboxEntry> {
        return queries.getAllForTargetDevice(target.id).executeAsList().mapNotNull { mapRowOrDrop(it) }
    }

    override fun listDue(now: Long): List<OutboxEntry> {
        return queries.getDue(now).executeAsList().mapNotNull { mapRowOrDrop(it) }
    }

    override fun pruneExpired(now: Long): Int {
        val removed = queries.deleteExpired(now).value.toInt()
        if (removed > 0) {
            logger.info(
                component = LogComponent.DATABASE,
                event = LogEvent.OUTBOX_EXPIRED_PRUNED,
                message = "Pruned expired outbox rows",
                fields = mapOf(
                    "removedCount" to removed,
                    "now" to now,
                ),
            )
        }
        return removed
    }

    override fun earliestPendingRetryAt(): Long? {
        return queries.getEarliestRetryAt().executeAsOneOrNull()
    }

    override fun relayCacheBytes(): Long {
        return queries.getCacheSize().executeAsOne()
    }

    override fun pruneRelayOverCapacity(maxBytes: Long): Int {
        var evicted = 0

        while (true) {
            val total = queries.getCacheSize().executeAsOne()
            if (total <= maxBytes) break

            val overflow = total - maxBytes

            val victims = queries.listRelayEvictionCandidates(200).executeAsList()

            var freed = 0L
            val idsToDelete = mutableListOf<String>()

            for (row in victims) {
                idsToDelete.add(row.packet_id)
                freed += row.blob_size
                evicted++
                if (freed >= overflow) break
            }

            if (idsToDelete.isEmpty()) break

            database.transaction {
                queries.deleteByPacketIds(idsToDelete)
            }
        }

        if (evicted > 0) {
            logger.info(
                component = LogComponent.DATABASE,
                event = LogEvent.OUTBOX_RELAY_EVICTED,
                message = "Evicted relay cache rows over capacity",
                fields = mapOf(
                    "evictedCount" to evicted,
                    "maxBytes" to maxBytes,
                    "remainingBytes" to queries.getCacheSize().executeAsOne(),
                ),
            )
        }

        return evicted
    }

    private fun mapRowOrDrop(row: Outbox): OutboxEntry? {
        val packetId = runCatching { PacketId.fromHex(row.packet_id) }.getOrElse { error ->
            dropRow(
                packetIdHex = row.packet_id,
                event = LogEvent.OUTBOX_ROW_INVALID,
                message = "Dropped outbox row with invalid packet id",
                isRelay = row.is_relay,
                error = error,
            )
            return null
        }

        val envelope = runCatching { BinaryEnvelope.decode(row.envelope_blob) }.getOrElse { error ->
            dropRow(
                packetIdHex = row.packet_id,
                event = LogEvent.OUTBOX_DECODE_FAILED,
                message = "Dropped corrupt outbox row",
                isRelay = row.is_relay,
                error = error,
            )
            return null
        }

        return OutboxEntry(
            packetId = packetId,
            envelope = envelope,
            nextRetryAt = row.next_retry_at,
            attempts = row.retry_count,
        )
    }

    private fun dropRow(
        packetIdHex: String,
        event: LogEvent,
        message: String,
        isRelay: Boolean,
        error: Throwable,
    ) {
        logger.error(
            component = LogComponent.DATABASE,
            event = event,
            message = message,
            throwable = error,
            fields = mapOf(
                "packetId" to packetIdHex,
                "isRelay" to isRelay,
            ),
        )
        queries.deleteByPacketId(packetIdHex)
    }
}
