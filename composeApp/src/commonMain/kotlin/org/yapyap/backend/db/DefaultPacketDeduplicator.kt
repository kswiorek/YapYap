package org.yapyap.backend.db

import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerId

class DefaultPacketDeduplicator(
    private val database: YapYapDatabase,
    private val logger: AppLogger = NoopAppLogger,
) : PacketDeduplicator {
    override fun firstSeen(packetId: PacketId, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean {
        val packetHex = packetId.toHex()
        val queries = database.dedupQueries
        return queries.transactionWithResult {
            val existing = queries.selectDedupBySourceAndPacketId(
                source_device_id = sourceDeviceId.id,
                packet_id = packetHex,
            ).executeAsOneOrNull()
            if (existing != null) {
                logger.debug(
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
                    received_at = receivedAtEpochSeconds,
                )
                logger.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.DEDUP_CACHE_MISS,
                    message = "Deduplicator recorded new packet",
                    fields = mapOf("packetId" to packetHex, "sourceDeviceId" to sourceDeviceId),
                )
                true
            }
        }
    }

    override fun prune(receivedBeforeEpochSeconds: Long) {
        database.dedupQueries.deleteDedupReceivedBefore(receivedBeforeEpochSeconds)
        logger.info(
            component = LogComponent.DATABASE,
            event = LogEvent.DEDUP_PRUNED,
            message = "Pruned old deduplicator records",
            fields = mapOf("receivedBeforeEpochSeconds" to receivedBeforeEpochSeconds),
        )
    }
}
