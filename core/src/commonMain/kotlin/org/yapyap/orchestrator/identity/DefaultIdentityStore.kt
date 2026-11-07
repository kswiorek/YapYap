package org.yapyap.orchestrator.identity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.keys.SignedPreKeyId
import org.yapyap.crypto.keys.SigningPublicKey
import org.yapyap.persistence.Accounts
import org.yapyap.persistence.Devices
import org.yapyap.persistence.Signed_prekeys
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.*
import kotlin.time.Instant

internal class DefaultIdentityStore(
    private val database: YapYapDatabase,
    private val deviceType: DeviceType,
    private val defaults: DeviceRecordDefaults = DeviceRecordDefaults(),
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : IdentityStore {

    override suspend fun getAccountIdentity(accountId: AccountId): AccountIdentity? =
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            val account = queries.selectAccountById(accountId).executeAsOneOrNull()

            if (account == null) {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_MISSING,
                    message = "Account identity record not found",
                    fields = mapOf("accountId" to accountId, "found" to false),
                )
                null
            } else {
                account.toIdentity()
            }
        }

    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        withContext(dbDispatcher) {
            database.identityQueries.selectAccountStatusById(accountId).executeAsOneOrNull()
        }

    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? =
        withContext(dbDispatcher) {
            database.identityQueries.selectDeviceStatusById(deviceId).executeAsOneOrNull()
        }

    override suspend fun upsertChainAccount(
        accountId: AccountId,
        accountSigningPublicKey: SigningPublicKey?,
        role: AccountRole,
        status: IdentityStatus,
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
                    role = role,
                    status = status,
                    display_name = displayName,
                    provisional = false,
                )
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
                message = "Committed chain-derived account identity record",
                fields = mapOf("accountId" to accountId, "role" to role, "status" to status),
            )
        }
    }

    override suspend fun upsertChainDevice(
        deviceId: PeerId,
        accountId: AccountId,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        signingPublicKey: SigningPublicKey,
        encryptionPublicKey: org.yapyap.crypto.keys.EncryptionPublicKey,
        status: IdentityStatus,
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            var reboundFrom: AccountId? = null
            database.transaction {
                val existing = queries.selectDeviceById(deviceId).executeAsOneOrNull()
                queries.putDevice(
                    device_id = deviceId,
                    is_local_device = existing?.is_local_device ?: false,
                    account_id = accountId,
                    device_type = deviceType,
                    onion_address = torEndpoint.onionAddress,
                    onion_port = torEndpoint.port.toLong(),
                    signing_pub_key = signingPublicKey,
                    encryption_pub_key = encryptionPublicKey,
                    status = status,
                    current_signed_prekey_id = existing?.current_signed_prekey_id,
                    provisional = false,
                )
                if (existing?.is_local_device == true) {
                    reboundFrom = rebindLocalAccountLocked(accountId)
                }
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Committed chain-derived device identity record",
                fields = mapOf("deviceId" to deviceId, "accountId" to accountId, "status" to status),
            )
            reboundFrom?.let { previous ->
                AppLog.info(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
                    message = "Local device confirmed under another account; local account rebound",
                    fields = mapOf("from" to previous, "to" to accountId),
                )
            }
        }
    }

    override suspend fun rebindLocalAccountToLocalDevice(): AccountId? =
        withContext(dbDispatcher) {
            val previous = database.transactionWithResult {
                val device = database.identityQueries.selectLocalDevice().executeAsOneOrNull()
                if (device == null || device.provisional) null else rebindLocalAccountLocked(device.account_id)
            }
            if (previous != null) {
                AppLog.info(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
                    message = "Local account rebound to the confirmed local device's account",
                    fields = mapOf("from" to previous),
                )
            }
            previous
        }

    /**
     * The chain is authoritative about which account our device belongs to: once it confirms
     * the local device under [accountId], that account is the local one. A device added to an
     * existing account starts on a key-less stand-in row (its account is only known from the
     * sponsor's AddDevice), which is replaced here: memberships carry over and the stand-in
     * row is dropped once nothing references it. Returns the previous local account id when it
     * changed. Must run inside a transaction, after [accountId]'s row is committed.
     */
    private fun rebindLocalAccountLocked(accountId: AccountId): AccountId? {
        val identity = database.identityQueries
        val previous = identity.selectLocalAccount().executeAsOneOrNull()?.account_id
        if (previous == accountId) return null
        if (identity.selectAccountById(accountId).executeAsOneOrNull() == null) return null
        identity.setLocalAccount(accountId)
        if (previous != null) {
            val rooms = database.roomQueries
            rooms.copyRoomMembersToAccount(toAccountId = accountId, fromAccountId = previous)
            rooms.deleteRoomMembersOfAccount(previous)
            identity.deleteAccountIfUnreferenced(previous)
        }
        return previous
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
                    role = AccountRole.MEMBER,
                    status = IdentityStatus.BANNED,
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
                    encryption_pub_key = existing.encryption_pub_key,
                    status = IdentityStatus.BANNED,
                    current_signed_prekey_id = existing.current_signed_prekey_id,
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

    override suspend fun getDeviceIdentity(deviceId: PeerId): DeviceIdentity? =
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
                device.toIdentity()
            }
        }

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity? =
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
                device.toIdentity()
            }
        }

    override suspend fun getLocalAccountIdentity(): AccountIdentity? =
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            val account = queries.selectLocalAccount().executeAsOneOrNull()

            if (account == null) {
                AppLog.warn(
                    component = LogComponent.DATABASE,
                    event = LogEvent.IDENTITY_ACCOUNT_RECORD_MISSING,
                    message = "Account local identity record not found",
                )
                null
            } else {
                account.toIdentity()
            }
        }

    override suspend fun insertLocalDevice(
        accountId: AccountId,
        identity: DeviceIdentity,
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
                    signing_pub_key = identity.signingKey,
                    encryption_pub_key = identity.encryptionKey,
                    status = IdentityStatus.ACTIVE,
                    current_signed_prekey_id = identity.signedPreKey?.id,
                    provisional = provisional,
                )
                identity.signedPreKey?.let { spk -> persistSignedPreKey(spk, createdAt = Instant.DISTANT_PAST) }
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Inserted/updated local device identity record",
                fields = mapOf("deviceId" to identity.deviceId, "accountId" to accountId),
            )
        }
    }

    override suspend fun insertLocalAccount(
        identity: AccountIdentity,
        role: AccountRole,
        provisional: Boolean,
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            queries.putAccount(
                account_id = identity.accountId,
                account_pub_key = identity.signingKey,
                is_local_account = true,
                role = role,
                status = IdentityStatus.ACTIVE,
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
            database.identityQueries.selectLocalAccountRole().executeAsOneOrNull()?.isAdmin ?: false
        }

    override suspend fun isLocalAccountOwner(): Boolean =
        withContext(dbDispatcher) {
            database.identityQueries.selectLocalAccountRole().executeAsOneOrNull() == AccountRole.OWNER
        }

    override suspend fun isAccountAdmin(accountId: AccountId): Boolean =
        withContext(dbDispatcher) {
            database.identityQueries.selectAccountRoleById(accountId).executeAsOneOrNull()?.isAdmin ?: false
        }

    override suspend fun isAccountOwner(accountId: AccountId): Boolean =
        withContext(dbDispatcher) {
            database.identityQueries.selectAccountRoleById(accountId).executeAsOneOrNull() == AccountRole.OWNER
        }

    override suspend fun getOwnerAccountId(): AccountId? =
        withContext(dbDispatcher) {
            database.identityQueries.selectOwnerAccountId().executeAsOneOrNull()
        }

    override suspend fun isDeviceProvisional(deviceId: PeerId): Boolean =
        withContext(dbDispatcher) {
            database.identityQueries.selectDeviceProvisionalById(deviceId).executeAsOneOrNull() ?: true
        }

    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? =
        withContext(dbDispatcher) {
            val device = database.identityQueries.selectDeviceById(deviceId).executeAsOneOrNull()
                ?: return@withContext null
            TorEndpoint(
                onionAddress = device.onion_address,
                port = device.onion_port.toInt(),
            )
        }

    override suspend fun insertPeerAccount(
        identity: AccountIdentity,
        role: AccountRole,
        status: IdentityStatus,
        displayName: String,
        provisional: Boolean,
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries

            queries.putAccount(
                account_id = identity.accountId,
                account_pub_key = identity.signingKey,
                is_local_account = false,
                role = role,
                status = status,
                display_name = displayName,
                provisional = provisional,
            )
        }
    }

    override suspend fun seedProvisionalPeerAccount(
        identity: AccountIdentity,
        role: AccountRole,
        displayName: String,
    ) {
        withContext(dbDispatcher) {
            val queries = database.identityQueries
            database.transaction {
                // Insert-only: never clobber an existing row with intro data.
                if (queries.selectAccountById(identity.accountId).executeAsOneOrNull() != null) return@transaction
                queries.putAccount(
                    account_id = identity.accountId,
                    account_pub_key = identity.signingKey,
                    is_local_account = false,
                    role = role,
                    status = IdentityStatus.ACTIVE,
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
        identity: DeviceIdentity,
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
                    signing_pub_key = identity.signingKey,
                    encryption_pub_key = identity.encryptionKey,
                    status = IdentityStatus.ACTIVE,
                    current_signed_prekey_id = null,
                    provisional = provisional,
                )
                identity.signedPreKey?.let { spk -> persistSignedPreKey(spk, createdAt = Instant.DISTANT_PAST) }
            }
        }
    }

    override suspend fun seedProvisionalPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentity,
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
                    signing_pub_key = identity.signingKey,
                    encryption_pub_key = identity.encryptionKey,
                    status = IdentityStatus.ACTIVE,
                    current_signed_prekey_id = null,
                    provisional = true,
                )
                identity.signedPreKey?.let { spk -> persistSignedPreKey(spk, createdAt = Instant.DISTANT_PAST) }
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Seeded provisional peer device identity record",
                fields = mapOf("deviceId" to identity.deviceId, "accountId" to accountId),
            )
        }
    }

    override suspend fun getSignedPreKey(id: SignedPreKeyId): SignedPreKey? =
        withContext(dbDispatcher) {
            database.identityQueries.selectSignedPreKeyById(id).executeAsOneOrNull()?.toSignedPreKey()
        }

    override suspend fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKey? =
        withContext(dbDispatcher) {
            database.identityQueries.selectActiveSignedPreKeyForDevice(deviceId).executeAsOneOrNull()?.toSignedPreKey()
        }

    override suspend fun getSignedPreKeyIds(deviceId: PeerId): List<SignedPreKeyId> =
        withContext(dbDispatcher) {
            database.identityQueries.selectSignedPreKeyIdsForDevice(deviceId).executeAsList()
        }

    override suspend fun insertSignedPreKey(spk: SignedPreKey, createdAt: Instant) {
        withContext(dbDispatcher) {
            database.identityQueries.insertSignedPreKey(
                spk_id = spk.id,
                device_id = spk.deviceId,
                public_key = spk.publicKey,
                signature = spk.signature,
                is_active = true,
                created_at_epoch_seconds = createdAt,
            )
        }
    }

    override suspend fun upsertDeviceSignedPreKey(spk: SignedPreKey) {
        withContext(dbDispatcher) {
            database.transaction {
                database.identityQueries.deactivateSignedPreKeysForDevice(spk.deviceId)
                persistSignedPreKey(spk, createdAt = Instant.DISTANT_PAST)
            }
            AppLog.info(
                component = LogComponent.DATABASE,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Updated device signed prekey",
                fields = mapOf("deviceId" to spk.deviceId, "signedPreKeyId" to spk.id),
            )
        }
    }

    override suspend fun getAllDeviceIds(): List<PeerId> =
        withContext(dbDispatcher) {
            database.identityQueries.selectAllDeviceIds().executeAsList()
        }

    override suspend fun getAllActiveDeviceIds(): List<PeerId> =
        withContext(dbDispatcher) {
            database.identityQueries.selectActiveDeviceIds().executeAsList()
        }

    override suspend fun getAllAccounts(): List<Account> =
        withContext(dbDispatcher) {
            database.identityQueries.selectAllAccounts().executeAsList().map {
                Account(
                    identity = it.toIdentity(),
                    role = it.role,
                    status = it.status,
                    isLocal = it.is_local_account,
                )
            }
        }

    override suspend fun getAllDevices(): List<Device> =
        withContext(dbDispatcher) {
            database.identityQueries.selectAllDevices().executeAsList().map {
                Device(
                    identity = it.toIdentity(),
                    torEndpoint = TorEndpoint(it.onion_address, it.onion_port.toInt()),
                    accountId = it.account_id,
                    deviceType = it.device_type,
                    status = it.status,
                    isLocal = it.is_local_device,
                    provisional = it.provisional,
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

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        withContext(dbDispatcher) {
            database.identityQueries
                .selectAccountIdByDeviceId(deviceId)
                .executeAsOneOrNull()
        }


    /** Stores [spk] as the device's active prekey. Must run on [dbDispatcher]. */
    private fun persistSignedPreKey(spk: SignedPreKey, createdAt: Instant) {
        database.identityQueries.insertSignedPreKey(
            spk_id = spk.id,
            device_id = spk.deviceId,
            public_key = spk.publicKey,
            signature = spk.signature,
            is_active = true,
            // Chain-derived SPKs carry no timestamp (AddDevice has none). Nothing reads peer
            // SPK age; local rotation is clock-driven on its own rows.
            created_at_epoch_seconds = createdAt,
        )
        database.identityQueries.updateDeviceCurrentSignedPreKey(
            current_signed_prekey_id = spk.id,
            device_id = spk.deviceId,
        )
    }

    private fun Accounts.toIdentity(): AccountIdentity =
        AccountIdentity(accountId = account_id, displayName = display_name, signingKey = account_pub_key)

    /** Must run on [dbDispatcher]. */
    private fun Devices.toIdentity(): DeviceIdentity =
        DeviceIdentity(
            deviceId = device_id,
            signingKey = signing_pub_key,
            encryptionKey = encryption_pub_key,
            signedPreKey = database.identityQueries.selectActiveSignedPreKeyForDevice(device_id)
                .executeAsOneOrNull()?.toSignedPreKey(),
        )

    private fun Signed_prekeys.toSignedPreKey(): SignedPreKey =
        SignedPreKey(id = spk_id, deviceId = device_id, publicKey = public_key, signature = signature)
}
