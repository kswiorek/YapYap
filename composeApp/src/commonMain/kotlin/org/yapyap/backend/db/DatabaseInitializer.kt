package org.yapyap.backend.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger

class DatabaseInitializer(
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
    private val logger: AppLogger = NoopAppLogger,
) {
    fun initialize(driver: SqlDriver) {
        enableForeignKeys(driver)

        val currentVersion = readUserVersion(driver)
        val targetVersion = schema.version

        when {
            currentVersion == 0L -> {
                schema.create(driver)
                setUserVersion(driver, targetVersion)
                logger.info(
                    component = LogComponent.DATABASE,
                    event = LogEvent.DATABASE_INITIALIZED,
                    message = "Database schema created",
                    fields = mapOf("fromVersion" to currentVersion, "toVersion" to targetVersion),
                )
            }
            currentVersion < targetVersion -> {
                schema.migrate(driver, currentVersion, targetVersion)
                setUserVersion(driver, targetVersion)
                logger.info(
                    component = LogComponent.DATABASE,
                    event = LogEvent.DATABASE_MIGRATED,
                    message = "Database schema migrated",
                    fields = mapOf("fromVersion" to currentVersion, "toVersion" to targetVersion),
                )
            }
            else -> logger.debug(
                component = LogComponent.DATABASE,
                event = LogEvent.DATABASE_INITIALIZED,
                message = "Database schema already up to date",
                fields = mapOf("version" to currentVersion),
            )
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
