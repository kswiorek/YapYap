package org.yapyap.orchestrator.identity

import org.yapyap.protocol.*
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity

/** A stored account with its devices, as the global fold projected them. */
internal data class Account(
    val identity: AccountIdentity,
    val role: AccountRole,
    val status: IdentityStatus,
    val isLocal: Boolean,
    val devices: List<AccountDevice>,
) {
    val accountId: AccountId get() = identity.accountId
    val displayName: String get() = identity.displayName
}

/**
 * A device as a member of its account: identity facts only. Where the device is reached and
 * when it was last heard from belong to the router.
 */
internal data class AccountDevice(
    val identity: DeviceIdentity,
    val deviceType: DeviceType,
    val status: IdentityStatus,
    val isLocal: Boolean,
    val provisional: Boolean,
) {
    val deviceId: PeerId get() = identity.deviceId
}
