package org.yapyap.transport.tor.backend

import kotlinx.serialization.Serializable

/**
 * Runtime tuning values for Tor backend implementations.
 */

@Serializable
data class TorBackendConfig(
    val startupTimeoutMillis: Long = 120_000,
    val maxPayloadBytes: Int = 4 * 1024 * 1024,
    val socksRetryTimeoutMillis: Long = 300_000,
    val socksRetryDelayMillis: Long = 1_000,
    val socksTransientFailureCodes: Set<Int> = setOf(3, 4, 6),
    val defaultTorPort: Int = 80,
) {
    init {
        require(startupTimeoutMillis > 0) { "startupTimeoutMillis must be > 0" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be > 0" }
        require(socksRetryTimeoutMillis > 0) { "socksRetryTimeoutMillis must be > 0" }
        require(socksRetryDelayMillis > 0) { "socksRetryDelayMillis must be > 0" }
        require(defaultTorPort in 1..65535) { "defaultTorPort must be in range 1..65535" }
    }
}

