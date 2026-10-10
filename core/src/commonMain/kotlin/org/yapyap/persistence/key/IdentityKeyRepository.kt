package org.yapyap.persistence.key

import org.yapyap.crypto.identity.*
import org.yapyap.protocol.*
import kotlin.time.Instant

/** Roster read: one account row with its chain-derived columns (any status — BANNED included). */
internal data class AccountRow(
    val accountId: AccountId,
    val displayName: String,
    val role: AccountRole,
    val status: IdentityStatus,
    val isLocal: Boolean,
)

/** Roster read: one device row with its chain-derived columns (any status — BANNED included). */
internal data class DeviceRow(
    val deviceId: PeerId,
    val accountId: AccountId,
    val deviceType: DeviceType,
    val status: IdentityStatus,
    val isLocal: Boolean,
    val provisional: Boolean,
    /** Last inbound traffic; null when never seen (stored as the NEVER sentinel). */
    val lastSeen: Instant?,
)

internal interface IdentityKeyRepository {
    suspend fun getAccountRecord(accountId: AccountId): AccountIdentityRecord?

    /** Chain-derived membership status, or null when absent (absence asserts nothing — treat as "not yet known", never "removed"). */
    suspend fun getAccountStatus(accountId: AccountId): IdentityStatus?

    /** Chain-derived device ban state, or null when absent (same absence semantics as [getAccountStatus]). */
    suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus?

    /**
     * Projector commit write: upserts the chain-derived account columns (pub key, role, status,
     * display name) and clears `provisional` (the fold now carries the Add event). Preserves
     * local-only columns (`is_local_account`, key id/version bookkeeping — minted as chain
     * placeholders when the row is fresh).
     */
    suspend fun upsertChainAccount(
        accountId: AccountId,
        accountSigningPublicKey: ByteArray?,
        role: AccountRole,
        status: IdentityStatus,
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
        status: IdentityStatus,
    )

    /** Projector commit write: status flip to BANNED with admin revoked, keys stay resolvable. No-op when the row is absent. */
    suspend fun tombstoneAccount(accountId: AccountId)

    /** Projector commit write: status flip to BANNED, keys stay resolvable. No-op when the row is absent. */
    suspend fun tombstoneDevice(deviceId: PeerId)

    suspend fun getDeviceRecord(deviceId: PeerId): DeviceIdentityRecord?

    suspend fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentityRecord, provisional: Boolean = true)

    suspend fun getLocalDeviceRecord(): DeviceIdentityRecord?

    suspend fun getLocalAccountRecord(): AccountIdentityRecord?

    /** Local account's admin flag (false when absent; OWNER implies admin) — the sponsor's fail-fast read. Chain-owned; seeded locally, projector-corrected. */
    suspend fun isLocalAccountAdmin(): Boolean

    /** Local account's owner flag (false when absent) — the self-leave handover gate. Chain-owned; seeded locally, projector-corrected. */
    suspend fun isLocalAccountOwner(): Boolean

    /** Chain-derived admin flag for any account (false when absent — absence asserts nothing; OWNER implies admin). */
    suspend fun isAccountAdmin(accountId: AccountId): Boolean

    /** Chain-derived owner flag for any account (false when absent — absence asserts nothing). */
    suspend fun isAccountOwner(accountId: AccountId): Boolean

    /** The single OWNER account id, or null when no owner row is committed yet. */
    suspend fun getOwnerAccountId(): AccountId?

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
    suspend fun seedProvisionalPeerAccount(identity: AccountIdentityRecord, role: AccountRole, displayName: String)

    suspend fun insertLocalAccount(
        identity: AccountIdentityRecord,
        role: AccountRole = AccountRole.MEMBER,
        provisional: Boolean = true,
    )

    suspend fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord?

    suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint?

    suspend fun insertPeerAccount(
        identity: AccountIdentityRecord,
        role: AccountRole,
        status: IdentityStatus,
        displayName: String,
        provisional: Boolean = true
    )

    suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId>

    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?

    /** All account rows, any status (BANNED rows stay visible with their status) — the read-only roster source. */
    suspend fun getAllAccountRows(): List<AccountRow>

    /** All device rows, any status — the read-only roster source. */
    suspend fun getAllDeviceRows(): List<DeviceRow>

    suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)

    suspend fun getSignedPreKey(spkId: String): SignedPreKeyRecord?

    suspend fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKeyRecord?

    /** All signed-prekey IDs registered for a device (keystore cleanup at wipe). */
    suspend fun getSignedPreKeyIds(deviceId: PeerId): List<String>

    suspend fun insertSignedPreKey(spk: SignedPreKeyRecord)

    suspend fun upsertDeviceSignedPreKey(spk: SignedPreKeyRecord)

    suspend fun getAllDeviceIds(): List<PeerId>

    /** Active-only enumeration (excludes BANNED) for send/relay/ping candidate lists. */
    suspend fun getAllActiveDeviceIds(): List<PeerId> {
        return getAllDeviceIds().filter { getDeviceStatus(it) != IdentityStatus.BANNED }
    }
}
