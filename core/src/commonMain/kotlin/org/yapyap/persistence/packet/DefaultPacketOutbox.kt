package org.yapyap.persistence.packet

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.Outbox
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import kotlin.uuid.Uuid

class DefaultPacketOutbox(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PacketOutbox {
    val queries = database.outboxQueries

    override suspend fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long, relayMessage: Boolean) {
        withContext(dbDispatcher) {
            val envelopeBlob = envelope.encode()
            queries.insertOutbox(
                packet_id = envelope.packetId,
                target_device_id = envelope.target.id,
                is_relay = relayMessage,
                retry_count = 0,
                expires_at = envelope.expiresAtEpochSeconds,
                last_attempt_at = envelope.createdAtEpochSeconds,
                envelope_blob = envelopeBlob,
                blob_size = envelopeBlob.size.toLong(),
                next_retry_at = nextRetryAt,
            )
            AppLog.debug(
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
    }

    override suspend fun markDelivered(packetId: Uuid) {
        withContext(dbDispatcher) {
            queries.deleteByPacketId(packetId)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.OUTBOX_DELIVERED,
                message = "Removed delivered packet from outbox",
                fields = mapOf("packetId" to packetId),
            )
        }
    }

    override suspend fun setDueForTarget(target: PeerId, nextRetryAt: Long) {
        withContext(dbDispatcher) {
            queries.setNextRetry(nextRetryAt, target.id)
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.OUTBOX_DUE_SET,
                message = "Accelerated pending outbox retries for target",
                fields = mapOf(
                    "target" to target,
                    "nextRetryAt" to nextRetryAt,
                ),
            )
        }
    }

    override suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Long, now: Long) {
        withContext(dbDispatcher) {
            queries.updateAttempt(
                packet_id = packetId,
                last_attempt_at = now,
                next_retry_at = nextRetryAt,
            )
            AppLog.debug(
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
    }

    override suspend fun listAllForTarget(target: PeerId): List<OutboxEntry> =
        withContext(dbDispatcher) {
            queries.getAllForTargetDevice(target.id).executeAsList().mapNotNull { mapRowOrDrop(it) }
        }

    override suspend fun listDue(now: Long): List<OutboxEntry> =
        withContext(dbDispatcher) {
            queries.getDue(now).executeAsList().mapNotNull { mapRowOrDrop(it) }
        }

    override suspend fun pruneExpired(now: Long): Int =
        withContext(dbDispatcher) {
            val removed = queries.deleteExpired(now).value.toInt()
            if (removed > 0) {
                AppLog.info(
                    component = LogComponent.DATABASE,
                    event = LogEvent.OUTBOX_EXPIRED_PRUNED,
                    message = "Pruned expired outbox rows",
                    fields = mapOf(
                        "removedCount" to removed,
                        "now" to now,
                    ),
                )
            }
            removed
        }

    override suspend fun earliestPendingRetryAt(): Long? =
        withContext(dbDispatcher) {
            queries.getEarliestRetryAt().executeAsOneOrNull()
        }

    override suspend fun relayCacheBytes(): Long =
        withContext(dbDispatcher) {
            queries.getCacheSize().executeAsOne()
        }

    override suspend fun pruneRelayOverCapacity(maxBytes: Long): Int =
        withContext(dbDispatcher) {
            var evicted = 0

            while (true) {
                val total = queries.getCacheSize().executeAsOne()
                if (total <= maxBytes) break

                val overflow = total - maxBytes

                val victims = queries.listRelayEvictionCandidates(200).executeAsList()

                var freed = 0L
                val idsToDelete = mutableListOf<Uuid>()

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
                AppLog.info(
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

            evicted
        }

    private fun mapRowOrDrop(row: Outbox): OutboxEntry? {
        val packetId = row.packet_id

        val envelope = runCatching { BinaryEnvelope.decode(row.envelope_blob) }.getOrElse { error ->
            AppLog.error(
                component = LogComponent.DATABASE,
                event = LogEvent.OUTBOX_DECODE_FAILED,
                message = "Dropped corrupt outbox row",
                throwable = error,
                fields = mapOf(
                    "packetId" to packetId,
                    "isRelay" to row.is_relay,
                ),
            )
            queries.deleteByPacketId(packetId)
            return null
        }

        return OutboxEntry(
            packetId = packetId,
            envelope = envelope,
            nextRetryAt = row.next_retry_at,
            attempts = row.retry_count,
        )
    }
}
