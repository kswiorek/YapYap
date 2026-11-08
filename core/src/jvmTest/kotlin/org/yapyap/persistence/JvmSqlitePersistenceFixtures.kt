package org.yapyap.persistence


import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.yapyap.orchestrator.identity.DefaultIdentityStore
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.testfixtures.epochSeconds
import kotlin.uuid.Uuid

/** Plain JDBC SQLite (no SQLCipher) for JVM contract tests. */
internal class JvmInMemorySqliteDriverFactory : DriverFactory {
    override fun createDriver() = JdbcSqliteDriver("jdbc:sqlite::memory:")
}

internal fun openMemoryDatabase(): DatabaseConnection =
    DatabaseFactory(JvmInMemorySqliteDriverFactory()).createConnection()

/**
 * Minimal account + local device rows satisfying FK from [dedup] → [devices].
 */
internal suspend fun seedLocalAccountAndDevice(
    database: YapYapDatabase,
    accountId: AccountId,
    deviceId: PeerId,
) {
    val repo = DefaultIdentityStore(database, DeviceType.DESKTOP)
    val accountRecord = AccountIdentity(
        accountId = accountId,
        displayName = "Fixture Account",
        signingKey = SigningPublicKey(byteArrayOf(0x0A, 0x0B, 0x0C)),
    )
    val deviceRecord = DeviceIdentity(
        deviceId = deviceId,
        signingKey = SigningPublicKey(byteArrayOf(0x01, 0x02)),
        encryptionKey = EncryptionPublicKey(byteArrayOf(0x03, 0x04)),
    )
    repo.insertLocalAccount(identity = accountRecord)
    repo.insertLocalDevice(accountId = accountId, identity = deviceRecord)
}

internal val FixtureAccountId = AccountId("fixture-account-id")
internal val FixtureDevicePeerId =
    PeerId("fixturedevicepeeridaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

internal val FixtureTorEndpoint = TorEndpoint(onionAddress = "fixturerelay.onion", port = 443)

internal val FixtureRemotePeerId =
    PeerId("fixtureremotepeeridbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

/**
 * Insert a room row satisfying FK from [messages] → [rooms].
 */
internal fun seedRoom(
    database: YapYapDatabase,
    roomId: RoomId = RoomId(Uuid.random()),
    name: String = "Test Room",
) {
    database.roomQueries.insertRoom(roomId, null, RoomType.TEXT_CHANNEL, name)
}

internal suspend fun seedPeerDevice(
    database: YapYapDatabase,
    accountId: AccountId,
    deviceId: PeerId,
    torEndpoint: TorEndpoint = TorEndpoint(onionAddress = "peer.onion", port = 80),
) {
    val repo = DefaultIdentityStore(database, DeviceType.DESKTOP)
    val deviceRecord = DeviceIdentity(
        deviceId = deviceId,
        signingKey = SigningPublicKey(byteArrayOf(0x05, 0x06)),
        encryptionKey = EncryptionPublicKey(byteArrayOf(0x07, 0x08)),
    )
    repo.insertPeerDevice(
        accountId = accountId,
        deviceType = DeviceType.DESKTOP,
        identity = deviceRecord,
        torEndpoint = torEndpoint,
    )
}

internal suspend fun seedLocalAndRemoteDevices(database: YapYapDatabase) {
    seedLocalAccountAndDevice(database, FixtureAccountId, FixtureDevicePeerId)
    seedPeerDevice(database, FixtureAccountId, FixtureRemotePeerId, FixtureTorEndpoint)
}

internal fun sampleOutboxEnvelope(
    packetId: Uuid = Uuid.random(),
    target: PeerId,
    now: Long,
    expiresAt: Long = now + 3_600,
    payload: ByteArray = byteArrayOf(0x01, 0x02, 0x03),
    source: PeerId = target,
): BinaryEnvelope =
    BinaryEnvelope(
        packetId = packetId,
        packetType = PacketType.MESSAGE,
        dispositionRequested = true,
        createdAt = epochSeconds(now),
        expiresAt = epochSeconds(expiresAt),
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

internal fun corruptOutboxBlob(driver: SqlDriver, packetIdHex: Uuid, corruptBlob: ByteArray = byteArrayOf(0x00)) {
    driver.execute(
        identifier = null,
        sql = "UPDATE outbox SET envelope_blob = ? WHERE packet_id = ?",
        parameters = 2,
        binders = {
            bindBytes(0, corruptBlob)
            bindString(1, packetIdHex.toHexString())
        },
    )
}
