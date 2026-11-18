package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Upgrade path for 6.sqm: placeholder file tables go, the DAG-owned
 * `message_attachments` index lands. Runs with FK off, checked with FK check.
 */
class FileSchemaMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    @Test
    fun versionFiveWithPlaceholders_migratesToAttachments() = runTest {
        val path = Files.createTempDirectory("yapyap-file-migration").resolve("v.db").toString().replace('\\', '/')

        val roomId = Uuid.random()
        val messageId = Uuid.random().toHexString()
        factory(path).createConnection().let { connection ->
            val driver = connection.driver
            revertToVersionFive(driver)
            fun exec(sql: String) = driver.execute(null, sql, 0)
            // revertToVersionFive already restores the version-5 placeholders (empty).
            // Parent message for the placeholder FK.
            exec("INSERT INTO rooms (room_id, space_id, type, name) VALUES ('${roomId.toHexString()}', NULL, 'TEXT_CHANNEL', 'r')")
            exec(
                "INSERT INTO messages (message_id, room_id, sender_account_id, author_device_id, " +
                        "verification_state, created_at_epoch_seconds, payload_type, message_payload, " +
                        "is_orphaned, ancestry_complete) VALUES ('$messageId', '${roomId.toHexString()}', " +
                        "'acc', 'dev', 'VERIFIED', 0, 'TEXT', X'00', 0, 1)",
            )
            exec(
                "INSERT INTO file_transfers (transfer_id, message_id, status, total_bytes, chunk_size_bytes, " +
                        "chunk_count, object_hash, local_file_path) VALUES ('t1', '$messageId', 'IN_FLIGHT', " +
                        "10, 5, 2, X'01', '/tmp/x')",
            )
            exec("INSERT INTO file_chunk_tracker (transfer_id, chunk_index, status) VALUES ('t1', 0, 'MISSING')")
            exec("PRAGMA user_version = 5")
            driver.close()
        }

        val connection = factory(path).createConnection()
        try {
            assertEquals(YapYapDatabase.Schema.version, readPragmaUserVersion(connection.driver))
            val driver = connection.driver
            fun tableExists(name: String): Boolean {
                var found = false
                driver.executeQuery(
                    null,
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='$name'",
                    mapper = { cursor ->
                        if (cursor.next().value) found = true
                        app.cash.sqldelight.db.QueryResult.Value(Unit)
                    },
                    parameters = 0,
                )
                return found
            }
            assertFalse(tableExists("file_transfers"), "placeholder must go")
            assertFalse(tableExists("file_chunk_tracker"), "placeholder must go")
            assertTrue(tableExists("message_attachments"), "attachment index must land")
            assertTrue(tableExists("spaces"), "unrelated placeholder stays")
            // Index usable: write + read back through generated queries.
            val db = connection.database
            val mid = Uuid.parseHex(messageId)
            db.messageQueries.insertMessageAttachment(
                mid,
                org.yapyap.protocol.FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
            )
            assertEquals(
                listOf(mid),
                db.messageQueries.selectMessageIdsByFileId(
                    org.yapyap.protocol.FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                ).executeAsList(),
            )
        } finally {
            connection.driver.close()
        }
    }
}
