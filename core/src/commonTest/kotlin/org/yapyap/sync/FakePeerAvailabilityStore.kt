package org.yapyap.sync

import org.yapyap.persistence.availability.PeerAvailability
import org.yapyap.persistence.availability.PeerAvailabilityStore
import org.yapyap.protocol.PeerId
import kotlin.time.Instant

/** In-memory [PeerAvailabilityStore] for tests: no dispatcher, records writes into maps. */
class FakePeerAvailabilityStore : PeerAvailabilityStore {
    val reliability = mutableMapOf<PeerId, Double>()
    val lastSeen = mutableMapOf<PeerId, Instant>()

    override suspend fun markSeen(deviceId: PeerId, at: Instant) {
        lastSeen[deviceId] = at
    }

    override suspend fun updateReliability(deviceId: PeerId, score: Double, seenAt: Instant?) {
        reliability[deviceId] = score
        if (seenAt != null) lastSeen[deviceId] = seenAt
    }

    override suspend fun availability(deviceId: PeerId): PeerAvailability? =
        reliability[deviceId]?.let { PeerAvailability(deviceId, it, lastSeen[deviceId] ?: Instant.fromEpochSeconds(0)) }

    override suspend fun availabilityForAll(): List<PeerAvailability> =
        reliability.keys.map {
            PeerAvailability(
                it,
                reliability.getValue(it),
                lastSeen[it] ?: Instant.fromEpochSeconds(0)
            )
        }
}
