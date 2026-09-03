package org.yapyap.persistence.availability

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import kotlin.time.Instant

/**
 * Persisted peer availability state (living on the `devices` table):
 * the last time a peer was seen and its decaying reliability score in [0, 1].
 *
 * Writes are cheap single-row UPDATEs and run on [databaseDispatcher]; all methods are suspend so
 * callers never block a thread on IO. Instants are converted to epoch seconds only when binding the
 * SQL queries.
 */
interface PeerAvailabilityStore {
    /** Records that [deviceId] was seen at [at]. */
    suspend fun markSeen(deviceId: PeerId, at: Instant)

    /**
     * Persists a peer's reliability [score]. [seenAt] refreshes the stored last-seen when non-null;
     * otherwise the existing value is preserved.
     */
    suspend fun updateReliability(deviceId: PeerId, score: Double, seenAt: Instant?)

    /** Loads a single peer's persisted availability, or null if the device is not known. */
    suspend fun availability(deviceId: PeerId): PeerAvailability?

    /** Loads persisted availability for every known device (used for swarm/uptime selection). */
    suspend fun availabilityForAll(): List<PeerAvailability>
}

data class PeerAvailability(
    val deviceId: PeerId,
    val reliabilityScore: Double,
    val lastSeen: Instant,
)

class DefaultPeerAvailabilityStore(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : PeerAvailabilityStore {

    private val queries = database.identityQueries

    override suspend fun markSeen(deviceId: PeerId, at: Instant) {
        withContext(dbDispatcher) {
            queries.markDeviceLastSeen(at.epochSeconds, deviceId)
        }
    }

    override suspend fun updateReliability(deviceId: PeerId, score: Double, seenAt: Instant?) {
        withContext(dbDispatcher) {
            queries.updateDeviceReliability(score, seenAt?.epochSeconds, deviceId)
        }
    }

    override suspend fun availability(deviceId: PeerId): PeerAvailability? =
        withContext(dbDispatcher) {
            queries.selectDeviceAvailabilityById(deviceId)
                .executeAsOneOrNull()
                ?.let { PeerAvailability(it.deviceId, it.reliabilityScore, Instant.fromEpochSeconds(it.lastSeenEpoch)) }
        }

    override suspend fun availabilityForAll(): List<PeerAvailability> =
        withContext(dbDispatcher) {
            queries.selectAllDeviceAvailability()
                .executeAsList()
                .map { PeerAvailability(it.deviceId, it.reliabilityScore, Instant.fromEpochSeconds(it.lastSeenEpoch)) }
        }
}
