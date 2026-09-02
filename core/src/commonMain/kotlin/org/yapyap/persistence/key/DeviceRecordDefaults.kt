package org.yapyap.persistence.key

data class DeviceRecordDefaults(
    val onionAddress: String = "unknown.onion",
    val onionPort: Long = 80L,
    val pushToken: String? = null,
    /** Neutral starting reliability score (between 0 and 1) for a freshly provisioned device. */
    val reliabilityScore: Double = 0.5,
    val lastSeenTimestamp: Long = 0L,
)