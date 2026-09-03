package org.yapyap.routing.maintenance

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.routing.router.RouterConfig
import kotlin.time.Clock

class PacketStoreMaintenance(
    private val outbox: PacketOutbox,
    private val dedup: PacketDeduplicator,
    private val config: StateFlow<RouterConfig>,
    private val clock: Clock = Clock.System,
) {
    suspend fun run() {
        val now = clock.now()
        dedup.prune(now - config.value.dedupRetention)
        outbox.pruneRelayOverCapacity(config.value.outboxMaxSizeBytes)
    }
}