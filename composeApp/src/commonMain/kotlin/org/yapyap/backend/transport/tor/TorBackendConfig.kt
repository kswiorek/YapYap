package org.yapyap.backend.transport.tor

import kotlinx.coroutines.channels.BufferOverflow

/**
 * Runtime tuning values for Tor backend implementations.
 */
data class TorBackendConfig(
    val startupTimeoutMillis: Long = 120_000,
    val maxPayloadBytes: Int = 4 * 1024 * 1024,
    val inboundReplay: Int = 0,
    val inboundExtraBufferCapacity: Int = 64,
    val inboundOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    val socksRetryTimeoutMillis: Long = 300_000,
    val socksRetryDelayMillis: Long = 1_000,
    val socksTransientFailureCodes: Set<Int> = setOf(3, 4, 6),
    val defaultTorPort: Int = 80,
) {
    init {
        require(startupTimeoutMillis > 0) { "startupTimeoutMillis must be > 0" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be > 0" }
        require(inboundReplay >= 0) { "inboundReplay must be >= 0" }
        require(inboundExtraBufferCapacity >= 0) { "inboundExtraBufferCapacity must be >= 0" }
        require(socksRetryTimeoutMillis > 0) { "socksRetryTimeoutMillis must be > 0" }
        require(socksRetryDelayMillis > 0) { "socksRetryDelayMillis must be > 0" }
        require(defaultTorPort in 1..65535) { "defaultTorPort must be in range 1..65535" }
    }
}

