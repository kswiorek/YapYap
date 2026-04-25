package org.yapyap.backend.db

import app.cash.sqldelight.db.SqlDriver

interface DriverFactory {
    fun createDriver(): SqlDriver
}

class DatabaseFactory(
    private val driverFactory: DriverFactory,
) {
    fun createConnection(): DatabaseConnection {
        val driver = driverFactory.createDriver()

        // Only initialize schema for a brand-new database file.
        if (!hasContactsTable(driver)) {
            YapYapDatabase.Schema.create(driver)
        }

        return DatabaseConnection(
            database = YapYapDatabase(driver),
            driver = driver,
        )
    }

    private fun hasContactsTable(driver: SqlDriver): Boolean {
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor -> cursor.next() },
            parameters = 1,
        ) {
            bindString(0, "contacts")
        }.value
    }
}

data class DatabaseConnection(
    val database: YapYapDatabase,
    val driver: SqlDriver,
)
