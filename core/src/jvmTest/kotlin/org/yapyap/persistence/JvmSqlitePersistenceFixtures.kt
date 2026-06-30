package org.yapyap.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.persistence.db.DatabaseConnection
import org.yapyap.persistence.db.DatabaseFactory
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.db.DriverFactory
import org.yapyap.persistence.key.DefaultIdentityKeyRepository
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.packet.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

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
    val repo = DefaultIdentityKeyRepository(database)
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

internal val FixtureRemotePeerId =
    PeerId("fixtureremotepeeridbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

internal fun seedPeerDevice(
    database: YapYapDatabase,
    accountId: AccountId,
    deviceId: PeerId,
    torEndpoint: TorEndpoint = TorEndpoint(onionAddress = "peer.onion", port = 80),
) {
    val repo = DefaultIdentityKeyRepository(database)
    val deviceRecord = DeviceIdentityRecord(
        deviceId = deviceId,
        signing = IdentityPublicKeyRecord(
            keyId = "fixture-peer-signing",
            keyVersion = 0L,
            purpose = IdentityKeyPurpose.SIGNING,
            publicKey = byteArrayOf(0x05, 0x06),
        ),
        encryption = IdentityPublicKeyRecord(
            keyId = "fixture-peer-encryption",
            keyVersion = 0L,
            purpose = IdentityKeyPurpose.ENCRYPTION,
            publicKey = byteArrayOf(0x07, 0x08),
        ),
    )
    repo.insertPeerDevice(
        accountId = accountId,
        deviceType = DeviceType.DESKTOP,
        identity = deviceRecord,
        torEndpoint = torEndpoint,
    )
}

internal fun seedLocalAndRemoteDevices(database: YapYapDatabase) {
    seedLocalAccountAndDevice(database, FixtureAccountId, FixtureDevicePeerId)
    seedPeerDevice(database, FixtureAccountId, FixtureRemotePeerId, FixtureTorEndpoint)
}

internal fun sampleOutboxEnvelope(
    packetId: PacketId,
    target: PeerId,
    now: Long,
    expiresAt: Long = now + 3_600,
    payload: ByteArray = byteArrayOf(0x01, 0x02, 0x03),
    source: PeerId = target,
): BinaryEnvelope =
    BinaryEnvelope(
        packetId = packetId,
        packetType = PacketType.MESSAGE,
        createdAtEpochSeconds = now,
        expiresAtEpochSeconds = expiresAt,
        source = source,
        target = target,
        payload = payload,
    )

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

internal fun corruptOutboxBlob(driver: SqlDriver, packetIdHex: String, corruptBlob: ByteArray = byteArrayOf(0x00)) {
    driver.execute(
        identifier = null,
        sql = "UPDATE outbox SET envelope_blob = ? WHERE packet_id = ?",
        parameters = 2,
        binders = {
            bindBytes(0, corruptBlob)
            bindString(1, packetIdHex)
        },
    )
}
