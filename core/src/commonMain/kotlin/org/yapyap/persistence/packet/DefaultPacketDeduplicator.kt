package org.yapyap.persistence.packet

import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.packet.PacketId

class DefaultPacketDeduplicator(
    database: YapYapDatabase,
    private val logger: AppLogger = NoopAppLogger,
) : PacketDeduplicator {
    private val queries = database.dedupQueries
    override fun firstSeen(packetId: PacketId, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean {
        val packetHex = packetId.toHex()
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

    override fun markNacked(packetId: PacketId, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        queries.updateNackReason(nackReason, sourceDeviceId.id, packetId.toHex())
    }

    override fun getNackReason(packetId: PacketId, sourceDeviceId: PeerId): PacketNackReason? {
        return queries
            .getNackReason(sourceDeviceId.id, packetId.toHex())
            .executeAsOneOrNull()
            ?.nack_reason
    }

    override fun prune(receivedBeforeEpochSeconds: Long) {
        queries.deleteDedupReceivedBefore(receivedBeforeEpochSeconds)
        logger.info(
            component = LogComponent.DATABASE,
            event = LogEvent.DEDUP_PRUNED,
            message = "Pruned old deduplicator records",
            fields = mapOf("receivedBeforeEpochSeconds" to receivedBeforeEpochSeconds),
        )
    }
}
