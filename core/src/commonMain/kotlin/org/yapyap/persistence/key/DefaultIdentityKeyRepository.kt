package org.yapyap.persistence.key

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.*
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

class DefaultIdentityKeyRepository(
    private val database: YapYapDatabase,
    private val deviceType: DeviceType,
    private val defaults: DeviceRecordDefaults = DeviceRecordDefaults(),
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : IdentityKeyRepository {

    override suspend fun getAccountRecord(accountId: AccountId): AccountIdentityRecord? =
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            val account = queries.selectAccountById(accountId).executeAsOneOrNull()

            (if (account == null) {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_MISSING,
                    message = "Account identity record not found",
                    fields = mapOf("accountId" to accountId, "found" to false),
                )
                null
            } else if (account.pub_key_id == null || account.pub_key_version == null || account.account_pub_key == null) {
                AccountIdentityRecord(
                    accountId = account.account_id,
                    displayName = account.display_name,
                    key = null,
                )
            } else {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_FOUND,
                    message = "Account identity record found",
                    fields = mapOf("accountId" to accountId, "found" to true),
                )
                AccountIdentityRecord(
                    accountId = account.account_id,
                    displayName = account.display_name,
                    key = IdentityPublicKeyRecord(
                        keyId = account.pub_key_id,
                        keyVersion = account.pub_key_version,
                        purpose = IdentityKeyPurpose.SIGNING,
                        publicKey = account.account_pub_key,
                    )
                )
            })
        }

    override suspend fun getDeviceRecord(deviceId: PeerId): DeviceIdentityRecord? =
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            val device = queries.selectDeviceById(deviceId).executeAsOneOrNull()

            if (device == null) {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_DEVICE_RECORD_NOT_FOUND,
                    message = "Device identity record not found",
                    fields = mapOf("deviceId" to deviceId, "found" to false),
                )
                null
            } else {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_DEVICE_RECORD_FOUND,
                    message = "Device identity record found",
                    fields = mapOf("deviceId" to deviceId, "found" to true),
                )
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
                    ),
                    signedPreKey = getActiveSignedPreKeyForDevice(deviceId),
                    keySignature = device.key_signature,
                )
            }
        }

    override suspend fun getLocalDeviceRecord(): DeviceIdentityRecord? =
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            val device = queries.selectLocalDevice().executeAsOneOrNull()

            if (device == null) {
                AppLog.warn(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_DEVICE_RECORD_NOT_FOUND,
                    message = "Local device identity record not found",
                )
                null
            } else {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_DEVICE_RECORD_FOUND,
                    message = "Local device identity record found",
                )
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
                    ),
                    signedPreKey = getActiveSignedPreKeyForDevice(device.device_id),
                    keySignature = device.key_signature,
                )
            }
        }

    override suspend fun getLocalAccountRecord(): AccountIdentityRecord? =
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            val account = queries.selectLocalAccount().executeAsOneOrNull()

            (if (account == null) {
                AppLog.warn(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_MISSING,
                    message = "Account local identity record not found",
                )
                null
            } else if (account.pub_key_id == null || account.pub_key_version == null || account.account_pub_key == null) {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_FOUND,
                    message = "Account local identity record found",
                )
                AccountIdentityRecord(
                    accountId = account.account_id,
                    displayName = account.display_name,
                    key = null,
                )
            } else {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_FOUND,
                    message = "Account local identity record found",
                )
                AccountIdentityRecord(
                    accountId = account.account_id,
                    displayName = account.display_name,
                    key = IdentityPublicKeyRecord(
                        keyId = account.pub_key_id,
                        keyVersion = account.pub_key_version,
                        purpose = IdentityKeyPurpose.SIGNING,
                        publicKey = account.account_pub_key,
                    )
                )
            })
        }

    override suspend fun insertLocalDevice(
        accountId: AccountId,
        identity: DeviceIdentityRecord,
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                queries.putDevice(
                    device_id = identity.deviceId,
                    is_local_device = true,
                    account_id = accountId,
                    device_type = deviceType,
                    onion_address = defaults.onionAddress,
                    onion_port = defaults.onionPort,
                    signing_pub_key = identity.signing.publicKey,
                    signing_key_id = identity.signing.keyId,
                    signing_key_version = identity.signing.keyVersion,
                    encryption_pub_key = identity.encryption.publicKey,
                    encryption_key_id = identity.encryption.keyId,
                    encryption_key_version = identity.encryption.keyVersion,
                    key_signature = identity.keySignature,
                    current_signed_prekey_id = identity.signedPreKey?.keyId,
                    push_token = defaults.pushToken,
                    ping_attempts = defaults.pingAttempts,
                    ping_successes = defaults.pingSuccesses,
                    last_seen_timestamp = defaults.lastSeenTimestamp,
                )
                identity.signedPreKey?.let { spk ->
                    persistSignedPreKey(
                        spk = spk,
                        activateOnDevice = true,
                    )
                }
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Inserted/updated local device identity record",
                fields = mapOf("deviceId" to identity.deviceId, "accountId" to accountId),
            )
        }
    }

    override suspend fun insertLocalAccount(identity: AccountIdentityRecord) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            queries.putAccount(
                account_id = identity.accountId,
                account_pub_key = identity.key?.publicKey,
                is_local_account = true,
                pub_key_version = identity.key?.keyVersion,
                pub_key_id = identity.key?.keyId,
                is_admin = false,
                status = AccountStatus.ACTIVE,
                display_name = identity.displayName,
            )
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
                message = "Inserted/updated local account identity record",
                fields = mapOf("accountId" to identity.accountId, "displayName" to identity.displayName),
            )
        }
    }

    override suspend fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? =
        withContext(dbDispatcher) {
            val device = database.identityQueries.selectDeviceById(deviceId).executeAsOneOrNull() ?: return@withContext null
            when (purpose) {
                IdentityKeyPurpose.SIGNING -> {
                    if (device.signing_key_id.isBlank() || device.signing_pub_key.isEmpty()) return@withContext null
                    IdentityPublicKeyRecord(
                        keyId = device.signing_key_id,
                        keyVersion = device.signing_key_version,
                        purpose = IdentityKeyPurpose.SIGNING,
                        publicKey = device.signing_pub_key,
                    )
                }

                IdentityKeyPurpose.ENCRYPTION -> {
                    if (device.encryption_key_id.isBlank() || device.encryption_pub_key.isEmpty()) return@withContext null
                    IdentityPublicKeyRecord(
                        keyId = device.encryption_key_id,
                        keyVersion = device.encryption_key_version,
                        purpose = IdentityKeyPurpose.ENCRYPTION,
                        publicKey = device.encryption_pub_key,
                    )
                }

                IdentityKeyPurpose.SIGNED_PREKEY -> {
                    val active = getActiveSignedPreKeyForDevice(deviceId) ?: return@withContext null
                    IdentityPublicKeyRecord(
                        keyId = active.keyId,
                        keyVersion = 0,
                        purpose = IdentityKeyPurpose.SIGNED_PREKEY,
                        publicKey = active.publicKey,
                    )
                }
            }
        }

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? =
        withContext(dbDispatcher) {
            val device = database.identityQueries.selectDeviceById(deviceId).executeAsOneOrNull()
                ?: return@withContext null
            TorEndpoint(
                onionAddress = device.onion_address,
                port = device.onion_port.toInt(),
            )
        }

    override suspend fun insertPeerAccount(
        identity: AccountIdentityRecord,
        admin: Boolean,
        status: AccountStatus,
        displayName: String
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries

            queries.putAccount(
                account_id = identity.accountId,
                account_pub_key = identity.key?.publicKey,
                is_local_account = false,
                pub_key_version = identity.key?.keyVersion,
                pub_key_id = identity.key?.keyId,
                is_admin = admin,
                status = status,
                display_name = displayName
            )
        }
    }

    override suspend fun insertPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentityRecord,
        torEndpoint: TorEndpoint
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                queries.putDevice(
                    device_id = identity.deviceId,
                    is_local_device = false,
                    account_id = accountId,
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
                    push_token = defaults.pushToken,
                    ping_attempts = defaults.pingAttempts,
                    ping_successes = defaults.pingSuccesses,
                    last_seen_timestamp = defaults.lastSeenTimestamp,
                )
                identity.signedPreKey?.let { spk ->
                    persistSignedPreKey(
                        spk = spk,
                        activateOnDevice = true,
                    )
                }
            }
        }
    }

    override suspend fun getSignedPreKey(spkId: String): SignedPreKeyRecord? =
        withContext(dbDispatcher) {
            database.identityQueries.selectSignedPreKeyById(spkId).executeAsOneOrNull().let {
                if (it == null) null else
                    SignedPreKeyRecord(
                        keyId = it.spk_id,
                        publicKey = it.public_key,
                        signature = it.signature,
                        privateKey = null,
                        deviceId = it.device_id,
                        isActive = it.is_active,
                        createdAtEpochSeconds = it.created_at_epoch_seconds,
                    )
            }
        }

    override suspend fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKeyRecord? =
        withContext(dbDispatcher) {
            database.identityQueries.selectActiveSignedPreKeyForDevice(deviceId).executeAsOneOrNull().let {
                if (it == null) null else
                    SignedPreKeyRecord(
                        keyId = it.spk_id,
                        publicKey = it.public_key,
                        signature = it.signature,
                        privateKey = null,
                        deviceId = it.device_id,
                        isActive = it.is_active,
                        createdAtEpochSeconds = it.created_at_epoch_seconds,
                    )
            }
        }

    override suspend fun insertSignedPreKey(spk: SignedPreKeyRecord) {
        withContext(dbDispatcher) {
            database.identityQueries.insertSignedPreKey(
                spk_id = spk.keyId,
                device_id = spk.deviceId,
                public_key = spk.publicKey,
                signature = spk.signature,
                is_active = spk.isActive,
                created_at_epoch_seconds = spk.createdAtEpochSeconds ?: 0L,
            )
        }
    }

    override suspend fun upsertDeviceSignedPreKey(
        spk: SignedPreKeyRecord,
    ) {
        withContext(dbDispatcher) {
            database.transaction {
                database.identityQueries.deactivateSignedPreKeysForDevice(spk.deviceId)
                persistSignedPreKey(
                    spk = spk,
                    activateOnDevice = true,
                )
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Updated device signed prekey",
                fields = mapOf("deviceId" to spk.deviceId, "signedPreKeyId" to spk.keyId),
            )
        }
    }

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            queries.selectDevicesByAccountId(accountId).executeAsList().map { it.device_id }
        }

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
        withContext(dbDispatcher) {
            database.identityQueries
                .selectDevicesByAccountIds(accountIds)
                .executeAsList()
        }

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            queries.updateDeviceTorEndpoint(
                device_id = deviceId,
                onion_address = torEndpoint.onionAddress,
                onion_port = torEndpoint.port.toLong(),
            )
        }
    }

    private fun persistSignedPreKey(spk: SignedPreKeyRecord, activateOnDevice: Boolean) {
        database.identityQueries.insertSignedPreKey(
            spk_id = spk.keyId,
            device_id = spk.deviceId,
            public_key = spk.publicKey,
            signature = spk.signature,
            is_active = spk.isActive,
            created_at_epoch_seconds = spk.createdAtEpochSeconds!!,
        )
        if (activateOnDevice && spk.isActive) {
            database.identityQueries.updateDeviceCurrentSignedPreKey(
                current_signed_prekey_id = spk.keyId,
                device_id = spk.deviceId,
            )
        }
    }
}
