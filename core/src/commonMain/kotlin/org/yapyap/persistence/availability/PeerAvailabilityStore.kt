package org.yapyap.persistence.availability

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId

/**
 * Persisted peer availability state (living on the `devices` table):
 * the last epoch a peer was seen and its decaying reliability score in [0, 1].
 *
 * Writes are cheap single-row UPDATEs and run on [databaseDispatcher]; all methods are suspend so
 * callers never block a thread on IO.
 */
interface PeerAvailabilityStore {
    /** Records that [deviceId] was seen at [atEpochSeconds]. */
    suspend fun markSeen(deviceId: PeerId, atEpochSeconds: Long)

    /**
     * Persists a peer's reliability [score]. [seenAtEpochSeconds] refreshes the stored last-seen
     * when non-null; otherwise the existing value is preserved.
     */
    suspend fun updateReliability(deviceId: PeerId, score: Double, seenAtEpochSeconds: Long?)

    /** Loads a single peer's persisted availability, or null if the device is not known. */
    suspend fun availability(deviceId: PeerId): PeerAvailability?

    /** Loads persisted availability for every known device (used for swarm/uptime selection). */
    suspend fun availabilityForAll(): List<PeerAvailability>
}

data class PeerAvailability(
    val deviceId: PeerId,
    val reliabilityScore: Double,
    val lastSeenEpoch: Long,
)

class DefaultPeerAvailabilityStore(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PeerAvailabilityStore {

    private val queries = database.identityQueries

    override suspend fun markSeen(deviceId: PeerId, atEpochSeconds: Long) {
        withContext(dbDispatcher) {
            queries.markDeviceLastSeen(atEpochSeconds, deviceId)
        }
    }

    override suspend fun updateReliability(deviceId: PeerId, score: Double, seenAtEpochSeconds: Long?) {
        withContext(dbDispatcher) {
            queries.updateDeviceReliability(score, seenAtEpochSeconds, deviceId)
        }
    }

    override suspend fun availability(deviceId: PeerId): PeerAvailability? =
        withContext(dbDispatcher) {
            queries.selectDeviceAvailabilityById(deviceId)
                .executeAsOneOrNull()
                ?.let { PeerAvailability(it.deviceId, it.reliabilityScore, it.lastSeenEpoch) }
        }

    override suspend fun availabilityForAll(): List<PeerAvailability> =
        withContext(dbDispatcher) {
            queries.selectAllDeviceAvailability()
                .executeAsList()
                .map { PeerAvailability(it.deviceId, it.reliabilityScore, it.lastSeenEpoch) }
        }
}
