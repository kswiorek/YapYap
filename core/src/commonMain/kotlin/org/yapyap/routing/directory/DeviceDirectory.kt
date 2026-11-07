package org.yapyap.routing.directory

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

/**
 * What the router may know about accounts and devices: which devices an account has and whether
 * they are active. Read-only; the identity tables are written by the orchestrator's folds.
 */
internal interface DeviceDirectory {
    /** Owning account of a device, or null when the device is unknown ("unknown device", not an error). */
    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?

    /** The account's non-banned devices. */
    suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId>

    /** Every non-banned device, local included — the candidate list for ping/relay/link fan-out. */
    suspend fun getAllActiveDeviceIds(): List<PeerId>

    /** Device ban state, or null when the device has no row (absence asserts nothing). */
    suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus?

    /** Account status, or null when absent (absence asserts nothing — never read it as "removed"). */
    suspend fun getAccountStatus(accountId: AccountId): IdentityStatus?

    /** The endpoint the device declared (chain AddDevice or intro), or null when the device is unknown. */
    suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint?
}

/** Where devices can be reached: the router's own state about peers (it is the only writer). */
internal interface DeviceRouteStore {
    /** The endpoint the router last proved for the device, else the one it declared; null when unknown. */
    suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint?

    /** Records an endpoint proven by an authenticated inbound stream, or our own after Tor starts. */
    suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)
}
