package org.yapyap.persistence.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent

internal class DatabaseInitializer(
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
) {
    /** Brings the schema up to date; true when the schema was created (the store was new). */
    fun initialize(driver: SqlDriver): Boolean {
        enableForeignKeys(driver)

        val currentVersion = readUserVersion(driver)
        val targetVersion = schema.version

        return when {
            currentVersion == 0L -> {
                schema.create(driver)
                setUserVersion(driver, targetVersion)
                AppLog.info(
                    component = LogComponent.DATABASE,
                    event = LogEvent.DATABASE_INITIALIZED,
                    message = "Database schema created",
                    fields = mapOf("fromVersion" to currentVersion, "toVersion" to targetVersion),
                )
                true
            }

            currentVersion < targetVersion -> {
                schema.migrate(driver, currentVersion, targetVersion)
                setUserVersion(driver, targetVersion)
                AppLog.info(
                    component = LogComponent.DATABASE,
                    event = LogEvent.DATABASE_MIGRATED,
                    message = "Database schema migrated",
                    fields = mapOf("fromVersion" to currentVersion, "toVersion" to targetVersion),
                )
                false
            }

            else -> {
                AppLog.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.DATABASE_INITIALIZED,
                    message = "Database schema already up to date",
                    fields = mapOf("version" to currentVersion),
                )
                false
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
