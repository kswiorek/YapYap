package org.yapyap.routing.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.directory.DeviceDirectory
import org.yapyap.routing.directory.DeviceRouteStore
import kotlin.time.Instant

/**
 * Persisted peer availability: the last time a peer was seen and its decaying reliability
 * score in [0, 1]. Router-owned state in `device_routes`.
 */
internal interface PeerAvailabilityStore {
    /** Records that [deviceId] was seen at [at]. */
    suspend fun markSeen(deviceId: PeerId, at: Instant)

    /**
     * Persists a peer's reliability [score]. [seenAt] refreshes the stored last-seen when non-null;
     * otherwise the existing value is preserved.
     */
    suspend fun updateReliability(deviceId: PeerId, score: Double, seenAt: Instant?)

    /** A single peer's persisted availability, or null when nothing was ever recorded for it. */
    suspend fun availability(deviceId: PeerId): PeerAvailability?

    /** Persisted availability for every device the router has recorded (swarm/uptime selection). */
    suspend fun availabilityForAll(): List<PeerAvailability>
}

internal data class PeerAvailability(
    val deviceId: PeerId,
    val reliabilityScore: Double,
    /** [Instant.DISTANT_PAST] when the peer was never seen. */
    val lastSeen: Instant,
)

/**
 * The router's `device_routes` table: endpoints it proved and availability it measured. The
 * effective endpoint is the observed one, else the one the device declared (read through
 * [directory], which the router does not write).
 */
internal class DefaultDeviceRouteStore(
    private val database: YapYapDatabase,
    private val directory: DeviceDirectory,
    private val defaultReliability: Double = DEFAULT_RELIABILITY,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : DeviceRouteStore, PeerAvailabilityStore {

    private val queries = database.routesQueries

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? {
        val observed = withContext(dbDispatcher) {
            queries.selectRoute(deviceId).executeAsOneOrNull()?.let { row ->
                val address = row.onion_address ?: return@let null
                val port = row.onion_port ?: return@let null
                TorEndpoint(address, port.toInt())
            }
        }
        return observed ?: directory.getDeclaredTorEndpoint(deviceId)
    }

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        withContext(dbDispatcher) {
            database.transaction {
                queries.ensureRoute(deviceId)
                queries.updateTorEndpoint(
                    onionAddress = torEndpoint.onionAddress,
                    onionPort = torEndpoint.port.toLong(),
                    deviceId = deviceId,
                )
            }
        }
    }

    override suspend fun markSeen(deviceId: PeerId, at: Instant) {
        withContext(dbDispatcher) {
            database.transaction {
                queries.ensureRoute(deviceId)
                queries.updateLastSeen(at = at, deviceId = deviceId)
            }
        }
    }

    override suspend fun updateReliability(deviceId: PeerId, score: Double, seenAt: Instant?) {
        withContext(dbDispatcher) {
            database.transaction {
                queries.ensureRoute(deviceId)
                queries.updateReliability(score = score, seenAt = seenAt, deviceId = deviceId)
            }
        }
    }

    override suspend fun availability(deviceId: PeerId): PeerAvailability? =
        withContext(dbDispatcher) {
            queries.selectRoute(deviceId).executeAsOneOrNull()?.toAvailability()
        }

    override suspend fun availabilityForAll(): List<PeerAvailability> =
        withContext(dbDispatcher) {
            queries.selectAllRoutes().executeAsList().mapNotNull { it.toAvailability() }
        }

    private fun org.yapyap.persistence.Device_routes.toAvailability(): PeerAvailability? {
        if (reliability_score == null && last_seen_timestamp == null) return null
        return PeerAvailability(
            deviceId = device_id,
            reliabilityScore = reliability_score ?: defaultReliability,
            lastSeen = last_seen_timestamp ?: Instant.DISTANT_PAST,
        )
    }

    companion object {
        /** Neutral starting reliability score (between 0 and 1) for a device never measured. */
        const val DEFAULT_RELIABILITY: Double = 0.5
    }
}
