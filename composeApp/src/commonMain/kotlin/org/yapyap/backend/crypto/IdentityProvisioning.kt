package org.yapyap.backend.crypto

import org.yapyap.backend.db.AccountStatus
import org.yapyap.backend.protocol.TorEndpoint

interface IdentityProvisioning {
    fun createNewDeviceIdentity(): DeviceIdentityRecord

    fun createNewAccountIdentity(displayName: String): AccountIdentityRecord

    fun provisionDeviceIdentity(accountId: AccountId, deviceIdentity: DeviceIdentityRecord, torEndpoint: TorEndpoint)

    fun provisionAccountIdentity(displayName: String, accountIdentity: AccountIdentityRecord, admin: Boolean, status: AccountStatus)
}