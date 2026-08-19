package org.yapyap.routing.maintenance

import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.routing.router.RouterConfig
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider

class PacketStoreMaintenance(
    private val outbox: PacketOutbox,
    private val dedup: PacketDeduplicator,
    private val config: RouterConfig,
    private val timeProvider: EpochProvider = SystemEpochProvider,
) {
    suspend fun run() {
        val now = timeProvider.nowEpochSeconds()
        dedup.prune(now - config.dedupRetentionSeconds)
        outbox.pruneRelayOverCapacity(config.outboxMaxSizeBytes)
    }
}