package org.yapyap.crypto.identity

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.session.X3dhRemotePeerKeys
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

internal interface IdentityResolver {
    suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord

    suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord

    suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus

    /**
     * Device status, or null when the device has no row. Absence asserts nothing —
     * treat null as "unknown device", never as banned or removed. Prefer this over
     * [getDeviceStatus] wherever a missing row is an expected case rather than an
     * error (inbound policy, fan-out filtering).
     */
    suspend fun getDeviceStatusOrNull(deviceId: PeerId): IdentityStatus? {
        return try {
            getDeviceStatus(deviceId)
        } catch (_: CryptoException) {
            null
        }
    }

    /** Chain-derived account status, or null when absent (absence asserts nothing — treat as "not yet known", never "removed"). */
    suspend fun getAccountStatus(accountId: AccountId): IdentityStatus?

    suspend fun isLocalAccountAdmin(): Boolean

    /** Local account's owner flag (false when absent) — the self-leave handover gate. */
    suspend fun isLocalAccountOwner(): Boolean

    suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray

    suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray

    suspend fun getLocalDeviceId(): PeerId

    suspend fun getLocalAccountId(): AccountId

    suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord

    suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint

    suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> {
        if (accountIds.isEmpty()) return emptyList()
        return accountIds.flatMap { getAllPeerDevicesForAccount(it) }
    }

    /**
     * Resolves the owning [AccountId] for a peer device, or null if the device is unknown.
     * Callers must treat null as "unknown device" rather than an error.
     */
    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?

    suspend fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

    suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String? = null,
    ): X3dhRemotePeerKeys

    suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord

    /** Resolves a local SPK by wire id (supports archived keys after rotation). */
    suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord

    suspend fun getAllPeers(): List<PeerId>

    /** Active-only peer list (excludes BANNED) for ping/relay/send fan-out. */
    suspend fun getAllActivePeers(): List<PeerId> {
        return getAllPeers().filter {
            try {
                getDeviceStatusOrNull(it) != IdentityStatus.BANNED
            } catch (_: Exception) {
                true
            }
        }
    }
}