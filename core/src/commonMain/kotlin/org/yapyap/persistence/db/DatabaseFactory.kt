package org.yapyap.persistence.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import org.yapyap.crypto.e2ee.X3dhMode
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LoggingTypes
import org.yapyap.persistence.*
import org.yapyap.protocol.PeerId
import kotlin.uuid.Uuid

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
            event = LoggingTypes.DATABASE_INITIALIZED,
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
                ),
                dedupAdapter = Dedup.Adapter(
                    nack_reasonAdapter = EnumColumnAdapter(),
                    packet_idAdapter = UuidAdapter(),
                ),
                crypto_sessionsAdapter = Crypto_sessions.Adapter(
                    roleAdapter = EnumColumnAdapter(),
                    x3dh_modeAdapter = EnumColumnAdapter<X3dhMode>(),
                    statusAdapter = EnumColumnAdapter(),
                ),
                one_time_prekeysAdapter = One_time_prekeys.Adapter(
                    statusAdapter = EnumColumnAdapter(),
                    device_idAdapter = PeerIdAdapter(),
                ),
                room_membersAdapter = Room_members.Adapter(
                    roleAdapter = EnumColumnAdapter(),
                ),
                roomsAdapter = Rooms.Adapter(
                    typeAdapter = EnumColumnAdapter(),
                ),
                messagesAdapter = Messages.Adapter(
                    payload_typeAdapter = EnumColumnAdapter(),
                    lifecycle_stateAdapter = EnumColumnAdapter(),
                    message_idAdapter = UuidAdapter(),
                    prev_idAdapter = UuidAdapter(),
                ),
                causal_holdAdapter = Causal_hold.Adapter(
                    gap_idAdapter = UuidAdapter(),
                    missing_prev_idAdapter = UuidAdapter(),
                    orphaned_message_idAdapter = UuidAdapter()
                ),
                outboxAdapter = Outbox.Adapter(
                    packet_idAdapter = UuidAdapter()
                ),
                signed_prekeysAdapter = Signed_prekeys.Adapter(
                    device_idAdapter = PeerIdAdapter()
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
