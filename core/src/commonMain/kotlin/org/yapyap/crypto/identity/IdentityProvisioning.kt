package org.yapyap.crypto.identity

import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.TorEndpoint

interface IdentityProvisioning {
    suspend fun createNewDeviceIdentity(): DeviceIdentityRecord

    suspend fun createNewAccountIdentity(displayName: String): AccountIdentityRecord

    suspend fun createPlaceholderAccountIdentity(): AccountIdentityRecord

    /** Export local account signing key + display name as a pasteable recovery code. */
    suspend fun exportLocalAccountRecoveryKey(): String

    /** Restore local account from a recovery code (keystore + local accounts row). */
    suspend fun importLocalAccountFromRecovery(recoveryKey: String): AccountIdentityRecord

    fun provisionDeviceIdentity(accountId: AccountId, deviceType: DeviceType, deviceIdentity: DeviceIdentityRecord, torEndpoint: TorEndpoint)

    fun provisionAccountIdentity(accountIdentity: AccountIdentityRecord, admin: Boolean, status: AccountStatus)

    suspend fun provisionSignedPreKey(): SignedPreKeyRecord
}