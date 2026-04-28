package org.yapyap.backend.db

import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.crypto.IdentityPublicKeyRepository
import org.yapyap.backend.crypto.IdentityKeyServiceConfig
import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.DeviceIdentityRecord

class DefaultIdentityPublicKeyRepository(
    private val database: YapYapDatabase,
    private val config: IdentityKeyServiceConfig = IdentityKeyServiceConfig(),
) : IdentityPublicKeyRepository {

    override fun getAccountPublicKey(accountId: String): AccountIdentityRecord? {
        val queries = database.identityQueries
        val account = queries.selectAccountById(accountId).executeAsOneOrNull()

        return if (account == null) {
            null
        } else {
            AccountIdentityRecord(
                accountId = account.account_id,
                key = IdentityPublicKeyRecord(
                    keyId = account.pub_key_id,
                    keyVersion = account.pub_key_version,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = account.account_pub_key,
                )
            )
        }
    }

    override fun getDevicePublicKey(deviceId: String): DeviceIdentityRecord? {
        val queries = database.identityQueries
        val device = queries.selectDeviceById(deviceId).executeAsOneOrNull()

        return if (device == null) {
            null
        } else {
            DeviceIdentityRecord(
                deviceId = device.device_id,
                signing = IdentityPublicKeyRecord(
                    keyId = device.signing_key_id,
                    keyVersion = device.signing_key_version,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = device.signing_pub_key,
                ),
                encryption = IdentityPublicKeyRecord(
                    keyId = device.encryption_key_id,
                    keyVersion = device.encryption_key_version,
                    purpose = IdentityKeyPurpose.ENCRYPTION,
                    publicKey = device.encryption_pub_key,
                )
            )
        }
    }

    override fun insertLocalDevice(accountId: String, identity: DeviceIdentityRecord) {
        val queries = database.identityQueries

        queries.putDevice(
            device_id = identity.deviceId,
            account_id = accountId,
            device_type = config.defaultDeviceType,
            onion_address = config.defaultOnionAddress,
            onion_port = config.defaultOnionPort,
            signing_pub_key = identity.signing.publicKey,
            signing_key_id = identity.signing.keyId,
            signing_key_version = identity.signing.keyVersion,
            encryption_pub_key = identity.encryption.publicKey,
            encryption_key_id = identity.encryption.keyId,
            encryption_key_version = identity.encryption.keyVersion,
            push_token = config.defaultPushToken,
            ping_attempts = config.defaultPingAttempts,
            ping_successes = config.defaultPingSuccesses,
            last_seen_timestamp = config.defaultLastSeenTimestamp,
        )
    }

    override fun insertLocalAccount(displayName: String, identity: AccountIdentityRecord) {
        val queries = database.identityQueries

        queries.putAccount(
            account_id = identity.accountId,
            account_pub_key = identity.key.publicKey,
            pub_key_version = identity.key.keyVersion,
            pub_key_id = identity.key.keyId,
            is_admin = false,
            status = AccountStatus.ACTIVE,
            display_name = displayName,
        )
    }

    override fun resolveDeviceKey(deviceId: String, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? {
        val device = database.identityQueries.selectDeviceById(deviceId).executeAsOneOrNull() ?: return null
        return when (purpose) {
            IdentityKeyPurpose.SIGNING -> {
                if (device.signing_key_id.isBlank() || device.signing_pub_key.isEmpty()) return null
                IdentityPublicKeyRecord(
                    keyId = device.signing_key_id,
                    keyVersion = device.signing_key_version,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = device.signing_pub_key,
                )
            }
            IdentityKeyPurpose.ENCRYPTION -> {
                if (device.encryption_key_id.isBlank() || device.encryption_pub_key.isEmpty()) return null
                IdentityPublicKeyRecord(
                    keyId = device.encryption_key_id,
                    keyVersion = device.encryption_key_version,
                    purpose = IdentityKeyPurpose.ENCRYPTION,
                    publicKey = device.encryption_pub_key,
                )
            }
        }
    }
}
