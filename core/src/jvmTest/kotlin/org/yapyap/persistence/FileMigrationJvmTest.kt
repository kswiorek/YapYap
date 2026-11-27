package org.yapyap.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The tables and indexes 6.sqm creates, children before parents. */
private val fileIndexes = listOf(
    "message_attachments_file_idx",
    "file_receive_chunks_file_idx",
    "file_incoming_seals_file_idx",
    "file_incoming_transfers_seal_idx",
    "file_seals_file_target_idx",
    "file_transfers_out_seal_idx",
    "file_transfers_out_relay_idx",
    "file_transfer_chunks_packet_idx",
    "file_pull_attempts_due_idx",
)
private val fileTables = listOf(
    "message_attachments",
    "file_descriptors",
    "file_receive_chunks",
    "file_receives",
    "pending_file_requests",
    "file_incoming_seals",
    "file_incoming_transfers",
    "file_seals",
    "file_transfers_out",
    "file_transfer_chunks",
    "file_pull_declined_devices",
    "file_pull_attempts",
    "file_relay_chunks",
    "file_relay_transfers",
)

/** Tables whose shape 6.sqm must match fresh, including the outbox retry_class column. */
private val comparedTables = fileTables + "outbox"

/**
 * Undoes 6.sqm on a store created at today's schema: drops every file table, drops the
 * outbox retry_class column, and restores the version-5 placeholders (empty).
 */
internal fun revertFileMigration(driver: SqlDriver) {
    (fileIndexes.map { "DROP INDEX $it" } + fileTables.map { "DROP TABLE $it" } + listOf(
        "ALTER TABLE outbox DROP COLUMN retry_class",
        """CREATE TABLE file_transfers (
            transfer_id TEXT NOT NULL PRIMARY KEY,
            message_id TEXT NOT NULL,
            status TEXT NOT NULL,
            file_name_hint TEXT,
            mime_type TEXT,
            total_bytes INTEGER NOT NULL,
            chunk_size_bytes INTEGER NOT NULL,
            chunk_count INTEGER NOT NULL,
            object_hash BLOB NOT NULL,
            highest_contiguous_chunk INTEGER NOT NULL DEFAULT -1,
            local_file_path TEXT NOT NULL,
            FOREIGN KEY (message_id) REFERENCES messages(message_id)
        )""",
        """CREATE TABLE file_chunk_tracker (
            transfer_id TEXT NOT NULL,
            chunk_index INTEGER NOT NULL,
            status TEXT NOT NULL,
            PRIMARY KEY (transfer_id, chunk_index),
            FOREIGN KEY (transfer_id) REFERENCES file_transfers(transfer_id)
        )""",
        "CREATE INDEX file_transfers_message_idx ON file_transfers(message_id)",
        "CREATE INDEX file_chunk_tracker_status_idx ON file_chunk_tracker(status)",
    )).forEach { driver.execute(null, it, 0) }
}

/**
 * Upgrade path for 6.sqm: the version-5 placeholders (with rows in them) go, and every file
 * table lands in exactly the shape a fresh store creates from the `.sq` files.
 */
class FileMigrationJvmTest {

    private fun factory(path: String) = DatabaseFactory(object : DriverFactory {
        override fun createDriver() = SingleConnectionJdbcDriver("jdbc:sqlite:file:$path?foreign_keys=on")
    })

    private fun tempDb(name: String) =
        Files.createTempDirectory("yapyap-file-migration").resolve(name).toString().replace('\\', '/')

    @Test
    fun versionFiveStore_migratesToTheFreshFileSchema() = runTest {
        val migratedPath = tempDb("migrated.db")
        factory(migratedPath).createConnection().let { connection ->
            val driver = connection.driver
            revertToVersionFive(driver)
            fun exec(sql: String) = driver.execute(null, sql, 0)
            val roomId = Uuid.random().toHexString()
            val messageId = Uuid.random().toHexString()
            exec("INSERT INTO rooms (room_id, space_id, type, name) VALUES ('$roomId', NULL, 'TEXT_CHANNEL', 'r')")
            exec(
                "INSERT INTO messages (message_id, room_id, sender_account_id, author_device_id, " +
                        "verification_state, created_at_epoch_seconds, payload_type, message_payload, " +
                        "is_orphaned, ancestry_complete) VALUES ('$messageId', '$roomId', " +
                        "'acc', 'dev', 'VERIFIED', 0, 'TEXT', X'00', 0, 1)",
            )
            exec(
                "INSERT INTO file_transfers (transfer_id, message_id, status, total_bytes, chunk_size_bytes, " +
                        "chunk_count, object_hash, local_file_path) VALUES ('t1', '$messageId', 'IN_FLIGHT', " +
                        "10, 5, 2, X'01', '/tmp/x')",
            )
            exec("INSERT INTO file_chunk_tracker (transfer_id, chunk_index, status) VALUES ('t1', 0, 'MISSING')")
            driver.close()
        }

        val freshPath = tempDb("fresh.db")
        val fresh = factory(freshPath).createConnection()
        val migrated = factory(migratedPath).createConnection()
        try {
            val driver = migrated.driver
            assertEquals(YapYapDatabase.Schema.version, readPragmaUserVersion(driver))
            assertFalse("file_chunk_tracker" in tableNames(driver), "placeholder must go")
            assertFalse("file_transfers" in tableNames(driver), "placeholder must go")
            assertTrue("spaces" in tableNames(driver), "unrelated placeholder stays")
            for (table in comparedTables) {
                assertEquals(shape(fresh.driver, table), shape(driver, table), "$table differs from the fresh schema")
            }
            assertEquals(emptyList(), rows(driver, "PRAGMA foreign_key_check", columns = 4), "foreign_key_check")
        } finally {
            migrated.driver.close()
            fresh.driver.close()
        }
    }

    private fun tableNames(driver: SqlDriver): Set<String> =
        rows(driver, "SELECT name FROM sqlite_master WHERE type = 'table'", columns = 1).map { it.single() }.toSet()

    /** Columns, foreign keys and indexes (with their columns) of [table], as SQLite reports them. */
    private fun shape(driver: SqlDriver, table: String): List<List<String>> {
        val indexes = rows(driver, "SELECT name FROM pragma_index_list('$table') ORDER BY name", columns = 1)
        return rows(driver, "PRAGMA table_info($table)", columns = 6) +
                rows(driver, "PRAGMA foreign_key_list($table)", columns = 8) +
                indexes.flatMap { (index) ->
                    listOf(listOf(index)) + rows(
                        driver,
                        "PRAGMA index_info($index)",
                        columns = 3
                    )
                }
    }

    private fun rows(driver: SqlDriver, sql: String, columns: Int): List<List<String>> =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val out = mutableListOf<List<String>>()
                while (cursor.next().value) {
                    out += List(columns) { column -> cursor.getString(column).orEmpty() }
                }
                QueryResult.Value(out)
            },
            parameters = 0,
        ).value
}
