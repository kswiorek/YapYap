package org.yapyap.sync

import org.yapyap.persistence.availability.PeerAvailability
import org.yapyap.persistence.availability.PeerAvailabilityStore
import org.yapyap.protocol.PeerId

/** In-memory [PeerAvailabilityStore] for tests: no dispatcher, records writes into maps. */
class FakePeerAvailabilityStore : PeerAvailabilityStore {
    val reliability = mutableMapOf<PeerId, Double>()
    val lastSeen = mutableMapOf<PeerId, Long>()

    override suspend fun markSeen(deviceId: PeerId, atEpochSeconds: Long) {
        lastSeen[deviceId] = atEpochSeconds
    }

    override suspend fun updateReliability(deviceId: PeerId, score: Double, seenAtEpochSeconds: Long?) {
        reliability[deviceId] = score
        if (seenAtEpochSeconds != null) lastSeen[deviceId] = seenAtEpochSeconds
    }

    override suspend fun availability(deviceId: PeerId): PeerAvailability? =
        reliability[deviceId]?.let { PeerAvailability(deviceId, it, lastSeen[deviceId] ?: 0L) }

    override suspend fun availabilityForAll(): List<PeerAvailability> =
        reliability.keys.map { PeerAvailability(it, reliability.getValue(it), lastSeen[it] ?: 0L) }
}
