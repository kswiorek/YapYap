package org.yapyap.orchestrator.identity

/** The declared endpoint a local device row starts with, until the chain carries the real one. */
internal data class DeviceRecordDefaults(
    val onionAddress: String = "unknown.onion",
    val onionPort: Long = 80L,
)