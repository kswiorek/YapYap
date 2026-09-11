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
import kotlin.time.Instant

class DefaultIdentityKeyRepository(
    private val database: YapYapDatabase,
    private val deviceType: DeviceType,
    private val defaults: DeviceRecordDefaults = DeviceRecordDefaults(),
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : IdentityKeyRepository {

    private companion object {
        /** Locally-minted key id/version for fold-derived rows (the chain carries raw keys only). */
        const val CHAIN_KEY_ID = "chain"
        const val CHAIN_KEY_VERSION = 0L
    }

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

    override suspend fun getAccountStatus(accountId: AccountId): AccountStatus? =
        withContext(dbDispatcher) {
            database.identityQueries.selectAccountStatusById(accountId).executeAsOneOrNull()
        }

    override suspend fun getDeviceStatus(deviceId: PeerId): AccountStatus? =
        withContext(dbDispatcher) {
            database.identityQueries.selectDeviceStatusById(deviceId).executeAsOneOrNull()
        }

    override suspend fun upsertChainAccount(
        accountId: AccountId,
        accountSigningPublicKey: ByteArray?,
        isAdmin: Boolean,
        status: AccountStatus,
        displayName: String,
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                val existing = queries.selectAccountById(accountId).executeAsOneOrNull()
                queries.putAccount(
                    account_id = accountId,
                    account_pub_key = accountSigningPublicKey,
                    is_local_account = existing?.is_local_account ?: false,
                    pub_key_version = existing?.pub_key_version ?: CHAIN_KEY_VERSION,
                    pub_key_id = existing?.pub_key_id ?: CHAIN_KEY_ID,
                    is_admin = isAdmin,
                    status = status,
                    display_name = displayName,
                    provisional = false,
                )
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
                message = "Committed chain-derived account identity record",
                fields = mapOf("accountId" to accountId, "isAdmin" to isAdmin, "status" to status),
            )
        }
    }

    override suspend fun upsertChainDevice(
        deviceId: PeerId,
        accountId: AccountId,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        signingPublicKey: ByteArray,
        encryptionPublicKey: ByteArray,
        keySignature: ByteArray?,
        status: AccountStatus,
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                val existing = queries.selectDeviceById(deviceId).executeAsOneOrNull()
                // Fresh rows take the event endpoint; confirmed rows keep a live-updated onion
                // (Tor rotation has no chain event — the fold must not clobber it). Provisional
                // rows are fixed up to the chain values wholesale.
                val confirmed = existing != null && !existing.provisional
                queries.putDevice(
                    device_id = deviceId,
                    is_local_device = existing?.is_local_device ?: false,
                    account_id = accountId,
                    device_type = deviceType,
                    onion_address = if (confirmed) existing.onion_address else torEndpoint.onionAddress,
                    onion_port = if (confirmed) existing.onion_port else torEndpoint.port.toLong(),
                    signing_pub_key = signingPublicKey,
                    signing_key_id = existing?.signing_key_id ?: CHAIN_KEY_ID,
                    signing_key_version = existing?.signing_key_version ?: CHAIN_KEY_VERSION,
                    encryption_pub_key = encryptionPublicKey,
                    encryption_key_id = existing?.encryption_key_id ?: CHAIN_KEY_ID,
                    encryption_key_version = existing?.encryption_key_version ?: CHAIN_KEY_VERSION,
                    key_signature = keySignature,
                    status = status,
                    current_signed_prekey_id = existing?.current_signed_prekey_id,
                    push_token = existing?.push_token,
                    reliability_score = existing?.reliability_score ?: defaults.reliabilityScore,
                    last_seen_timestamp = existing?.last_seen_timestamp ?: defaults.lastSeenTimestamp,
                    provisional = false,
                )
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Committed chain-derived device identity record",
                fields = mapOf("deviceId" to deviceId, "accountId" to accountId, "status" to status),
            )
        }
    }

    override suspend fun tombstoneAccount(accountId: AccountId) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                val existing = queries.selectAccountById(accountId).executeAsOneOrNull() ?: return@transaction
                queries.putAccount(
                    account_id = existing.account_id,
                    account_pub_key = existing.account_pub_key,
                    is_local_account = existing.is_local_account,
                    pub_key_version = existing.pub_key_version,
                    pub_key_id = existing.pub_key_id,
                    is_admin = false,
                    status = AccountStatus.BANNED,
                    display_name = existing.display_name,
                    provisional = false,
                )
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
                message = "Tombstoned account identity record",
                fields = mapOf("accountId" to accountId),
            )
        }
    }

    override suspend fun tombstoneDevice(deviceId: PeerId) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                val existing = queries.selectDeviceById(deviceId).executeAsOneOrNull() ?: return@transaction
                queries.putDevice(
                    device_id = existing.device_id,
                    is_local_device = existing.is_local_device,
                    account_id = existing.account_id,
                    device_type = existing.device_type,
                    onion_address = existing.onion_address,
                    onion_port = existing.onion_port,
                    signing_pub_key = existing.signing_pub_key,
                    signing_key_id = existing.signing_key_id,
                    signing_key_version = existing.signing_key_version,
                    encryption_pub_key = existing.encryption_pub_key,
                    encryption_key_id = existing.encryption_key_id,
                    encryption_key_version = existing.encryption_key_version,
                    key_signature = existing.key_signature,
                    status = AccountStatus.BANNED,
                    current_signed_prekey_id = existing.current_signed_prekey_id,
                    push_token = existing.push_token,
                    reliability_score = existing.reliability_score,
                    last_seen_timestamp = existing.last_seen_timestamp,
                    provisional = false,
                )
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Tombstoned device identity record",
                fields = mapOf("deviceId" to deviceId),
            )
        }
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
        provisional: Boolean,
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
                    status = AccountStatus.ACTIVE,
                    current_signed_prekey_id = identity.signedPreKey?.keyId,
                    push_token = defaults.pushToken,
                    reliability_score = defaults.reliabilityScore,
                    last_seen_timestamp = defaults.lastSeenTimestamp,
                    provisional = provisional,
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

    override suspend fun insertLocalAccount(identity: AccountIdentityRecord, admin: Boolean, provisional: Boolean) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            queries.putAccount(
                account_id = identity.accountId,
                account_pub_key = identity.key?.publicKey,
                is_local_account = true,
                pub_key_version = identity.key?.keyVersion,
                pub_key_id = identity.key?.keyId,
                is_admin = admin,
                status = AccountStatus.ACTIVE,
                display_name = identity.displayName,
                provisional = provisional,
            )
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
                message = "Inserted/updated local account identity record",
                fields = mapOf("accountId" to identity.accountId, "displayName" to identity.displayName),
            )
        }
    }

    override suspend fun isLocalAccountAdmin(): Boolean =
        withContext(dbDispatcher) {
            database.identityQueries.selectLocalAccountAdmin().executeAsOneOrNull() ?: false
        }

    override suspend fun isAccountAdmin(accountId: AccountId): Boolean =
        withContext(dbDispatcher) {
            database.identityQueries.selectAccountAdminById(accountId).executeAsOneOrNull() ?: false
        }

    override suspend fun isDeviceProvisional(deviceId: PeerId): Boolean =
        withContext(dbDispatcher) {
            database.identityQueries.selectDeviceProvisionalById(deviceId).executeAsOneOrNull() ?: true
        }

    override suspend fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? =
        withContext(dbDispatcher) {
            val device =
                database.identityQueries.selectDeviceById(deviceId).executeAsOneOrNull() ?: return@withContext null
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

                IdentityKeyPurpose.BOOTSTRAP_SECRET -> {
                    // Not a device identity key — the one-time bootstrap secret lives in the
                    // KeyStore, never in the devices table.
                    return@withContext null
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
        displayName: String,
        provisional: Boolean,
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
                display_name = displayName,
                provisional = provisional,
            )
        }
    }

    override suspend fun seedProvisionalPeerAccount(
        identity: AccountIdentityRecord,
        admin: Boolean,
        displayName: String,
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                // Insert-only: never clobber an existing row with intro data.
                if (queries.selectAccountById(identity.accountId).executeAsOneOrNull() != null) return@transaction
                queries.putAccount(
                    account_id = identity.accountId,
                    account_pub_key = identity.key?.publicKey,
                    is_local_account = false,
                    pub_key_version = identity.key?.keyVersion,
                    pub_key_id = identity.key?.keyId,
                    is_admin = admin,
                    status = AccountStatus.ACTIVE,
                    display_name = displayName,
                    provisional = true,
                )
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
                message = "Seeded provisional peer account identity record",
                fields = mapOf("accountId" to identity.accountId),
            )
        }
    }

    override suspend fun insertPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentityRecord,
        torEndpoint: TorEndpoint,
        provisional: Boolean,
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
                    status = AccountStatus.ACTIVE,
                    current_signed_prekey_id = null,
                    push_token = defaults.pushToken,
                    reliability_score = defaults.reliabilityScore,
                    last_seen_timestamp = defaults.lastSeenTimestamp,
                    provisional = provisional,
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

    override suspend fun seedProvisionalPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentityRecord,
        torEndpoint: TorEndpoint
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                // Insert-only: never clobber an existing row with intro data.
                if (queries.selectDeviceById(identity.deviceId).executeAsOneOrNull() != null) return@transaction
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
                    status = AccountStatus.ACTIVE,
                    current_signed_prekey_id = null,
                    push_token = defaults.pushToken,
                    reliability_score = defaults.reliabilityScore,
                    last_seen_timestamp = defaults.lastSeenTimestamp,
                    provisional = true,
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
                message = "Seeded provisional peer device identity record",
                fields = mapOf("deviceId" to identity.deviceId, "accountId" to accountId),
            )
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
                        createdAt = it.created_at_epoch_seconds,
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
                        createdAt = it.created_at_epoch_seconds,
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
                created_at_epoch_seconds = spk.createdAt ?: Instant.DISTANT_PAST,
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

    override suspend fun getAllDeviceIds(): List<PeerId> =
        withContext(dbDispatcher) {
            database.identityQueries.selectAllDeviceIds().executeAsList()
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

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        withContext(dbDispatcher) {
            database.identityQueries
                .selectAccountIdByDeviceId(deviceId)
                .executeAsOneOrNull()
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
            created_at_epoch_seconds = spk.createdAt!!,
        )
        if (activateOnDevice && spk.isActive) {
            database.identityQueries.updateDeviceCurrentSignedPreKey(
                current_signed_prekey_id = spk.keyId,
                device_id = spk.deviceId,
            )
        }
    }
}
