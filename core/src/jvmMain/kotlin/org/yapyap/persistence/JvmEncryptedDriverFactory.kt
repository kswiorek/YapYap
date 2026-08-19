package org.yapyap.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.db.DriverFactory
import java.nio.file.Path

class JvmEncryptedDriverFactory(
    private val databaseFile: Path,
    private val masterKey: ByteArray,
) : DriverFactory {
    override fun createDriver(): SqlDriver {
        val path = databaseFile.toString().replace('\\', '/')   // JDBC wants forward slashes
        val url = "jdbc:sqlite:file:$path?cipher=sqlcipher&key=${masterKey.toHexString()}&foreign_keys=on&journal_mode=WAL"

        AppLog.info(
            component = LogComponent.DATABASE,
            event = LogEvent.DATABASE_INITIALIZED,
            message = "Creating encrypted JDBC SQLite driver",
            fields = mapOf("databaseFile" to databaseFile),
        )
        return JdbcSqliteDriver(url)
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        byte.toUByte().toString(radix = 16).padStart(2, '0')
    }
}
