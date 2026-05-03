package org.yapyap.backend.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

/** Plain JDBC SQLite (no SQLCipher) for JVM contract tests. */
internal class JvmInMemorySqliteDriverFactory : DriverFactory {
    override fun createDriver() = JdbcSqliteDriver("jdbc:sqlite::memory:")
}

internal fun openMemoryDatabase(): DatabaseConnection =
    DatabaseFactory(JvmInMemorySqliteDriverFactory()).createConnection()

/**
 * Minimal account + local device rows satisfying FK from [dedup] → [devices].
 */
internal fun seedLocalAccountAndDevice(
    database: YapYapDatabase,
    accountId: AccountId,
    deviceId: PeerId,
) {
    val repo = DefaultIdentityPublicKeyRepository(database)
    val accountRecord = AccountIdentityRecord(
        accountId = accountId,
        key = IdentityPublicKeyRecord(
            keyId = "fixture-account-key",
            keyVersion = 0L,
            purpose = IdentityKeyPurpose.SIGNING,
            publicKey = byteArrayOf(0x0A, 0x0B, 0x0C),
        ),
    )
    val deviceRecord = DeviceIdentityRecord(
        deviceId = deviceId,
        signing = IdentityPublicKeyRecord(
            keyId = "fixture-device-signing",
            keyVersion = 0L,
            purpose = IdentityKeyPurpose.SIGNING,
            publicKey = byteArrayOf(0x01, 0x02),
        ),
        encryption = IdentityPublicKeyRecord(
            keyId = "fixture-device-encryption",
            keyVersion = 0L,
            purpose = IdentityKeyPurpose.ENCRYPTION,
            publicKey = byteArrayOf(0x03, 0x04),
        ),
    )
    repo.insertLocalAccount(displayName = "Fixture Account", identity = accountRecord)
    repo.insertLocalDevice(accountId = accountId, identity = deviceRecord)
}

internal val FixtureAccountId = AccountId("fixture-account-id")
internal val FixtureDevicePeerId =
    PeerId("fixturedevicepeeridaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

internal val FixtureTorEndpoint = TorEndpoint(onionAddress = "fixturerelay.onion", port = 443)

internal fun readPragmaUserVersion(driver: SqlDriver): Long =
    driver.executeQuery(
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

internal fun readPragmaForeignKeys(driver: SqlDriver): Boolean =
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA foreign_keys",
        mapper = { cursor ->
            if (!cursor.next().value) {
                QueryResult.Value(false)
            } else {
                QueryResult.Value((cursor.getLong(0) ?: 0L) != 0L)
            }
        },
        parameters = 0,
        binders = null,
    ).value
