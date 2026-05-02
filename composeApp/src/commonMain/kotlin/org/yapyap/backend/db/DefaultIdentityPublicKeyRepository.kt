package org.yapyap.backend.db

import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.crypto.IdentityKeyServiceConfig
import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

class DefaultIdentityPublicKeyRepository(
    private val database: YapYapDatabase,
    private val config: IdentityKeyServiceConfig = IdentityKeyServiceConfig(),
    private val logger: AppLogger = NoopAppLogger,
) : IdentityPublicKeyRepository {

    override fun getAccountPublicKey(accountId: AccountId): AccountIdentityRecord? {
        val queries = database.identityQueries
        val account = queries.selectAccountById(accountId.id).executeAsOneOrNull()

        return if (account == null) {
            logger.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_FOUND,
                message = "Account identity record not found",
                fields = mapOf("accountId" to accountId, "found" to false),
            )
            null
        } else {
            logger.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_FOUND,
                message = "Account identity record found",
                fields = mapOf("accountId" to accountId, "found" to true),
            )
            AccountIdentityRecord(
                accountId = AccountId(account.account_id),
                key = IdentityPublicKeyRecord(
                    keyId = account.pub_key_id,
                    keyVersion = account.pub_key_version,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = account.account_pub_key,
                )
            )
        }
    }

    override fun getDevicePublicKey(deviceId: PeerId): DeviceIdentityRecord? {
        val queries = database.identityQueries
        val device = queries.selectDeviceById(deviceId.id).executeAsOneOrNull()

        return if (device == null) {
            logger.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_FOUND,
                message = "Device identity record not found",
                fields = mapOf("deviceId" to deviceId, "found" to false),
            )
            null
        } else {
            logger.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_FOUND,
                message = "Device identity record found",
                fields = mapOf("deviceId" to deviceId, "found" to true),
            )
            DeviceIdentityRecord(
                deviceId = PeerId(device.device_id),
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

    override fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentityRecord) {
        val queries = database.identityQueries

        queries.putDevice(
            device_id = identity.deviceId.id,
            account_id = accountId.id,
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
        logger.info(
            component = LogComponent.DATABASE,
            event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
            message = "Inserted/updated local device identity record",
            fields = mapOf("deviceId" to identity.deviceId, "accountId" to accountId),
        )
    }

    override fun insertLocalAccount(displayName: String, identity: AccountIdentityRecord) {
        val queries = database.identityQueries

        queries.putAccount(
            account_id = identity.accountId.id,
            account_pub_key = identity.key.publicKey,
            pub_key_version = identity.key.keyVersion,
            pub_key_id = identity.key.keyId,
            is_admin = false,
            status = AccountStatus.ACTIVE,
            display_name = displayName,
        )
        logger.info(
            component = LogComponent.DATABASE,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Inserted/updated local account identity record",
            fields = mapOf("accountId" to identity.accountId, "displayName" to displayName),
        )
    }

    override fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? {
        val device = database.identityQueries.selectDeviceById(deviceId.id).executeAsOneOrNull() ?: return null
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

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint {
        val device = database.identityQueries.selectDeviceById(deviceId.id).executeAsOneOrNull()
            ?: error("Device identity record not found for deviceId: $deviceId")
        return TorEndpoint(
            onionAddress = device.onion_address,
            port = device.onion_port.toInt(),
        )
    }

    override fun insertPeerAccount(identity: AccountIdentityRecord, admin: Boolean, status: AccountStatus, displayName: String) {
        val queries = database.identityQueries

        queries.putAccount(
            account_id = identity.accountId.id,
            account_pub_key = identity.key.publicKey,
            pub_key_version = identity.key.keyVersion,
            pub_key_id = identity.key.keyId,
            is_admin = admin,
            status = status,
            display_name = displayName
        )
    }

    override fun insertPeerDevice(accountId: AccountId, deviceType: DeviceType, identity: DeviceIdentityRecord, torEndpoint: TorEndpoint) {
        val queries = database.identityQueries

        queries.putDevice(
            device_id = identity.deviceId.id,
            account_id = accountId.id,
            device_type = deviceType,
            onion_address = torEndpoint.onionAddress,
            onion_port = torEndpoint.port.toLong(),
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

    override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> {
        val queries = database.identityQueries
        return queries.selectDevicesByAccountId(accountId.id).executeAsList().map { PeerId(it.device_id) }
    }

    override fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        val queries = database.identityQueries
        queries.updateDeviceTorEndpoint(
            device_id = deviceId.id,
            onion_address = torEndpoint.onionAddress,
            onion_port = torEndpoint.port.toLong(),
        )
    }
}
