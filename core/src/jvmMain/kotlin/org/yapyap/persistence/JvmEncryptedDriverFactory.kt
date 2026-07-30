package org.yapyap.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.db.DriverFactory

class JvmEncryptedDriverFactory(
    private val databasePath: String,
    masterKey: ByteArray,
) : DriverFactory {
    private val masterKeyHex = masterKey.toHexString()

    override fun createDriver(): SqlDriver {
        val url = "jdbc:sqlite:file:$databasePath?cipher=sqlcipher&key=$masterKeyHex&foreign_keys=on"
        AppLog.info(
            component = LogComponent.DATABASE,
            event = LogEvent.DATABASE_INITIALIZED,
            message = "Creating encrypted JDBC SQLite driver",
            fields = mapOf("databasePath" to databasePath),
        )
        return JdbcSqliteDriver(url)
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        byte.toUByte().toString(radix = 16).padStart(2, '0')
    }
}
