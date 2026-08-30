package org.yapyap.persistence.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.yapyap.crypto.e2ee.session.X3dhMode
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.*
import org.yapyap.protocol.PeerId
import kotlin.uuid.Uuid

/**
 * Single shared dispatcher that confines all SQLDelight/SQLite access to a single thread.
 *
 * All repositories in the persistence package share one SQLDelight connection (created in
 * [DatabaseFactory.createConnection]), and JDBC connections are not thread-safe. Serializing every
 * DB call onto one thread matches SQLite's single-writer model and avoids `SQLITE_BUSY`/corruption
 * when operations are issued concurrently from many coroutines.
 */
val databaseDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

interface DriverFactory {
    fun createDriver(): SqlDriver
}

class DatabaseFactory(
    private val driverFactory: DriverFactory,
    private val initializer: DatabaseInitializer = DatabaseInitializer(YapYapDatabase.Schema),
) {
    fun createConnection(): DatabaseConnection {
        val driver = driverFactory.createDriver()
        initializer.initialize(driver)
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
                    account_idAdapter = AccountIdAdapter(),
                ),
                devicesAdapter = Devices.Adapter(
                    device_typeAdapter = EnumColumnAdapter(),
                    device_idAdapter = PeerIdAdapter(),
                    account_idAdapter = AccountIdAdapter(),
                ),
                dedupAdapter = Dedup.Adapter(
                    nack_reasonAdapter = EnumColumnAdapter(),
                    packet_idAdapter = UuidAdapter(),
                ),
                crypto_sessionsAdapter = Crypto_sessions.Adapter(
                    roleAdapter = EnumColumnAdapter(),
                    x3dh_modeAdapter = EnumColumnAdapter<X3dhMode>(),
                    statusAdapter = EnumColumnAdapter(),
                    peer_device_idAdapter = PeerIdAdapter(),
                ),
                one_time_prekeysAdapter = One_time_prekeys.Adapter(
                    statusAdapter = EnumColumnAdapter(),
                    device_idAdapter = PeerIdAdapter(),
                ),
                room_membersAdapter = Room_members.Adapter(
                    roleAdapter = EnumColumnAdapter(),
                    room_idAdapter = RoomIdAdapter(),
                ),
                roomsAdapter = Rooms.Adapter(
                    typeAdapter = EnumColumnAdapter(),
                    room_idAdapter = RoomIdAdapter(),
                ),
                messagesAdapter = Messages.Adapter(
                    payload_typeAdapter = EnumColumnAdapter(),
                    message_idAdapter = UuidAdapter(),
                    prev_idAdapter = UuidAdapter(),
                    room_idAdapter = RoomIdAdapter(),
                ),
                causal_holdAdapter = Causal_hold.Adapter(
                    gap_idAdapter = UuidAdapter(),
                    missing_prev_idAdapter = UuidAdapter(),
                    orphaned_message_idAdapter = UuidAdapter()
                ),
                outboxAdapter = Outbox.Adapter(
                    packet_idAdapter = UuidAdapter()
                ),
                pending_syncsAdapter = Pending_syncs.Adapter(
                    sync_idAdapter = UuidAdapter(),
                    room_idAdapter = RoomIdAdapter(),
                ),
                pending_sync_attempted_peersAdapter = Pending_sync_attempted_peers.Adapter(
                    sync_idAdapter = UuidAdapter(),
                    device_idAdapter = PeerIdAdapter(),
                ),
                signed_prekeysAdapter = Signed_prekeys.Adapter(
                    device_idAdapter = PeerIdAdapter()
                ),
                pending_sync_candidate_accountsAdapter = Pending_sync_candidate_accounts.Adapter(
                    sync_idAdapter = UuidAdapter(),
                    account_idAdapter = AccountIdAdapter()
                ),
            ),
            driver = driver,
        )
    }
}

class UuidAdapter: ColumnAdapter<Uuid, String> {
    override fun decode(databaseValue: String): Uuid {
        return Uuid.parseHex(databaseValue)
    }

    override fun encode(value: Uuid): String {
        return value.toHexString()
    }

}

class RoomIdAdapter : ColumnAdapter<RoomId, String> {
    override fun decode(databaseValue: String) = RoomId(Uuid.parseHex(databaseValue))
    override fun encode(value: RoomId) = value.value.toHexString()
}

class AccountIdAdapter: ColumnAdapter<AccountId, String> {
    override fun decode(databaseValue: String): AccountId {
        return AccountId(databaseValue)
    }

    override fun encode(value: AccountId): String {
        return value.id
    }
}

class PeerIdAdapter: ColumnAdapter<PeerId, String> {
    override fun decode(databaseValue: String): PeerId {
        return PeerId(databaseValue)
    }
    override fun encode(value: PeerId): String {
        return value.id
    }
}

data class DatabaseConnection(
    val database: YapYapDatabase,
    val driver: SqlDriver,
)
