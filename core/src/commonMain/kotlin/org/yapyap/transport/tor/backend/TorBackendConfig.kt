package org.yapyap.transport.tor.backend

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runtime tuning values for Tor backend implementations.
 */

@Serializable
data class TorBackendConfig(
    val startupTimeout: Duration = 120_000L.milliseconds,
    val maxPayloadBytes: Int = 4 * 1024 * 1024,
    val socksRetryTimeout: Duration = 300_000.milliseconds,
    val socksRetryDelay: Duration = 1_000.milliseconds,
    val socksTransientFailureCodes: Set<Int> = setOf(3, 4, 6),
    val defaultTorPort: Int = 80,
) {
    init {
        require(startupTimeout > 0L.milliseconds) { "startupTimeoutMillis must be > 0" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be > 0" }
        require(socksRetryTimeout > 0L.milliseconds) { "socksRetryTimeoutMillis must be > 0" }
        require(socksRetryDelay > 0L.milliseconds) { "socksRetryDelayMillis must be > 0" }
        require(defaultTorPort in 1..65535) { "defaultTorPort must be in range 1..65535" }
    }
}

