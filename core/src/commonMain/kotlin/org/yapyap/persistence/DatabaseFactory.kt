package org.yapyap.persistence

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.OneTimePreKeyId
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.protocol.identity.SigningPublicKey
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Single shared dispatcher that confines all SQLDelight/SQLite access to a single thread.
 *
 * All repositories in the persistence package share one SQLDelight connection (created in
 * [DatabaseFactory.createConnection]), and JDBC connections are not thread-safe. Serializing every
 * DB call onto one thread matches SQLite's single-writer model and avoids `SQLITE_BUSY`/corruption
 * when operations are issued concurrently from many coroutines.
 */
internal val databaseDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

internal interface DriverFactory {
    fun createDriver(): SqlDriver
}

internal class DatabaseFactory(
    private val driverFactory: DriverFactory,
    private val initializer: DatabaseInitializer = DatabaseInitializer(YapYapDatabase.Schema),
) {
    fun createConnection(): DatabaseConnection {
        val driver = driverFactory.createDriver()
        val created = initializer.initialize(driver)
        AppLog.info(
            component = LogComponent.DATABASE,
            event = LogEvent.DATABASE_INITIALIZED,
            message = "Database connection created",
        )

        return DatabaseConnection(
            database = YapYapDatabase.Companion(
                driver = driver,
                accountsAdapter = Accounts.Adapter(
                    statusAdapter = EnumColumnAdapter(),
                    roleAdapter = EnumColumnAdapter(),
                    account_idAdapter = AccountIdAdapter(),
                    account_pub_keyAdapter = SigningPublicKeyAdapter,
                ),
                devicesAdapter = Devices.Adapter(
                    device_typeAdapter = EnumColumnAdapter(),
                    device_idAdapter = PeerIdAdapter(),
                    account_idAdapter = AccountIdAdapter(),
                    statusAdapter = EnumColumnAdapter(),
                    signing_pub_keyAdapter = SigningPublicKeyAdapter,
                    encryption_pub_keyAdapter = EncryptionPublicKeyAdapter,
                    current_signed_prekey_idAdapter = SignedPreKeyIdAdapter,
                ),
                device_routesAdapter = Device_routes.Adapter(
                    device_idAdapter = PeerIdAdapter(),
                    last_seen_timestampAdapter = InstantEpochSecondsAdapter,
                ),
                dedupAdapter = Dedup.Adapter(
                    nack_reasonAdapter = EnumColumnAdapter(),
                    packet_idAdapter = UuidAdapter(),
                    received_atAdapter = InstantEpochSecondsAdapter,
                ),
                crypto_sessionsAdapter = Crypto_sessions.Adapter(
                    roleAdapter = EnumColumnAdapter(),
                    x3dh_modeAdapter = EnumColumnAdapter(),
                    statusAdapter = EnumColumnAdapter(),
                    peer_device_idAdapter = PeerIdAdapter(),
                    created_at_epoch_secondsAdapter = InstantEpochSecondsAdapter,
                    updated_at_epoch_secondsAdapter = InstantEpochSecondsAdapter,
                ),
                one_time_prekeysAdapter = One_time_prekeys.Adapter(
                    opk_idAdapter = OneTimePreKeyIdAdapter,
                    public_keyAdapter = EncryptionPublicKeyAdapter,
                    statusAdapter = EnumColumnAdapter(),
                    device_idAdapter = PeerIdAdapter(),
                    created_at_epoch_secondsAdapter = InstantEpochSecondsAdapter,
                    offered_at_epoch_secondsAdapter = InstantEpochSecondsAdapter,
                ),
                room_membersAdapter = Room_members.Adapter(
                    roleAdapter = EnumColumnAdapter(),
                    statusAdapter = EnumColumnAdapter(),
                    room_idAdapter = RoomIdAdapter(),
                    account_idAdapter = AccountIdAdapter(),
                    removal_node_idAdapter = UuidAdapter(),
                    joined_timestampAdapter = InstantEpochSecondsAdapter,
                ),
                roomsAdapter = Rooms.Adapter(
                    typeAdapter = EnumColumnAdapter(),
                    room_idAdapter = RoomIdAdapter(),
                ),
                messagesAdapter = Messages.Adapter(
                    payload_typeAdapter = EnumColumnAdapter(),
                    message_idAdapter = UuidAdapter(),
                    room_idAdapter = RoomIdAdapter(),
                    created_at_epoch_secondsAdapter = InstantEpochSecondsAdapter,
                    verification_stateAdapter = EnumColumnAdapter(),
                    author_device_idAdapter = PeerIdAdapter(),
                    sender_account_idAdapter = AccountIdAdapter(),
                ),
                causal_holdAdapter = Causal_hold.Adapter(
                    gap_idAdapter = UuidAdapter(),
                    missing_prev_idAdapter = UuidAdapter(),
                    orphaned_message_idAdapter = UuidAdapter(),
                    detected_timestampAdapter = InstantEpochSecondsAdapter,
                ),
                message_parentsAdapter = Message_parents.Adapter(
                    message_idAdapter = UuidAdapter(),
                    parent_idAdapter = UuidAdapter(),
                ),
                outboxAdapter = Outbox.Adapter(
                    packet_idAdapter = UuidAdapter(),
                    expires_atAdapter = InstantEpochSecondsAdapter,
                    last_attempt_atAdapter = InstantEpochSecondsAdapter,
                    next_retry_atAdapter = InstantEpochSecondsAdapter,
                    tag_idAdapter = UuidAdapter(),
                ),
                pending_syncsAdapter = Pending_syncs.Adapter(
                    sync_idAdapter = UuidAdapter(),
                    room_idAdapter = RoomIdAdapter(),
                    target_message_idAdapter = UuidAdapter(),
                ),
                message_deliveriesAdapter = Message_deliveries.Adapter(
                    message_idAdapter = UuidAdapter(),
                    account_idAdapter = AccountIdAdapter(),
                    delivered_atAdapter = InstantEpochSecondsAdapter,
                ),
                sync_attemptsAdapter = Sync_attempts.Adapter(
                    sync_idAdapter = UuidAdapter(),
                    next_attempt_atAdapter = InstantEpochSecondsAdapter,
                ),
                sync_declined_devicesAdapter = Sync_declined_devices.Adapter(
                    sync_idAdapter = UuidAdapter(),
                    device_idAdapter = PeerIdAdapter(),
                ),
                signed_prekeysAdapter = Signed_prekeys.Adapter(
                    spk_idAdapter = SignedPreKeyIdAdapter,
                    public_keyAdapter = EncryptionPublicKeyAdapter,
                    device_idAdapter = PeerIdAdapter(),
                    created_at_epoch_secondsAdapter = InstantEpochSecondsAdapter,
                ),
                pending_sync_candidate_accountsAdapter = Pending_sync_candidate_accounts.Adapter(
                    sync_idAdapter = UuidAdapter(),
                    account_idAdapter = AccountIdAdapter()
                ),
            ),
            driver = driver,
            created = created,
        )
    }
}

