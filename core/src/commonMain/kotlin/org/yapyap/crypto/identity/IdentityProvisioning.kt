package org.yapyap.crypto.identity

interface IdentityProvisioning {
    suspend fun createNewDeviceIdentity(): DeviceIdentityRecord

    /**
     * @param admin initial `is_admin` for the local accounts row. True for the genesis of a new
     *   network (genesis account is admin by definition — §3 of the global-events doc); false for a
     *   new account joining an existing network (its admin status is chain-derived via GrantAdmin).
     */
    suspend fun createNewAccountIdentity(displayName: String, admin: Boolean = false): AccountIdentityRecord

    suspend fun createPlaceholderAccountIdentity(): AccountIdentityRecord

    /** Export local account signing key + display name as a pasteable recovery code. */
    suspend fun exportLocalAccountRecoveryKey(): String

    /** Restore local account from a recovery code (keystore + local accounts row). */
    suspend fun importLocalAccountFromRecovery(recoveryKey: String): AccountIdentityRecord

    suspend fun provisionSignedPreKey(): SignedPreKeyRecord
}