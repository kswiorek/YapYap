package org.yapyap.backend.db

import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.crypto.IdentityPublicKeyRepository
import org.yapyap.backend.crypto.IdentityKeyServiceConfig
import org.yapyap.backend.crypto.LocalIdentityRecord

class DefaultIdentityPublicKeyRepository(
    private val database: YapYapDatabase,
    private val config: IdentityKeyServiceConfig = IdentityKeyServiceConfig(),
) : IdentityPublicKeyRepository {
    override fun ensureAccountExists(accountId: String) {
        val queries = database.identityQueriesQueries
        val account = queries.selectAccountByPubKey(accountId).executeAsOneOrNull()
        if (account == null) {
            queries.putAccount(
                account_pub_key = accountId,
                is_admin = false,
                status = AccountStatus.ACTIVE,
                display_name = accountId,
            )
        }
    }

    override fun ensureDeviceExists(address: org.yapyap.backend.protocol.DeviceAddress) {
        val queries = database.identityQueriesQueries
        val existingDevice = queries.selectDeviceById(address.deviceId).executeAsOneOrNull()
        if (existingDevice != null) return

        queries.putDevice(
            device_id = address.deviceId,
            account_pub_key = address.accountId,
            device_type = config.defaultDeviceType,
            onion_address = config.defaultOnionAddress,
            onion_port = config.defaultOnionPort,
            signing_pub_key = ByteArray(0),
            signing_key_id = "",
            signing_key_version = 0L,
            encryption_pub_key = ByteArray(0),
            encryption_key_id = "",
            encryption_key_version = 0L,
            push_token = config.defaultPushToken,
            ping_attempts = config.defaultPingAttempts,
            ping_successes = config.defaultPingSuccesses,
            last_seen_timestamp = config.defaultLastSeenTimestamp,
        )
    }

    override fun upsertLocalIdentity(identity: LocalIdentityRecord) {
        ensureAccountExists(identity.address.accountId)
        ensureDeviceExists(identity.address)

        database.identityQueriesQueries.updateDeviceIdentityKeys(
            signing_pub_key = identity.signing.publicKey,
            signing_key_id = identity.signing.keyId,
            signing_key_version = identity.signing.keyVersion,
            encryption_pub_key = identity.encryption.publicKey,
            encryption_key_id = identity.encryption.keyId,
            encryption_key_version = identity.encryption.keyVersion,
            device_id = identity.address.deviceId,
        )
    }

    override fun resolveDeviceKey(deviceId: String, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? {
        val device = database.identityQueriesQueries.selectDeviceById(deviceId).executeAsOneOrNull() ?: return null
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
