package org.yapyap.persistence.key

import org.yapyap.crypto.identity.*
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

interface IdentityKeyRepository {
    suspend fun getAccountRecord(accountId: AccountId): AccountIdentityRecord?

    /** Chain-derived membership status, or null when absent (absence asserts nothing — treat as "not yet known", never "removed"). */
    suspend fun getAccountStatus(accountId: AccountId): AccountStatus?

    /** Chain-derived device ban state, or null when absent (same absence semantics as [getAccountStatus]). */
    suspend fun getDeviceStatus(deviceId: PeerId): AccountStatus?

    /**
     * Projector commit write: upserts the chain-derived account columns (pub key, admin, status,
     * display name) and clears `provisional` (the fold now carries the Add event). Preserves
     * local-only columns (`is_local_account`, key id/version bookkeeping — minted as chain
     * placeholders when the row is fresh).
     */
    suspend fun upsertChainAccount(
        accountId: AccountId,
        accountSigningPublicKey: ByteArray?,
        isAdmin: Boolean,
        status: AccountStatus,
        displayName: String,
    )

    /**
     * Projector commit write: upserts the chain-derived device columns (binding, keys, type,
     * key signature, status) and clears `provisional`. Preserves local-only columns
     * (`is_local_device`, key id bookkeeping, SPK pointer, push token, reliability, last-seen).
     * A live-updated onion is preserved on confirmed rows (Tor rotation has no chain event);
     * provisional rows are fixed up to the event values wholesale.
     */
    suspend fun upsertChainDevice(
        deviceId: PeerId,
        accountId: AccountId,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        signingPublicKey: ByteArray,
        encryptionPublicKey: ByteArray,
        keySignature: ByteArray?,
        status: AccountStatus,
    )

    /** Projector commit write: status flip to BANNED with admin revoked, keys stay resolvable. No-op when the row is absent. */
    suspend fun tombstoneAccount(accountId: AccountId)

    /** Projector commit write: status flip to BANNED, keys stay resolvable. No-op when the row is absent. */
    suspend fun tombstoneDevice(deviceId: PeerId)

    suspend fun getDeviceRecord(deviceId: PeerId): DeviceIdentityRecord?

    suspend fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentityRecord, provisional: Boolean = true)

    suspend fun getLocalDeviceRecord(): DeviceIdentityRecord?

    suspend fun getLocalAccountRecord(): AccountIdentityRecord?

    /** Local account's admin flag (false when absent) — the sponsor's fail-fast read. Chain-owned; seeded locally, projector-corrected. */
    suspend fun isLocalAccountAdmin(): Boolean

    /** Chain-derived admin flag for any account (false when absent — absence asserts nothing). */
    suspend fun isAccountAdmin(accountId: AccountId): Boolean

    /** Provisional bit of a device row (true when absent — unknown rows are unconfirmed by definition). */
    suspend fun isDeviceProvisional(deviceId: PeerId): Boolean

    suspend fun insertPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentityRecord,
        torEndpoint: TorEndpoint,
        provisional: Boolean = true
    )

    /** Insert-only intro seed (`provisional = true`); existing rows are left untouched. Cleared by the projector once the fold carries the Add event. */
    suspend fun seedProvisionalPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentityRecord,
        torEndpoint: TorEndpoint
    )

    /** Insert-only intro seed, account half of [seedProvisionalPeerDevice] (`provisional = true`, ACTIVE). */
    suspend fun seedProvisionalPeerAccount(identity: AccountIdentityRecord, admin: Boolean, displayName: String)

    suspend fun insertLocalAccount(identity: AccountIdentityRecord, admin: Boolean = false, provisional: Boolean = true)

    suspend fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord?

    suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint?

    suspend fun insertPeerAccount(
        identity: AccountIdentityRecord,
        admin: Boolean,
        status: AccountStatus,
        displayName: String,
        provisional: Boolean = true
    )

    suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId>

    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?

    suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

    suspend fun getSignedPreKey(spkId: String): SignedPreKeyRecord?

    suspend fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKeyRecord?

    suspend fun insertSignedPreKey(spk: SignedPreKeyRecord)

    suspend fun upsertDeviceSignedPreKey(spk: SignedPreKeyRecord)

    suspend fun getAllDeviceIds(): List<PeerId>
}
