package org.yapyap.persistence

import app.cash.sqldelight.db.SqlDriver
import kotlinx.io.files.Path
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.db.DriverFactory

internal class JvmEncryptedDriverFactory(
    private val databaseFile: Path,
    private val masterKey: ByteArray,
) : DriverFactory {
    override fun createDriver(): SqlDriver {
        val path = databaseFile.toString().replace('\\', '/')   // JDBC wants forward slashes
        val url =
            "jdbc:sqlite:file:$path?cipher=sqlcipher&key=${masterKey.toHexString()}&foreign_keys=on&journal_mode=WAL"

        AppLog.info(
            component = LogComponent.DATABASE,
            event = LogEvent.DATABASE_INITIALIZED,
            message = "Creating encrypted JDBC SQLite driver",
            fields = mapOf("databaseFile" to databaseFile),
        )
        // Single persistent connection: per-query open/close would re-run the full
        // SQLCipher key derivation on every statement (~90ms measured).
        return SingleConnectionJdbcDriver(url)
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        byte.toUByte().toString(radix = 16).padStart(2, '0')
    }
}
