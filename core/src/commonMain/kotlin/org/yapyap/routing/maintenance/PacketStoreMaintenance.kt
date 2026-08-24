package org.yapyap.routing.maintenance

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.routing.router.RouterConfig
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider

class PacketStoreMaintenance(
    private val outbox: PacketOutbox,
    private val dedup: PacketDeduplicator,
    private val config: StateFlow<RouterConfig>,
    private val timeProvider: EpochProvider = SystemEpochProvider,
) {
    suspend fun run() {
        val now = timeProvider.nowEpochSeconds()
        dedup.prune(now - config.value.dedupRetentionSeconds)
        outbox.pruneRelayOverCapacity(config.value.outboxMaxSizeBytes)
    }
}