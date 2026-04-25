package org.yapyap.backend.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

class JvmEncryptedDriverFactory(
    private val databasePath: String,
    masterKey: ByteArray,
) : DriverFactory {
    private val masterKeyHex = masterKey.toHexString()

    override fun createDriver(): SqlDriver {
        val url = "jdbc:sqlite:file:$databasePath?cipher=sqlcipher&key=$masterKeyHex&foreign_keys=on"
        return JdbcSqliteDriver(url)
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        byte.toUByte().toString(radix = 16).padStart(2, '0')
    }
}
