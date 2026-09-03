package org.yapyap.persistence.packet

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.PacketNackReason
import kotlin.time.Instant
import kotlin.uuid.Uuid

class DefaultPacketDeduplicator(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PacketDeduplicator {
    private val queries = database.dedupQueries
    override suspend fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAt: Instant): Boolean =
        withContext(dbDispatcher) {
            val packetHex = packetId
            queries.transactionWithResult {
                val existing = queries.selectDedupBySourceAndPacketId(
                    source_device_id = sourceDeviceId.id,
                    packet_id = packetHex,
                ).executeAsOneOrNull()
                if (existing != null) {
                    AppLog.debug(
                        component = LogComponent.DATABASE,
                        event = LogEvent.DEDUP_CACHE_HIT,
                        message = "Deduplicator hit existing packet",
                        fields = mapOf("packetId" to packetHex, "sourceDeviceId" to sourceDeviceId),
                    )
                    false
                } else {
                    queries.insertDedup(
                        packet_id = packetHex,
                        source_device_id = sourceDeviceId.id,
                        received_at = receivedAt,
                    )
                    AppLog.debug(
                        component = LogComponent.DATABASE,
                        event = LogEvent.DEDUP_CACHE_MISS,
                        message = "Deduplicator recorded new packet",
                        fields = mapOf("packetId" to packetHex, "sourceDeviceId" to sourceDeviceId),
                    )
                    true
                }
            }
        }

    override suspend fun clearPacket(
        packetId: Uuid,
        sourceDeviceId: PeerId
    ) {
        withContext(dbDispatcher) {
            queries.clearPacket(packetId, sourceDeviceId.id)
        }
    }

    override suspend fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        withContext(dbDispatcher) {
            queries.updateNackReason(nackReason, sourceDeviceId.id, packetId)
        }
    }

    override suspend fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason? =
        withContext(dbDispatcher) {
            queries
                .getNackReason(sourceDeviceId.id, packetId)
                .executeAsOneOrNull()
                ?.nack_reason
        }

    override suspend fun prune(receivedBefore: Instant) {
        withContext(dbDispatcher) {
            queries.deleteDedupReceivedBefore(receivedBefore)
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.DEDUP_PRUNED,
                message = "Pruned old deduplicator records",
                fields = mapOf("receivedBefore" to receivedBefore),
            )
        }
    }
}