internal class UuidAdapter : ColumnAdapter<Uuid, String> {
    override fun decode(databaseValue: String): Uuid {
        return Uuid.parseHex(databaseValue)
    }

    override fun encode(value: Uuid): String {
        return value.toHexString()
    }

}

internal object InstantEpochSecondsAdapter : ColumnAdapter<Instant, Long> {
    override fun decode(databaseValue: Long): Instant = Instant.fromEpochSeconds(databaseValue)
    override fun encode(value: Instant): Long = value.epochSeconds
}

internal object SigningPublicKeyAdapter : ColumnAdapter<SigningPublicKey, ByteArray> {
    override fun decode(databaseValue: ByteArray) = SigningPublicKey(databaseValue)
    override fun encode(value: SigningPublicKey) = value.bytes
}

internal object EncryptionPublicKeyAdapter : ColumnAdapter<EncryptionPublicKey, ByteArray> {
    override fun decode(databaseValue: ByteArray) = EncryptionPublicKey(databaseValue)
    override fun encode(value: EncryptionPublicKey) = value.bytes
}

internal object SignedPreKeyIdAdapter : ColumnAdapter<SignedPreKeyId, String> {
    override fun decode(databaseValue: String) = SignedPreKeyId(databaseValue)
    override fun encode(value: SignedPreKeyId) = value.value
}

internal object OneTimePreKeyIdAdapter : ColumnAdapter<OneTimePreKeyId, String> {
    override fun decode(databaseValue: String) = OneTimePreKeyId(databaseValue)
    override fun encode(value: OneTimePreKeyId) = value.value
}

internal class RoomIdAdapter : ColumnAdapter<RoomId, String> {
    override fun decode(databaseValue: String) = RoomId(Uuid.parseHex(databaseValue))
    override fun encode(value: RoomId) = value.value.toHexString()
}

internal class AccountIdAdapter : ColumnAdapter<AccountId, String> {
    override fun decode(databaseValue: String): AccountId {
        return AccountId(databaseValue)
    }

    override fun encode(value: AccountId): String {
        return value.id
    }
}

internal class PeerIdAdapter : ColumnAdapter<PeerId, String> {
    override fun decode(databaseValue: String): PeerId {
        return PeerId(databaseValue)
    }

    override fun encode(value: PeerId): String {
        return value.id
    }
}

internal data class DatabaseConnection(
    val database: YapYapDatabase,
    val driver: SqlDriver,
    /** The schema was created on this open: the store holds nothing from before. */
    val created: Boolean = false,
)
