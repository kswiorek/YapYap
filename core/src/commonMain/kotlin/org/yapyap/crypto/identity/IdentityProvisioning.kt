package org.yapyap.crypto.identity

import org.yapyap.persistence.db.AccountStatus
import org.yapyap.protocol.TorEndpoint

interface IdentityProvisioning {
    suspend fun createNewDeviceIdentity(): DeviceIdentityRecord

    suspend fun createNewAccountIdentity(displayName: String): AccountIdentityRecord

    fun provisionDeviceIdentity(accountId: AccountId, deviceIdentity: DeviceIdentityRecord, torEndpoint: TorEndpoint)

    fun provisionAccountIdentity(displayName: String, accountIdentity: AccountIdentityRecord, admin: Boolean, status: AccountStatus)

    suspend fun provisionSignedPreKey(): SignedPreKeyRecord
}