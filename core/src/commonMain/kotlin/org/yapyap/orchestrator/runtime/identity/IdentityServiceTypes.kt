package org.yapyap.orchestrator.runtime.identity

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.PeerId
import kotlin.time.Instant

/** GUI-facing snapshot of one known account and its devices. */
data class AccountView(
    val accountId: AccountId,
    val displayName: String,
    val isAdmin: Boolean,
    /** Chain-derived membership status (ACTIVE / BANNED / UNBOUND). */
    val status: AccountStatus,
    val isLocal: Boolean,
    val availability: AccountAvailability,
    val devices: List<DeviceView>,
)

/** GUI-facing snapshot of one known device. */
data class DeviceView(
    val deviceId: PeerId,
    val deviceType: DeviceType,
    /** Chain-derived device state (ACTIVE / BANNED). */
    val status: AccountStatus,
    val isLocal: Boolean,
    /** True until the global fold carries the device's Add event. */
    val provisional: Boolean,
    val lastSeen: Instant?,
)

/** Account-level presence, aggregated from the account's devices. */
data class AccountAvailability(
    val label: AvailabilityLabel,
    /** Most recent device sighting; null when never seen. */
    val lastSeen: Instant?,
)

enum class AvailabilityLabel {
    ONLINE,
    RECENT,
    OFFLINE,
    UNKNOWN,
}
