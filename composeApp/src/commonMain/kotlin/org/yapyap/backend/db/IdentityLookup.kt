package org.yapyap.backend.db

import org.yapyap.backend.protocol.TorEndpoint

/**
 * Thin SQLDelight-backed lookup helpers used by temporary router/protection glue.
 */
class IdentityLookup(
    private val database: YapYapDatabase,
) {
    fun resolveAccountIdForDevice(deviceId: String): String {
        val row = requireNotNull(database.identityQueriesQueries.selectDeviceById(deviceId).executeAsOneOrNull()) {
            "Unknown deviceId: $deviceId"
        }
        return row.account_pub_key
    }

    fun resolveTorEndpointForDevice(deviceId: String): TorEndpoint {
        val row = requireNotNull(database.identityQueriesQueries.selectDeviceById(deviceId).executeAsOneOrNull()) {
            "Unknown deviceId: $deviceId"
        }
        return TorEndpoint(
            onionAddress = row.onion_address,
            port = row.onion_port.toInt(),
        )
    }

    fun resolveSigningKeyIdForDevice(deviceId: String): String {
        val row = requireNotNull(database.identityQueriesQueries.selectDeviceById(deviceId).executeAsOneOrNull()) {
            "Unknown deviceId: $deviceId"
        }
        return row.signing_key_id
    }
}
