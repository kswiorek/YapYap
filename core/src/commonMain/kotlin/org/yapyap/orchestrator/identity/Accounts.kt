package org.yapyap.orchestrator.identity

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
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
 * A device as a member of its account: chain facts only. The endpoint the router has since
 * observed and when the device was last heard from belong to the router.
 */
internal data class AccountDevice(
    val identity: DeviceIdentity,
    /** The endpoint the device declared on the chain (or in its intro). */
    val declaredEndpoint: TorEndpoint,
    val deviceType: DeviceType,
    val status: IdentityStatus,
    val isLocal: Boolean,
    val provisional: Boolean,
) {
    val deviceId: PeerId get() = identity.deviceId
}
