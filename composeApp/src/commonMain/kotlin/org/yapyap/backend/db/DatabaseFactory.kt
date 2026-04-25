package org.yapyap.backend.db

import app.cash.sqldelight.db.SqlDriver

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

        return DatabaseConnection(
            database = YapYapDatabase(driver),
            driver = driver,
        )
    }
}

data class DatabaseConnection(
    val database: YapYapDatabase,
    val driver: SqlDriver,
)
