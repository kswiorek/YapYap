package org.yapyap.orchestrator.runtime.identity

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import kotlin.time.Instant

/** GUI-facing snapshot of one known account and its devices. */
public data class AccountView(
    val accountId: AccountId,
    val displayName: String,
    /** Chain-derived network role (MEMBER / ADMIN / OWNER); OWNER implies admin authority. */
    val role: AccountRole,
    /** Chain-derived membership status (ACTIVE / BANNED / UNBOUND). */
    val status: IdentityStatus,
    val isLocal: Boolean,
    val availability: AccountAvailability,
    val devices: List<DeviceView>,
)

/** GUI-facing snapshot of one known device. */
public data class DeviceView(
    val deviceId: PeerId,
    /** Where the device says it is reached (this device: where Tor published it); null for a banned device. */
    val torEndpoint: TorEndpoint?,
    val deviceType: DeviceType,
    /** Chain-derived device state (ACTIVE / BANNED). */
    val status: IdentityStatus,
    val isLocal: Boolean,
    /** True until the global fold carries the device's Add event. */
    val provisional: Boolean,
)

/** Account-level presence, aggregated from the account's devices. The GUI sorts
 *  by [AccountAvailability.lastSeen] for finer gradations — recency buckets are
 *  a display concern, not a backend one. */
public data class AccountAvailability(
    val label: AvailabilityLabel,
    /** Most recent device sighting; null when never seen. */
    val lastSeen: Instant?,
)

public enum class AvailabilityLabel {
    ONLINE,
    OFFLINE,
    UNKNOWN,
}
