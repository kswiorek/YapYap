package org.yapyap.persistence.db

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import org.yapyap.crypto.e2ee.X3dhMode
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.Accounts
import org.yapyap.persistence.Crypto_sessions
import org.yapyap.persistence.Dedup
import org.yapyap.persistence.Devices
import org.yapyap.persistence.One_time_prekeys
import org.yapyap.persistence.YapYapDatabase

interface DriverFactory {
    fun createDriver(): SqlDriver
}

class DatabaseFactory(
    private val driverFactory: DriverFactory,
    private val initializer: DatabaseInitializer = DatabaseInitializer(YapYapDatabase.Schema),
    private val logger: AppLogger = NoopAppLogger,
) {
    fun createConnection(): DatabaseConnection {
        val driver = driverFactory.createDriver()
        initializer.initialize(driver)
        logger.info(
            component = LogComponent.DATABASE,
            event = LogEvent.DATABASE_INITIALIZED,
            message = "Database connection created",
        )

        return DatabaseConnection(
            database = YapYapDatabase.Companion(
                driver = driver,
                accountsAdapter = Accounts.Adapter(
                    statusAdapter = EnumColumnAdapter(),
                ),
                devicesAdapter = Devices.Adapter(
                    device_typeAdapter = EnumColumnAdapter(),
                ),
                dedupAdapter = Dedup.Adapter(
                    nack_reasonAdapter = EnumColumnAdapter(),
                ),
                crypto_sessionsAdapter = Crypto_sessions.Adapter(
                    roleAdapter = EnumColumnAdapter(),
                    x3dh_modeAdapter = EnumColumnAdapter<X3dhMode>(),
                    statusAdapter = EnumColumnAdapter(),
                ),
                one_time_prekeysAdapter = One_time_prekeys.Adapter(
                    statusAdapter = EnumColumnAdapter(),
                ),
            ),
            driver = driver,
        )
    }
}

data class DatabaseConnection(
    val database: YapYapDatabase,
    val driver: SqlDriver,
)
