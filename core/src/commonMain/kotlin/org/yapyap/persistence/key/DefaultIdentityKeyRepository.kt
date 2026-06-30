package org.yapyap.persistence.key

import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.crypto.identity.SignedPreKeyRecord
import org.yapyap.crypto.identity.IdentityKeyServiceConfig
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

class DefaultIdentityKeyRepository(
    private val database: YapYapDatabase,
    private val config: IdentityKeyServiceConfig = IdentityKeyServiceConfig(),
    private val logger: AppLogger = NoopAppLogger,
) : IdentityKeyRepository {

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

    override fun getDeviceRecord(deviceId: PeerId): DeviceIdentityRecord? {
        val queries = database.identityQueries
        val device = queries.selectDeviceById(deviceId.id).executeAsOneOrNull()

        return if (device == null) {
            logger.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_NOT_FOUND,
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
                ),
                signedPreKey = getActiveSignedPreKeyForDevice(deviceId),
                keySignature = device.key_signature,
            )
        }
    }

    override fun getLocalDeviceRecord(): DeviceIdentityRecord? {
        val queries = database.identityQueries
        val device = queries.selectLocalDevice().executeAsOneOrNull()

        return if (device == null) {
            logger.warn(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_NOT_FOUND,
                message = "Local device identity record not found",
            )
            null
        } else {
            logger.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_FOUND,
                message = "Local device identity record found",
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
                ),
                signedPreKey = getActiveSignedPreKeyForDevice(PeerId(device.device_id)),
                keySignature = device.key_signature,
            )
        }
    }

    override fun insertLocalDevice(
        accountId: AccountId,
        identity: DeviceIdentityRecord,
    ) {
        val queries = database.identityQueries
        database.transaction {
            queries.putDevice(
                device_id = identity.deviceId.id,
                is_local_device = true,
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
                key_signature = identity.keySignature,
                current_signed_prekey_id = identity.signedPreKey?.keyId,
                push_token = config.defaultPushToken,
                ping_attempts = config.defaultPingAttempts,
                ping_successes = config.defaultPingSuccesses,
                last_seen_timestamp = config.defaultLastSeenTimestamp,
            )
            identity.signedPreKey?.let { spk ->
                persistSignedPreKey(
                    spk = spk,
                    activateOnDevice = true,
                )
            }
        }
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

            IdentityKeyPurpose.SIGNED_PREKEY -> {
                val active = getActiveSignedPreKeyForDevice(deviceId) ?: return null
                IdentityPublicKeyRecord(
                    keyId = active.keyId,
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.SIGNED_PREKEY,
                    publicKey = active.publicKey,
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

    override fun insertPeerAccount(
        identity: AccountIdentityRecord,
        admin: Boolean,
        status: AccountStatus,
        displayName: String
    ) {
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

    override fun insertPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentityRecord,
        torEndpoint: TorEndpoint
    ) {
        val queries = database.identityQueries
        database.transaction {
            queries.putDevice(
                device_id = identity.deviceId.id,
                is_local_device = false,
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
                key_signature = identity.keySignature,
                current_signed_prekey_id = null,
                push_token = config.defaultPushToken,
                ping_attempts = config.defaultPingAttempts,
                ping_successes = config.defaultPingSuccesses,
                last_seen_timestamp = config.defaultLastSeenTimestamp,
            )
            identity.signedPreKey?.let { spk ->
                persistSignedPreKey(
                    spk = spk,
                    activateOnDevice = true,
                )
            }
        }
    }

    override fun getSignedPreKey(spkId: String): SignedPreKeyRecord? =
        database.identityQueries.selectSignedPreKeyById(spkId).executeAsOneOrNull().let {
            if (it == null) null else
                SignedPreKeyRecord(
                    keyId = it.spk_id,
                    publicKey = it.public_key,
                    signature = it.signature,
                    privateKey = null,
                    deviceId = PeerId(it.device_id),
                    isActive = it.is_active,
                    createdAtEpochSeconds = it.created_at_epoch_seconds,
                )
        }

    override fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKeyRecord? =
        database.identityQueries.selectActiveSignedPreKeyForDevice(deviceId.id).executeAsOneOrNull().let {
            if (it == null) null else
                SignedPreKeyRecord(
                    keyId = it.spk_id,
                    publicKey = it.public_key,
                    signature = it.signature,
                    privateKey = null,
                    deviceId = PeerId(it.device_id),
                    isActive = it.is_active,
                    createdAtEpochSeconds = it.created_at_epoch_seconds,
                )
        }

    override fun insertSignedPreKey(spk: SignedPreKeyRecord) {
        database.identityQueries.insertSignedPreKey(
            spk_id = spk.keyId,
            device_id = spk.deviceId.id,
            public_key = spk.publicKey,
            signature = spk.signature,
            is_active = spk.isActive,
            created_at_epoch_seconds = spk.createdAtEpochSeconds ?: 0L,
        )

    }

    override fun upsertDeviceSignedPreKey(
        spk: SignedPreKeyRecord,
    ) {
        database.transaction {
            database.identityQueries.deactivateSignedPreKeysForDevice(spk.deviceId.id)
            persistSignedPreKey(
                spk = spk,
                activateOnDevice = true,
            )
        }
        logger.info(
            component = LogComponent.DATABASE,
            event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
            message = "Updated device signed prekey",
            fields = mapOf("deviceId" to spk.deviceId, "signedPreKeyId" to spk.keyId),
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

    private fun persistSignedPreKey(spk: SignedPreKeyRecord, activateOnDevice: Boolean) {
        database.identityQueries.insertSignedPreKey(
            spk_id = spk.keyId,
            device_id = spk.deviceId.id,
            public_key = spk.publicKey,
            signature = spk.signature,
            is_active = spk.isActive,
            created_at_epoch_seconds = spk.createdAtEpochSeconds!!,
        )
        if (activateOnDevice && spk.isActive) {
            database.identityQueries.updateDeviceCurrentSignedPreKey(
                current_signed_prekey_id = spk.keyId,
                device_id = spk.deviceId.id,
            )
        }
    }
}
