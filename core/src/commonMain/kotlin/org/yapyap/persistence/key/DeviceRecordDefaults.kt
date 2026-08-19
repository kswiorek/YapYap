package org.yapyap.persistence.key

data class DeviceRecordDefaults(
    val onionAddress: String = "unknown.onion",
    val onionPort: Long = 80L,
    val pushToken: String? = null,
    val pingAttempts: Long = 0L,
    val pingSuccesses: Long = 0L,
    val lastSeenTimestamp: Long = 0L,
)