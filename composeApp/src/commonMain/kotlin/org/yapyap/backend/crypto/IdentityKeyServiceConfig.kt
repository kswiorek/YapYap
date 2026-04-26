package org.yapyap.backend.crypto

import org.yapyap.backend.db.DeviceType

data class IdentityKeyServiceConfig(
    val defaultDeviceType: DeviceType = DeviceType.DESKTOP,
    val defaultOnionAddress: String = "unknown.onion",
    val defaultOnionPort: Long = 80L,
    val defaultPushToken: String? = null,
    val defaultPingAttempts: Long = 0L,
    val defaultPingSuccesses: Long = 0L,
    val defaultLastSeenTimestamp: Long = 0L,
) {
    init {
        require(defaultOnionAddress.endsWith(".onion")) { "defaultOnionAddress must end with .onion" }
        require(defaultOnionPort in 1L..65535L) { "defaultOnionPort must be in range 1..65535" }
        require(defaultPingAttempts >= 0L) { "defaultPingAttempts must be >= 0" }
        require(defaultPingSuccesses >= 0L) { "defaultPingSuccesses must be >= 0" }
        require(defaultLastSeenTimestamp >= 0L) { "defaultLastSeenTimestamp must be >= 0" }
    }
}
