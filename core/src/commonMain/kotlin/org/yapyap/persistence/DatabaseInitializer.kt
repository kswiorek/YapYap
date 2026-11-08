package org.yapyap.persistence

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
        setForeignKeys(driver, enabled = true)

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
                // SQLite's table-rebuild procedure: migrations may drop and recreate a table
                // other tables reference, so they run with foreign keys off and the result is
                // checked before they are back on.
                setForeignKeys(driver, enabled = false)
                schema.migrate(driver, currentVersion, targetVersion)
                checkForeignKeys(driver)
                setForeignKeys(driver, enabled = true)
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

    private fun setForeignKeys(driver: SqlDriver, enabled: Boolean) {
        driver.execute(
            identifier = null,
            sql = "PRAGMA foreign_keys = ${if (enabled) "ON" else "OFF"}",
            parameters = 0,
            binders = null,
        )
    }

    /** Fails the migration when it left a row pointing at a missing parent. */
    private fun checkForeignKeys(driver: SqlDriver) {
        val violations = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA foreign_key_check",
            mapper = { cursor ->
                val tables = mutableListOf<String>()
                while (cursor.next().value) tables += cursor.getString(0).orEmpty()
                QueryResult.Value(tables)
            },
            parameters = 0,
            binders = null,
        ).value
        check(violations.isEmpty()) {
            "Migration left foreign key violations in: ${
                violations.distinct().joinToString()
            }"
        }
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
