package org.yapyap.crypto.identity

import org.yapyap.persistence.db.AccountRole

interface IdentityProvisioning {
    suspend fun createNewDeviceIdentity(): DeviceIdentityRecord

    /**
     * @param role initial `role` for the local accounts row. OWNER for the genesis of a new
     *   network (genesis account is owner by definition — §3 of the global-events doc); MEMBER
     *   for a new account joining an existing network (its role is chain-derived via
     *   GrantAdmin / the owner handover).
     */
    suspend fun createNewAccountIdentity(
        displayName: String,
        role: AccountRole = AccountRole.MEMBER,
    ): AccountIdentityRecord

    suspend fun createPlaceholderAccountIdentity(): AccountIdentityRecord

    /** Export local account signing key + display name as a pasteable recovery code. */
    suspend fun exportLocalAccountRecoveryKey(): String

    /**
     * Verifies a recovery code against the local account (decode + derive the public key +
     * compare to the stored account key) WITHOUT importing anything — the possession proof
     * for the last-device removal gate. False on malformed codes and on key mismatch.
     */
    suspend fun verifyRecoveryKey(recoveryKey: String): Boolean

    /** Restore local account from a recovery code (keystore + local accounts row). */
    suspend fun importLocalAccountFromRecovery(recoveryKey: String): AccountIdentityRecord

    suspend fun provisionSignedPreKey(): SignedPreKeyRecord
}