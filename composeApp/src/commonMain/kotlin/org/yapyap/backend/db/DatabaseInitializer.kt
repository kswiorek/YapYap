package org.yapyap.backend.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

class DatabaseInitializer(
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
) {
    fun initialize(driver: SqlDriver) {
        enableForeignKeys(driver)

        val currentVersion = readUserVersion(driver)
        val targetVersion = schema.version

        when {
            currentVersion == 0L -> {
                schema.create(driver)
                setUserVersion(driver, targetVersion)
            }
            currentVersion < targetVersion -> {
                schema.migrate(driver, currentVersion, targetVersion)
                setUserVersion(driver, targetVersion)
            }
        }
    }

    private fun readUserVersion(driver: SqlDriver): Long {
        return driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                if (!cursor.next().value) {
                    QueryResult.Value(0L)
                } else {
                    QueryResult.Value(cursor.getLong(0) ?: 0L)
                }
            },
            parameters = 0,
            binders = null,
        ).value
    }

    private fun enableForeignKeys(driver: SqlDriver) {
        driver.execute(
            identifier = null,
            sql = "PRAGMA foreign_keys = ON",
            parameters = 0,
            binders = null,
        )
    }

    private fun setUserVersion(driver: SqlDriver, version: Long) {
        driver.execute(
            identifier = null,
            sql = "PRAGMA user_version = $version",
            parameters = 0,
            binders = null,
        )
    }
}
