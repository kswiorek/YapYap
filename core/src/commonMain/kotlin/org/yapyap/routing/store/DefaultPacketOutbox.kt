package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.Outbox
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class DefaultPacketOutbox(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PacketOutbox {
    val queries = database.outboxQueries

    override suspend fun enqueue(
        envelope: BinaryEnvelope,
        nextRetryAt: Instant,
        relayMessage: Boolean,
        targetEndpoint: TorEndpoint?,
        relayBackup: RelayBackup,
        tag: OutboxTag?,
        retryClass: RetryClass,
    ) {
        withContext(dbDispatcher) {
            val envelopeBlob = envelope.encode()
            queries.insertOutbox(
                packet_id = envelope.packetId,
                target_device_id = envelope.target.id,
                target_onion_address = targetEndpoint?.onionAddress,
                target_onion_port = targetEndpoint?.port?.toLong(),
                is_relay = relayMessage,
                relay_backup = relayBackup.code,
                retry_count = 0,
                expires_at = envelope.expiresAt,
                last_attempt_at = envelope.createdAt,
                envelope_blob = envelopeBlob,
                blob_size = envelopeBlob.size.toLong(),
                next_retry_at = nextRetryAt,
                tag_kind = tag?.kind?.code,
                tag_id = tag?.columnId,
                retry_class = retryClass.code,
            )
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.OUTBOX_ENQUEUED,
                message = "Enqueued packet to outbox",
                fields = mapOf(
                    "packetId" to envelope.packetId,
                    "packetType" to envelope.packetType,
                    "target" to envelope.target,
                    "hasEndpointOverride" to (targetEndpoint != null),
                    "isRelay" to relayMessage,
                    "nextRetryAt" to nextRetryAt,
                    "expiresAt" to envelope.expiresAt,
                    "blobSize" to envelopeBlob.size,
                    "tag" to tag,
                ),
            )
        }
    }

    override suspend fun remove(packetId: Uuid): OutboxEntry? =
        withContext(dbDispatcher) {
            val row = database.transactionWithResult<Outbox?> {
                queries.getByPacketId(packetId).executeAsOneOrNull()
                    ?.also { queries.deleteByPacketId(packetId) }
            } ?: return@withContext null
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.OUTBOX_DELIVERED,
                message = "Removed packet from outbox",
                fields = mapOf("packetId" to packetId),
            )
            mapRowOrDrop(row)
        }

    override suspend fun setDueForTarget(target: PeerId, nextRetryAt: Instant) {
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

    override suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Instant, at: Instant) {
        withContext(dbDispatcher) {
            queries.updateAttempt(
                packet_id = packetId,
                last_attempt_at = at,
                next_retry_at = nextRetryAt,
            )
            AppLog.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.OUTBOX_ATTEMPT_RECORDED,
                message = "Recorded outbox dispatch attempt",
                fields = mapOf(
                    "packetId" to packetId,
                    "lastAttemptAt" to at,
                    "nextRetryAt" to nextRetryAt,
                ),
            )
        }
    }

    override suspend fun claimRelayBackup(packetId: Uuid): Boolean =
        withContext(dbDispatcher) {
            queries.claimRelayBackup(packetId).value > 0L
        }

    override suspend fun deferRetry(packetId: Uuid, nextRetryAt: Instant) {
        withContext(dbDispatcher) {
            queries.deferRetry(next_retry_at = nextRetryAt, packet_id = packetId)
        }
    }

    override suspend fun listAllForTarget(target: PeerId): List<OutboxEntry> =
        withContext(dbDispatcher) {
            queries.getAllForTargetDevice(target.id).executeAsList().mapNotNull { mapRowOrDrop(it) }
        }

    override suspend fun listDueIds(
        now: Instant,
        nonFileLimit: Int,
        fileLimit: Int,
        excludeTargets: Collection<PeerId>,
    ): List<DueOutboxRow> =
        withContext(dbDispatcher) {
            val fileKinds = OutboxTag.Kind.FILE_KINDS.map { it.code }
            val busy = if (excludeTargets.isEmpty()) listOf("") else excludeTargets.map { it.id }
            val nonFile = queries.getDueIdsNonFile(now, fileKinds, busy, nonFileLimit.toLong()).executeAsList()
            val file = queries.getDueIdsFile(now, fileKinds, busy, fileLimit.toLong()).executeAsList()
            nonFile.map { row ->
                DueOutboxRow(
                    row.packet_id,
                    PeerId(row.target_device_id),
                    row.retry_count,
                    OutboxTag.fromColumns(row.tag_kind, row.tag_id),
                    RetryClass.fromCode(row.retry_class),
                )
            } + file.map { row ->
                DueOutboxRow(
                    row.packet_id,
                    PeerId(row.target_device_id),
                    row.retry_count,
                    OutboxTag.fromColumns(row.tag_kind, row.tag_id),
                    RetryClass.fromCode(row.retry_class),
                )
            }
        }

    override suspend fun getByPacketId(packetId: Uuid): OutboxEntry? = withContext(dbDispatcher) {
        queries.getByPacketId(packetId).executeAsOneOrNull()?.let { mapRowOrDrop(it) }
    }

    override suspend fun deleteByTag(tag: OutboxTag) {
        val id = tag.requireColumnId()
        withContext(dbDispatcher) {
            queries.deleteByTag(tag.kind.code, id)
        }
    }

    override suspend fun packetIdsByTag(tag: OutboxTag): List<Uuid> {
        val id = tag.requireColumnId()
        return withContext(dbDispatcher) {
            queries.selectPacketIdsByTag(tag.kind.code, id).executeAsList()
        }
    }

    override suspend fun pruneExpired(now: Instant): List<OutboxEntry> =
        withContext(dbDispatcher) {
            val expired = database.transactionWithResult {
                queries.getExpired(now).executeAsList().also { queries.deleteExpired(now) }
            }
            val removed = expired.size
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
            expired.mapNotNull { mapRowOrDrop(it) }
        }

    override suspend fun hasTagged(tag: OutboxTag): Boolean {
        val id = tag.requireColumnId()
        return withContext(dbDispatcher) {
            queries.hasTagged(tag.kind.code, id).executeAsOne()
        }
    }

    override suspend fun isQueued(packetId: Uuid): Boolean =
        withContext(dbDispatcher) {
            queries.isQueued(packetId).executeAsOne()
        }

    /** Tag queries match on the id; a tag without one correlates by packet id instead. */
    private fun OutboxTag.requireColumnId(): Uuid =
        requireNotNull(columnId) { "$kind tags carry no id; query their packets by packet id" }

    override suspend fun earliestPendingRetryAt(): Instant? =
        withContext(dbDispatcher) {
            queries.getEarliestRetryAt().executeAsOneOrNull()
        }

    override suspend fun earliestPendingRetryAfter(after: Instant): Instant? =
        withContext(dbDispatcher) {
            queries.getEarliestRetryAfter(after).executeAsOneOrNull()
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
            targetEndpoint = row.target_onion_address?.let { address ->
                row.target_onion_port?.let { port -> TorEndpoint(address, port.toInt()) }
            },
            tag = OutboxTag.fromColumns(row.tag_kind, row.tag_id),
            retryClass = RetryClass.fromCode(row.retry_class),
            relayBackup = RelayBackup.entries.first { it.code == row.relay_backup },
        )
    }
}
