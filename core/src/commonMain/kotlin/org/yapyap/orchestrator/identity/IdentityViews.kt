package org.yapyap.orchestrator.identity

import org.yapyap.crypto.identity.Account
import org.yapyap.crypto.identity.AccountIdentity
import org.yapyap.crypto.identity.Device
import org.yapyap.crypto.identity.DeviceIdentity
import org.yapyap.crypto.identity.SignedPreKey
import org.yapyap.crypto.keys.EncryptionPublicKey
import org.yapyap.crypto.keys.SigningPublicKey
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

/** Read side of the `accounts`/`devices` tables for orchestrator services. */
internal interface IdentityReader {
    suspend fun getAccountIdentity(accountId: AccountId): AccountIdentity?

    /** Chain-derived membership status, or null when absent (absence asserts nothing — treat as "not yet known", never "removed"). */
    suspend fun getAccountStatus(accountId: AccountId): IdentityStatus?

    /** Chain-derived device ban state, or null when absent (same absence semantics as [getAccountStatus]). */
    suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus?

    suspend fun getLocalDeviceIdentity(): DeviceIdentity?

    suspend fun getLocalAccountIdentity(): AccountIdentity?

    /** Local account's admin flag (false when absent; OWNER implies admin) — the sponsor's fail-fast read. */
    suspend fun isLocalAccountAdmin(): Boolean

    /** Local account's owner flag (false when absent) — the self-leave handover gate. */
    suspend fun isLocalAccountOwner(): Boolean

    /** Chain-derived admin flag for any account (false when absent — absence asserts nothing; OWNER implies admin). */
    suspend fun isAccountAdmin(accountId: AccountId): Boolean

    /** Chain-derived owner flag for any account (false when absent — absence asserts nothing). */
    suspend fun isAccountOwner(accountId: AccountId): Boolean

    /** The single OWNER account id, or null when no owner row is committed yet. */
    suspend fun getOwnerAccountId(): AccountId?

    /** Provisional bit of a device row (true when absent — unknown rows are unconfirmed by definition). */
    suspend fun isDeviceProvisional(deviceId: PeerId): Boolean

    suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?

    /** All account rows, any status (BANNED rows stay visible with their status) — the roster source. */
    suspend fun getAllAccounts(): List<Account>

    /** All device rows, any status — the roster source. */
    suspend fun getAllDevices(): List<Device>

    suspend fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKey?
}

/** Chain-derived identity writes. Handed only to the global fold's projector, the sole writer of these columns. */
internal interface IdentityWriter {
    /**
     * Upserts the chain-derived account columns (pub key, role, status, display name) and clears
     * `provisional` (the fold now carries the Add event). Preserves the local-only `is_local_account` flag.
     */
    suspend fun upsertChainAccount(
        accountId: AccountId,
        accountSigningPublicKey: SigningPublicKey?,
        role: AccountRole,
        status: IdentityStatus,
        displayName: String,
    )

    /**
     * Upserts the chain-derived device columns (binding, keys, type, declared endpoint, status)
     * and clears `provisional`. Preserves local-only columns (`is_local_device`, SPK pointer).
     * The endpoint the router has since observed lives in its own table and is untouched.
     * Committing the local device makes [accountId] the local account (the chain decides which
     * account a device belongs to), replacing a pre-confirmation stand-in account row.
     */
    suspend fun upsertChainDevice(
        deviceId: PeerId,
        accountId: AccountId,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        signingPublicKey: SigningPublicKey,
        encryptionPublicKey: EncryptionPublicKey,
        status: IdentityStatus,
    )

    /** Status flip to BANNED with admin revoked, keys stay resolvable. No-op when the row is absent. */
    suspend fun tombstoneAccount(accountId: AccountId)

    /** Status flip to BANNED, keys stay resolvable. No-op when the row is absent. */
    suspend fun tombstoneDevice(deviceId: PeerId)

    /** Makes [spk] the device's only active prekey. */
    suspend fun upsertDeviceSignedPreKey(spk: SignedPreKey)
}

/**
 * Intro-time seeding of peer rows before the chain carries them. Handed only to onboarding, the
 * single writer of provisional rows; the fold clears `provisional` when it commits the Add event.
 */
internal interface ProvisionalIdentitySeeder {
    /** Insert-only (`provisional = true`); existing rows are left untouched. */
    suspend fun seedProvisionalPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentity,
        torEndpoint: TorEndpoint,
    )

    /** Insert-only, account half of [seedProvisionalPeerDevice] (`provisional = true`, ACTIVE). */
    suspend fun seedProvisionalPeerAccount(identity: AccountIdentity, role: AccountRole, displayName: String)
}
