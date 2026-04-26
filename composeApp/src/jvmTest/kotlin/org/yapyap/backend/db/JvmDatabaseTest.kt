package org.yapyap.backend.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmDatabaseTest {

    @Test
    fun createsEncryptedDatabaseAndReopens() {
        val tempDb = Files.createTempFile("yapyap-encrypted-", ".db")
        val masterKey = ByteArray(32) { i -> (i + 1).toByte() }
        val driverFactory = JvmEncryptedDriverFactory(
            databasePath = tempDb.toAbsolutePath().toString(),
            masterKey = masterKey,
        )
        val databaseFactory = DatabaseFactory(driverFactory)

        try {
            val firstConnection = databaseFactory.createConnection()
            insertAccount(
                driver = firstConnection.driver,
                accountPubKey = "acc-alice",
                isAdmin = true,
                status = AccountStatus.ACTIVE,
                displayName = "Alice",
            )
            assertEquals(1L, countRows(firstConnection.driver, "accounts"))
            assertEquals(YapYapDatabase.Schema.version, readUserVersion(firstConnection.driver))
            firstConnection.driver.close()

            val secondConnection = databaseFactory.createConnection()
            assertEquals(1L, countRows(secondConnection.driver, "accounts"))
            assertEquals(YapYapDatabase.Schema.version, readUserVersion(secondConnection.driver))
            secondConnection.driver.close()
        } finally {
            Files.deleteIfExists(tempDb)
        }
    }

    @Test
    fun enforcesForeignKeysForDevices() {
        withConnection { driver ->
            assertFailsWith<Throwable> {
                insertDevice(
                    driver = driver,
                    deviceId = "device-1",
                    accountPubKey = "missing-account",
                    deviceType = DeviceType.DESKTOP,
                    onionAddress = "onion-device-1",
                    signingPubKey = byteArrayOf(1, 2, 3),
                    signingKeyId = "sign-key-1",
                    signingKeyVersion = 1L,
                    encryptionPubKey = byteArrayOf(4, 5, 6),
                    encryptionKeyId = "enc-key-1",
                    encryptionKeyVersion = 1L,
                )
            }

            insertAccount(
                driver = driver,
                accountPubKey = "acc-bob",
                isAdmin = false,
                status = AccountStatus.ACTIVE,
                displayName = "Bob",
            )
            insertDevice(
                driver = driver,
                deviceId = "device-1",
                accountPubKey = "acc-bob",
                deviceType = DeviceType.DESKTOP,
                onionAddress = "onion-device-1",
                signingPubKey = byteArrayOf(1, 2, 3),
                signingKeyId = "sign-key-1",
                signingKeyVersion = 1L,
                encryptionPubKey = byteArrayOf(4, 5, 6),
                encryptionKeyId = "enc-key-1",
                encryptionKeyVersion = 1L,
            )
            assertEquals(1L, countRows(driver, "devices"))
        }
    }

    @Test
    fun enforcesCompositePrimaryKeyForRoomMembers() {
        withConnection { driver ->
            insertAccount(driver, "acc-one", false, AccountStatus.ACTIVE, "One")
            insertAccount(driver, "acc-two", false, AccountStatus.ACTIVE, "Two")
            insertRoom(driver, roomId = "room-1", roomType = RoomType.TEXT_CHANNEL, name = "general")

            insertRoomMember(driver, roomId = "room-1", accountPubKey = "acc-one", role = RoomMemberRole.MEMBER)
            assertFailsWith<Throwable> {
                insertRoomMember(driver, roomId = "room-1", accountPubKey = "acc-one", role = RoomMemberRole.ADMIN)
            }
            insertRoomMember(driver, roomId = "room-1", accountPubKey = "acc-two", role = RoomMemberRole.ADMIN)

            assertEquals(2L, countRows(driver, "room_members"))
        }
    }

    @Test
    fun persistsEnumAndBooleanValues() {
        withConnection { driver ->
            insertAccount(
                driver = driver,
                accountPubKey = "acc-enum",
                isAdmin = true,
                status = AccountStatus.BANNED,
                displayName = "EnumUser",
            )
            insertRoom(driver, roomId = "room-enum", roomType = RoomType.GLOBAL_CONTROL, name = "control")
            insertMessage(
                driver = driver,
                messageId = "msg-enum",
                roomId = "room-enum",
                senderAccountPubKey = "acc-enum",
                payloadType = MessagePayloadType.REVOKE_DEVICE,
                lifecycleState = MessageLifecycleState.ARCHIVED,
                isOrphaned = true,
            )

            assertEquals("BANNED", readSingleText(driver, "SELECT status FROM accounts WHERE account_pub_key = 'acc-enum'"))
            assertEquals(1L, readSingleLong(driver, "SELECT is_admin FROM accounts WHERE account_pub_key = 'acc-enum'"))
            assertEquals("REVOKE_DEVICE", readSingleText(driver, "SELECT payload_type FROM messages WHERE message_id = 'msg-enum'"))
            assertEquals("ARCHIVED", readSingleText(driver, "SELECT lifecycle_state FROM messages WHERE message_id = 'msg-enum'"))
            assertEquals(1L, readSingleLong(driver, "SELECT is_orphaned FROM messages WHERE message_id = 'msg-enum'"))
        }
    }

    @Test
    fun supportsFileTransferAndChunkTrackerConstraints() {
        withConnection { driver ->
            insertAccount(driver, "acc-file", false, AccountStatus.ACTIVE, "FileUser")
            insertRoom(driver, roomId = "room-file", roomType = RoomType.TEXT_CHANNEL, name = "files")
            insertMessage(
                driver = driver,
                messageId = "msg-file",
                roomId = "room-file",
                senderAccountPubKey = "acc-file",
                payloadType = MessagePayloadType.FILE_OFFER,
                lifecycleState = MessageLifecycleState.CREATED,
                isOrphaned = false,
            )
            insertFileTransfer(
                driver = driver,
                transferId = "tx-1",
                messageId = "msg-file",
                status = FileTransferStatus.IN_FLIGHT,
                highestContiguousChunk = -1,
            )

            insertFileChunkTracker(driver, transferId = "tx-1", chunkIndex = 0, status = FileChunkStatus.MISSING)
            insertFileChunkTracker(driver, transferId = "tx-1", chunkIndex = 1, status = FileChunkStatus.REQUESTED)

            assertFailsWith<Throwable> {
                insertFileChunkTracker(driver, transferId = "tx-1", chunkIndex = 1, status = FileChunkStatus.WRITTEN)
            }
            assertFailsWith<Throwable> {
                insertFileChunkTracker(driver, transferId = "tx-missing", chunkIndex = 0, status = FileChunkStatus.MISSING)
            }

            assertEquals(2L, countRows(driver, "file_chunk_tracker"))
        }
    }

    @Test
    fun migrationReadinessUpdatesVersionAndInvokesMigrate() {
        withConnection { driver ->
            insertAccount(driver, "acc-migrate", false, AccountStatus.ACTIVE, "Migrator")
            setUserVersion(driver, 1L)

            val migrationSchema = object : SqlSchema<QueryResult.Value<Unit>> {
                override val version: Long = 2L

                override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit

                override fun migrate(
                    driver: SqlDriver,
                    oldVersion: Long,
                    newVersion: Long,
                    vararg callbacks: AfterVersion,
                ): QueryResult.Value<Unit> {
                    driver.execute(
                        identifier = null,
                        sql = "CREATE TABLE migration_marker (id INTEGER PRIMARY KEY)",
                        parameters = 0,
                        binders = null,
                    )
                    return QueryResult.Unit
                }
            }

            DatabaseInitializer(migrationSchema).initialize(driver)

            assertEquals(2L, readUserVersion(driver))
            assertEquals(1L, countRows(driver, "accounts"))
            assertEquals(1L, tableExists(driver, "migration_marker"))
        }
    }


    private fun withConnection(block: (SqlDriver) -> Unit) {
        val tempDb = Files.createTempFile("yapyap-encrypted-test-", ".db")
        val driverFactory = JvmEncryptedDriverFactory(
            databasePath = tempDb.toAbsolutePath().toString(),
            masterKey = ByteArray(32) { i -> (i + 11).toByte() },
        )
        val databaseFactory = DatabaseFactory(driverFactory)

        try {
            val connection = databaseFactory.createConnection()
            try {
                block(connection.driver)
            } finally {
                connection.driver.close()
            }
        } finally {
            Files.deleteIfExists(tempDb)
        }
    }

    private fun insertAccount(
        driver: SqlDriver,
        accountPubKey: String,
        isAdmin: Boolean,
        status: AccountStatus,
        displayName: String,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO accounts (account_pub_key, is_admin, status, display_name) VALUES (?, ?, ?, ?)",
            parameters = 4,
        ) {
            bindString(0, accountPubKey)
            bindLong(1, if (isAdmin) 1L else 0L)
            bindString(2, status.name)
            bindString(3, displayName)
        }
    }

    private fun insertDevice(
        driver: SqlDriver,
        deviceId: String,
        accountPubKey: String,
        deviceType: DeviceType,
        onionAddress: String,
        signingPubKey: ByteArray,
        signingKeyId: String,
        signingKeyVersion: Long,
        encryptionPubKey: ByteArray,
        encryptionKeyId: String,
        encryptionKeyVersion: Long,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO devices (
                    device_id, account_pub_key, device_type, onion_address, onion_port,
                    signing_pub_key, signing_key_id, signing_key_version,
                    encryption_pub_key, encryption_key_id, encryption_key_version,
                    push_token, ping_attempts, ping_successes, last_seen_timestamp
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 15,
        ) {
            bindString(0, deviceId)
            bindString(1, accountPubKey)
            bindString(2, deviceType.name)
            bindString(3, onionAddress)
            bindLong(4, 80L)
            bindBytes(5, signingPubKey)
            bindString(6, signingKeyId)
            bindLong(7, signingKeyVersion)
            bindBytes(8, encryptionPubKey)
            bindString(9, encryptionKeyId)
            bindLong(10, encryptionKeyVersion)
            bindString(11, null)
            bindLong(12, 0L)
            bindLong(13, 0L)
            bindLong(14, 0L)
        }
    }

    private fun insertRoom(driver: SqlDriver, roomId: String, roomType: RoomType, name: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO rooms (room_id, space_id, type, name, local_seq_n) VALUES (?, ?, ?, ?, ?)",
            parameters = 5,
        ) {
            bindString(0, roomId)
            bindString(1, null)
            bindString(2, roomType.name)
            bindString(3, name)
            bindLong(4, 1L)
        }
    }

    private fun insertRoomMember(
        driver: SqlDriver,
        roomId: String,
        accountPubKey: String,
        role: RoomMemberRole,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO room_members (room_id, account_pub_key, role, joined_timestamp) VALUES (?, ?, ?, ?)",
            parameters = 4,
        ) {
            bindString(0, roomId)
            bindString(1, accountPubKey)
            bindString(2, role.name)
            bindLong(3, 1_700_000_000L)
        }
    }

    private fun insertMessage(
        driver: SqlDriver,
        messageId: String,
        roomId: String,
        senderAccountPubKey: String,
        payloadType: MessagePayloadType,
        lifecycleState: MessageLifecycleState,
        isOrphaned: Boolean,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO messages (
                    message_id, room_id, sender_account_pub_key, prev_id,
                    lamport_clock, payload_type, encrypted_payload, lifecycle_state, is_orphaned
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 9,
        ) {
            bindString(0, messageId)
            bindString(1, roomId)
            bindString(2, senderAccountPubKey)
            bindString(3, null)
            bindLong(4, 1L)
            bindString(5, payloadType.name)
            bindBytes(6, byteArrayOf(0x01, 0x02))
            bindString(7, lifecycleState.name)
            bindLong(8, if (isOrphaned) 1L else 0L)
        }
    }

    private fun insertFileTransfer(
        driver: SqlDriver,
        transferId: String,
        messageId: String,
        status: FileTransferStatus,
        highestContiguousChunk: Long,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO file_transfers (
                    transfer_id, message_id, status, file_name_hint, mime_type, total_bytes,
                    chunk_size_bytes, chunk_count, object_hash, highest_contiguous_chunk, local_file_path
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 11,
        ) {
            bindString(0, transferId)
            bindString(1, messageId)
            bindString(2, status.name)
            bindString(3, "archive.zip")
            bindString(4, "application/zip")
            bindLong(5, 1024L)
            bindLong(6, 256L)
            bindLong(7, 4L)
            bindBytes(8, byteArrayOf(0x0A, 0x0B, 0x0C))
            bindLong(9, highestContiguousChunk)
            bindString(10, "/tmp/archive.zip")
        }
    }

    private fun insertFileChunkTracker(
        driver: SqlDriver,
        transferId: String,
        chunkIndex: Long,
        status: FileChunkStatus,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO file_chunk_tracker (transfer_id, chunk_index, status) VALUES (?, ?, ?)",
            parameters = 3,
        ) {
            bindString(0, transferId)
            bindLong(1, chunkIndex)
            bindString(2, status.name)
        }
    }

    private fun countRows(driver: SqlDriver, tableName: String): Long {
        return readSingleLong(driver, "SELECT COUNT(*) FROM $tableName")
    }

    private fun tableExists(driver: SqlDriver, tableName: String): Long {
        return readSingleLong(
            driver,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$tableName'",
        )
    }

    private fun readSingleLong(driver: SqlDriver, sql: String): Long {
        return driver.executeQuery(
            identifier = null,
            sql = sql,
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

    private fun readSingleText(driver: SqlDriver, sql: String): String {
        return driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (!cursor.next().value) {
                    QueryResult.Value("")
                } else {
                    QueryResult.Value(cursor.getString(0) ?: "")
                }
            },
            parameters = 0,
            binders = null,
        ).value
    }

    private fun readUserVersion(driver: SqlDriver): Long {
        return readSingleLong(driver, "PRAGMA user_version")
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
