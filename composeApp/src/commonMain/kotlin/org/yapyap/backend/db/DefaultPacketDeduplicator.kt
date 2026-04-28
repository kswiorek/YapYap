package org.yapyap.backend.db

import org.yapyap.backend.protocol.PacketId

class DefaultPacketDeduplicator(
    private val database: YapYapDatabase,
) : PacketDeduplicator {
    override fun firstSeen(packetId: PacketId, sourceDeviceId: String, receivedAtEpochSeconds: Long): Boolean {
        val packetHex = packetId.toHex()
        val queries = database.dedupQueries
        return queries.transactionWithResult {
            val existing = queries.selectDedupBySourceAndPacketId(
                source_device_id = sourceDeviceId,
                packet_id = packetHex,
            ).executeAsOneOrNull()
            if (existing != null) {
                false
            } else {
                queries.insertDedup(
                    packet_id = packetHex,
                    source_device_id = sourceDeviceId,
                    received_at = receivedAtEpochSeconds,
                )
                true
            }
        }
    }

    override fun prune(receivedBeforeEpochSeconds: Long) {
        database.dedupQueries.deleteDedupReceivedBefore(receivedBeforeEpochSeconds)
    }
}
